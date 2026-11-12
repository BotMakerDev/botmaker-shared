package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.EmulatorInstall;
import com.botmaker.shared.emulator.tree.UiTree;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.botmaker.shared.emulator.google.TestScreens.node;
import static com.botmaker.shared.emulator.google.TestScreens.tree;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 12: BotMaker presses Install in the Play app, and nothing that costs money. */
class PlayAppInstallTest {

    private static final String GAME = "com.zjcs.android.us";
    private static final String PLAY = PlayScreen.PLAY;

    private static UiTree page(String button) {
        return tree(PLAY, node("", "Sword x Staff", 1), node("", "Contains ads · In-app purchases", 2),
                node("", button, 5));
    }

    /** A Play app whose Install starts a download that lands {@code after} polls later. */
    private static final class Phone implements PlayAppInstall.Phone {
        UiTree screen;
        Set<String> accounts = Set.of("user@example.com");
        final List<String> log = new ArrayList<>();
        boolean installed;
        int pollsToInstall = -1;
        int polls;
        boolean installMoves = true;

        Phone(UiTree screen) {
            this.screen = screen;
        }

        @Override
        public Optional<UiTree> screen() {
            polls++;
            if (pollsToInstall >= 0 && --pollsToInstall == 0) installed = true;
            return Optional.ofNullable(screen);
        }

        @Override
        public void tap(Rectangle bounds) {
            log.add("tap " + (int) bounds.getCenterY());
            if (installMoves) {
                screen = tree(PLAY, node("", "Sword x Staff", 1), node("", "42%", 4), node("", "Cancel", 5));
                pollsToInstall = 3;
            }
        }

        @Override
        public String shell(String command) {
            log.add(command);
            if (command.startsWith("pm list packages")) return installed ? "package:" + GAME + "\n" : "";
            return "";
        }

        @Override
        public Set<String> accounts() {
            return accounts;
        }

        @Override
        public void close() {
        }
    }

    private static PlayAppInstall.Result run(Phone phone) throws InterruptedException {
        return new PlayAppInstall(phone, new TestScreens.Clock()).install(GAME, "LDPlayer-0", null);
    }

    private static long taps(Phone phone) {
        return phone.log.stream().filter(l -> l.startsWith("tap")).count();
    }

    @Test
    void installIsPressedOnceAndTheInstallIsWaitedFor() throws Exception {
        Phone phone = new Phone(page("Install"));
        PlayAppInstall.Result result = run(phone);
        assertEquals(PlayAppInstall.Outcome.INSTALLED, result.outcome(), result.message());
        assertEquals(1, taps(phone));
        assertTrue(phone.log.contains(EmulatorInstall.storeIntent(GAME)));
    }

    @Test
    void theFrenchInstallerIsPressedToo() throws Exception {
        Phone phone = new Phone(page("Installer"));
        assertEquals(PlayAppInstall.Outcome.INSTALLED, run(phone).outcome());
        assertEquals(1, taps(phone));
    }

    @Test
    void aPriceBuyOrPreRegisterIsNeverPressed() throws Exception {
        for (String button : List.of("$4.99", "1,99 €", "US$0.99", "Buy", "Acheter", "Pre-register")) {
            Phone phone = new Phone(page(button));
            PlayAppInstall.Result result = run(phone);
            assertTrue(result.outcome() == PlayAppInstall.Outcome.PRICED
                    || result.outcome() == PlayAppInstall.Outcome.PRE_REGISTER, button + ": " + result);
            assertEquals(0, taps(phone), button);
        }
    }

    @Test
    void anInstallBesideAPriceIsNotPressedEither() throws Exception {
        Phone phone = new Phone(tree(PLAY, node("", "Install", 5), node("", "€2.99", 6)));
        assertEquals(PlayAppInstall.Outcome.PRICED, run(phone).outcome());
        assertEquals(0, taps(phone));
    }

    @Test
    void anInstanceWithNoGoogleAccountIsNotOpened() throws Exception {
        Phone phone = new Phone(page("Install"));
        phone.accounts = Set.of();
        assertEquals(PlayAppInstall.Outcome.NOT_SIGNED_IN, run(phone).outcome());
        assertTrue(phone.log.stream().noneMatch(l -> l.startsWith("am start")));
    }

    @Test
    void anInstallThatDoesNotMoveIsTheUsersAfterThreePresses() throws Exception {
        Phone phone = new Phone(page("Install"));
        phone.installMoves = false;
        assertEquals(PlayAppInstall.Outcome.YOURS, run(phone).outcome());
        assertEquals(PlayAppInstall.TAPS, taps(phone));
    }

    @Test
    void aScreenItDoesNotKnowIsTheUsers() throws Exception {
        Phone phone = new Phone(tree(PLAY, node("", "Complete account setup", 2), node("", "Continue", 5)));
        assertEquals(PlayAppInstall.Outcome.YOURS, run(phone).outcome());
        assertEquals(0, taps(phone));
    }

    @Test
    void anotherAppsWindowsAreNotRead() {
        assertEquals(PlayScreen.LOADING, PlayScreen.of(tree("com.other", node("", "Install", 5))));
        assertEquals(PlayScreen.INSTALLED, PlayScreen.of(page("Open")));
        assertEquals(PlayScreen.SIGNED_OUT, PlayScreen.of(page("Sign in")));
        // a promotion's percentage beside Install is not a download
        assertEquals(PlayScreen.INSTALL, PlayScreen.of(tree(PLAY, node("", "30% off gems", 4), node("", "50%", 3),
                node("", "Install", 5))));
    }
}
