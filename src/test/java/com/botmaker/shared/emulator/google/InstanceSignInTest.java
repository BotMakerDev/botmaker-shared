// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.config.AccountRecord.Verification;
import com.botmaker.shared.emulator.config.Operation;
import com.botmaker.shared.emulator.tree.UiNode;
import com.botmaker.shared.emulator.tree.UiTree;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

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

    private static InstanceSignIn.Result run(TestScreens.Device device, Verification method, Codes codes,
                                             TestScreens.Ctx ctx) throws InterruptedException {
        return new InstanceSignIn(device, new TestScreens.Clock()).signIn(EMAIL, SECRET.toCharArray(),
                new InstanceSignIn.SecondStep(email -> method, codes), ctx);
    }

    /** Past the password to {@code first}; from there a tap leads where {@code then} says, given the screen. */
    private static TestScreens.Device toSecondStep(UiTree first, BiFunction<TestScreens.Device, Point, UiTree> then) {
        TestScreens.Device device = flow(captured("mumu-a15-email"), TestScreens.password(), first);
        Function<Point, UiTree> before = device.onTap;
        device.onTap = point -> SignInScreen.of(device.screen).secondStep() ? then.apply(device, point)
                : before.apply(point);
        return device;
    }

    private static boolean on(Optional<UiNode> node, Point point) {
        return node.map(n -> n.bounds().contains(point)).orElse(false);
    }

    /** The account signed in, as Google's last answer to a tap. */
    private static UiTree signedIn(TestScreens.Device device) {
        device.accounts.add(EMAIL);
        return TestScreens.home();
    }

    /** Answers each ask with the next of {@code answers}, keeping which try each was. */
    private static final class FakeCodes implements Codes {
        final List<Integer> attempts = new ArrayList<>();
        final List<Verification> methods = new ArrayList<>();
        private final List<String> answers;

        FakeCodes(String... answers) {
            this.answers = new ArrayList<>(List.of(answers));
        }

        @Override
        public char[] ask(String email, Verification method, int attempt) {
            assertEquals(EMAIL, email);
            attempts.add(attempt);
            methods.add(method);
            return answers.isEmpty() ? null : (answers.size() == 1 ? answers.getFirst() : answers.removeFirst())
                    .toCharArray();
        }
    }

    /** The phone prompt isn't the account's method: Try another way, the code's row, then the code asked and typed. */
    @Test
    void anotherWayToTheMethodsRowThenTheCodeTyped() throws Exception {
        TestScreens.Device device = toSecondStep(TestScreens.twoStep(), (d, point) -> switch (SignInScreen.of(d.screen)) {
            case PHONE_TAP -> on(SignInScreen.anotherWay(d.screen), point)
                    ? TestScreens.challenges("phone", "code", "backup") : null;
            case CHALLENGES -> on(SignInScreen.row(d.screen, Verification.CODE), point) ? TestScreens.code(false)
                    : null;
            case CODE -> on(SignInScreen.CODE.next(d.screen), point) ? signedIn(d) : null;
            default -> null;
        });
        FakeCodes codes = new FakeCodes("123456");
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        InstanceSignIn.Result result = run(device, Verification.CODE, codes, ctx);
        assertEquals(InstanceSignIn.End.SIGNED_IN, result.end(), result.words());
        assertEquals(List.of(EMAIL, SECRET, "123456"), device.typed);
        assertEquals(List.of(1), codes.attempts);
        assertEquals(List.of(Verification.CODE), codes.methods);
        assertEquals(1, ctx.needs.size());
        assertTrue(ctx.needs.getFirst().contains("dialog"), ctx.needs.getFirst());
        assertEquals(1, ctx.resumed);
        assertTrue(device.log.contains("type 6"));
        for (String said : ctx.steps) assertFalse(said.contains("123456") || said.contains("@"), said);
        assertFalse(ctx.needs.getFirst().contains("123456"));
    }

    /**
     * The email's Next tapped once without effect (seen live 2026-11-14: "Google stayed on the email screen") is
     * tapped again, and the email typed once only.
     */
    @Test
    void theEmailsNextIsTappedAgainWhenTheFirstDidNothing() throws Exception {
        TestScreens.Device device = flow(captured("mumu-a15-email"), TestScreens.password(), TestScreens.services(),
                TestScreens.home());
        Function<Point, UiTree> moves = device.onTap;
        int[] nexts = {0};
        device.onTap = point -> SignInScreen.of(device.screen) == SignInScreen.EMAIL
                && on(SignInScreen.EMAIL.next(device.screen), point) && ++nexts[0] == 1 ? null : moves.apply(point);
        InstanceSignIn.Result result = run(device, SECRET, new TestScreens.Ctx());
        assertEquals(InstanceSignIn.End.SIGNED_IN, result.end(), result.words());
        assertEquals(2, nexts[0]);
        assertEquals(List.of(EMAIL, SECRET), device.typed);
    }

    /**
     * As seen live (2026-11-14): Google's passkey first, whose first "Try another way" a loading page loses; tapped
     * again, the list comes under a heading BotMaker doesn't read, and the phone's row, tapped as the box around its
     * words, brings the phone prompt, which waits.
     */
    @Test
    void thePasskeyIsAnsweredWithAnotherWayTriedAgainThenThePhonesRow() throws Exception {
        int[] anotherWay = {0};
        TestScreens.Device device = toSecondStep(TestScreens.passkey(), (d, point) -> switch (SignInScreen.of(d.screen)) {
            case PASSKEY -> on(SignInScreen.anotherWay(d.screen), point) && ++anotherWay[0] == 2
                    ? TestScreens.waysInBoxes() : null;
            case CHALLENGES -> on(SignInScreen.row(d.screen, Verification.PHONE), point) ? TestScreens.twoStep()
                    : null;
            default -> null;
        });
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        device.onPoll = poll -> SignInScreen.of(device.screen) == SignInScreen.PHONE_TAP && !ctx.needs.isEmpty()
                ? signedIn(device) : null;
        FakeCodes codes = new FakeCodes("123456");
        InstanceSignIn.Result result = run(device, Verification.PHONE, codes, ctx);
        assertEquals(InstanceSignIn.End.SIGNED_IN, result.end(), result.words());
        assertEquals(2, anotherWay[0], "the lost tap is tried again");
        assertEquals(1, ctx.needs.size());
        assertTrue(ctx.needs.getFirst().contains("phone"), ctx.needs.getFirst());
        assertTrue(codes.attempts.isEmpty());
        assertEquals(List.of(EMAIL, SECRET), device.typed);
    }

    /** The phone prompt is the account's method: BotMaker taps nothing and waits for the user's phone. */
    @Test
    void thePhonePromptWaits() throws Exception {
        UiTree phone = TestScreens.twoStep();
        TestScreens.Device device = toSecondStep(phone, (d, point) -> null);
        device.onPoll = poll -> poll == 40 ? signedIn(device) : null;
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        FakeCodes codes = new FakeCodes("123456");
        assertEquals(InstanceSignIn.End.SIGNED_IN, run(device, Verification.PHONE, codes, ctx).end());
        assertEquals(1, ctx.needs.size());
        assertTrue(ctx.needs.getFirst().contains("phone"), ctx.needs.getFirst());
        assertEquals(1, ctx.resumed);
        Point another = TestScreens.at(phone, "Try another way");
        assertFalse(device.log.contains("tap " + another.x + "," + another.y));
        assertTrue(codes.attempts.isEmpty());
    }

    /** A refused code is asked again, three tries in all, then the screen is the user's. */
    @Test
    void aRefusedCodeIsAskedAgainThreeTimes() throws Exception {
        TestScreens.Device device = toSecondStep(TestScreens.code(false),
                (d, point) -> on(SignInScreen.CODE.next(d.screen), point) ? TestScreens.code(true) : null);
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        device.onPoll = poll -> !ctx.needs.isEmpty() && ctx.needs.getLast().contains("refused") ? signedIn(device)
                : null;
        FakeCodes codes = new FakeCodes("111111", "222222", "333333");
        assertEquals(InstanceSignIn.End.SIGNED_IN, run(device, Verification.CODE, codes, ctx).end());
        assertEquals(List.of(1, 2, 3), codes.attempts);
        assertEquals(List.of(EMAIL, SECRET, "111111", "222222", "333333"), device.typed);
        assertEquals(4, ctx.needs.size());
        assertTrue(ctx.needs.getLast().contains("refused " + InstanceSignIn.CODE_TRIES), ctx.needs.getLast());
        // the refused code Google keeps in the field is deleted before each next one
        int first = device.log.indexOf("type 6");
        int second = first + 1 + device.log.subList(first + 1, device.log.size()).indexOf("type 6");
        assertEquals("key " + InstanceSignIn.KEY_DEL, device.log.get(second - 1));
        assertEquals(2, device.log.stream().filter(("key " + InstanceSignIn.KEY_MOVE_END)::equals).count());
    }

    private static InstanceSignIn.Result run(TestScreens.Device device, AuthenticatorKeys keys, Codes codes,
                                             TestScreens.Ctx ctx) throws InterruptedException {
        return new InstanceSignIn(device, new TestScreens.Clock()).signIn(EMAIL, SECRET.toCharArray(),
                new InstanceSignIn.SecondStep(email -> Verification.AUTHENTICATOR, codes, keys), ctx);
    }

    private static AuthenticatorKeys keyed() {
        AuthenticatorKeys keys = new AuthenticatorKeys(new PasswordStoreTest.FakeStore());
        assertEquals(AuthenticatorKeys.Put.KEPT, keys.put(EMAIL, AuthenticatorKeysTest.KEY.toCharArray()));
        return keys;
    }

    /** The code {@link AuthenticatorKeysTest#KEY} gives in the 30-second window {@code window}. */
    private static String keyCode(long window) {
        return new String(Totp.code(Totp.decode(AuthenticatorKeysTest.KEY.toCharArray()).orElseThrow(),
                window * Totp.STEP.toMillis()));
    }

    /** The account's key held: the authenticator's row chosen, its code made and typed, nothing asked of the user. */
    @Test
    void theAuthenticatorsCodeIsTypedFromItsKeyWithNoDialog() throws Exception {
        TestScreens.Device device = toSecondStep(TestScreens.challenges("phone", "code", "backup"),
                (d, point) -> switch (SignInScreen.of(d.screen)) {
                    case CHALLENGES -> on(SignInScreen.row(d.screen, Verification.AUTHENTICATOR), point)
                            ? TestScreens.code(false) : null;
                    case CODE -> on(SignInScreen.CODE.next(d.screen), point) ? signedIn(d) : null;
                    default -> null;
                });
        FakeCodes codes = new FakeCodes("123456");
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        InstanceSignIn.Result result = run(device, keyed(), codes, ctx);
        assertEquals(InstanceSignIn.End.SIGNED_IN, result.end(), result.words());
        assertEquals(List.of(EMAIL, SECRET, keyCode(0)), device.typed);
        assertTrue(codes.attempts.isEmpty(), "no dialog");
        assertTrue(ctx.needs.isEmpty(), ctx.needs.toString());
        for (String said : ctx.steps) assertFalse(said.contains(keyCode(0)), said);
        assertTrue(ctx.steps.stream().anyMatch(s -> s.contains("6 characters")), ctx.steps.toString());
    }

    /** Google showing a texted code's screen: the key's code isn't typed there, another way is tried. */
    @Test
    void theKeysCodeIsNeverTypedIntoATextedCodesScreen() throws Exception {
        TestScreens.Device device = toSecondStep(TestScreens.texted(),
                (d, point) -> switch (SignInScreen.of(d.screen)) {
                    case CODE -> SignInScreen.authenticatorCode(d.screen)
                            ? on(SignInScreen.CODE.next(d.screen), point) ? signedIn(d) : null
                            : on(SignInScreen.anotherWay(d.screen), point)
                            ? TestScreens.challenges("phone", "code") : null;
                    case CHALLENGES -> on(SignInScreen.row(d.screen, Verification.AUTHENTICATOR), point)
                            ? TestScreens.code(false) : null;
                    default -> null;
                });
        FakeCodes codes = new FakeCodes("123456");
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        InstanceSignIn.Result result = run(device, keyed(), codes, ctx);
        assertEquals(InstanceSignIn.End.SIGNED_IN, result.end(), result.words());
        assertEquals(3, device.typed.size(), "one code typed, on the authenticator's screen");
        assertTrue(codes.attempts.isEmpty(), "no dialog");
    }

    /** An alias's key is its account's: looked up under the address the book keeps it under. */
    @Test
    void theKeyIsLookedUpUnderTheAccountsMainAddress() throws Exception {
        TestScreens.Device device = toSecondStep(TestScreens.code(false),
                (d, point) -> on(SignInScreen.CODE.next(d.screen), point) ? signedIn(d) : null);
        AuthenticatorKeys keys = new AuthenticatorKeys(new PasswordStoreTest.FakeStore());
        keys.put("main@example.com", AuthenticatorKeysTest.KEY.toCharArray());
        FakeCodes codes = new FakeCodes("123456");
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        InstanceSignIn.Result result = new InstanceSignIn(device, new TestScreens.Clock()).signIn(EMAIL,
                SECRET.toCharArray(), new InstanceSignIn.SecondStep(email -> Verification.AUTHENTICATOR, codes, keys,
                        email -> "main@example.com"), ctx);
        assertEquals(InstanceSignIn.End.SIGNED_IN, result.end(), result.words());
        assertEquals(List.of(EMAIL, SECRET, keyCode(0)), device.typed);
        assertTrue(codes.attempts.isEmpty(), "no dialog");
    }

    /**
     * A code Google refused is tried with the window before (this PC's clock ahead), then the next one's once it comes
     * (behind), then the code is asked as a typed one's.
     */
    @Test
    void aRefusedKeysCodeIsTriedInTheWindowsAroundThenAsked() throws Exception {
        TestScreens.Device device = toSecondStep(TestScreens.code(false),
                (d, point) -> on(SignInScreen.CODE.next(d.screen), point) ? TestScreens.code(true) : null);
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        FakeCodes codes = new FakeCodes("333333");
        device.onPoll = poll -> !codes.attempts.isEmpty() ? signedIn(device) : null;
        InstanceSignIn.Result result = run(device, keyed(), codes, ctx);
        assertEquals(InstanceSignIn.End.SIGNED_IN, result.end(), result.words());
        assertEquals(List.of(EMAIL, SECRET, keyCode(0), keyCode(-1), keyCode(1), "333333"), device.typed);
        assertEquals(List.of(Verification.CODE), codes.methods, "asked as a typed code");
        assertTrue(ctx.steps.stream().anyMatch(s -> s.contains("refused the codes")), ctx.steps.toString());
    }

    /** No key held for the account: its code is asked, as a typed code's. */
    @Test
    void anAuthenticatorAccountWithNoKeyIsAsked() throws Exception {
        TestScreens.Device device = toSecondStep(TestScreens.code(false),
                (d, point) -> on(SignInScreen.CODE.next(d.screen), point) ? signedIn(d) : null);
        FakeCodes codes = new FakeCodes("123456");
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        InstanceSignIn.Result result = run(device, AuthenticatorKeys.NONE, codes, ctx);
        assertEquals(InstanceSignIn.End.SIGNED_IN, result.end(), result.words());
        assertEquals(List.of(EMAIL, SECRET, "123456"), device.typed);
        assertEquals(List.of(Verification.CODE), codes.methods);
        assertTrue(ctx.steps.stream().anyMatch(s -> s.contains("holds no authenticator key")), ctx.steps.toString());
    }

    @Test
    void theAuthenticatorIsAnAccountsOwnNeverTheBooksDefault() {
        assertTrue(Verification.AUTHENTICATOR.isMethod());
        assertFalse(Verification.defaults().contains(Verification.AUTHENTICATOR));
        assertEquals(Verification.FIRST_DEFAULT, Verification.DEFAULT.followed(Verification.AUTHENTICATOR));
        assertEquals(Verification.AUTHENTICATOR, Verification.fromId("authenticator"));
    }

    /** Cancelled while the code's dialog was open: nothing typed, and the instance sent home. */
    @Test
    void aCancelWhileTheCodeIsAskedTypesNothing() throws Exception {
        TestScreens.Device device = toSecondStep(TestScreens.code(false), (d, point) -> null);
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        Codes cancelling = (email, method, attempt) -> {
            ctx.cancel = true;
            return "123456".toCharArray();
        };
        assertEquals(InstanceSignIn.End.CANCELLED, run(device, Verification.CODE, cancelling, ctx).end());
        assertEquals(List.of(EMAIL, SECRET), device.typed);
        assertEquals(1, ctx.resumed);
        assertEquals("key " + InstanceSignIn.KEY_HOME, device.log.getLast());
    }

    /** Handed over, the user picks another way: a page loading between doesn't let BotMaker tap against it. */
    @Test
    void aSecondStepHandedOverStaysTheUsers() throws Exception {
        UiTree list = TestScreens.challenges("code");
        TestScreens.Device device = toSecondStep(list, (d, point) -> null);
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        int[] handedAt = {-1};
        device.onPoll = poll -> {
            if (ctx.needs.isEmpty()) return null;
            if (handedAt[0] < 0) handedAt[0] = poll;
            int after = poll - handedAt[0];
            if (after == 3) return TestScreens.tree(TestScreens.GMS, TestScreens.node("", "", 1)); // loading
            if (after == 5) return TestScreens.code(false); // the user tapped the code's row
            return after == 40 ? signedIn(device) : null;
        };
        FakeCodes codes = new FakeCodes("123456");
        assertEquals(InstanceSignIn.End.SIGNED_IN, run(device, Verification.PHONE, codes, ctx).end());
        assertTrue(ctx.needs.getFirst().contains("phone prompt"), ctx.needs.getFirst());
        assertEquals(1, ctx.needs.size());
        assertEquals(1, ctx.resumed, "resumed once, as the account came, not by the page loading");
        Point another = TestScreens.at(TestScreens.code(false), "Try another way");
        assertFalse(device.log.contains("tap " + another.x + "," + another.y));
        assertTrue(codes.attempts.isEmpty());
    }

    /** The account's method isn't in Google's list: the list is handed to the user, naming the method. */
    @Test
    void aMissingMethodHandsTheScreenOver() throws Exception {
        TestScreens.Device device = toSecondStep(TestScreens.twoStep(), (d, point) ->
                SignInScreen.of(d.screen) == SignInScreen.PHONE_TAP && on(SignInScreen.anotherWay(d.screen), point)
                        ? TestScreens.challenges("phone", "code") : null);
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        device.onPoll = poll -> ctx.needs.isEmpty() ? null : signedIn(device);
        FakeCodes codes = new FakeCodes("12345678");
        assertEquals(InstanceSignIn.End.SIGNED_IN, run(device, Verification.BACKUP_CODE, codes, ctx).end());
        assertEquals(1, ctx.needs.size());
        assertTrue(ctx.needs.getFirst().contains("backup code"), ctx.needs.getFirst());
        assertTrue(codes.attempts.isEmpty());
        assertEquals(List.of(EMAIL, SECRET), device.typed);
    }

    /** No code given (the dialog's Show the screen): the screen is the user's, nothing typed. */
    @Test
    void aCodeNotGivenLeavesTheScreenToTheUser() throws Exception {
        TestScreens.Device device = toSecondStep(TestScreens.backupCode(), (d, point) -> null);
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        device.onPoll = poll -> poll == 40 ? signedIn(device) : null;
        assertEquals(InstanceSignIn.End.SIGNED_IN, run(device, Verification.BACKUP_CODE, Codes.NONE, ctx).end());
        assertEquals(1, ctx.needs.size());
        assertTrue(ctx.needs.getFirst().contains("backup codes"), ctx.needs.getFirst());
        assertEquals(List.of(EMAIL, SECRET), device.typed);

        InstanceSignIn.SecondStep second = new InstanceSignIn.SecondStep(email -> Verification.DEFAULT, null);
        assertEquals(Verification.ASK, second.method(EMAIL), "a default not followed yet is the user's");
        assertEquals(Verification.ASK, new InstanceSignIn.SecondStep(email -> null, null).method(EMAIL));
        assertEquals(Verification.ASK, new InstanceSignIn.SecondStep(email -> Verification.UNKNOWN, null)
                .method(EMAIL));
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
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        InstanceSignIn.Result result = new InstanceSignIn(device, new TestScreens.Clock())
                .signIn(alias, SECRET.toCharArray(), ctx);
        assertEquals(InstanceSignIn.End.SIGNED_IN, result.end(), result.words());
        assertEquals(EMAIL, result.listedAs());
        assertEquals(List.of(new Operation.Finding.Alias(alias, EMAIL)), ctx.found,
                "the address Android lists is offered as an alias of the one typed");
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
