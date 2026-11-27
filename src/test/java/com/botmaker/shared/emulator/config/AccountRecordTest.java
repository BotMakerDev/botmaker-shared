// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator.config;

import com.botmaker.shared.emulator.config.AccountRecord.Kind;
import com.botmaker.shared.emulator.config.AccountRecord.Verification;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An account's addresses and the verification its sign-in follows (Google sign-in plan, phase 2). */
class AccountRecordTest {

    private static final AccountRecord ONE = new AccountRecord(Kind.GOOGLE, "Player-One@example.com", "farm",
            List.of(" P.One@Example.com ", "p.one@example.com", "player-one@example.com", ""), Verification.CODE);

    @Test
    void aliasesAreLowerCaseEachOnceAndNeverTheMainAddress() {
        assertEquals(List.of("p.one@example.com"), ONE.aliases());
        assertEquals(List.of("Player-One@example.com", "p.one@example.com"), ONE.addresses());
        assertEquals(new AccountRecord.Key(Kind.GOOGLE, "player-one@example.com"), ONE.key(), "an alias is no key");
    }

    @Test
    void answersToAnyOfItsAddressesCaseAndSpacesAside() {
        assertTrue(ONE.answersTo("player-one@EXAMPLE.com"));
        assertTrue(ONE.answersTo("  P.ONE@example.com"));
        assertFalse(ONE.answersTo("other@example.com"));
        assertFalse(ONE.answersTo(""));
        assertFalse(ONE.answersTo(null));
    }

    @Test
    void theThreePartConstructorHasNoAliasAndTheDefault() {
        AccountRecord plain = new AccountRecord(Kind.GOOGLE, "farm@example.com", "farm");
        assertEquals(List.of(), plain.aliases());
        assertEquals(Verification.DEFAULT, plain.verify());
        assertEquals(new AccountRecord(Kind.GOOGLE, "farm@example.com", "farm", null, null), plain);
    }

    @Test
    void aliasesAreAddedOnceAndTakenOff() {
        AccountRecord two = ONE.withAlias("ALT@example.com").withAlias("alt@example.com").withAlias("p.one@example.com");
        assertEquals(List.of("p.one@example.com", "alt@example.com"), two.aliases());
        assertEquals(List.of("alt@example.com"), two.withoutAlias(" P.one@example.com").aliases());
        assertEquals("farm", two.note());
        assertEquals(Verification.CODE, two.verify(), "the other parts stay");
    }

    @Test
    void fromIdIsTotalAndEachIdReadsBack() {
        Arrays.stream(Verification.values()).forEach(method -> assertEquals(method, Verification.fromId(method.id())));
        assertEquals(Verification.UNKNOWN, Verification.fromId("security-key"));
        assertEquals(Verification.DEFAULT, Verification.fromId(""));
        assertEquals(Verification.DEFAULT, Verification.fromId(null));
    }

    @Test
    void theDefaultFollowsTheBooksAndAnUnknownIsHandedToTheUser() {
        assertEquals(Verification.CODE, Verification.DEFAULT.followed(Verification.CODE));
        assertEquals(Verification.FIRST_DEFAULT, Verification.DEFAULT.followed(Verification.DEFAULT));
        assertEquals(Verification.BACKUP_CODE, Verification.BACKUP_CODE.followed(Verification.PHONE));
        assertEquals(Verification.ASK, Verification.UNKNOWN.followed(Verification.PHONE));
        assertEquals(List.of(Verification.PHONE, Verification.CODE, Verification.AUTHENTICATOR,
                Verification.BACKUP_CODE, Verification.ASK), Verification.methods());
        assertEquals(List.of(Verification.PHONE, Verification.CODE, Verification.BACKUP_CODE, Verification.ASK),
                Verification.defaults());
    }

    @Test
    void aLaunchTargetsAccountIsKeyedByItsTargetSoTwoGamesMayEachHaveAMain() {
        AccountRecord one = AccountRecord.ofLaunchTarget(" emu-app:com.one ", "Main", "");
        AccountRecord two = AccountRecord.ofLaunchTarget("steam:12345", "main", "");
        assertEquals(new AccountRecord.Key(Kind.LAUNCH_TARGET, "emu-app:com.one", "main"), one.key());
        assertFalse(one.key().equals(two.key()), "another launch target's main is another account");
        assertEquals("launch-target", Kind.LAUNCH_TARGET.id());
        assertEquals(Kind.LAUNCH_TARGET, Kind.fromId("launch-target"));
        assertFalse(Kind.LAUNCH_TARGET.signedInByBotMaker());
        assertEquals(new AccountRecord.Key(Kind.GOOGLE, "a@example.com"),
                new AccountRecord.Key(Kind.GOOGLE, "emu-app:com.one", "A@example.com"), "only a launch target's key has one");
        assertEquals(List.of(one.key(), new AccountRecord.Key(Kind.LAUNCH_TARGET, "emu-app:com.one", "alt")),
                one.withAlias("Alt").keys(), "each address keyed under the target");
    }

    @Test
    void aLaunchTargetsAccountKeepsItsLinksThroughEveryChange() {
        AccountRecord.Key google = new AccountRecord.Key(Kind.GOOGLE, "Main@Gmail.com");
        AccountRecord linked = AccountRecord.ofLaunchTarget("emu-app:com.one", "main", "")
                .withSignsInWith(Optional.of(google)).withPlaysAt(" this-pc/android/MEmu_1 ");
        AccountRecord changed = linked.withNote("farm").withVerify(Verification.CODE).withAlias("alt");
        assertEquals(Optional.of(google), changed.signsInWith());
        assertEquals("this-pc/android/MEmu_1", changed.playsAt());
        assertEquals("emu-app:com.one", changed.target());
        assertEquals("", changed.withPlaysAt("").playsAt(), "no place: any free place");
    }

    @Test
    void onlyALaunchTargetsAccountHasLinksAndItNamesItsTarget() {
        AccountRecord google = new AccountRecord(Kind.GOOGLE, "a@example.com", "", List.of(), Verification.DEFAULT,
                "emu-app:com.one", Optional.of(new AccountRecord.Key(Kind.MAIL, "m")), "this-pc/desktop");
        assertEquals("", google.target());
        assertTrue(google.signsInWith().isEmpty());
        assertEquals("", google.playsAt());
        assertThrows(IllegalArgumentException.class, () -> AccountRecord.ofLaunchTarget(" ", "main", ""));
        AccountRecord game = AccountRecord.ofLaunchTarget("emu-app:com.one", "main", "");
        assertThrows(IllegalArgumentException.class, () -> game.withSignsInWith(Optional.of(game.key())),
                "a game's account signs in with a Google, Facebook or mail one");
    }

    @Test
    void aLinksKeyReadsBackFromTheTextTheBookWrites() {
        AccountRecord.Key google = new AccountRecord.Key(Kind.GOOGLE, "Main@Gmail.com");
        assertEquals("google:main@gmail.com", google.text());
        assertEquals(Optional.of(google), AccountRecord.Key.parse(google.text()));
        assertTrue(AccountRecord.Key.parse("google:").isEmpty());
        assertTrue(AccountRecord.Key.parse("newer:x").isEmpty(), "a kind this build does not know");
        assertTrue(AccountRecord.Key.parse("launch-target:x").isEmpty());
        assertTrue(AccountRecord.Key.parse(null).isEmpty());
    }
}
