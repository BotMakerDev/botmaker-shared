package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.config.AccountRecord.Verification;
import com.botmaker.shared.emulator.config.Operation;
import com.botmaker.shared.emulator.tree.UiNode;
import com.botmaker.shared.emulator.tree.UiTree;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Signs a running instance in to one Google account through Android's own sign-in (the user's call, 2026-11-12: no
 * token is copied in). It opens Android's add-account screen for Google, then reads each screen and answers it: the
 * email, the password, Google's terms, its services page, a phone prompt skipped. A second step follows the account's
 * method ({@link SecondStep}, 2026-11-14): "Try another way" to the method's row, the phone prompt waited for, a code
 * asked by {@link Codes} and typed. A second step it can't follow, a captcha or a screen it doesn't know is the user's
 * ({@link Operation.Context#needsYou}); it waits until the screen moves on. A code is never stored, and no step names it.
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
    /** How many codes are typed before a second step that refuses them is handed to the user. */
    public static final int CODE_TRIES = 3;
    /**
     * How many codes an authenticator key gives before the code is asked: this window's, the one before, the next
     * one.
     */
    static final int KEY_TRIES = 3;
    static final int KEY_ENTER = 66;
    static final int KEY_DEL = 67;
    static final int KEY_MOVE_END = 123;
    /** How many characters a refused code's field is cleared of: a backup code with its spaces, and some. */
    static final int CLEARED = 16;
    static final int KEY_HOME = 3;

    private final AccountDevice device;
    private final Pace pace;
    /** Whether the last sign-in got past the password: Google showed a screen that follows it. */
    private boolean passwordAccepted;

    InstanceSignIn(AccountDevice device, Pace pace) {
        this.device = device;
        this.pace = pace;
    }

    /**
     * The second step each account's sign-in follows, and the prompt its codes are asked by.
     *
     * @param methodOf the method of the email typed, given stripped and lower case (match it with
     *                 {@code AccountRecord.answersTo}), the book's default already followed; null, {@code DEFAULT} or
     *                 {@code UNKNOWN} is {@link Verification#ASK}
     */
    public record SecondStep(Function<String, Verification> methodOf, Codes codes, AuthenticatorKeys keys,
                             Function<String, String> keyedAs) {
        /** Every second step handed to the user, as before 2026-11-14. */
        public static final SecondStep ASK = new SecondStep(email -> Verification.ASK, Codes.NONE);

        /**
         * @param keyedAs the address an email's authenticator key is kept under: the main address of the account it
         *                answers to, as an alias's key is its account's; null keeps each under itself
         */
        public SecondStep {
            methodOf = methodOf == null ? email -> Verification.ASK : methodOf;
            codes = codes == null ? Codes.NONE : codes;
            keys = keys == null ? AuthenticatorKeys.NONE : keys;
            keyedAs = keyedAs == null ? Function.identity() : keyedAs;
        }

        /** Each authenticator key kept under the address typed. */
        public SecondStep(Function<String, Verification> methodOf, Codes codes, AuthenticatorKeys keys) {
            this(methodOf, codes, keys, null);
        }

        /** No authenticator key: an account of that method has its code asked. */
        public SecondStep(Function<String, Verification> methodOf, Codes codes) {
            this(methodOf, codes, AuthenticatorKeys.NONE);
        }

        String keyOf(String email) {
            String kept = keyedAs.apply(email);
            return kept == null || kept.isBlank() ? email : kept;
        }

        Verification method(String email) {
            Verification method = methodOf.apply(email);
            return method == null ? Verification.ASK : method.followed(Verification.ASK);
        }
    }

    /** Signs {@code device} in to {@code email}. Blocking, up to five minutes plus what the user takes. */
    public static Result run(AccountDevice device, String email, char[] password, Operation.Context context)
            throws InterruptedException {
        return run(device, email, password, SecondStep.ASK, context);
    }

    /** As {@link #run(AccountDevice, String, char[], Operation.Context)}, following {@code second}'s method. */
    public static Result run(AccountDevice device, String email, char[] password, SecondStep second,
                             Operation.Context context) throws InterruptedException {
        return new InstanceSignIn(device, Pace.REAL).signIn(email, password, second, context);
    }

    /** The {@link SignInDriver} that reads Google's pages here, each sign-in on a new reader paced by {@code pace}. */
    static SignInDriver driver(Pace pace) {
        return (instance, device, email, password, second, context) -> {
            InstanceSignIn signIn = new InstanceSignIn(device, pace);
            InstanceSignIn.Result result = signIn.signIn(email, password, second, context);
            return new SignInDriver.Attempt(result, signIn.passwordAccepted());
        };
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
        return signIn(email, password, SecondStep.ASK, context);
    }

    /**
     * Whether the last {@link #signIn} got past the password, signed in or not: a second step, Google's terms or
     * services. A password Google took is worth keeping even when the step after it failed.
     */
    boolean passwordAccepted() {
        return passwordAccepted;
    }

    Result signIn(String email, char[] password, SecondStep second, Operation.Context context)
            throws InterruptedException {
        passwordAccepted = false;
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
        Follow follow = new Follow(wanted, second.method(wanted), second.codes(), second.keys(),
                second.keyOf(wanted), context);
        SignInScreen last = null;
        long since = 0;
        long lastTap = 0;
        int taps = 0;
        long emailTypedAt = 0;
        long emailTapAt = 0;
        int emailTaps = 0;
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
            if (passwordTyped && (screen.secondStep() || screen == SignInScreen.TERMS
                    || screen == SignInScreen.SERVICES || screen == SignInScreen.PHONE_PROMPT)) {
                passwordAccepted = true;
            }
            // once the password is typed, or while the user answers a screen (who may sign in by hand, a passkey)
            if ((passwordTyped || waitingForYou) && !screen.equals(SignInScreen.TERMS)
                    && !screen.equals(SignInScreen.SERVICES) && !screen.equals(SignInScreen.PHONE_PROMPT)) {
                Optional<String> added = added(device.accounts(), wanted, before);
                if (added.isPresent()) {
                    if (waitingForYou) context.resumed();
                    device.key(KEY_HOME);
                    if (added.get().equals(wanted)) return new Result(End.SIGNED_IN, "Signed in.");
                    // one account, two addresses: the book may record the one Android lists as an alias
                    context.found(new Operation.Finding.Alias(wanted, added.get()));
                    return new Result(End.SIGNED_IN, "Signed in. Android lists the account under another of its "
                            + "addresses, which the configuration now names.", added.get());
                }
            }
            if (screen != last) {
                // a page loading between two of the user's screens doesn't give the screen back to BotMaker
                if (waitingForYou && !screen.needsYou() && screen != SignInScreen.LOADING) {
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
                        emailTypedAt = now;
                        emailTapAt = now;
                    } else if (now - emailTypedAt <= SETTLE.toMillis()) {
                        // a tap that missed Next, or one the page wasn't ready for: Next again, then Enter; counted
                        // from the typing, not per visit, as the page may flicker through loading between
                        if (emailTaps < TAPS && now - emailTapAt > RETAP.toMillis()) {
                            if (emailTaps < TAPS - 1) tapNext(tree, SignInScreen.EMAIL);
                            else device.key(KEY_ENTER);
                            emailTaps++;
                            emailTapAt = now;
                        }
                    } else {
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
                case CHALLENGES, PHONE_TAP, CODE, BACKUP_CODE, PASSKEY, TWO_STEP -> {
                    if (!waitingForYou) {
                        Act act = follow.answer(screen, tree, now, on);
                        if (act == Act.HANDED) waitingForYou = true;
                        // the user's time typing a code doesn't count against the limit
                        if (act == Act.ASKED) deadline = Math.max(deadline, pace.nowMillis() + LIMIT.toMillis());
                    }
                }
                case CAPTCHA -> {
                    if (!waitingForYou) {
                        context.needsYou("Google shows a captcha: answer it on the instance's screen.");
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

    /** What answering a second-step screen did. */
    private enum Act {
        /** BotMaker tapped or typed, or waits for the screen to react. */
        WAITS,
        /** The user asked a code and typed one: their time doesn't count. */
        ASKED,
        /** The screen is the user's now, and the context was told why. */
        HANDED
    }

    /**
     * One sign-in's second step, by the account's method: "Try another way" (the passkey's answer too) and the method's
     * row, each tapped again while its screen stays, {@link #TAPS} at most, then the
     * method's screen answered (the phone prompt waited for, a code asked and typed, {@link #CODE_TRIES} at most).
     * Anything else is handed to the user, saying why.
     */
    private final class Follow {
        private final String email;
        private final Verification method;
        private final Codes codes;
        private final AuthenticatorKeys keys;
        /** The address the account's authenticator key is kept under: its main one. */
        private final String keyedAs;
        private final Operation.Context context;
        /** The codes made from the account's authenticator key and typed, {@link #KEY_TRIES} at most. */
        private int keyTries;
        /** The 30-second window the first key's code was made for. */
        private long keyWindow;
        /** Whether the key gave all it may: from then on the code is asked. */
        private boolean keysDone;
        /** Whether any code was typed in the field, which a refused one leaves there. */
        private boolean typedAny;
        /** The screen the taps below were made on: a new screen starts them again. */
        private SignInScreen tapsOn;
        /** Taps on this screen ("Try another way" or the method's row), retried while it stays. */
        private int taps;
        private long lastTap;
        /** Every tap of the second step, so screens taking turns don't loop for ever. */
        private int allTaps;
        private boolean handedOver;
        /** The codes typed, an untypeable one not counted. */
        private int tries;
        /** When the last code was typed, while its screen hasn't moved on; -1 when none waits. */
        private long typedAt = -1;

        Follow(String email, Verification method, Codes codes, AuthenticatorKeys keys, String keyedAs,
               Operation.Context context) {
            this.email = email;
            this.method = method;
            this.codes = codes;
            this.keys = keys;
            this.keyedAs = keyedAs;
            this.context = context;
        }

        Act answer(SignInScreen screen, UiTree tree, long now, long on) throws InterruptedException {
            // once handed over, the second step stays the user's: BotMaker never taps against their choice
            if (handedOver) return handOver("Answer the second step on the instance's screen.");
            // a code waits for Google's answer on its own screen only (a page loading between is not answered here)
            if (!screen.answers(method)) typedAt = -1;
            if (method == Verification.ASK) {
                return handOver("Google asks for a second step: approve it on your phone, or type its code on the "
                        + "instance's screen.");
            }
            String named = method.displayName().toLowerCase(Locale.ROOT);
            if (screen != tapsOn) {
                tapsOn = screen;
                taps = 0;
            }
            if (screen == SignInScreen.CHALLENGES) {
                if (!mayTap(now)) {
                    return on > SETTLE.toMillis() ? handOver("Ways to sign in: BotMaker's tap on the " + named
                            + " didn't move it on. Answer it on the instance's screen.") : Act.WAITS;
                }
                Optional<UiNode> row = SignInScreen.row(tree, method);
                if (row.isEmpty()) {
                    return handOver("Google doesn't offer the " + named + " for this account: pick another way on "
                            + "the instance's screen.");
                }
                context.step("Choosing the " + named + "…");
                device.tap(row.get().bounds());
                tapped(now);
                return Act.WAITS;
            }
            // a texted code's screen is not the authenticator's: a key's code typed there would only spend Google's tries
            if (screen.answers(method) && (method != Verification.AUTHENTICATOR
                    || SignInScreen.authenticatorCode(tree))) {
                return screen == SignInScreen.PHONE_TAP
                        ? handOver("Approve the sign-in on your phone: Google sent it a prompt.")
                        : code(screen, tree, now);
            }
            if (mayTap(now)) {
                Optional<UiNode> another = SignInScreen.anotherWay(tree);
                if (another.isEmpty()) {
                    return handOver("Google asks for another second step than the " + named + ", with no other way "
                            + "offered: answer it on the instance's screen.");
                }
                context.step("Trying another way, for the " + named + "…");
                device.tap(another.get().bounds());
                tapped(now);
                return Act.WAITS;
            }
            return on > SETTLE.toMillis() ? handOver("Google didn't show the " + named + "'s screen: answer it on the "
                    + "instance's screen.") : Act.WAITS;
        }

        /**
         * Whether a tap may go again: the first at once, then {@link #RETAP} after the last while its screen stays (a
         * tap during a page's loading is lost, and Google may show the passkey again), {@link #TAPS} at most on one
         * screen and three times that over the second step.
         */
        private boolean mayTap(long now) {
            if (allTaps >= 3 * TAPS) return false;
            return taps == 0 || taps < TAPS && now - lastTap > RETAP.toMillis();
        }

        private void tapped(long now) {
            taps++;
            allTaps++;
            lastTap = now;
        }

        /** The code's screen: a code typed waits for Google's answer, a refused one is asked again. */
        private Act code(SignInScreen screen, UiTree tree, long now) throws InterruptedException {
            if (typedAt >= 0) {
                // asked again only when Google says it was wrong: a code it is still checking isn't typed over
                if (now - typedAt >= RETAP.toMillis() && SignInScreen.wrongCode(tree)) {
                    typedAt = -1;
                } else if (now - typedAt > SETTLE.toMillis()) {
                    return handOver("Google didn't answer the code: answer it on the instance's screen.");
                } else {
                    return Act.WAITS;
                }
            }
            if (method == Verification.AUTHENTICATOR && !keysDone) {
                long clock = pace.nowMillis();
                // Google's clock and this PC's may differ either way: this window's code, then the one before (this
                // PC ahead), then, once it comes, the next one (this PC behind)
                long time = keyTries == 1 ? (keyWindow - 1) * Totp.STEP.toMillis() : clock;
                if (keyTries == 2 && Totp.window(clock) <= keyWindow) return Act.WAITS;
                Optional<char[]> code = keyTries < KEY_TRIES ? keys.code(keyedAs, time) : Optional.empty();
                if (code.isPresent()) {
                    try {
                        if (keyTries == 0) keyWindow = Totp.window(time);
                        keyTries++;
                        context.step("Typing the code from BotMaker's authenticator key (" + code.get().length
                                + " characters)…");
                        type(screen, tree, code.get());
                        return Act.WAITS;
                    } finally {
                        Arrays.fill(code.get(), '\0');
                    }
                }
                context.step(keyTries == 0 ? "BotMaker holds no authenticator key for this account: its code is asked."
                        : "Google refused the codes of BotMaker's authenticator key: the code is asked.");
                keysDone = true;
            }
            // an authenticator account whose key gave none Google took: its code is asked as a typed one's
            Verification asked = method == Verification.AUTHENTICATOR ? Verification.CODE : method;
            while (tries < CODE_TRIES) {
                int attempt = tries + 1;
                context.needsYou((asked == Verification.BACKUP_CODE ? "Type one of the account's backup codes"
                        : "Type the code Google texted, or the one your authenticator app shows,")
                        + " in BotMaker's dialog (try " + attempt + " of " + CODE_TRIES + ").");
                char[] code;
                try {
                    code = codes.ask(email, asked, attempt);
                } catch (InterruptedException | RuntimeException e) {
                    context.resumed();
                    throw e;
                }
                if (code == null || code.length == 0) {
                    // the user answers on the instance's screen: the needs-you stays
                    return Act.HANDED;
                }
                try {
                    context.resumed();
                    // cancelled while the dialog was open: the next poll goes home, nothing typed
                    if (context.cancelled()) return Act.WAITS;
                    if (!typeable(code)) {
                        // not one Google refused: the try isn't counted
                        context.step("That code has signs BotMaker can't type (ASCII only).");
                        continue;
                    }
                    tries = attempt;
                    context.step("Typing the code (" + code.length + " characters)…");
                    type(screen, tree, code);
                    return Act.ASKED;
                } finally {
                    Arrays.fill(code, '\0');
                }
            }
            return handOver("Google refused " + CODE_TRIES + " codes: answer it on the instance's screen.");
        }

        /** Types {@code code} in the code's field, cleared of a refused one first, and waits for Google's answer. */
        private void type(SignInScreen screen, UiTree tree, char[] code) {
            SignInScreen.codeField(tree).ifPresent(field -> device.tap(field.bounds()));
            // Google keeps a refused code in the field: the next one would be typed after it
            if (typedAny) clearField();
            enter(tree, null, code, screen);
            typedAny = true;
            typedAt = pace.nowMillis();
        }

        /** Moves to the field's end and deletes what a refused code left there. */
        private void clearField() {
            device.key(KEY_MOVE_END);
            for (int i = 0; i < CLEARED; i++) device.key(KEY_DEL);
        }

        private Act handOver(String why) {
            handedOver = true;
            context.needsYou(why);
            return Act.HANDED;
        }
    }

    /** Taps {@code field}, types {@code text} into it, then taps the screen's Next, or presses Enter. */
    private void enter(UiTree tree, UiNode field, char[] text, SignInScreen screen) {
        if (field != null) device.tap(field.bounds());
        device.type(text);
        // the keyboard the field brought up moves the page: Next is found where it is now, else where it was
        Optional<UiNode> next = device.screen().flatMap(screen::next).or(() -> screen.next(tree));
        if (next.isPresent()) device.tap(next.get().bounds());
        else device.key(KEY_ENTER);
    }

    /** Taps what moves {@code screen} on in {@code tree}, else presses Enter. */
    private void tapNext(UiTree tree, SignInScreen screen) {
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
