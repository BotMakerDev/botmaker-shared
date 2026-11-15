package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.PlatformId;
import com.botmaker.shared.emulator.config.AccountRecord;
import com.botmaker.shared.emulator.config.ConfigDiff;
import com.botmaker.shared.emulator.config.GameRecord;
import com.botmaker.shared.emulator.config.Operation;
import com.botmaker.shared.emulator.tree.UiTree;
import com.botmaker.shared.launch.GameInstall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static com.botmaker.shared.emulator.google.TestScreens.captured;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceChangesTest {

    private static final EmulatorInstance MUMU = new EmulatorInstance(PlatformId.MUMU, "MuMu-0", "127.0.0.1", 16384);
    private static final String OLD = "old@example.com";
    private static final String NEW = "new@example.com";
    private static final GameRecord GAME = new GameRecord("com.zjcs.android.us", "Sword x Staff");

    private static ConfigDiff.AccountChange change(AccountRecord.Kind kind, String name, ConfigDiff.AccountChange.Op op) {
        return new ConfigDiff.AccountChange(new AccountRecord(kind, name, ""), op);
    }

    /** A stopped instance holding OLD; the sign-in signs in at once (the account appears when the email is typed). */
    private static class Host implements InstanceChanges.Host {
        final List<String> log = new ArrayList<>();
        final TestScreens.Device device = new TestScreens.Device(TestScreens.home());
        boolean running;

        Host() {
            device.accounts.add(OLD);
        }

        @Override
        public boolean running(EmulatorInstance instance) {
            return running;
        }

        @Override
        public String start(EmulatorInstance instance, Operation.Context context) {
            log.add("start");
            running = true;
            return null;
        }

        @Override
        public String stop(EmulatorInstance instance) {
            log.add("stop");
            running = false;
            return null;
        }

        @Override
        public AccountDevice device(EmulatorInstance instance) {
            log.add("device");
            return new AccountDevice() {
                @Override
                public Optional<UiTree> screen() {
                    return device.screen();
                }

                @Override
                public void tap(int x, int y) {
                    device.tap(x, y);
                }

                @Override
                public void type(char[] text) {
                    device.type(text);
                    log.add("type " + text.length);
                }

                @Override
                public void key(int keyCode) {
                    device.key(keyCode);
                }

                @Override
                public void shell(String command) {
                    log.add(command.equals(InstanceSignOut.ACCOUNTS) ? "sign out" : "sign in");
                    if (command.equals(InstanceSignOut.ACCOUNTS)) device.accounts.remove(OLD);
                    else device.screen = captured("mumu-a15-email");
                }

                @Override
                public Set<String> accounts() {
                    return device.accounts();
                }

                @Override
                public void close() {
                    log.add("close");
                }
            };
        }

        @Override
        public GameInstall.Result install(EmulatorInstance instance, GameRecord game, Consumer<String> progress) {
            log.add("install " + game.packageName());
            return new GameInstall.Result(GameInstall.Outcome.INSTALLED, "Installed.");
        }
    }

    @Test
    void removalsFirstThenSignInsThenGamesAndTheInstanceStoppedAgain() throws Exception {
        Host host = new Host();
        host.device.onTap = point -> {
            SignInScreen screen = SignInScreen.of(host.device.screen);
            if (!screen.next(host.device.screen).map(n -> n.bounds().contains(point)).orElse(false)) return null;
            if (screen == SignInScreen.EMAIL) return TestScreens.password();
            if (screen != SignInScreen.PASSWORD) return null;
            host.device.accounts.add(NEW);
            return TestScreens.home();
        };
        List<String> asked = new ArrayList<>();
        char[][] given = new char[1][];
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        try (Passwords passwords = new Passwords(email -> {
            asked.add(email);
            given[0] = "pw".toCharArray();
            return Passwords.Answer.once(given[0]);
        })) {
            String failed = new InstanceChanges(host, new TestScreens.Clock()).run(MUMU, List.of(
                    change(AccountRecord.Kind.GOOGLE, NEW, ConfigDiff.AccountChange.Op.ADD),
                    change(AccountRecord.Kind.FACEBOOK, "fb-user", ConfigDiff.AccountChange.Op.ADD),
                    change(AccountRecord.Kind.GOOGLE, OLD, ConfigDiff.AccountChange.Op.REMOVE)),
                    List.of(GAME), passwords, ctx);
            assertNull(failed);
        }
        assertEquals(List.of(NEW), asked, "asked once, Facebook is a record only");
        assertEquals(List.of("start", "device", "sign out", "sign in", "type " + NEW.length(), "type 2", "close",
                "install " + GAME.packageName(), "stop"), host.log);
        assertTrue(Arrays.equals(new char[2], given[0]), "the password is cleared when the batch closes");
        for (String said : ctx.steps) assertFalse(said.contains("@"), said);
    }

    @Test
    void nothingToDoStartsNothing() throws Exception {
        Host host = new Host();
        try (Passwords passwords = new Passwords(email -> null)) {
            assertNull(new InstanceChanges(host, new TestScreens.Clock()).run(MUMU, List.of(
                    change(AccountRecord.Kind.MAIL, "a@example.com", ConfigDiff.AccountChange.Op.ADD)),
                    List.of(), passwords, new TestScreens.Ctx()));
        }
        assertTrue(host.log.isEmpty());
    }

    @Test
    void aSkippedPasswordSkipsThatAccountAndSaysSo() throws Exception {
        Host host = new Host();
        host.running = true;
        try (Passwords passwords = new Passwords(email -> null)) {
            String failed = new InstanceChanges(host, new TestScreens.Clock()).run(MUMU, List.of(
                    change(AccountRecord.Kind.GOOGLE, NEW, ConfigDiff.AccountChange.Op.ADD)),
                    List.of(GAME), passwords, new TestScreens.Ctx());
            assertTrue(failed.contains("no password given"), failed);
            assertFalse(failed.contains("@"), failed);
        }
        assertFalse(host.log.contains("sign in"));
        assertTrue(host.log.contains("install " + GAME.packageName()));
        assertFalse(host.log.contains("stop"), "it was running: left running");
    }

    @Test
    void aStoppedInstanceWithOnlySkippedAccountsIsNotStarted() throws Exception {
        Host host = new Host();
        try (Passwords passwords = new Passwords(email -> null)) {
            String failed = new InstanceChanges(host, new TestScreens.Clock()).run(MUMU, List.of(
                    change(AccountRecord.Kind.GOOGLE, NEW, ConfigDiff.AccountChange.Op.ADD)),
                    List.of(), passwords, new TestScreens.Ctx());
            assertTrue(failed.contains("no password given"), failed);
        }
        assertTrue(host.log.isEmpty(), host.log.toString());
    }

    @Test
    void gamesAreInstalledWhenTheAccountDeviceFails() throws Exception {
        Host host = new Host() {
            @Override
            public AccountDevice device(EmulatorInstance instance) {
                throw new IllegalStateException("no tree reader");
            }
        };
        try (Passwords passwords = new Passwords(email -> null)) {
            String failed = new InstanceChanges(host, new TestScreens.Clock()).run(MUMU, List.of(
                    change(AccountRecord.Kind.GOOGLE, OLD, ConfigDiff.AccountChange.Op.REMOVE)),
                    List.of(GAME), passwords, new TestScreens.Ctx());
            assertTrue(failed.contains("not changed"), failed);
        }
        assertEquals(List.of("start", "install " + GAME.packageName(), "stop"), host.log);
    }

    /** Review item 6 across a batch: a wrong password drops the account, and the next instance doesn't try it. */
    @Test
    void aWrongPasswordDropsTheAccountForTheRestOfTheBatch() throws Exception {
        Host host = new Host();
        host.device.onTap = point -> SignInScreen.of(host.device.screen) == SignInScreen.EMAIL
                ? TestScreens.password() : SignInScreen.of(host.device.screen) == SignInScreen.PASSWORD
                ? TestScreens.wrongPassword() : null;
        int[] asked = {0};
        try (Passwords passwords = new Passwords(email -> {
            asked[0]++;
            return Passwords.Answer.once("pw".toCharArray());
        })) {
            List<ConfigDiff.AccountChange> add = List.of(change(AccountRecord.Kind.GOOGLE, NEW,
                    ConfigDiff.AccountChange.Op.ADD));
            String first = new InstanceChanges(host, new TestScreens.Clock()).run(MUMU, add, List.of(), passwords,
                    new TestScreens.Ctx());
            assertTrue(first.contains(InstanceSignIn.End.WRONG_PASSWORD.displayName())
                    || first.toLowerCase().contains("password"), first);
            host.log.clear();
            String second = new InstanceChanges(host, new TestScreens.Clock()).run(MUMU, add, List.of(), passwords,
                    new TestScreens.Ctx());
            assertTrue(second.contains("left out"), second);
            assertFalse(host.log.contains("sign in"));
            assertNull(passwords.of(NEW));
        }
        assertEquals(1, asked[0]);
    }

    /** A password the user asked to remember is kept once its sign-in is done, and asked no more. */
    @Test
    void aSignedInAccountsPasswordIsRememberedWhenAsked() throws Exception {
        Host host = new Host();
        host.running = true;
        host.device.onTap = point -> {
            SignInScreen screen = SignInScreen.of(host.device.screen);
            if (screen == SignInScreen.EMAIL) return TestScreens.password();
            if (screen != SignInScreen.PASSWORD) return null;
            host.device.accounts.add(NEW);
            return TestScreens.home();
        };
        PasswordStoreTest.FakeStore store = new PasswordStoreTest.FakeStore();
        try (Passwords passwords = new Passwords(store, email -> new Passwords.Answer("pw".toCharArray(), true))) {
            assertNull(new InstanceChanges(host, new TestScreens.Clock()).run(MUMU, List.of(
                    change(AccountRecord.Kind.GOOGLE, NEW, ConfigDiff.AccountChange.Op.ADD)), List.of(), passwords,
                    new TestScreens.Ctx()));
        }
        assertTrue(store.remembered(NEW));
    }

    /**
     * A password Google took is remembered though the step after it failed (seen live 2026-11-14: asked again on
     * every retry), and one it never got past isn't.
     */
    @Test
    void aPasswordGoogleTookIsRememberedThoughTheSecondStepFailed() throws Exception {
        Host host = new Host();
        host.running = true;
        UiTree refused = TestScreens.tree(TestScreens.GMS, TestScreens.node("", "Couldn’t sign you in", 1));
        // only a tap on Next moves on: the password is typed on its own screen
        host.device.onTap = point -> {
            UiTree now = host.device.screen;
            SignInScreen screen = SignInScreen.of(now);
            boolean next = screen.next(now).map(n -> n.bounds().contains(point)).orElse(false);
            if (!next) return null;
            if (screen == SignInScreen.EMAIL) return TestScreens.password();
            return screen == SignInScreen.PASSWORD ? TestScreens.passkey() : null;
        };
        host.device.onPoll = n -> SignInScreen.of(host.device.screen) == SignInScreen.PASSKEY && n > 40 ? refused
                : null;
        PasswordStoreTest.FakeStore store = new PasswordStoreTest.FakeStore();
        try (Passwords passwords = new Passwords(store, email -> new Passwords.Answer("pw".toCharArray(), true))) {
            String failed = new InstanceChanges(host, new TestScreens.Clock()).run(MUMU, List.of(
                    change(AccountRecord.Kind.GOOGLE, NEW, ConfigDiff.AccountChange.Op.ADD)), List.of(), passwords,
                    new TestScreens.Ctx());
            assertTrue(failed != null && failed.contains("refused"), String.valueOf(failed));
            assertTrue(store.remembered(NEW), failed + " " + host.device.log);
        }

        Host other = new Host();
        other.running = true;
        other.device.onTap = point -> {
            UiTree now = other.device.screen;
            SignInScreen screen = SignInScreen.of(now);
            if (!screen.next(now).map(n -> n.bounds().contains(point)).orElse(false)) return null;
            return screen == SignInScreen.EMAIL ? TestScreens.password() : screen == SignInScreen.PASSWORD ? refused
                    : null;
        };
        PasswordStoreTest.FakeStore none = new PasswordStoreTest.FakeStore();
        try (Passwords passwords = new Passwords(none, email -> new Passwords.Answer("pw".toCharArray(), true))) {
            new InstanceChanges(other, new TestScreens.Clock()).run(MUMU, List.of(
                    change(AccountRecord.Kind.GOOGLE, NEW, ConfigDiff.AccountChange.Op.ADD)), List.of(), passwords,
                    new TestScreens.Ctx());
        }
        assertFalse(none.remembered(NEW), "refused right after the password: not known to be right");
    }

    /** Another emulator answering the instance's address: nothing is signed in or installed, and the words say why. */
    @Test
    void anotherEmulatorOnTheAddressChangesNothing() throws Exception {
        Host host = new Host() {
            @Override
            public Optional<String> notThis(EmulatorInstance instance) {
                return Optional.of("127.0.0.1:5555 answers as BlueStacks, not MuMu Player");
            }
        };
        host.running = true;
        try (Passwords passwords = new Passwords(email -> Passwords.Answer.once("pw".toCharArray()))) {
            String failed = new InstanceChanges(host, new TestScreens.Clock()).run(MUMU, List.of(
                    change(AccountRecord.Kind.GOOGLE, NEW, ConfigDiff.AccountChange.Op.ADD)), List.of(), passwords,
                    new TestScreens.Ctx());
            assertTrue(failed != null && failed.contains("answers as BlueStacks"), String.valueOf(failed));
        }
        assertFalse(host.log.contains("device"), String.valueOf(host.log));
    }

    /** A remembered password Google calls wrong is forgotten, and the account left out of the batch. */
    @Test
    void aRememberedPasswordGoogleRefusesIsForgotten() throws Exception {
        Host host = new Host();
        host.running = true;
        host.device.onTap = point -> SignInScreen.of(host.device.screen) == SignInScreen.EMAIL
                ? TestScreens.password() : SignInScreen.of(host.device.screen) == SignInScreen.PASSWORD
                ? TestScreens.wrongPassword() : null;
        PasswordStoreTest.FakeStore store = new PasswordStoreTest.FakeStore();
        store.remember(NEW, "old-pw".toCharArray());
        try (Passwords passwords = new Passwords(store, email -> {
            throw new AssertionError("a remembered password is not asked");
        })) {
            String failed = new InstanceChanges(host, new TestScreens.Clock()).run(MUMU, List.of(
                    change(AccountRecord.Kind.GOOGLE, NEW, ConfigDiff.AccountChange.Op.ADD)), List.of(), passwords,
                    new TestScreens.Ctx());
            assertTrue(failed != null && !failed.contains("@"), String.valueOf(failed));
            assertTrue(passwords.left(NEW));
        }
        assertFalse(store.remembered(NEW));
    }

    @Test
    void passwordsAreAskedOnceAndRefusedOnceClosed() throws Exception {
        int[] asked = {0};
        Passwords passwords = new Passwords(email -> {
            asked[0]++;
            return Passwords.Answer.once("pw".toCharArray());
        });
        char[] first = passwords.of("User@Example.com");
        assertTrue(first == passwords.of("user@example.com"));
        assertEquals(1, asked[0]);
        passwords.close();
        assertTrue(Arrays.equals(new char[2], first));
        assertThrows(IllegalStateException.class, () -> passwords.of(NEW));
    }
}
