package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.tree.UiTree;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.botmaker.shared.emulator.google.TestScreens.at;
import static com.botmaker.shared.emulator.google.TestScreens.captured;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceSignInTest {

    private static final String EMAIL = "user@example.com";
    private static final String SECRET = "s3cret pass";

    /** Each screen's Next, tapped, leads to the following one; the account is listed once the services are accepted. */
    private static TestScreens.Device flow(UiTree... screens) {
        TestScreens.Device device = new TestScreens.Device(screens[0]);
        List<UiTree> rest = new ArrayList<>(List.of(screens).subList(1, screens.length));
        device.onTap = point -> {
            SignInScreen screen = SignInScreen.of(device.screen);
            boolean onNext = screen.next(device.screen).map(n -> n.bounds().contains(point)).orElse(false);
            if (!onNext || rest.isEmpty()) return null;
            UiTree next = rest.removeFirst();
            if (SignInScreen.of(next) == SignInScreen.LOADING || rest.isEmpty() && screen == SignInScreen.SERVICES) {
                device.accounts.add(EMAIL);
            }
            return next;
        };
        return device;
    }

    private static InstanceSignIn.Result run(TestScreens.Device device, String password, TestScreens.Ctx ctx)
            throws InterruptedException {
        return new InstanceSignIn(device, new TestScreens.Clock()).signIn(EMAIL, password.toCharArray(), ctx);
    }

    @Test
    void signsInScreenByScreen() throws Exception {
        TestScreens.Device device = flow(captured("mumu-a15-email"), TestScreens.password(), TestScreens.terms(),
                TestScreens.services(), TestScreens.home());
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        InstanceSignIn.Result result = run(device, SECRET, ctx);
        assertEquals(InstanceSignIn.End.SIGNED_IN, result.end(), result.words());
        assertEquals(InstanceSignIn.ADD_ACCOUNT, device.log.getFirst());
        assertEquals(List.of(EMAIL, SECRET), device.typed);
        assertEquals(List.of("type " + EMAIL.length(), "type " + SECRET.length()),
                device.log.stream().filter(l -> l.startsWith("type")).toList());
        for (String said : ctx.steps) {
            assertFalse(said.contains(EMAIL) || said.contains(SECRET) || said.contains("@"), said);
        }
        assertFalse(result.words().contains("@"));
        assertTrue(ctx.needs.isEmpty());
    }

    /** Review item 6: a wrong password ends the sign-in at once, with no second try. */
    @Test
    void wrongPasswordStops() throws Exception {
        TestScreens.Device device = flow(captured("mumu-a15-email"), TestScreens.password(),
                TestScreens.wrongPassword());
        InstanceSignIn.Result result = run(device, SECRET, new TestScreens.Ctx());
        assertEquals(InstanceSignIn.End.WRONG_PASSWORD, result.end());
        assertTrue(result.end().dropsAccount());
        assertEquals(1, device.typed.stream().filter(SECRET::equals).count());
    }

    @Test
    void aPasswordScreenThatComesBackIsAWrongPassword() throws Exception {
        TestScreens.Device device = flow(captured("mumu-a15-email"), TestScreens.password(), TestScreens.password());
        InstanceSignIn.Result result = run(device, SECRET, new TestScreens.Ctx());
        assertEquals(InstanceSignIn.End.WRONG_PASSWORD, result.end());
        assertEquals(1, device.typed.stream().filter(SECRET::equals).count());
    }

    @Test
    void anAccountGoogleDoesNotKnowStops() throws Exception {
        TestScreens.Device device = flow(captured("mumu-a15-email"), captured("mumu-a15-no-such-account"));
        InstanceSignIn.Result result = run(device, SECRET, new TestScreens.Ctx());
        assertEquals(InstanceSignIn.End.NO_SUCH_ACCOUNT, result.end());
        assertEquals(List.of(EMAIL), device.typed);
    }

    /** A captcha is the user's: BotMaker asks once, types nothing into it, and goes on once the screen moves. */
    @Test
    void theCaptchaIsHandedToTheUser() throws Exception {
        TestScreens.Device device = flow(captured("mumu-a15-email"), TestScreens.captcha());
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        int[] answeredAt = {-1};
        device.onPoll = poll -> {
            if (SignInScreen.of(device.screen) != SignInScreen.CAPTCHA) return null;
            if (answeredAt[0] < 0) answeredAt[0] = poll + 30; // the user takes 30 s
            if (poll < answeredAt[0]) return null;
            // past the captcha: the password, then the account
            device.onTap = point -> {
                if (!SignInScreen.PASSWORD.next(device.screen).orElseThrow().bounds().contains(point)) return null;
                device.accounts.add(EMAIL);
                return TestScreens.home();
            };
            return TestScreens.password();
        };
        InstanceSignIn.Result result = run(device, SECRET, ctx);
        assertEquals(InstanceSignIn.End.SIGNED_IN, result.end(), result.words());
        assertEquals(1, ctx.needs.size());
        assertTrue(ctx.needs.getFirst().contains("captcha"), ctx.needs.getFirst());
        assertEquals(1, ctx.resumed);
        assertEquals(List.of(EMAIL, SECRET), device.typed);
    }

    @Test
    void aSecondStepWaitsForTheUser() throws Exception {
        TestScreens.Device device = flow(captured("mumu-a15-email"), TestScreens.password(), TestScreens.twoStep());
        device.onPoll = poll -> {
            if (poll == 40) device.accounts.add(EMAIL); // approved on the phone
            return poll == 40 ? TestScreens.home() : null;
        };
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        assertEquals(InstanceSignIn.End.SIGNED_IN, run(device, SECRET, ctx).end());
        assertEquals(1, ctx.needs.size());
        assertEquals(1, ctx.resumed);
    }

    /** A screen BotMaker doesn't know (Google's passkey) that the user signs in past by hand, no password typed. */
    @Test
    void anAccountTheUserSignedInByHandEndsTheSignIn() throws Exception {
        TestScreens.Device device = flow(captured("mumu-a15-email"), TestScreens.home());
        device.onPoll = poll -> {
            if (poll == 40) device.accounts.add(EMAIL); // the user finished on the live screen
            return null;
        };
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        InstanceSignIn.Result result = run(device, SECRET, ctx);
        assertEquals(InstanceSignIn.End.SIGNED_IN, result.end(), result.words());
        assertEquals(1, ctx.needs.size());
        assertEquals(1, ctx.resumed);
        assertEquals(List.of(EMAIL), device.typed);
    }

    /** Typed by another of the account's addresses: Android lists the main one, which is the account that came. */
    @Test
    void anAccountListedUnderAnotherAddressIsTheOneSignedIn() throws Exception {
        String alias = "alias@example.com";
        TestScreens.Device device = flow(captured("mumu-a15-email"), TestScreens.password(), TestScreens.terms(),
                TestScreens.services(), TestScreens.home());
        device.accounts.add("held@example.com");
        InstanceSignIn.Result result = new InstanceSignIn(device, new TestScreens.Clock())
                .signIn(alias, SECRET.toCharArray(), new TestScreens.Ctx());
        assertEquals(InstanceSignIn.End.SIGNED_IN, result.end(), result.words());
        assertEquals(EMAIL, result.listedAs());
        assertFalse(result.words().contains("@"));
        assertEquals(List.of(alias, SECRET), device.typed);

        assertEquals(Optional.empty(), InstanceSignIn.added(Set.of("a@x", "b@x"), alias, Set.of()),
                "two new accounts: neither is known to be it");
    }

    @Test
    void aPasswordInputTextCannotTypeIsRefusedBeforeAnythingStarts() throws Exception {
        TestScreens.Device device = flow(captured("mumu-a15-email"));
        InstanceSignIn.Result result = run(device, "pässwörd", new TestScreens.Ctx());
        assertEquals(InstanceSignIn.End.CANNOT_TYPE, result.end());
        assertTrue(device.log.isEmpty(), device.log.toString());
        assertFalse(InstanceSignIn.typeable(new char[0]));
        assertTrue(InstanceSignIn.typeable("a b!~".toCharArray()));
        assertTrue(InstanceSignIn.typeable("100%".toCharArray()));
        assertFalse(InstanceSignIn.typeable("Ab%sx9".toCharArray()), "input text types %s as a space");
    }

    @Test
    void anAccountAlreadyThereIsNotSignedInAgain() throws Exception {
        TestScreens.Device device = flow(captured("mumu-a15-email"));
        device.accounts.add(EMAIL);
        assertEquals(InstanceSignIn.End.SIGNED_IN, run(device, SECRET, new TestScreens.Ctx()).end());
        assertTrue(device.log.isEmpty());
    }

    @Test
    void aScreenThatNeverEndsTimesOut() throws Exception {
        TestScreens.Device device = flow(TestScreens.tree(TestScreens.GMS,
                TestScreens.node("com.google.android.gms:id/minute_maid", "", 1)));
        assertEquals(InstanceSignIn.End.TIMED_OUT, run(device, SECRET, new TestScreens.Ctx()).end());
    }

    @Test
    void aCancelGoesHome() throws Exception {
        TestScreens.Device device = flow(captured("mumu-a15-email"), TestScreens.password());
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        var next = device.onTap;
        device.onTap = point -> {
            UiTree after = next.apply(point);
            ctx.cancel = after != null; // cancelled as the email is sent
            return after;
        };
        assertEquals(InstanceSignIn.End.CANCELLED, run(device, SECRET, ctx).end());
        assertEquals("key " + InstanceSignIn.KEY_HOME, device.log.getLast());
        assertFalse(device.typed.contains(SECRET));
    }

    @Test
    void theEmailIsTappedTypedAndNexted() throws Exception {
        UiTree email = captured("mumu-a15-email");
        TestScreens.Device device = flow(email, TestScreens.password());
        device.onPoll = poll -> poll > 3 ? TestScreens.wrongPassword() : null;
        run(device, SECRET, new TestScreens.Ctx());
        Point next = new Point((int) SignInScreen.EMAIL.next(email).orElseThrow().bounds().getCenterX(),
                (int) SignInScreen.EMAIL.next(email).orElseThrow().bounds().getCenterY());
        assertEquals(List.of(InstanceSignIn.ADD_ACCOUNT, "tap 800,435", "type " + EMAIL.length(),
                "tap " + next.x + "," + next.y), device.log.subList(0, 4));
        assertEquals(at(TestScreens.password(), "Next").y, at(TestScreens.wrongPassword(), "Next").y);
    }
}
