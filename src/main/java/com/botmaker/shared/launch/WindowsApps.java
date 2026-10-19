package com.botmaker.shared.launch;

import com.botmaker.shared.Spawn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The apps Windows' Start menu lists, as {@link DesktopEntries.Entry}s: every shortcut under both Start Menu folders
 * and every Store or Xbox app, read with PowerShell's {@code Get-StartApps}. Each comes with its AppUserModelID,
 * which {@code shell:AppsFolder\<id>} starts whatever it is ({@link LaunchCommands#desktop}).
 *
 * <p>A shortcut to a program has its path as its id, a folder Windows knows standing as its GUID
 * ({@code {6D809377-…}\Vendor\Game.exe} for Program Files); a shortcut to a link has the link
 * ({@code steam://rungameid/570}), which {@link DesktopEntries#shortcutIn} gives to its launcher. Windows only;
 * blocking, never throws.
 */
final class WindowsApps {

    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(20);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The known folders a Start menu id names by GUID, and the environment variable each one is. */
    private static final Map<String, String> KNOWN_FOLDERS = Map.of(
            "{6D809377-6AF0-444B-8957-A3773F02200E}", "ProgramW6432",
            "{7C5A40EF-A0FB-4BFC-874A-C0F2E0B9FA8E}", "ProgramFiles(x86)",
            "{F38BF404-1D43-42F2-9305-67DE0B28FC23}", "SystemRoot",
            "{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}", "SystemRoot\\System32");

    private WindowsApps() {}

    /** Every app the Start menu lists, by name. */
    static List<DesktopEntries.Entry> list() {
        try {
            Spawn.Completed query = Spawn.run(QUERY_TIMEOUT, "powershell.exe", "-NoProfile", "-NonInteractive",
                    // UTF-8 out: Windows PowerShell writes a redirected stdout in the OEM code page ("Pokémon").
                    "-Command", "[Console]::OutputEncoding = [Text.Encoding]::UTF8; "
                            + "Get-StartApps | Select-Object Name, AppID | ConvertTo-Json -Compress");
            return query == null || !query.ok() ? List.of() : parse(query.output(), WindowsApps::folder);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * {@code Get-StartApps}' JSON as entries: one object or an array of them, each with {@code Name} and
     * {@code AppID}. An entry's {@code exec} is its id in quotes, so a path with spaces reads as one program; its
     * {@code icon} the program's path with the known folder resolved by {@code folders}, when it names an
     * {@code .exe}. Package-private and pure for the tests.
     */
    static List<DesktopEntries.Entry> parse(String json, Function<String, String> folders) {
        Map<String, DesktopEntries.Entry> byId = new LinkedHashMap<>();
        try {
            JsonNode root = MAPPER.readTree(json == null ? "" : json.strip());
            if (root == null) return List.of();
            Iterable<JsonNode> apps = root.isArray() ? root : List.of(root);
            for (JsonNode app : apps) {
                String name = text(app, "Name");
                String id = text(app, "AppID");
                if (name.isBlank() || id.isBlank()) continue;
                String program = program(id, folders);
                byId.putIfAbsent(id, new DesktopEntries.Entry(id, name, "\"" + id + "\"",
                        program == null ? "" : program, Set.of(), ""));
            }
        } catch (Exception e) {
            // what was read stands
        }
        List<DesktopEntries.Entry> entries = new ArrayList<>(byId.values());
        entries.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return List.copyOf(entries);
    }

    /**
     * The program a Start menu id runs, its known folder's GUID resolved, or {@code null} when the id names no
     * {@code .exe} (a Store app, a link) or a folder {@code folders} doesn't know.
     */
    static String program(String id, Function<String, String> folders) {
        if (!id.toLowerCase(Locale.ROOT).endsWith(".exe")) return null;
        if (!id.startsWith("{")) return id.contains(":\\") ? id : null;
        int close = id.indexOf('}');
        String folder = close < 0 ? null : KNOWN_FOLDERS.get(id.substring(0, close + 1).toUpperCase(Locale.ROOT));
        String path = folder == null ? null : folders.apply(folder);
        return path == null || path.isBlank() ? null : path + id.substring(close + 1);
    }

    /** A known folder by its environment variable ({@code SystemRoot\System32} is a folder below one). */
    private static String folder(String variable) {
        int slash = variable.indexOf('\\');
        String value = System.getenv(slash < 0 ? variable : variable.substring(0, slash));
        return value == null ? null : slash < 0 ? value : value + variable.substring(slash);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText().strip();
    }
}
