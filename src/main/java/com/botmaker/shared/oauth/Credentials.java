package com.botmaker.shared.oauth;

import com.botmaker.shared.config.CacheDirs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code credentials.json} under the BotMaker cache dir — never inside {@code ~/BotMakerProjects/}, which may
 * itself be published — holding every provider's sign-in under its own key ({@code github_token},
 * {@code google_token}, …), best-effort {@code 0600}.
 *
 * <p>Every write merges the whole map, or signing into one provider would wipe the other's token. Every
 * failure is logged and swallowed: a sign-in that can't be remembered still works for the session.
 */
public final class Credentials {

    private static final Path FILE = CacheDirs.cacheRoot().resolve("credentials.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Credentials() {}

    /** The value under {@code key}, or {@code ""} when there is none or the file can't be read. */
    public static String read(String key) {
        try {
            if (Files.exists(FILE)) {
                return MAPPER.readTree(FILE.toFile()).path(key).asText("");
            }
        } catch (Exception e) {
            System.err.println("Failed to read credentials: " + e.getMessage());
        }
        return "";
    }

    /** Sets {@code key} to {@code value}, keeping every other key. */
    public static void store(String key, String value) {
        try {
            Files.createDirectories(FILE.getParent());
            createPrivate();
            Map<String, String> all = readAll();
            all.put(key, value);
            MAPPER.writeValue(FILE.toFile(), all);
            restrictPermissions();
        } catch (Exception e) {
            System.err.println("Failed to store credentials: " + e.getMessage());
        }
    }

    /** Removes {@code key}, and the file with it when nothing else is left. */
    public static void remove(String key) {
        try {
            if (!Files.exists(FILE)) return;
            Map<String, String> all = readAll();
            all.remove(key);
            if (all.isEmpty()) {
                Files.deleteIfExists(FILE);
            } else {
                MAPPER.writeValue(FILE.toFile(), all);
                restrictPermissions();
            }
        } catch (Exception e) {
            System.err.println("Failed to clear credentials: " + e.getMessage());
        }
    }

    private static Map<String, String> readAll() {
        Map<String, String> all = new LinkedHashMap<>();
        try {
            if (Files.exists(FILE)) {
                JsonNode n = MAPPER.readTree(FILE.toFile());
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
     * the write and {@link #restrictPermissions}. Windows: the cache folder's own ACL, the user's.
     */
    private static void createPrivate() {
        if (Files.exists(FILE)) return;
        try {
            Files.createFile(FILE, PosixFilePermissions.asFileAttribute(EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        } catch (UnsupportedOperationException notPosix) {
            // Windows: written plainly below
        } catch (Exception e) {
            // a race with another writer, or no permission: the write below says so
        }
    }

    private static void restrictPermissions() {
        try {
            Files.setPosixFilePermissions(FILE, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (Exception ignored) {
            // Non-POSIX filesystem (e.g. Windows) — best-effort only.
        }
    }
}
