// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.config.AccountRecord.Verification;
import com.botmaker.shared.emulator.tree.Trait;
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

    /** Captured 2026-11-15 on LDPlayer 14, Android 14: no HTML id reaches the tree, and NEXT is a native button. */
    @Test
    void readsTheEmailScreenWithoutGooglesIds() {
        UiTree email = captured("ldplayer-a14-email");
        assertEquals(SignInScreen.EMAIL, SignInScreen.of(email));
        assertEquals(new Rectangle(198, 430, 885, 83), SignInScreen.emailField(email).orElseThrow().bounds());
        assertEquals("NEXT", SignInScreen.EMAIL.next(email).orElseThrow().text());
        String field = "<node resource-id=\"\" text=\"\" class=\"android.widget.EditText\" package=\"PKG\" "
                + "bounds=\"[100,400][700,480]\"/>";
        assertEquals(SignInScreen.CODE, SignInScreen.of(tree(GMS, node("", "2-Step Verification", 1),
                        node("", "Enter the code", 2), field, node("", "Create account", 6))),
                "a code's one field is the code's, even beside the email page's words");
        assertEquals(SignInScreen.UNKNOWN, SignInScreen.of(tree(GMS, node("", "Create account", 1), field,
                field.replace("400][700,480", "500][700,580"))), "two fields are not the email page's one");
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
        assertEquals(SignInScreen.PHONE_TAP, SignInScreen.of(TestScreens.twoStep()));
        assertEquals(SignInScreen.TWO_STEP, SignInScreen.of(tree(GMS, node("", "2-Step Verification", 1),
                node("", "Insert your security key", 2))));
        assertEquals(SignInScreen.TWO_STEP, SignInScreen.of(tree(GMS, node("", "Verify it’s you", 1),
                node("", "", 4, true), node("passwordNext", "Next", 5))), "a password asked again is the user's");
        assertEquals(SignInScreen.TWO_STEP, SignInScreen.of(tree(GMS, node("", "Confirm you’re signing in", 1),
                node("", "Try another way", 5))), "a page offering another way is a second step, whatever its heading");
        assertEquals(SignInScreen.TWO_STEP, SignInScreen.of(tree(GMS, node("", "Welcome", 1),
                node("", "More ways to verify", 5))));
        assertEquals(SignInScreen.CAPTCHA, SignInScreen.of(TestScreens.captcha()));
        assertEquals(SignInScreen.TERMS, SignInScreen.of(TestScreens.terms()));
        assertEquals(SignInScreen.SERVICES, SignInScreen.of(TestScreens.services()));
        assertEquals(SignInScreen.REFUSED, SignInScreen.of(tree(GMS, node("", "Couldn’t sign you in", 1),
                node("", "This browser or app may not be secure.", 2))));
        assertEquals(SignInScreen.PHONE_PROMPT, SignInScreen.of(tree(GMS, node("", "Add phone number?", 1),
                node("", "Skip", 5))));
        assertEquals(SignInScreen.UNKNOWN, SignInScreen.of(tree(GMS, node("", "Choose an account", 1))));
    }

    /** The second steps, split by what each shows; the list first, as its rows name every method. */
    @Test
    void readsEachSecondStep() {
        UiTree list = TestScreens.challenges("phone", "code", "backup", "key");
        assertEquals(SignInScreen.CHALLENGES, SignInScreen.of(list));
        assertEquals("Tap Yes on your phone or tablet", SignInScreen.row(list, Verification.PHONE).orElseThrow().text());
        assertTrue(SignInScreen.row(list, Verification.CODE).orElseThrow().text().contains("Authenticator"));
        assertTrue(SignInScreen.row(list, Verification.BACKUP_CODE).orElseThrow().text().contains("backup"));
        assertTrue(SignInScreen.row(TestScreens.challenges("phone", "key"), Verification.BACKUP_CODE).isEmpty());
        assertTrue(SignInScreen.row(list, Verification.ASK).isEmpty());

        assertEquals(SignInScreen.PASSKEY, SignInScreen.of(TestScreens.passkey()));
        assertEquals("Try another way", SignInScreen.anotherWay(TestScreens.passkey()).orElseThrow().text());
        UiTree boxes = TestScreens.waysInBoxes();
        assertEquals(SignInScreen.CHALLENGES, SignInScreen.of(boxes), "the list by its rows, under any heading");
        UiNode phone = SignInScreen.row(boxes, Verification.PHONE).orElseThrow();
        assertTrue(phone.has(Trait.CLICKABLE), "the tap goes to the row around the words");
        assertEquals(new Rectangle(50, 300, 850, 90), phone.bounds());
        assertEquals(SignInScreen.PASSWORD, SignInScreen.of(tree(GMS, node("", "Welcome", 1),
                node("", "Select “Try another way” to use your passkey for an easier, more secure sign-in", 2),
                node("", "", 4, true), node("passwordNext", "Next", 5), node("", "Try another way", 6))),
                "the password page's passkey hint is not the passkey's screen");

        assertEquals(SignInScreen.CODE, SignInScreen.of(TestScreens.code(false)));
        assertEquals(SignInScreen.CODE, SignInScreen.of(TestScreens.code(true)));
        assertEquals(SignInScreen.CODE, SignInScreen.of(tree(GMS, node("", "2-Step Verification", 1),
                node("idvPin", "", 3), node("", "Lost your phone? Use a backup code", 4))), "Google's id wins");
        assertTrue(SignInScreen.wrongCode(TestScreens.code(true)));
        assertFalse(SignInScreen.wrongCode(TestScreens.code(false)));
        assertEquals("totpPin", SignInScreen.codeField(TestScreens.code(false)).orElseThrow().shortId());
        assertEquals("totpNext", SignInScreen.CODE.next(TestScreens.code(false)).orElseThrow().shortId());
        assertEquals(SignInScreen.BACKUP_CODE, SignInScreen.of(TestScreens.backupCode()));
        assertEquals("backupCodePin", SignInScreen.codeField(TestScreens.backupCode()).orElseThrow().shortId());
        assertEquals("Try another way", SignInScreen.anotherWay(TestScreens.twoStep()).orElseThrow().text());

        assertTrue(SignInScreen.PHONE_TAP.answers(Verification.PHONE));
        assertTrue(SignInScreen.CODE.answers(Verification.CODE));
        assertTrue(SignInScreen.BACKUP_CODE.answers(Verification.BACKUP_CODE));
        assertFalse(SignInScreen.CODE.answers(Verification.BACKUP_CODE));
        assertFalse(SignInScreen.TWO_STEP.answers(Verification.ASK));
    }

    @Test
    void servicesPressMoreUntilAcceptShows() {
        UiTree more = tree(GMS, node("", "Google services", 1), node("", "More", 5));
        assertEquals("More", SignInScreen.SERVICES.next(more).orElseThrow().text());
        assertEquals("Accept", SignInScreen.SERVICES.next(TestScreens.services()).orElseThrow().text());
    }

    @Test
    void onlyTheSecondStepsTheCaptchaAndUnknownNeedTheUser() {
        for (SignInScreen screen : SignInScreen.values()) {
            boolean user = screen.secondStep() || screen == SignInScreen.CAPTCHA || screen == SignInScreen.UNKNOWN;
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
