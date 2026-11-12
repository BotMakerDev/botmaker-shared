package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.tree.UiNode;
import com.botmaker.shared.emulator.tree.UiTree;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;

import static com.botmaker.shared.emulator.google.TestScreens.GMS;
import static com.botmaker.shared.emulator.google.TestScreens.captured;
import static com.botmaker.shared.emulator.google.TestScreens.node;
import static com.botmaker.shared.emulator.google.TestScreens.tree;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignInScreenTest {

    /** Captured 2026-11-12 on MuMu 12, Android 15. */
    @Test
    void readsTheCapturedEmailScreens() {
        UiTree email = captured("mumu-a15-email");
        assertEquals(SignInScreen.EMAIL, SignInScreen.of(email));
        assertEquals(new Rectangle(358, 394, 884, 83), SignInScreen.emailField(email).orElseThrow().bounds());
        UiNode next = SignInScreen.EMAIL.next(email).orElseThrow();
        assertEquals("identifierNext", next.shortId());
        assertEquals(SignInScreen.NO_SUCH_ACCOUNT, SignInScreen.of(captured("mumu-a15-no-such-account")));
    }

    @Test
    void aPageWithNoWordsYetIsLoading() {
        assertEquals(SignInScreen.LOADING, SignInScreen.of(null));
        assertEquals(SignInScreen.LOADING, SignInScreen.of(tree(GMS, node("com.google.android.gms:id/minute_maid", "",
                1))));
    }

    @Test
    void readsTheScreensPastTheEmail() {
        assertEquals(SignInScreen.PASSWORD, SignInScreen.of(TestScreens.password()));
        assertEquals("passwordNext", SignInScreen.PASSWORD.next(TestScreens.password()).orElseThrow().shortId());
        assertEquals(SignInScreen.WRONG_PASSWORD, SignInScreen.of(TestScreens.wrongPassword()));
        assertEquals(SignInScreen.TWO_STEP, SignInScreen.of(TestScreens.twoStep()));
        assertEquals(SignInScreen.TWO_STEP, SignInScreen.of(tree(GMS, node("", "Verify it’s you", 1),
                node("", "", 4, true), node("passwordNext", "Next", 5))), "a password asked again is the user's");
        assertEquals(SignInScreen.CAPTCHA, SignInScreen.of(TestScreens.captcha()));
        assertEquals(SignInScreen.TERMS, SignInScreen.of(TestScreens.terms()));
        assertEquals(SignInScreen.SERVICES, SignInScreen.of(TestScreens.services()));
        assertEquals(SignInScreen.REFUSED, SignInScreen.of(tree(GMS, node("", "Couldn’t sign you in", 1),
                node("", "This browser or app may not be secure.", 2))));
        assertEquals(SignInScreen.PHONE_PROMPT, SignInScreen.of(tree(GMS, node("", "Add phone number?", 1),
                node("", "Skip", 5))));
        assertEquals(SignInScreen.UNKNOWN, SignInScreen.of(tree(GMS, node("", "Choose an account", 1))));
    }

    @Test
    void servicesPressMoreUntilAcceptShows() {
        UiTree more = tree(GMS, node("", "Google services", 1), node("", "More", 5));
        assertEquals("More", SignInScreen.SERVICES.next(more).orElseThrow().text());
        assertEquals("Accept", SignInScreen.SERVICES.next(TestScreens.services()).orElseThrow().text());
    }

    @Test
    void onlyTheSecondStepTheCaptchaAndUnknownNeedTheUser() {
        for (SignInScreen screen : SignInScreen.values()) {
            boolean user = screen == SignInScreen.TWO_STEP || screen == SignInScreen.CAPTCHA
                    || screen == SignInScreen.UNKNOWN;
            assertEquals(user, screen.needsYou(), screen.id());
            assertEquals(screen, SignInScreen.fromId(screen.id()));
        }
        assertEquals(SignInScreen.UNKNOWN, SignInScreen.fromId("nope"));
    }

    @Test
    void readsTheSignOutScreens() {
        String email = "user@example.com";
        assertEquals(SignOutScreen.UNKNOWN, SignOutScreen.of(captured("mumu-a15-accounts-none"), email));
        assertEquals(SignOutScreen.ACCOUNTS, SignOutScreen.of(TestScreens.accountsList(email), email));
        assertEquals(SignOutScreen.ACCOUNTS, SignOutScreen.of(TestScreens.accountsList("User@Example.com"), email));
        assertEquals(SignOutScreen.UNKNOWN, SignOutScreen.of(TestScreens.accountsList("other@example.com"), email));
        assertEquals(SignOutScreen.ACCOUNT, SignOutScreen.of(TestScreens.accountPage(), email));
        assertEquals(SignOutScreen.CONFIRM, SignOutScreen.of(TestScreens.confirm(), email));
        assertEquals("android:id/button1", SignOutScreen.CONFIRM.next(TestScreens.confirm(), email).orElseThrow().id());
        assertFalse(SignOutScreen.ACCOUNT.next(TestScreens.accountPage(), email).isEmpty());
        assertTrue(SignOutScreen.of(null, email) == SignOutScreen.LOADING);
    }
}
