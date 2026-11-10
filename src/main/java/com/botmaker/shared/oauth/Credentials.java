package com.botmaker.shared.oauth;

import com.botmaker.shared.config.CacheDirs;
import com.botmaker.shared.tools.UserDirs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code credentials.json} in the user's config dir ({@link UserDirs#config()}) — never inside
 * {@code ~/BotMakerProjects/}, which may itself be published, nor in the cache, which may be cleared — holding every
 * provider's sign-in under its own key ({@code github_token}, {@code google_token}, {@code google_play_aas}, …),
 * best-effort {@code 0600}. It lived in the cache dir until 2026-11-10 (the user's call): the first use moves it.
 *
 * <p>Every write merges the whole map, or signing into one provider would wipe the other's token. Every
 * failure is logged and swallowed: a sign-in that can't be remembered still works for the session.
 */
public final class Credentials {

    private static final String NAME = "credentials.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Whether this process has moved the cache dir's file over: once, on the first use. */
    private static boolean moved;

    private Credentials() {}

    /** The value under {@code key}, or {@code ""} when there is none or the file can't be read. */
    public static String read(String key) {
        return read(file(), key);
    }

    /** Sets {@code key} to {@code value}, keeping every other key. */
    public static void store(String key, String value) {
        store(file(), key, value);
    }

    /** Removes {@code key}, and the file with it when nothing else is left. */
    public static void remove(String key) {
        remove(file(), key);
    }

    /** The file, in the config dir, with the cache dir's moved into it first. */
    private static synchronized Path file() {
        Path file = UserDirs.config().resolve(NAME);
        if (!moved) {
            moved = true;
            moveOver(CacheDirs.cacheRoot().resolve(NAME), file);
        }
        return file;
    }

    /**
     * Moves {@code old} to {@code now}. When both exist, {@code now}'s keys win and {@code old}'s others are added:
     * a sign-in made since is the newer, and none is lost. Logged and swallowed, as every failure here.
     */
    static synchronized void moveOver(Path old, Path now) {
        try {
            if (!Files.exists(old)) return;
            if (Files.exists(now)) {
                Map<String, String> all = readAll(old);
                all.putAll(readAll(now));
                write(now, all);
                Files.delete(old);
                return;
            }
            Files.createDirectories(now.getParent());
            Files.move(old, now);
            restrictPermissions(now);
        } catch (Exception e) {
            System.err.println("Failed to move credentials to " + now + ": " + e.getMessage());
        }
    }

    static synchronized String read(Path file, String key) {
        try {
            if (Files.exists(file)) {
                JsonNode node = MAPPER.readTree(file.toFile());
                return node == null ? "" : node.path(key).asText("");
            }
        } catch (Exception e) {
            System.err.println("Failed to read credentials: " + e.getMessage());
        }
        return "";
    }

    static synchronized void store(Path file, String key, String value) {
        try {
            Files.createDirectories(file.getParent());
            createPrivate(file);
            Map<String, String> all = readAll(file);
            all.put(key, value);
            write(file, all);
        } catch (Exception e) {
            System.err.println("Failed to store credentials: " + e.getMessage());
        }
    }

    static synchronized void remove(Path file, String key) {
        try {
            if (!Files.exists(file)) return;
            Map<String, String> all = readAll(file);
            all.remove(key);
            if (all.isEmpty()) {
                Files.deleteIfExists(file);
            } else {
                write(file, all);
            }
        } catch (Exception e) {
            System.err.println("Failed to clear credentials: " + e.getMessage());
        }
    }

    private static void write(Path file, Map<String, String> all) throws IOException {
        MAPPER.writeValue(file.toFile(), all);
        restrictPermissions(file);
    }

    private static Map<String, String> readAll(Path file) {
        Map<String, String> all = new LinkedHashMap<>();
        try {
            if (Files.exists(file)) {
                JsonNode n = MAPPER.readTree(file.toFile());
                // null: the file createPrivate just made, still empty
                if (n != null) n.properties().forEach(e -> all.put(e.getKey(), e.getValue().asText("")));
            }
        } catch (Exception e) {
            System.err.println("Failed to read credentials: " + e.getMessage());
        }
        return all;
    }

    /**
     * Creates the file owner-only before anything is written to it, so no token sits in it readable by others between
     * the write and {@link #restrictPermissions}. Windows: the config folder's own ACL, the user's.
     */
    private static void createPrivate(Path file) {
        if (Files.exists(file)) return;
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        } catch (UnsupportedOperationException notPosix) {
            // Windows: written plainly below
        } catch (Exception e) {
            // a race with another writer, or no permission: the write below says so
        }
    }

    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (Exception ignored) {
            // Non-POSIX filesystem (e.g. Windows) — best-effort only.
        }
    }
}
