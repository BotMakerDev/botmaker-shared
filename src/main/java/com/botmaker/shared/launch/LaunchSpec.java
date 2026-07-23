package com.botmaker.shared.launch;

/**
 * A parsed {@code launch.target} spec — the {@code <kind>:<token>} string persisted in
 * {@code botmaker-project.properties} and the single value both the SDK (which launches it) and Studio (which
 * shows and edits it) work from.
 *
 * <pre>
 *   steam:&lt;appId&gt;
 *   epic:&lt;appName&gt;
 *   heroic:&lt;appName&gt;
 *   faugus:&lt;gameId&gt;
 *   cli:&lt;command line&gt;
 *   exe:&lt;path&gt;
 *   emu-app:&lt;package&gt;@&lt;instanceName&gt;
 * </pre>
 *
 * <p>Parsing is <b>total</b> — the spec is user-editable text in a properties file, so a missing colon, an
 * empty token or an unknown kind yields {@code null} or a {@link LaunchKind#UNKNOWN} spec rather than an
 * exception. Everything a consumer used to re-split by hand (the kind, the token, a file name, a label) is a
 * method here instead; Studio's {@code LaunchTargetNames} was that re-splitting, and it has been deleted.
 *
 * @param kind  what sort of thing this points at
 * @param token the launcher's own identity for it — an appId, an {@code AppName}, a path, a command line
 */
public record LaunchSpec(LaunchKind kind, String token) {

    public LaunchSpec {
        if (kind == null) kind = LaunchKind.UNKNOWN;
        if (token == null) token = "";
    }

    /**
     * Parses a spec string, or returns {@code null} when there is nothing to parse (null/blank, no colon, or an
     * empty token). An unrecognised kind still parses — to a {@link LaunchKind#UNKNOWN} spec — so it can be
     * described and round-tripped even by a build that doesn't know how to launch it.
     */
    public static LaunchSpec parse(String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String trimmed = spec.trim();
        int colon = trimmed.indexOf(':');
        if (colon <= 0 || colon == trimmed.length() - 1) {
            return null;
        }
        String rest = trimmed.substring(colon + 1).trim();
        if (rest.isEmpty()) {
            return null;
        }
        LaunchKind kind = LaunchKind.fromId(trimmed.substring(0, colon));
        // Preserve the original text for an unknown kind: it is the only way describe()/spec() can still show
        // the user what their properties file actually says.
        return kind == LaunchKind.UNKNOWN ? new LaunchSpec(kind, trimmed) : new LaunchSpec(kind, rest);
    }

    /** The canonical string this spec round-trips to — what gets written back to the properties file. */
    public String spec() {
        return kind == LaunchKind.UNKNOWN ? token : kind.id() + ":" + token;
    }

    /**
     * A friendly one-line description, e.g. {@code "Steam game 570"} — what a dialog or a checklist prints.
     * A {@code null}/blank spec string is the caller's own business; use {@link #describe(String)} for that.
     */
    public String describe() {
        return switch (kind) {
            case EXE -> kind.displayName() + " " + fileName();
            case UNKNOWN -> token;
            default -> kind.displayName() + " " + token;
        };
    }

    /** {@link #describe()} for a raw spec string, with {@code "(none)"} for nothing configured. */
    public static String describe(String spec) {
        LaunchSpec parsed = parse(spec);
        if (parsed != null) return parsed.describe();
        return spec == null || spec.isBlank() ? "(none)" : spec;
    }

    /**
     * The short label for a button: {@code displayName} when the spec resolved to an installed game's title,
     * else the bare token (a file name for {@code exe:}).
     */
    public String shortLabel(String displayName) {
        if (displayName != null && !displayName.isBlank()) return displayName;
        return kind == LaunchKind.EXE ? fileName() : token;
    }

    /** {@link #shortLabel(String)} for a raw spec, falling back to {@code "Launch Target"} for no spec. */
    public static String shortLabel(String spec, String displayName) {
        if (displayName != null && !displayName.isBlank()) return displayName;
        LaunchSpec parsed = parse(spec);
        if (parsed != null) return parsed.shortLabel(null);
        return spec == null || spec.isBlank() ? "Launch Target" : spec;
    }

    /** The token's trailing path segment — the executable/image name for {@code exe:} and {@code cli:}. */
    public String fileName() {
        return fileNameOf(kind == LaunchKind.CLI ? firstWord() : token);
    }

    /** The {@code <package>} half of an {@code emu-app:} token, or {@code null} for any other kind. */
    public String emulatorPackage() {
        int at = emulatorSplit();
        return at < 0 ? null : token.substring(0, at).trim();
    }

    /** The {@code <instance>} half of an {@code emu-app:} token, or {@code null} for any other kind. */
    public String emulatorInstance() {
        int at = emulatorSplit();
        return at < 0 ? null : token.substring(at + 1).trim();
    }

    /** The command line split on whitespace: executable first, then arguments. Empty for other kinds. */
    public String[] commandTokens() {
        if (kind != LaunchKind.CLI || token.isBlank()) {
            return new String[0];
        }
        return token.trim().split("\\s+");
    }

    /**
     * The distinctive string a <em>live</em> incarnation of this target carries — in a process command line,
     * and often in a window title. Not {@link #spec()}: that is our own encoding, whereas this is the
     * launcher's own launch identity. {@code null} when the kind has no host-side token at all.
     */
    public String runningToken() {
        return switch (kind) {
            // Not the bare id — a number that short would match any command line by accident. Steam's own
            // launch wrapper spells it `reaper SteamLaunch AppId=<id> --`, which is unambiguous.
            case STEAM -> "AppId=" + token;
            case EPIC, HEROIC, FAUGUS -> token;
            case EXE, CLI -> {
                String name = fileName();
                yield name.isBlank() ? null : name;
            }
            // An app inside an emulator shows up nowhere on the host process table; asked over ADB instead.
            case EMULATOR_APP, UNKNOWN -> null;
        };
    }

    /** Index of the {@code @} separating package from instance — the <em>last</em> one, so dots are kept. */
    private int emulatorSplit() {
        if (kind != LaunchKind.EMULATOR_APP) {
            return -1;
        }
        int at = token.lastIndexOf('@');
        return (at <= 0 || at == token.length() - 1) ? -1 : at;
    }

    private String firstWord() {
        String[] parts = commandTokens();
        return parts.length == 0 ? "" : parts[0];
    }

    private static String fileNameOf(String path) {
        if (path == null || path.isBlank()) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
    }
}
