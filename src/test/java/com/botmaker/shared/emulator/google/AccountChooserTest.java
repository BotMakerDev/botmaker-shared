// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.config.AccountRecord;
import com.botmaker.shared.emulator.config.AccountRecord.Kind;
import com.botmaker.shared.emulator.tree.UiTree;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.botmaker.shared.emulator.google.TestScreens.GMS;
import static com.botmaker.shared.emulator.google.TestScreens.at;
import static com.botmaker.shared.emulator.google.TestScreens.node;
import static com.botmaker.shared.emulator.google.TestScreens.tree;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountChooserTest {

    private static final String GAME = "com.example.game";
    private static final AccountRecord ACCOUNT = new AccountRecord(Kind.GOOGLE, "main@example.com", "")
            .withAliases(List.of("alias@example.com"));

    /** Google's picker in its shape: a title, a row per account (a name, then the address), Add another account. */
    private static UiTree chooser(String... addresses) {
        String[] nodes = new String[addresses.length + 3];
        nodes[0] = node("", "Choose an account", 1);
        nodes[1] = node("", "to continue to Game", 2);
        for (int i = 0; i < addresses.length; i++) nodes[i + 2] = node("account_name", addresses[i], i + 3);
        nodes[nodes.length - 1] = node("", "Add another account", addresses.length + 3);
        return tree(GMS, nodes);
    }

    private static UiTree game() {
        return tree(GAME, node("", "Tap to start", 4));
    }

    private static AccountChooser.Outcome watch(TestScreens.Device device, TestScreens.Clock clock, long ms)
            throws InterruptedException {
        return AccountChooser.watch(device, ACCOUNT, GAME, () -> false, clock, ms);
    }

    @Test
    void theRowIsTappedByAnAliasOnceTheListIsDrawnAndThePickerCloses() throws Exception {
        UiTree picker = chooser("other@example.com", "Alias@Example.com");
        TestScreens.Device device = new TestScreens.Device(TestScreens.home());
        // the launcher, the game, the sheet's title alone, then its rows
        device.onPoll = n -> n == 3 ? game() : n == 9 ? chooser() : n == 13 ? picker : null;
        device.onTap = p -> game();

        assertEquals(AccountChooser.Outcome.PICKED, watch(device, new TestScreens.Clock(), 60_000));
        Point row = at(picker, "Alias@Example.com");
        assertEquals(List.of("tap " + row.x + "," + row.y), device.log, "one tap, on the alias's row");
    }

    @Test
    void theScreenIsReadOnlyWhenGooglesWindowIsOnTop() throws Exception {
        TestScreens.Device device = new TestScreens.Device(game());

        assertEquals(AccountChooser.Outcome.NONE, watch(device, new TestScreens.Clock(), 10_000));
        assertEquals(0, device.reads, "the game in front: only the focus is asked");
    }

    @Test
    void noRowOfTheAccountHandsThePickerOverAndAddsNoAccount() throws Exception {
        TestScreens.Device device = new TestScreens.Device(chooser("other@example.com"));

        assertEquals(AccountChooser.Outcome.HANDED_OVER, watch(device, new TestScreens.Clock(), 60_000));
        assertTrue(device.log.isEmpty(), "Add another account is never tapped: " + device.log);
    }

    @Test
    void aTapThePickerOutlivesIsSaid() throws Exception {
        TestScreens.Device device = new TestScreens.Device(chooser("main@example.com"));

        assertEquals(AccountChooser.Outcome.STAYED, watch(device, new TestScreens.Clock(), 60_000));
        assertEquals(1, device.log.size(), "tapped once: " + device.log);
    }

    @Test
    void theWatchEndsWhenTheGameLeavesTheFrontOrTheInstanceGoesQuiet() throws Exception {
        TestScreens.Device left = new TestScreens.Device(game());
        left.onPoll = n -> n == 4 ? TestScreens.home() : null;
        TestScreens.Clock clock = new TestScreens.Clock();
        assertEquals(AccountChooser.Outcome.NONE, watch(left, clock, 60_000));
        assertTrue(clock.now < 10_000, "ended at " + clock.now);

        TestScreens.Device gone = new TestScreens.Device(null);
        TestScreens.Clock quiet = new TestScreens.Clock();
        assertEquals(AccountChooser.Outcome.NONE, watch(gone, quiet, 60_000));
        assertEquals(AccountChooser.SILENT * AccountChooser.EVERY.toMillis() - AccountChooser.EVERY.toMillis(), quiet.now);
    }

    @Test
    void whileASignInRunsOnTheInstanceNothingIsReadOrTapped() throws Exception {
        TestScreens.Device device = new TestScreens.Device(chooser("main@example.com"));
        AtomicBoolean signing = new AtomicBoolean(true);

        assertEquals(AccountChooser.Outcome.NONE,
                AccountChooser.watch(device, ACCOUNT, GAME, signing::get, new TestScreens.Clock(), 5_000));
        assertEquals(0, device.polls);
        assertTrue(device.log.isEmpty());
        // Google's sign-in pages, its windows too, are not the picker
        assertTrue(AccountChooser.sheet(TestScreens.password()).isEmpty());
    }
}
