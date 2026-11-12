package com.botmaker.shared.emulator.config;

import com.botmaker.shared.emulator.PlatformId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayersTest {

    private static final InstanceKey LD1 = new InstanceKey(PlatformId.LDPLAYER, "LDPlayer-1");
    private static final ConfigValue PORTRAIT = new ConfigValue.Screen(1080, 1920, 440);
    private static final AccountRecord PC = new AccountRecord(AccountRecord.Kind.GOOGLE, "pc@example.com");
    private static final AccountRecord SECOND = new AccountRecord(AccountRecord.Kind.GOOGLE, "second@example.com");

    @Test
    void anInstanceInheritsItsEmulatorsFieldsAndOverridesOneAtATime() {
        EmulatorConfig config = EmulatorConfig.EMPTY
                .withEmulator(PlatformId.LDPLAYER, Layer.EMPTY.with(ConfigField.SCREEN, PORTRAIT)
                        .with(ConfigField.MEMORY, new ConfigValue.Megabytes(3072)))
                .withInstance(LD1, Layer.EMPTY.with(ConfigField.MEMORY, new ConfigValue.Megabytes(4096)));

        Wanted wanted = config.wanted(LD1);

        assertEquals(new Sourced<>(PORTRAIT, Sourced.Source.EMULATOR), wanted.fields().get(ConfigField.SCREEN));
        assertEquals(new Sourced<ConfigValue>(new ConfigValue.Megabytes(4096), Sourced.Source.INSTANCE),
                wanted.fields().get(ConfigField.MEMORY));
    }

    @Test
    void useEmulatorsDropsTheInstancesOwnValue() {
        EmulatorConfig config = EmulatorConfig.EMPTY
                .withEmulator(PlatformId.LDPLAYER, Layer.EMPTY.with(ConfigField.FPS, new ConfigValue.Count(30)))
                .withInstance(LD1, Layer.EMPTY.with(ConfigField.FPS, new ConfigValue.Count(15)));

        EmulatorConfig back = config.withInstance(LD1, config.instance(LD1).without(ConfigField.FPS));

        assertEquals(Sourced.Source.EMULATOR, back.wanted(LD1).fields().get(ConfigField.FPS).source());
        assertFalse(back.instances().containsKey(LD1), "an empty layer is not kept");
    }

    @Test
    void anEmulatorOnlyFieldNeverReachesAnInstance() {
        EmulatorConfig config = EmulatorConfig.EMPTY.withEmulator(PlatformId.LDPLAYER,
                Layer.EMPTY.with(ConfigField.PRODUCT_AUTO_UPDATE_OFF, new ConfigValue.Toggle(true)));

        assertTrue(config.wanted(LD1).field(ConfigField.PRODUCT_AUTO_UPDATE_OFF).isEmpty());
    }

    @Test
    void anInstanceRemovesAnInheritedAccountAndAddsItsOwn() {
        EmulatorConfig config = EmulatorConfig.EMPTY
                .withEmulator(PlatformId.LDPLAYER, Layer.EMPTY.withAccount(PC).withAccount(SECOND))
                .withInstance(LD1, Layer.EMPTY.removingAccount(SECOND.key())
                        .withAccount(new AccountRecord(AccountRecord.Kind.FACEBOOK, "Name", "alt")));

        Wanted wanted = config.wanted(LD1);

        assertEquals(List.of(PC.name(), "Name"), wanted.accounts().stream().map(a -> a.value().name()).toList());
        assertEquals(Sourced.Source.INSTANCE, wanted.accounts().get(1).source());
        assertTrue(wanted.removedAccounts().contains(SECOND.key()));
    }

    @Test
    void removalOfInheritedThatVanished() {
        EmulatorConfig config = EmulatorConfig.EMPTY
                .withEmulator(PlatformId.LDPLAYER, Layer.EMPTY.withAccount(PC).withAccount(SECOND))
                .withInstance(LD1, Layer.EMPTY.removingAccount(SECOND.key()));

        EmulatorConfig later = config.withEmulator(PlatformId.LDPLAYER, Layer.EMPTY.withAccount(PC));
        Wanted wanted = later.wanted(LD1);

        assertEquals(List.of(PC), wanted.accounts().stream().map(Sourced::value).toList());
        assertEquals(Set.of(SECOND.key()), wanted.removedAccounts(),
                "still not wanted on the instance; Apply signs it out only where it is held");
    }

    @Test
    void reAddingARemovedAccountWantsItAgain() {
        Layer own = Layer.EMPTY.removingAccount(SECOND.key()).withAccount(SECOND);
        EmulatorConfig config = EmulatorConfig.EMPTY
                .withEmulator(PlatformId.LDPLAYER, Layer.EMPTY.withAccount(SECOND)).withInstance(LD1, own);

        assertTrue(config.wanted(LD1).removedAccounts().isEmpty());
        assertEquals(1, config.wanted(LD1).accounts().size());
    }

    @Test
    void accountKeysIgnoreCase() {
        Layer layer = Layer.EMPTY.withAccount(PC)
                .withAccount(new AccountRecord(AccountRecord.Kind.GOOGLE, "PC@Example.com", "note"));

        assertEquals(1, layer.accounts().size());
        assertEquals("note", layer.accounts().getFirst().note());
    }

    @Test
    void theDefaultGoogleIsTheChosenOneWhileWantedElseTheFirst() {
        EmulatorConfig config = EmulatorConfig.EMPTY
                .withEmulator(PlatformId.LDPLAYER, Layer.EMPTY.withAccount(PC).withAccount(SECOND))
                .withInstance(LD1, Layer.EMPTY.withDefaultGoogle("SECOND@example.com"));
        assertEquals(Optional.of(SECOND.name()), config.wanted(LD1).defaultGoogle());

        EmulatorConfig removed = config.withInstance(LD1, config.instance(LD1).removingAccount(SECOND.key()));
        assertEquals(Optional.of(PC.name()), removed.wanted(LD1).defaultGoogle());

        assertEquals(Optional.empty(), EmulatorConfig.EMPTY.wanted(LD1).defaultGoogle());
    }

    @Test
    void gamesAndTagsLayerLikeAccounts() {
        GameRecord game = new GameRecord("com.zjcs.android.us", "Sword x Staff");
        EmulatorConfig config = EmulatorConfig.EMPTY
                .withEmulator(PlatformId.LDPLAYER, Layer.EMPTY.withGame(game))
                .withInstance(LD1, Layer.EMPTY.withTags(List.of("farm", " farm ", "alt")));

        assertEquals(List.of(game), config.wanted(LD1).games().stream().map(Sourced::value).toList());
        assertEquals(List.of("farm", "alt"), config.wanted(LD1).tags());

        EmulatorConfig without = config.withInstance(LD1, config.instance(LD1).removingGame(game.packageName()));
        assertTrue(without.wanted(LD1).games().isEmpty());
        assertEquals(Set.of(game.packageName()), without.wanted(LD1).removedGames());
    }

    @Test
    void anEmulatorOnlyFieldIsRefusedOnAnInstance() {
        Layer own = Layer.EMPTY.with(ConfigField.PRODUCT_AUTO_UPDATE_OFF, new ConfigValue.Toggle(true));

        assertThrows(IllegalArgumentException.class, () -> EmulatorConfig.EMPTY.withInstance(LD1, own));
        assertTrue(new EmulatorConfig(null, Map.of(LD1, own)).instances().isEmpty(), "read: dropped");
    }

    @Test
    void removedSetsKeepTheirOrder() {
        Layer layer = Layer.EMPTY;
        for (int i = 0; i < 20; i++) {
            layer = layer.removingGame("game." + i);
        }
        assertEquals("game.0", layer.removedGames().iterator().next());
        assertEquals(List.of("a"), Layer.EMPTY.withTags(Arrays.asList("a", null, " ")).tags());
    }

    @Test
    void aValueOfTheWrongKindIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> Layer.EMPTY.with(ConfigField.SCREEN, new ConfigValue.Count(3)));
    }

    @Test
    void instanceKeysRoundTripAndRefuseUnknownProducts() {
        assertEquals(LD1, InstanceKey.parse(LD1.text()));
        assertEquals(new InstanceKey(PlatformId.MUMU, "a/b"), InstanceKey.parse("mumu/a/b"));
        assertEquals(null, InstanceKey.parse("nox/Nox-1"));
        assertEquals(null, InstanceKey.parse("ldplayer/"));
        assertEquals(null, InstanceKey.parse("LDPlayer/LDPlayer-1"), "another spelling is another writer's key");
    }
}
