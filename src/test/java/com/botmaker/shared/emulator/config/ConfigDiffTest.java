package com.botmaker.shared.emulator.config;

import com.botmaker.shared.emulator.PlatformId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What Apply would change on an instance, and what it would leave to the user. */
class ConfigDiffTest {

    private static final Instant AT = Instant.parse("2026-11-12T10:00:00Z");
    private static final InstanceKey MUMU = new InstanceKey(PlatformId.MUMU, "MuMu-0");
    private static final InstanceKey LD = new InstanceKey(PlatformId.LDPLAYER, "LDPlayer");
    private static final GameRecord GAME = new GameRecord("com.zjcs.android.us", "Sword x Staff");

    @Test
    void eachFieldIsSameChangeByHandEachRunOrUnread() {
        Layer emulator = Layer.EMPTY.with(ConfigField.SCREEN, new ConfigValue.Screen(1080, 1920, 288))
                .with(ConfigField.CPU_CORES, new ConfigValue.Count(2))
                .with(ConfigField.MEMORY, new ConfigValue.Megabytes(4096))
                .with(ConfigField.LOCALE, new ConfigValue.Text("en_us"))
                .with(ConfigField.DO_NOT_DISTURB, new ConfigValue.Toggle(true))
                .with(ConfigField.POPUPS_OFF, new ConfigValue.Toggle(true));
        Layer own = Layer.EMPTY.with(ConfigField.SCREEN, new ConfigValue.Screen(900, 1600, 240));
        Wanted wanted = new EmulatorConfig(Map.of(PlatformId.MUMU, emulator), Map.of(MUMU, own)).wanted(MUMU);
        InstanceFacts facts = facts(MUMU, Map.of(ConfigField.CPU_CORES, new ConfigValue.Count(4),
                ConfigField.MEMORY, new ConfigValue.Megabytes(4096)), android(Set.of(), Set.of()));

        ConfigDiff diff = ConfigDiff.of(wanted, facts);

        assertEquals(ConfigDiff.Status.BY_HAND, status(diff, ConfigField.SCREEN), "MuMu's screen is set by hand");
        assertEquals(ConfigDiff.Status.CHANGE, status(diff, ConfigField.CPU_CORES));
        assertEquals(ConfigDiff.Status.SAME, status(diff, ConfigField.MEMORY));
        assertEquals(ConfigDiff.Status.SAME, status(diff, ConfigField.LOCALE), "en_us is en-US");
        assertEquals(ConfigDiff.Status.EACH_RUN, status(diff, ConfigField.DO_NOT_DISTURB));
        assertEquals(ConfigDiff.Status.UNREAD, status(diff, ConfigField.POPUPS_OFF));
        assertEquals(Sourced.Source.INSTANCE, field(diff, ConfigField.SCREEN).wanted().source());
        assertTrue(diff.differs());
        assertTrue(diff.applicable());
    }

    @Test
    void aFieldTheProductCannotApplyIsNeverAChange() {
        Wanted wanted = EmulatorConfig.EMPTY.withEmulator(PlatformId.GAMELOOP,
                Layer.EMPTY.with(ConfigField.MEMORY, new ConfigValue.Megabytes(8192))).wanted(
                new InstanceKey(PlatformId.GAMELOOP, "GameLoop"));
        InstanceFacts facts = facts(new InstanceKey(PlatformId.GAMELOOP, "GameLoop"), Map.of(), Optional.empty());

        ConfigDiff diff = ConfigDiff.of(wanted, facts);

        assertEquals(ConfigDiff.Status.BY_HAND, status(diff, ConfigField.MEMORY));
        assertFalse(diff.differs(), "not read: not known to differ");
        assertFalse(diff.applicable());

        ConfigDiff read = ConfigDiff.of(wanted, facts(new InstanceKey(PlatformId.GAMELOOP, "GameLoop"),
                Map.of(ConfigField.MEMORY, new ConfigValue.Megabytes(4096)), Optional.empty()));
        assertTrue(read.differs());
        assertFalse(read.applicable());
    }

    @Test
    void onlyALocaleIgnoresCaseAndUnderscores() {
        assertTrue(ConfigDiff.same(ConfigField.LOCALE, new ConfigValue.Text("en_us"), new ConfigValue.Text("en-US")));
        assertFalse(ConfigDiff.same(ConfigField.TIME_ZONE, new ConfigValue.Text("america/new_york"),
                new ConfigValue.Text("America/New_York")));
    }

    @Test
    void googleAccountsAreSignedInOrOutAndTheOthersAreRecordsOnly() {
        Layer emulator = Layer.EMPTY.withAccount(new AccountRecord(AccountRecord.Kind.GOOGLE, "Main@example.com"))
                .withAccount(new AccountRecord(AccountRecord.Kind.GOOGLE, "held@example.com"))
                .withAccount(new AccountRecord(AccountRecord.Kind.FACEBOOK, "a player"))
                .withAccount(new AccountRecord(AccountRecord.Kind.GOOGLE, "old@example.com"));
        Layer own = Layer.EMPTY.removingAccount(new AccountRecord.Key(AccountRecord.Kind.GOOGLE, "old@example.com"))
                .removingAccount(new AccountRecord.Key(AccountRecord.Kind.GOOGLE, "never@example.com"));
        Wanted wanted = new EmulatorConfig(Map.of(PlatformId.LDPLAYER, emulator), Map.of(LD, own)).wanted(LD);
        InstanceFacts facts = facts(LD, Map.of(), android(Set.of("held@example.com", "old@example.com"), Set.of()));

        ConfigDiff diff = ConfigDiff.of(wanted, facts);

        assertEquals(List.of(
                new ConfigDiff.AccountChange(new AccountRecord(AccountRecord.Kind.GOOGLE, "Main@example.com"),
                        ConfigDiff.AccountChange.Op.ADD),
                new ConfigDiff.AccountChange(new AccountRecord(AccountRecord.Kind.GOOGLE, "old@example.com"),
                        ConfigDiff.AccountChange.Op.REMOVE)), diff.accounts());
    }

    @Test
    void missingGamesAndUnreadAndroidSayNothingFalse() {
        Wanted wanted = EmulatorConfig.EMPTY.withEmulator(PlatformId.LDPLAYER, Layer.EMPTY.withGame(GAME)
                .withAccount(new AccountRecord(AccountRecord.Kind.GOOGLE, "main@example.com"))).wanted(LD);

        ConfigDiff missing = ConfigDiff.of(wanted, facts(LD, Map.of(), android(Set.of("main@example.com"), Set.of())));
        assertEquals(List.of(GAME), missing.missingGames());
        assertTrue(missing.applicable());

        ConfigDiff held = ConfigDiff.of(wanted, facts(LD, Map.of(),
                android(Set.of("main@example.com"), Set.of("com.zjcs.android.us"))));
        assertFalse(held.differs());

        ConfigDiff unread = ConfigDiff.of(wanted, facts(LD, Map.of(), Optional.empty()));
        assertFalse(unread.accountsRead());
        assertFalse(unread.gamesRead());
        assertEquals(List.of(), unread.accounts());
        assertEquals(List.of(), unread.missingGames());
        assertFalse(unread.differs());
    }

    private static InstanceFacts facts(InstanceKey key, Map<ConfigField, ConfigValue> settings,
                                       Optional<AndroidState> android) {
        return new InstanceFacts(key, settings, AT, android, android.isPresent());
    }

    private static Optional<AndroidState> android(Set<String> accounts, Set<String> packages) {
        return Optional.of(new AndroidState(Map.of(ConfigField.LOCALE, new ConfigValue.Text("en-US")),
                Optional.of(accounts), Optional.of(packages), "15", false, AT));
    }

    private static ConfigDiff.FieldState field(ConfigDiff diff, ConfigField field) {
        return diff.fields().stream().filter(f -> f.field() == field).findFirst().orElseThrow();
    }

    private static ConfigDiff.Status status(ConfigDiff diff, ConfigField field) {
        return field(diff, field).status();
    }
}
