package com.botmaker.shared.launch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each launcher's version, read from fixture text; a Windows program's own version, from a PE built here. */
class GameVersionTest {

    @Test
    void androidSaysItsNameAndCode() {
        String dumpsys = """
                Packages:
                  Package [com.game] (1a2b):
                    versionCode=4512 minSdk=24 targetSdk=34
                    versionName=2.3.1
                """;
        assertEquals("2.3.1 (4512)", GameVersion.android(dumpsys));
        assertNull(GameVersion.android("Unable to find package: com.game"));
    }

    @Test
    void steamSaysItsBuild() {
        String acf = """
                "AppState"
                {
                	"appid"		"570"
                	"buildid"		"15372034"
                }
                """;
        assertEquals("buildid 15372034", GameVersion.steam(acf));
        assertNull(GameVersion.steam("\"AppState\" { }"));
    }

    @Test
    void heroicEpicAndGogSayTheirVersion() {
        String epic = """
                {"Fortnite": {"app_name": "Fortnite", "title": "Fortnite", "version": "++Fortnite+Release-32.10"}}
                """;
        assertEquals("++Fortnite+Release-32.10", GameVersion.epic(epic, "Fortnite"));
        assertNull(GameVersion.epic(epic, "Other"));
        String gog = """
                {"installed": [{"appName": "1207658924", "version": "1.4.0.2", "platform": "windows"}]}
                """;
        assertEquals("1.4.0.2", GameVersion.gog(gog, "1207658924"));
        assertNull(GameVersion.gog("not json", "1207658924"));
    }

    @Test
    void faugusNamesItsProgram() {
        String games = """
                [{"gameid": "sword", "title": "Sword", "path": "/games/sword/Sword.exe"}]
                """;
        assertEquals(Optional.of(Path.of("/games/sword/Sword.exe")), GameVersion.faugusPath(games, "sword"));
        assertEquals(Optional.empty(), GameVersion.faugusPath(games, "other"));
    }

    @Test
    void aProgramStatesItsFileVersion(@TempDir Path dir) throws IOException {
        Path exe = Files.write(dir.resolve("game.exe"), pe(versionInfo(true)));
        assertEquals(Optional.of("1.4.2.0"), PeVersion.of(exe));
        assertEquals("1.4.2.0", GameVersion.read(new LaunchSpec(LaunchKind.EXE, exe.toString())));
    }

    @Test
    void withoutStringsTheFixedNumbersAreItsVersion(@TempDir Path dir) throws IOException {
        Path exe = Files.write(dir.resolve("game.exe"), pe(versionInfo(false)));
        assertEquals(Optional.of("2.0.3.7"), PeVersion.of(exe));
    }

    @Test
    void aProgramWithoutAVersionIsItsHash(@TempDir Path dir) throws IOException {
        Path exe = Files.write(dir.resolve("game.exe"), pe(null));
        assertEquals(Optional.empty(), PeVersion.of(exe));
        String version = GameVersion.read(new LaunchSpec(LaunchKind.EXE, exe.toString()));
        assertTrue(version.matches("build [0-9a-f]{12}"), version);
        assertEquals(GameVersion.UNKNOWN, GameVersion.read(new LaunchSpec(LaunchKind.CLI, exe + " --windowed")),
                "a command's first word is as often wine or env as the game");
        Path text = Files.writeString(dir.resolve("run.sh"), "#!/bin/sh\n");
        assertTrue(GameVersion.ofFile(text).startsWith("build "), "not a PE");
    }

    @Test
    void whatCannotBeReadIsUnknown() {
        assertEquals(GameVersion.UNKNOWN, GameVersion.of(new LaunchSpec(LaunchKind.EXE, "/no/such/game.exe")));
        assertEquals(GameVersion.UNKNOWN, GameVersion.of(new LaunchSpec(LaunchKind.URL, "uplay://launch/1/0")));
        assertEquals(GameVersion.UNKNOWN, GameVersion.of(null));
    }

    /** A PE32 with one {@code .rsrc} section holding {@code version} as its RT_VERSION resource; none for null. */
    private static byte[] pe(byte[] version) {
        int rawAt = 0x400, va = 0x1000;
        byte[] section = version == null ? new byte[0] : resources(version, va);
        ByteBuffer b = ByteBuffer.allocate(rawAt + section.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort(0, (short) 0x5A4D).putInt(0x3C, 0x40);
        b.putInt(0x40, 0x00004550).putShort(0x44, (short) 0x14C);
        b.putShort(0x46, (short) (version == null ? 0 : 1)).putShort(0x54, (short) 0xE0);
        int optional = 0x58;
        b.putShort(optional, (short) 0x10B).putInt(optional + 92, 16);
        if (version != null) {
            b.putInt(optional + 96 + 16, va).putInt(optional + 96 + 20, section.length);
            int header = optional + 0xE0;
            b.put(header, ".rsrc".getBytes(StandardCharsets.US_ASCII));
            b.putInt(header + 8, section.length).putInt(header + 12, va)
                    .putInt(header + 16, section.length).putInt(header + 20, rawAt);
            b.put(rawAt, section);
        }
        return b.array();
    }

    /** The resource tree: RT_VERSION → id 1 → language 0x409 → {@code version}, the data at {@code va + 0x58}. */
    private static byte[] resources(byte[] version, int va) {
        ByteBuffer b = ByteBuffer.allocate(0x58 + version.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort(0x0E, (short) 1).putInt(0x10, 16).putInt(0x14, 0x80000000 | 0x18);
        b.putShort(0x18 + 0x0E, (short) 1).putInt(0x28, 1).putInt(0x2C, 0x80000000 | 0x30);
        b.putShort(0x30 + 0x0E, (short) 1).putInt(0x40, 0x409).putInt(0x44, 0x48);
        b.putInt(0x48, va + 0x58).putInt(0x4C, version.length);
        b.put(0x58, version);
        return b.array();
    }

    /** A {@code VS_VERSIONINFO} with fixed file version 2.0.3.7 and, when {@code strings}, FileVersion 1.4.2.0. */
    private static byte[] versionInfo(boolean strings) {
        ByteBuffer fixed = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);
        fixed.putInt(0, 0xFEEF04BD).putInt(4, 0x10000).putInt(8, 2 << 16).putInt(12, (3 << 16) | 7);
        byte[] value = "1.4.2.0\0".getBytes(StandardCharsets.UTF_16LE);
        byte[] children = strings
                ? node("StringFileInfo", 1, new byte[0], 0,
                node("040904b0", 1, new byte[0], 0, node("FileVersion", 1, value, value.length / 2)))
                : new byte[0];
        return node("VS_VERSION_INFO", 0, fixed.array(), 52, children);
    }

    /** One version-resource node: header, key, padding, value, padding, children. */
    private static byte[] node(String key, int type, byte[] value, int valueLength, byte[]... children) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[6]);
        out.writeBytes((key + "\0").getBytes(StandardCharsets.UTF_16LE));
        pad(out);
        out.writeBytes(value);
        pad(out);
        for (byte[] child : children) out.writeBytes(child);
        byte[] bytes = out.toByteArray();
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(0, (short) bytes.length)
                .putShort(2, (short) valueLength).putShort(4, (short) type);
        return bytes;
    }

    private static void pad(ByteArrayOutputStream out) {
        while (out.size() % 4 != 0) out.write(0);
    }
}
