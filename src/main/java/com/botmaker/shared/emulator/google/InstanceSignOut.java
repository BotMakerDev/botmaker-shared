package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.config.Operation;
import com.botmaker.shared.emulator.tree.UiNode;
import com.botmaker.shared.emulator.tree.UiTree;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Removes one Google account from a running instance through Android Settings: the accounts list, the account, Remove,
 * confirm. It ends when Android no longer lists the account. A screen it doesn't know is the user's. No step or outcome
 * names the email.
 */
public final class InstanceSignOut {

    static final String ACCOUNTS = "am start -a android.settings.SYNC_SETTINGS";
    static final Duration LIMIT = Duration.ofMinutes(2);
    static final Duration YOUR_LIMIT = Duration.ofMinutes(15);

    private final AccountDevice device;
    private final Pace pace;

    InstanceSignOut(AccountDevice device, Pace pace) {
        this.device = device;
        this.pace = pace;
    }

    /** Removes {@code email} from {@code device}: null when it is gone, else why not. */
    public static String run(AccountDevice device, String email, Operation.Context context)
            throws InterruptedException {
        return new InstanceSignOut(device, Pace.REAL).signOut(email, context);
    }

    /**
     * Removes each of {@code emails}, one after another, going on past a failure. Null when all are gone, else what
     * failed, counted, never named.
     */
    public static String all(AccountDevice device, List<String> emails, Operation.Context context)
            throws InterruptedException {
        return new InstanceSignOut(device, Pace.REAL).signOutAll(emails, context);
    }

    String signOutAll(List<String> emails, Operation.Context context) throws InterruptedException {
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < emails.size(); i++) {
            if (context.cancelled()) {
                problems.add("Cancelled.");
                break;
            }
            context.step("Removing Google account " + (i + 1) + " of " + emails.size() + "…");
            String failed = signOut(emails.get(i), context);
            if (failed != null) problems.add("Removing Google account " + (i + 1) + ": " + failed);
        }
        return problems.isEmpty() ? null : String.join(" ", problems);
    }

    String signOut(String email, Operation.Context context) throws InterruptedException {
        String wanted = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (gone(wanted)) return null;
        context.step("Opening Android's accounts…");
        device.shell(ACCOUNTS);
        long deadline = pace.nowMillis() + LIMIT.toMillis();
        boolean waitingForYou = false;
        SignOutScreen last = null;
        long since = 0;
        long lastTap = 0;
        int taps = 0;
        while (true) {
            if (context.cancelled()) {
                device.key(InstanceSignIn.KEY_HOME);
                return "Cancelled: the account is still on the instance.";
            }
            if (gone(wanted)) {
                if (waitingForYou) context.resumed();
                device.key(InstanceSignIn.KEY_HOME);
                return null;
            }
            long now = pace.nowMillis();
            if (!waitingForYou && now > deadline) {
                return "The account wasn't removed within " + LIMIT.toMinutes() + " minutes.";
            }
            if (waitingForYou && now - since > YOUR_LIMIT.toMillis()) {
                return "The screen waited " + YOUR_LIMIT.toMinutes() + " minutes for you.";
            }
            UiTree tree = device.screen().orElse(null);
            SignOutScreen screen = SignOutScreen.of(tree, wanted);
            if (screen != last) {
                if (waitingForYou && screen != SignOutScreen.UNKNOWN) {
                    context.resumed();
                    waitingForYou = false;
                    deadline = Math.max(deadline, now + LIMIT.toMillis());
                }
                last = screen;
                since = now;
                taps = 0;
            }
            switch (screen) {
                case LOADING -> {
                }
                case ACCOUNTS, ACCOUNT, CONFIRM -> {
                    if (taps < InstanceSignIn.TAPS && (taps == 0 || now - lastTap > InstanceSignIn.RETAP.toMillis())) {
                        Optional<UiNode> next = screen.next(tree, wanted);
                        if (next.isPresent()) {
                            context.step(said(screen));
                            device.tap(next.get().bounds());
                            taps++;
                            lastTap = now;
                        }
                    } else if (!waitingForYou && now - since > InstanceSignIn.SETTLE.toMillis()) {
                        context.needsYou(screen.displayName() + ": BotMaker's tap didn't move it on. Remove the "
                                + "account on the instance's screen.");
                        waitingForYou = true;
                    }
                }
                case UNKNOWN -> {
                    if (!waitingForYou && now - since > InstanceSignIn.UNKNOWN_GRACE.toMillis()) {
                        context.needsYou("A screen BotMaker doesn't know: remove the account on the instance's "
                                + "screen, in Settings › Accounts.");
                        waitingForYou = true;
                    }
                }
            }
            pace.sleep(InstanceSignIn.POLL);
        }
    }

    private boolean gone(String email) {
        Set<String> held = device.accounts();
        return held != null && !held.contains(email);
    }

    private static String said(SignOutScreen screen) {
        return switch (screen) {
            case ACCOUNTS -> "Opening the account…";
            case ACCOUNT -> "Remove account…";
            case CONFIRM -> "Confirming the removal…";
            default -> screen.displayName() + "…";
        };
    }
}
