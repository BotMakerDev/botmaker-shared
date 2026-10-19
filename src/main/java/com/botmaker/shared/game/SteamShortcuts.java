package com.botmaker.shared.game;

import com.botmaker.shared.launch.SteamIds;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The non-Steam games a user added to Steam's library, from each account's
 * {@code userdata/<account>/config/shortcuts.vdf} — Steam's binary VDF, not the text one its manifests use. Each is
 * a {@code steam:} game by its 64-bit game id ({@link SteamIds}), so Steam starts it the way its library does: with
 * the shortcut's launch options and compatibility tool. The picture is the one the user set in Steam's grid
 * ({@code config/grid/<appid>p.png}), else the shortcut's icon. Best-effort, never throws.
 */
final class SteamShortcuts {

    private static final byte MAP = 0x00;
    private static final byte STRING = 0x01;
    private static final byte INT32 = 0x02;
    private static final byte FLOAT32 = 0x03;
    private static final byte POINTER = 0x04;
    private static final byte UINT64 = 0x07;
    private static final byte END = 0x08;

    private SteamShortcuts() {}

    /** Every shortcut of every account under the Steam root {@code root}. */
    static List<InstalledGame> under(Path root) {
        List<InstalledGame> games = new ArrayList<>();
        Path userdata = root.resolve("userdata");
        if (!Files.isDirectory(userdata)) return games;
        try (Stream<Path> accounts = Files.list(userdata)) {
            accounts.sorted().forEach(account -> {
                Path vdf = account.resolve("config").resolve("shortcuts.vdf");
                try {
                    if (Files.isRegularFile(vdf)) games.addAll(parse(Files.readAllBytes(vdf), account.resolve("config")));
                } catch (IOException | RuntimeException e) {
                    // this account's shortcuts are skipped; the others still count
                }
            });
        } catch (IOException | RuntimeException e) {
            // what was read stands
        }
        return games;
    }

    /**
     * The shortcuts a {@code shortcuts.vdf} holds; {@code config} is the account's {@code config} folder its grid
     * pictures are in. A truncated or unknown file is read as no shortcuts. Package-private and pure for the tests.
     */
    static List<InstalledGame> parse(byte[] vdf, Path config) {
        Map<String, Object> root = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try {
            read(vdf, new int[]{0}, root);
        } catch (RuntimeException truncated) {
            return List.of();
        }
        List<InstalledGame> games = new ArrayList<>();
        if (!(root.get("shortcuts") instanceof Map<?, ?> shortcuts)) return games;
        for (Object value : shortcuts.values()) {
            if (!(value instanceof Map<?, ?> shortcut)) continue;
            String name = text(shortcut.get("appname"));
            String exe = text(shortcut.get("exe"));
            if (name.isBlank()) continue;
            int appId = shortcut.get("appid") instanceof Integer id ? id : SteamIds.legacyShortcutAppId(exe, name);
            games.add(new InstalledGame(SteamLibraryScanner.PLATFORM, SteamIds.shortcutGameId(appId), name,
                    picture(config, Integer.toUnsignedString(appId), text(shortcut.get("icon")))));
        }
        return games;
    }

    /** The grid's portrait for the shortcut, else its icon when that is a PNG or a JPEG; {@code null} for none. */
    private static Path picture(Path config, String appId, String icon) {
        if (config != null) {
            for (String file : List.of(appId + "p.png", appId + "p.jpg")) {
                Path grid = config.resolve("grid").resolve(file);
                if (Files.isRegularFile(grid)) return grid;
            }
        }
        String file = unquote(icon);
        String lower = file.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".png") && !lower.endsWith(".jpg")) return null;
        try {
            Path path = Path.of(file);
            return Files.isRegularFile(path) ? path : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Reads entries into {@code into} until the map's end; {@code at} is the position, moved past what was read. */
    private static void read(byte[] bytes, int[] at, Map<String, Object> into) {
        while (at[0] < bytes.length) {
            byte type = bytes[at[0]++];
            if (type == END) return;
            String key = cString(bytes, at);
            switch (type) {
                case MAP -> {
                    Map<String, Object> child = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                    read(bytes, at, child);
                    into.put(key, child);
                }
                case STRING -> into.put(key, cString(bytes, at));
                case INT32 -> into.put(key, littleEndian(bytes, at, 4).intValue());
                case FLOAT32, POINTER -> at[0] += 4;
                case UINT64 -> into.put(key, littleEndian(bytes, at, 8));
                default -> throw new IllegalStateException("unknown VDF type " + type);
            }
        }
    }

    private static String cString(byte[] bytes, int[] at) {
        int start = at[0];
        while (bytes[at[0]] != 0) at[0]++;
        String text = new String(bytes, start, at[0] - start, StandardCharsets.UTF_8);
        at[0]++;
        return text;
    }

    private static Long littleEndian(byte[] bytes, int[] at, int size) {
        if (at[0] + size > bytes.length) throw new IllegalStateException("truncated");
        long value = 0;
        for (int i = size - 1; i >= 0; i--) value = (value << 8) | (bytes[at[0] + i] & 0xFF);
        at[0] += size;
        return value;
    }

    private static String text(Object value) {
        return value instanceof String s ? s.strip() : "";
    }

    private static String unquote(String path) {
        return path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")
                ? path.substring(1, path.length() - 1) : path;
    }
}
