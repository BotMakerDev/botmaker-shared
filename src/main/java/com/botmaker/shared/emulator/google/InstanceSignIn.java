package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.config.Operation;
import com.botmaker.shared.emulator.tree.UiNode;
import com.botmaker.shared.emulator.tree.UiTree;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Signs a running instance in to one Google account through Android's own sign-in (the user's call, 2026-11-12: no
 * token is copied in). It opens Android's add-account screen for Google, then reads each screen and answers it: the
 * email, the password, Google's terms, its services page, a phone prompt skipped. A second step, a captcha or a screen
 * it doesn't know is the user's ({@link Operation.Context#needsYou}); it waits until the screen moves on.
 * <p>
 * The email and the password are each typed once: a password screen that comes back, or says it is wrong, ends the
 * sign-in, never a second try (retries lock accounts). Neither is in a step, an outcome or a log: a typed text's step
 * says its length only. It never solves a captcha. The caller owns the password and clears it.
 */
public final class InstanceSignIn {

    /** How a sign-in ended. */
    public enum End {
        SIGNED_IN("signed_in", "Signed in"),
        WRONG_PASSWORD("wrong_password", "Wrong password"),
        NO_SUCH_ACCOUNT("no_such_account", "No such account"),
        REFUSED("refused", "Refused by Google"),
        CANNOT_TYPE("cannot_type", "Can't be typed"),
        TIMED_OUT("timed_out", "Timed out"),
        CANCELLED("cancelled", "Cancelled"),
        FAILED("failed", "Failed"),
        UNKNOWN("unknown", "Unknown");

        private final String id;
        private final String displayName;

        End(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        /** Whether this account must not be tried again in the batch: the same password would be typed again. */
        public boolean dropsAccount() {
            return this == WRONG_PASSWORD || this == NO_SUCH_ACCOUNT || this == REFUSED || this == CANNOT_TYPE;
        }

        public static End fromId(String id) {
            for (End end : values()) {
                if (end.id.equals(id)) return end;
            }
            return UNKNOWN;
        }
    }

    /**
     * How it ended, and why in the user's words, naming no email.
     *
     * @param listedAs the address Android lists the account under when it is not the one typed (a Google account's
     *                 other address), else null
     */
    public record Result(End end, String words, String listedAs) {
        public Result(End end, String words) {
            this(end, words, null);
        }

        public boolean signedIn() {
            return end == End.SIGNED_IN;
        }
    }

    /**
     * The account the sign-in added: {@code wanted} when Android lists it, else the one Google account that was not
     * there {@code before} (typed by another of its addresses); empty while neither.
     */
    static Optional<String> added(Set<String> accounts, String wanted, Set<String> before) {
        if (accounts == null) return Optional.empty();
        if (accounts.contains(wanted)) return Optional.of(wanted);
        List<String> fresh = accounts.stream().filter(a -> !before.contains(a)).toList();
        return fresh.size() == 1 ? Optional.of(fresh.getFirst()) : Optional.empty();
    }

    static final String ADD_ACCOUNT = "am start -a android.settings.ADD_ACCOUNT_SETTINGS --esa account_types com.google";
    static final Duration LIMIT = Duration.ofMinutes(5);
    /** How long one screen waits for the user. */
    static final Duration YOUR_LIMIT = Duration.ofMinutes(15);
    static final Duration POLL = Duration.ofSeconds(1);
    /** How long a screen BotMaker answered may stay before its answer counts as refused. */
    static final Duration SETTLE = Duration.ofSeconds(20);
    /** How long a screen BotMaker doesn't know may stay before it is handed to the user: pages load in steps. */
    static final Duration UNKNOWN_GRACE = Duration.ofSeconds(10);
    /** Between two taps on one of Google's buttons, and how many before the user is asked. */
    static final Duration RETAP = Duration.ofSeconds(5);
    static final int TAPS = 3;
    static final int KEY_ENTER = 66;
    static final int KEY_HOME = 3;

    private final AccountDevice device;
    private final Pace pace;

    InstanceSignIn(AccountDevice device, Pace pace) {
        this.device = device;
        this.pace = pace;
    }

    /** Signs {@code device} in to {@code email}. Blocking, up to five minutes plus what the user takes. */
    public static Result run(AccountDevice device, String email, char[] password, Operation.Context context)
            throws InterruptedException {
        return new InstanceSignIn(device, Pace.REAL).signIn(email, password, context);
    }

    /** Whether {@code input text} types every character of {@code text}: printable ASCII only. */
    public static boolean typeable(char[] text) {
        if (text == null || text.length == 0) return false;
        for (int i = 0; i < text.length; i++) {
            char c = text[i];
            if (c < 0x20 || c >= 0x7F) return false;
            // input text turns %s into a space: Google would get another password
            if (c == '%' && i + 1 < text.length && text[i + 1] == 's') return false;
        }
        return true;
    }

    Result signIn(String email, char[] password, Operation.Context context) throws InterruptedException {
        String wanted = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (!typeable(wanted.toCharArray()) || !typeable(password)) {
            return new Result(End.CANNOT_TYPE, "BotMaker can type only plain letters, digits and signs (ASCII), and "
                    + "this email or password has others: sign in by hand on the instance.");
        }
        Set<String> held = device.accounts();
        if (held != null && held.contains(wanted)) return new Result(End.SIGNED_IN, "Already signed in.");
        Set<String> before = held == null ? Set.of() : held;

        context.step("Opening Android's sign-in for Google…");
        device.shell(ADD_ACCOUNT);
        long deadline = pace.nowMillis() + LIMIT.toMillis();
        boolean emailTyped = false;
        boolean passwordTyped = false;
        boolean waitingForYou = false;
        SignInScreen last = null;
        long since = 0;
        long lastTap = 0;
        int taps = 0;
        while (true) {
            if (context.cancelled()) {
                device.key(KEY_HOME);
                return new Result(End.CANCELLED, "Cancelled: the account isn't signed in.");
            }
            long now = pace.nowMillis();
            if (!waitingForYou && now > deadline) {
                return new Result(End.TIMED_OUT, "The sign-in didn't end within " + LIMIT.toMinutes() + " minutes.");
            }
            if (waitingForYou && now - since > YOUR_LIMIT.toMillis()) {
                return new Result(End.TIMED_OUT, "The screen waited " + YOUR_LIMIT.toMinutes() + " minutes for you.");
            }
            UiTree tree = device.screen().orElse(null);
            SignInScreen screen = SignInScreen.of(tree);
            // once the password is typed, or while the user answers a screen (who may sign in by hand, a passkey)
            if ((passwordTyped || waitingForYou) && !screen.equals(SignInScreen.TERMS)
                    && !screen.equals(SignInScreen.SERVICES) && !screen.equals(SignInScreen.PHONE_PROMPT)) {
                Optional<String> added = added(device.accounts(), wanted, before);
                if (added.isPresent()) {
                    if (waitingForYou) context.resumed();
                    device.key(KEY_HOME);
                    return added.get().equals(wanted) ? new Result(End.SIGNED_IN, "Signed in.")
                            : new Result(End.SIGNED_IN, "Signed in. Android lists the account under another of its "
                                    + "addresses, which the configuration now names.", added.get());
                }
            }
            if (screen != last) {
                if (waitingForYou && !screen.needsYou()) {
                    context.resumed();
                    waitingForYou = false;
                    // the user's time doesn't count against the limit
                    deadline = Math.max(deadline, now + LIMIT.toMillis());
                }
                last = screen;
                since = now;
                taps = 0;
            }
            long on = now - since;
            switch (screen) {
                case LOADING -> {
                }
                case EMAIL -> {
                    if (!emailTyped) {
                        context.step("Typing the email…");
                        enter(tree, SignInScreen.emailField(tree).orElse(null), wanted.toCharArray(),
                                SignInScreen.EMAIL);
                        emailTyped = true;
                    } else if (on > SETTLE.toMillis()) {
                        return new Result(End.FAILED, "Google stayed on the email screen after the email was typed.");
                    }
                }
                case NO_SUCH_ACCOUNT -> {
                    return new Result(End.NO_SUCH_ACCOUNT, "Google has no account with this email.");
                }
                case PASSWORD -> {
                    if (!passwordTyped) {
                        context.step("Typing the password…");
                        enter(tree, SignInScreen.passwordField(tree).orElse(null), password, SignInScreen.PASSWORD);
                        passwordTyped = true;
                    } else if (on > SETTLE.toMillis()) {
                        return new Result(End.WRONG_PASSWORD, "Google asked for the password again. BotMaker types "
                                + "it once, so this account is left out: check its password.");
                    }
                }
                case WRONG_PASSWORD -> {
                    return new Result(End.WRONG_PASSWORD, "Google says the password is wrong. BotMaker types it once, "
                            + "so this account is left out: check its password.");
                }
                case REFUSED -> {
                    return new Result(End.REFUSED, "Google refused the sign-in on this instance: sign in by hand, or "
                            + "try again later.");
                }
                case TERMS, SERVICES, PHONE_PROMPT -> {
                    if (taps < TAPS && (taps == 0 || now - lastTap > RETAP.toMillis())) {
                        Optional<UiNode> next = screen.next(tree);
                        if (next.isPresent()) {
                            context.step(said(screen));
                            device.tap(next.get().bounds());
                            taps++;
                            lastTap = now;
                        }
                    } else if (!waitingForYou && on > SETTLE.toMillis()) {
                        context.needsYou(screen.displayName() + ": BotMaker's tap didn't move it on. Answer it on the "
                                + "instance's screen.");
                        waitingForYou = true;
                    }
                }
                case TWO_STEP, CAPTCHA -> {
                    if (!waitingForYou) {
                        context.needsYou(screen == SignInScreen.CAPTCHA
                                ? "Google shows a captcha: answer it on the instance's screen."
                                : "Google asks for a second step: approve it on your phone, or type its code on the "
                                        + "instance's screen.");
                        waitingForYou = true;
                    }
                }
                case UNKNOWN -> {
                    if (!waitingForYou && on > UNKNOWN_GRACE.toMillis()) {
                        context.needsYou("A screen BotMaker doesn't know: answer it on the instance's screen.");
                        waitingForYou = true;
                    }
                }
            }
            pace.sleep(POLL);
        }
    }

    /** Taps {@code field}, types {@code text} into it, then taps the screen's Next, or presses Enter. */
    private void enter(UiTree tree, UiNode field, char[] text, SignInScreen screen) {
        if (field != null) device.tap(field.bounds());
        device.type(text);
        Optional<UiNode> next = screen.next(tree);
        if (next.isPresent()) device.tap(next.get().bounds());
        else device.key(KEY_ENTER);
    }

    private static String said(SignInScreen screen) {
        return switch (screen) {
            case TERMS -> "Accepting Google's terms…";
            case SERVICES -> "Google services: Accept…";
            case PHONE_PROMPT -> "Skipping the phone number…";
            default -> screen.displayName() + "…";
        };
    }
}
