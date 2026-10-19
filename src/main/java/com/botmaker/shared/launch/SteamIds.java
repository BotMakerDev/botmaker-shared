package com.botmaker.shared.launch;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Steam's two ids for a game. A store game has one, its app id ({@code 570}). A non-Steam shortcut (a game the
 * user added to Steam's library) has a 32-bit app id of its own, and the 64-bit game id
 * {@code steam://rungameid/} takes, which is that app id shifted up 32 bits with the shortcut flag
 * {@code 0x02000000} below. A {@code steam:} target names a shortcut by its game id, so the one link starts both.
 * Pure.
 */
public final class SteamIds {

    private static final long SHORTCUT_FLAG = 0x02000000L;

    private SteamIds() {}

    /** Whether {@code gameId} names a non-Steam shortcut: a number above 32 bits. */
    public static boolean isShortcut(String gameId) {
        // Unsigned: a shortcut from before 2021 has its app id's top bit set, so its game id passes 2^63.
        return Long.compareUnsigned(parse(gameId), 0xFFFFFFFFL) > 0;
    }

    /**
     * The app id Steam's launch wrapper carries ({@code reaper SteamLaunch AppId=<id> --}): the shortcut's 32-bit
     * one for a game id, else {@code gameId} itself.
     */
    public static String appId(String gameId) {
        return isShortcut(gameId) ? Long.toString(parse(gameId) >>> 32) : gameId == null ? "" : gameId.trim();
    }

    /** The game id of the shortcut whose 32-bit app id is {@code appId}, as {@code shortcuts.vdf} stores it. */
    public static String shortcutGameId(int appId) {
        return Long.toUnsignedString(((appId & 0xFFFFFFFFL) << 32) | SHORTCUT_FLAG);
    }

    /**
     * The app id Steam gives a shortcut that records none (written before 2021): the CRC-32 of its program and name
     * with the top bit set.
     */
    public static int legacyShortcutAppId(String exe, String name) {
        CRC32 crc = new CRC32();
        crc.update(((exe == null ? "" : exe) + (name == null ? "" : name)).getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() | 0x80000000L);
    }

    /** {@code id} as an unsigned number, or 0 when it is none. */
    private static long parse(String id) {
        try {
            return id == null ? 0 : Long.parseUnsignedLong(id.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
