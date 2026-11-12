package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.config.Operation;
import com.botmaker.shared.emulator.tree.UiTree;

import java.awt.Point;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Screens for the sign-in tests: the dumps captured on MuMu, and small trees built in Google's and Settings' shapes for
 * the screens past the email (never captured: they need a real account). Plus a fake device, clock and context.
 */
final class TestScreens {

    static final String GMS = "com.google.android.gms";
    static final String SETTINGS = "com.android.settings";

    private TestScreens() {}

    static UiTree captured(String name) {
        try (InputStream in = TestScreens.class.getResourceAsStream("/emulator/google/" + name + ".xml")) {
            return UiTree.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A one-window tree of {@code nodes}, each {@link #node} XML. */
    static UiTree tree(String pkg, String... nodes) {
        StringBuilder xml = new StringBuilder("<hierarchy rotation=\"0\"><node resource-id=\"\" text=\"\" package=\"")
                .append(pkg).append("\" bounds=\"[0,0][1600,900]\">");
        for (String node : nodes) xml.append(node.replace("PKG", pkg));
        return UiTree.parse(xml.append("</node></hierarchy>").toString());
    }

    /** A node of {@code id} saying {@code text}, at row {@code row} (each 100 px high). */
    static String node(String id, String text, int row) {
        return node(id, text, row, false);
    }

    static String node(String id, String text, int row, boolean password) {
        return "<node resource-id=\"" + id + "\" text=\"" + text + "\" package=\"PKG\" password=\"" + password
                + "\" bounds=\"[100," + row * 100 + "][700," + (row * 100 + 80) + "]\"/>";
    }

    static UiTree password() {
        return tree(GMS, node("", "Welcome", 1), node("", "user@example.com", 2), node("password", "", 3),
                node("", "", 4, true), node("passwordNext", "Next", 5));
    }

    static UiTree wrongPassword() {
        return tree(GMS, node("", "Welcome", 1), node("", "", 4, true),
                node("", "Wrong password. Try again or click Forgot password to reset it.", 6),
                node("passwordNext", "Next", 5));
    }

    static UiTree terms() {
        return tree(GMS, node("", "Terms of Service", 1), node("signinconsentNext", "I agree", 5));
    }

    static UiTree services() {
        return tree(GMS, node("", "Google services", 1), node("", "Back up device data", 2),
                node("", "Accept", 5));
    }

    static UiTree captcha() {
        return tree(GMS, node("captchaimg", "", 2), node("ca", "", 3), node("", "Type the text you hear or see", 4));
    }

    static UiTree twoStep() {
        return tree(GMS, node("", "2-Step Verification", 1), node("", "Check your phone", 2));
    }

    static UiTree home() {
        return tree("app.lawnchair", node("", "Play Store", 3));
    }

    static UiTree accountsList(String email) {
        return tree(SETTINGS, node("android:id/title", "Accounts for Owner", 1), node("android:id/title", email, 2),
                node("android:id/summary", "Google", 3), node("android:id/title", "Add account", 4));
    }

    static UiTree accountPage() {
        return tree(SETTINGS, node("android:id/title", "Account sync", 2),
                node("com.android.settings:id/button", "Remove account", 6));
    }

    static UiTree confirm() {
        return tree(SETTINGS, node("android:id/alertTitle", "Remove account?", 2),
                node("android:id/button2", "Cancel", 5), node("android:id/button1", "Remove account", 6));
    }

    /** Taps at the middle of the node that says {@code text}. */
    static Point at(UiTree tree, String text) {
        return Screens.saying(tree, text).map(n -> new Point((int) n.bounds().getCenterX(),
                (int) n.bounds().getCenterY())).orElseThrow();
    }

    /** A clock that moves only when slept. */
    static final class Clock implements Pace {
        long now;

        @Override
        public long nowMillis() {
            return now;
        }

        @Override
        public void sleep(Duration duration) {
            now += duration.toMillis();
        }
    }

    /**
     * A device whose screen is {@link #screen}, changed by {@link #onTap} (the tapped point, the screen before) and
     * {@link #onPoll} (each read). Keeps what it typed, as copies, and every action.
     */
    static final class Device implements AccountDevice {
        UiTree screen;
        Set<String> accounts = new HashSet<>();
        final List<String> typed = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        Function<Point, UiTree> onTap = p -> null;
        Function<Integer, UiTree> onPoll = n -> null;
        int polls;
        boolean closed;

        Device(UiTree screen) {
            this.screen = screen;
        }

        @Override
        public Optional<UiTree> screen() {
            UiTree next = onPoll.apply(++polls);
            if (next != null) screen = next;
            return Optional.ofNullable(screen);
        }

        @Override
        public void tap(int x, int y) {
            log.add("tap " + x + "," + y);
            UiTree next = onTap.apply(new Point(x, y));
            if (next != null) screen = next;
        }

        @Override
        public void type(char[] text) {
            log.add("type " + text.length);
            typed.add(new String(text));
        }

        @Override
        public void key(int keyCode) {
            log.add("key " + keyCode);
        }

        @Override
        public void shell(String command) {
            log.add(command);
        }

        @Override
        public Set<String> accounts() {
            return Set.copyOf(accounts);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** A context that keeps what it was told. */
    static final class Ctx implements Operation.Context {
        final List<String> steps = new ArrayList<>();
        final List<String> needs = new ArrayList<>();
        int resumed;
        boolean cancel;

        @Override
        public void step(String words) {
            steps.add(words);
        }

        @Override
        public void progress(double fraction) {
        }

        @Override
        public void needsYou(String why) {
            needs.add(why);
        }

        @Override
        public void resumed() {
            resumed++;
        }

        @Override
        public boolean cancelled() {
            return cancel;
        }
    }
}
