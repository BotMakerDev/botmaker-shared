package com.botmaker.shared.emulator.google;

import com.botmaker.shared.Diag;
import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.EmulatorInstall;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.config.AndroidState;
import com.botmaker.shared.emulator.tree.TreeReader;
import com.botmaker.shared.emulator.tree.TreeReaders;
import com.botmaker.shared.emulator.tree.UiNode;
import com.botmaker.shared.emulator.tree.UiTree;

import java.awt.Rectangle;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Installs a game through the Play app on a running instance signed in to Google, pressing <em>Install</em> itself
 * (read from the tree, {@link PlayScreen}), then waiting for Android to list the package. It presses nothing else:
 * a page with a price, <em>Buy</em> or <em>Pre-register</em> ends it at once, untouched, and a screen it doesn't know
 * is handed back, so the caller can ask the user to press Install there instead.
 */
public final class PlayAppInstall {

    /** How an attempt ended. */
    public enum Outcome {
        INSTALLED,
        /** No Google account on the instance: Play has nothing to install with. */
        NOT_SIGNED_IN,
        /** A price or Buy on the page: never pressed. */
        PRICED,
        /** The game is only open to pre-registration. */
        PRE_REGISTER,
        /** Install didn't move, or a screen BotMaker doesn't know: the user's to press. */
        YOURS,
        /** Play wouldn't open, stalled, or the instance stopped answering: no point waiting on the user either. */
        FAILED
    }

    /** What an attempt did, and a sentence for the user. */
    public record Result(Outcome outcome, String message) {}

    /** What the install does to the instance; tests give their own. */
    interface Phone extends AutoCloseable {
        Optional<UiTree> screen();

        void tap(Rectangle bounds);

        String shell(String command);

        /** The Google emails signed in, or null when Android did not say. */
        Set<String> accounts();

        @Override
        void close();
    }

    static final Duration LOAD = Duration.ofSeconds(30);
    static final Duration SETTLE = Duration.ofSeconds(20);
    /** How long Play may go with no progress, as {@link EmulatorInstall#fromStore} waits. */
    static final Duration STALL = Duration.ofMinutes(15);
    static final Duration POLL = Duration.ofSeconds(2);
    static final int TAPS = 3;

    private final Phone phone;
    private final Pace pace;

    PlayAppInstall(Phone phone, Pace pace) {
        this.phone = phone;
        this.pace = pace;
    }

    /**
     * Installs {@code packageName} (a checked package name) on the running {@code live} through its Play app.
     *
     * @param progress optional narration, called on the calling thread
     */
    public static Result run(EmulatorInstance live, String packageName, Consumer<String> progress) {
        try (Phone phone = of(live)) {
            return new PlayAppInstall(phone, Pace.REAL).install(packageName, live.name(), progress);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.FAILED, "Stopped installing " + packageName + ".");
        } catch (RuntimeException e) {
            return new Result(Outcome.FAILED, "Couldn't talk to " + live.name() + " over ADB: " + e.getMessage());
        }
    }

    Result install(String packageName, String where, Consumer<String> progress) throws InterruptedException {
        if (listed(packageName)) return new Result(Outcome.INSTALLED, packageName + " is already on " + where + ".");
        Set<String> accounts = phone.accounts();
        if (accounts == null) {
            return yours(where, packageName, "Android didn't say which Google accounts are signed in");
        }
        if (accounts.isEmpty()) {
            return new Result(Outcome.NOT_SIGNED_IN, where + " has no Google account signed in, so its Play app "
                    + "can't install " + packageName + ".");
        }
        Diag.log("[Install] the Play app on " + where + " for " + packageName);
        String opened = phone.shell(EmulatorInstall.storeIntent(packageName));
        if (opened != null && opened.contains("Error")) {
            return new Result(Outcome.FAILED, "Google Play on " + where + " wouldn't open " + packageName + ": "
                    + opened.strip());
        }
        report(progress, "Google Play is open on " + where + " at " + packageName + "; pressing Install…");
        long start = pace.nowMillis();
        long since = start;
        long lastTap = 0;
        int taps = 0;
        boolean pressed = false;
        PlayScreen last = null;
        Optional<String> moved = Optional.empty();
        long movedAt = start;
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            long now = pace.nowMillis();
            if (listed(packageName)) {
                return new Result(Outcome.INSTALLED, packageName + " is installed on " + where + ".");
            }
            Optional<String> session = EmulatorInstall.sessionProgress(phone.shell(EmulatorInstall.ACTIVE_SESSIONS),
                    packageName);
            if (session.isPresent() && !session.equals(moved)) {
                moved = session;
                movedAt = now;
            }
            UiTree tree = phone.screen().orElse(null);
            PlayScreen screen = PlayScreen.of(tree);
            if (screen != last) {
                last = screen;
                since = now;
            }
            switch (screen) {
                case PRICED -> {
                    return new Result(Outcome.PRICED, packageName + "'s Play page asks for money: BotMaker pressed "
                            + "nothing there.");
                }
                case PRE_REGISTER -> {
                    return new Result(Outcome.PRE_REGISTER, packageName + " is only open to pre-registration on "
                            + "Google Play: BotMaker pressed nothing there.");
                }
                case SIGNED_OUT -> {
                    return new Result(Outcome.NOT_SIGNED_IN, "Google Play on " + where + " asks to sign in.");
                }
                case INSTALL -> {
                    boolean settled = now - lastTap > SETTLE.toMillis() / 4;
                    if (taps >= TAPS && settled) {
                        return yours(where, packageName, "Install didn't move on after " + TAPS + " presses");
                    }
                    if (taps == 0 || (taps < TAPS && settled)) {
                        Optional<UiNode> button = screen.next(tree);
                        if (button.isPresent()) {
                            phone.tap(button.get().bounds());
                            taps++;
                            lastTap = now;
                            pressed = true;
                            report(progress, "Pressed Install on " + where + "; Google Play is downloading "
                                    + packageName + "…");
                        }
                    }
                }
                case INSTALLING, INSTALLED -> {
                    if (screen == PlayScreen.INSTALLING) pressed = true;
                    // Open with the package unlisted: another app's page, or another user's install
                    else if (now - since > SETTLE.toMillis()) {
                        return yours(where, packageName, "Google Play says it is installed but Android doesn't list it");
                    }
                }
                case LOADING -> {
                    if (!pressed && now - start > LOAD.toMillis()) {
                        return yours(where, packageName, "Google Play's page didn't load");
                    }
                    // Play gone from the screen after the press, and its download not moving either
                    if (pressed && now - since > SETTLE.toMillis() && now - movedAt > SETTLE.toMillis()) {
                        return yours(where, packageName, "Google Play left the screen");
                    }
                }
                case UNKNOWN -> {
                    if (now - since > SETTLE.toMillis() && (!pressed || now - movedAt > SETTLE.toMillis())) {
                        return yours(where, packageName, "Google Play shows a screen BotMaker doesn't know");
                    }
                }
            }
            if (pressed && now - movedAt > STALL.toMillis()) {
                return new Result(Outcome.FAILED, packageName + " wasn't installed on " + where + ": Google Play did "
                        + "nothing for " + STALL.toMinutes() + " minutes.");
            }
            pace.sleep(POLL);
        }
    }

    private boolean listed(String packageName) {
        String out = phone.shell("pm list packages " + packageName);
        return out != null && out.lines().map(String::strip).anyMatch(("package:" + packageName)::equals);
    }

    private static Result yours(String where, String packageName, String why) {
        return new Result(Outcome.YOURS, why + " on " + where + ": press Install for " + packageName + " there.");
    }

    private static void report(Consumer<String> progress, String message) {
        if (progress != null) progress.accept(message);
    }

    /** The running {@code live}, over one adb connection and one tree reader, both closed with it. */
    static Phone of(EmulatorInstance live) {
        AdbDevice device = AdbDevice.connect(live.adb());
        TreeReader reader;
        try {
            reader = TreeReaders.open(device);
        } catch (RuntimeException | Error e) {
            device.close();
            throw e;
        }
        return new Phone() {
            @Override
            public Optional<UiTree> screen() {
                return reader.read();
            }

            @Override
            public void tap(Rectangle bounds) {
                device.tap((int) bounds.getCenterX(), (int) bounds.getCenterY());
            }

            @Override
            public String shell(String command) {
                return device.shell(command);
            }

            @Override
            public Set<String> accounts() {
                try {
                    return AndroidState.googleAccounts(device.shell(AndroidState.ACCOUNTS));
                } catch (RuntimeException | Error e) {
                    return null;
                }
            }

            @Override
            public void close() {
                try {
                    reader.close();
                } finally {
                    device.close();
                }
            }
        };
    }
}
