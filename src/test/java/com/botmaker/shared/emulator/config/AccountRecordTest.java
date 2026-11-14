package com.botmaker.shared.emulator.config;

import com.botmaker.shared.emulator.config.AccountRecord.Kind;
import com.botmaker.shared.emulator.config.AccountRecord.Verification;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
