// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.config.AccountRecord;
import com.botmaker.shared.emulator.tree.UiNode;
import com.botmaker.shared.emulator.tree.UiTree;

import java.time.Duration;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google's account picker, the "Choose an account" sheet of {@code com.google.android.gms} a game opens to sign in
 * (spec {@code 2026-11-14-google-signin-design.md}): a window of Google's own over the game, never the game's, so it is
 * known by the window on top, then read from Google's windows only. The row of the instance's account is tapped, matched
 * by any of its addresses, the main one or an alias; with no such row the picker is the user's. It never adds an
 * account: the only thing tapped is a row showing one of the account's addresses.
 *
 * <p>Watched from the host only, after a game is started on an instance; a bot's run reads no account (2026-11-14, the
 * user's call), and leaves the sheet to the user.
 */
public final class AccountChooser {

    /** How long after a game's start the picker is looked for. */
    public static final long WATCH_MS = 60_000;
    static final Duration EVERY = Duration.ofSeconds(1);
    /** How long a tapped picker has to close before the tap is said to have missed. */
    static final long CLOSE_MS = 5_000;
    /** Looks in a row at which Android says nothing: the instance is gone. */
    static final int SILENT = 5;

    static final String GMS = "com.google.android.gms";
    private static final String[] TITLE = {"Choose an account", "Choisissez un compte", "Choisir un compte",
            "Sélectionnez un compte"};
    private static final Pattern ADDRESS = Pattern.compile("[^\\s,;:()<>\"]+@[^\\s,;:()<>\"]+\\.[a-z]{2,}",
            Pattern.CASE_INSENSITIVE);

    private AccountChooser() {}

    /** What a watch came to. */
    public enum Outcome {
        /** No picker came up while it was watched. */
        NONE("none", "No account chooser came up"),
        /** The account's row was tapped, and the picker closed. */
        PICKED("picked", "The instance's Google account was picked in the game's account chooser"),
        /** The picker came up without the account on it: left to the user. */
        HANDED_OVER("handed_over", "The game's account chooser lists none of the instance's Google account's "
                + "addresses: pick one on the instance"),
        /** The account's row was tapped, but the picker stayed up: left to the user. */
        STAYED("stayed", "The instance's Google account was tapped in the game's account chooser, but the chooser "
                + "stayed up: pick it on the instance"),
        UNKNOWN("unknown", "An outcome BotMaker doesn't know");

        private final String id;
        private final String displayName;

        Outcome(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        public static Outcome fromId(String id) {
            for (Outcome outcome : values()) {
                if (outcome.id.equals(id)) return outcome;
            }
            return UNKNOWN;
        }
    }

    /** Google's picker in {@code tree}, its windows only; empty when it is not up. */
    static Optional<UiTree> sheet(UiTree tree) {
        if (tree == null) return Optional.empty();
        UiTree gms = tree.app(GMS);
        return gms.all().isEmpty() || !Screens.shows(gms, TITLE) ? Optional.empty() : Optional.of(gms);
    }

    /** The picker's row showing one of {@code account}'s addresses in its text or label. */
    static Optional<UiNode> row(UiTree sheet, AccountRecord account) {
        return sheet.all().stream().filter(n -> shows(n.text(), account) || shows(n.label(), account)).findFirst();
    }

    /** Whether the picker's rows are drawn: some node shows an address. Its title comes first, the list after. */
    static boolean listed(UiTree sheet) {
        return sheet.all().stream().anyMatch(n -> ADDRESS.matcher(n.text()).find() || ADDRESS.matcher(n.label()).find());
    }

    private static boolean shows(String words, AccountRecord account) {
        Matcher address = ADDRESS.matcher(words);
        while (address.find()) {
            if (account.answersTo(address.group())) return true;
        }
        return false;
    }

    /**
     * Watches the running {@code instance} for {@link #WATCH_MS} ms and taps {@code account}'s row when the picker comes
     * up. It ends early when {@code game} was in front and another app than Google's is now, or when the instance stops
     * answering; while a sign-in or sign-out runs on the instance it only waits. Blocks: never on the FX thread.
     */
    public static Outcome watch(EmulatorInstance instance, AccountRecord account, String game)
            throws InterruptedException {
        try (AccountDevice device = AccountDevice.looking(instance)) {
            return watch(device, account, game, () -> SignInsRunning.on(instance), Pace.REAL, WATCH_MS);
        }
    }

    static Outcome watch(AccountDevice device, AccountRecord account, String game, BooleanSupplier paused, Pace pace,
                         long ms) throws InterruptedException {
        long until = pace.nowMillis() + ms;
        boolean gameSeen = false;
        boolean sheetSeen = false;
        int silent = 0;
        for (; pace.nowMillis() < until; pace.sleep(EVERY)) {
            if (paused.getAsBoolean()) continue;
            Optional<String> front = device.focused();
            silent = front.isEmpty() ? silent + 1 : 0;
            if (silent >= SILENT) break;
            // the window on top first: the screen is read only when Google's is, or Android did not say
            if (front.isPresent() && !front.get().equals(GMS)) {
                if (front.get().equals(game)) gameSeen = true;
                else if (gameSeen) break;
                continue;
            }
            Optional<UiTree> sheet = sheet(device.screen().orElse(null));
            if (sheet.isEmpty()) continue;
            sheetSeen = true;
            if (!listed(sheet.get())) continue;
            Optional<UiNode> row = row(sheet.get(), account);
            if (row.isEmpty()) return Outcome.HANDED_OVER;
            device.tap(row.get().bounds());
            return closed(device, pace) ? Outcome.PICKED : Outcome.STAYED;
        }
        return sheetSeen ? Outcome.HANDED_OVER : Outcome.NONE;
    }

    /** Whether the tapped picker is gone within {@link #CLOSE_MS} ms. */
    private static boolean closed(AccountDevice device, Pace pace) throws InterruptedException {
        long until = pace.nowMillis() + CLOSE_MS;
        while (pace.nowMillis() < until) {
            pace.sleep(EVERY);
            if (device.focused().filter(app -> !app.equals(GMS)).isPresent()) return true;
            if (device.screen().map(tree -> sheet(tree).isEmpty()).orElse(false)) return true;
        }
        return false;
    }
}
