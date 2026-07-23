package com.botmaker.shared.launch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The {@code launch.target} grammar, which is now parsed once instead of three times (the SDK's
 * {@code LaunchTarget.parse}, Studio's {@code LaunchTargetNames} and its {@code describe} switch).
 *
 * <p>The ids are <b>persisted</b> in {@code botmaker-project.properties}, so the round-trip assertions here are
 * a compatibility contract, not a style check: change one and every existing project's target stops resolving.
 */
class LaunchSpecTest {

    @Test
    void everyKindRoundTripsThroughItsPersistedId() {
        for (String spec : new String[]{"steam:570", "epic:Fortnite", "heroic:Firestone", "faugus:battlenet",
                "cli:lutris rungame 3", "exe:/opt/games/thing.exe", "emu-app:com.foo.bar@Instance 1"}) {
            assertEquals(spec, LaunchSpec.parse(spec).spec(), "spec must round-trip unchanged");
        }
    }

    @Test
    void nothingParseableYieldsNullRatherThanThrowing() {
        // The spec is user-editable text in a properties file, so every one of these is reachable.
        for (String spec : new String[]{null, "", "   ", "steam", "steam:", ":570"}) {
            assertNull(LaunchSpec.parse(spec), "unparseable spec must be null, not an exception: " + spec);
        }
    }

    @Test
    void anUnknownKindStillParsesSoItCanBeShownBack() {
        // An older build must not lose a target a newer Studio wrote — it just can't launch it.
        LaunchSpec parsed = LaunchSpec.parse("gog:1234");
        assertEquals(LaunchKind.UNKNOWN, parsed.kind());
        assertEquals("gog:1234", parsed.spec(), "the original text is what the user must see back");
        assertNull(parsed.runningToken(), "an unknown kind has no host token to probe for");
    }

    @Test
    void steamMatchesItsWrapperNotItsBareId() {
        // `570` alone would match any command line carrying that number by accident; Steam's own reaper spells
        // it `SteamLaunch AppId=570 --`, which is what makes the command-line layer usable at all.
        assertEquals("AppId=570", LaunchSpec.parse("steam:570").runningToken());
    }

    @Test
    void anExecutableIsProbedAndLabelledByItsFileNameNotItsPath() {
        LaunchSpec exe = LaunchSpec.parse("exe:/opt/games/Firestone/Firestone.exe");
        assertEquals("Firestone.exe", exe.runningToken());
        assertEquals("Executable Firestone.exe", exe.describe());
        assertEquals("Firestone.exe", exe.shortLabel(null));
        assertEquals("Firestone", exe.shortLabel("Firestone"), "a resolved title wins over the file name");
    }

    @Test
    void aCommandLineSplitsIntoExecutableAndArgumentsAndIsProbedByTheExecutable() {
        LaunchSpec cli = LaunchSpec.parse("cli:/usr/bin/lutris rungame 3");
        assertArrayEquals(new String[]{"/usr/bin/lutris", "rungame", "3"}, cli.commandTokens());
        assertEquals("lutris", cli.runningToken(), "the process runs under the executable, not the whole line");
    }

    @Test
    void anEmulatorAppSplitsOnTheLastAtSoPackageDotsSurvive() {
        LaunchSpec emu = LaunchSpec.parse("emu-app:com.foo.bar@My@Instance");
        assertEquals("com.foo.bar@My", emu.emulatorPackage());
        assertEquals("Instance", emu.emulatorInstance());
        assertNull(emu.runningToken(), "an app inside an emulator shows up nowhere on the host process table");
    }

    @Test
    void describeAndShortLabelAreTotalForARawSpec() {
        assertEquals("(none)", LaunchSpec.describe(null));
        assertEquals("(none)", LaunchSpec.describe("  "));
        assertEquals("Steam game 570", LaunchSpec.describe("steam:570"));
        assertEquals("Launch Target", LaunchSpec.shortLabel(null, null));
        assertEquals("570", LaunchSpec.shortLabel("steam:570", null));
    }
}
