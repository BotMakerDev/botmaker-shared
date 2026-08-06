package com.botmaker.shared.launch;

import java.util.Set;

/**
 * The closed set of things a bot can be pointed at — the {@code <kind>} half of a {@code launch.target} spec.
 *
 * <p>The {@code PlatformId} pattern the repo prescribes for exactly this shape: a stable wire {@link #id()}
 * (which <em>is persisted</em>, in {@code botmaker-project.properties}, so it must never change) plus the
 * {@link #displayName()} a human reads, so no consumer keeps its own id→name switch. Before this existed the
 * set lived twice — as a {@code switch} in the SDK's {@code LaunchTarget.parse} and again in Studio's
 * {@code LaunchTargetNames.describe} — which is how a kind could be launchable but undescribable.
 *
 * <p>{@link #fromId(String)} is total: an unrecognised kind yields {@link #UNKNOWN} rather than throwing,
 * because the spec is user-editable text in a properties file and a spec written by a newer Studio must still
 * load in an older one.
 *
 * <p><b>The launcher facts live here too.</b> Three kinds are routed through a store launcher's own daemon,
 * and each of them carries the process names that daemon runs under, its Flatpak application id and the
 * product's name. Those used to be three per-kind tables in {@link HostLauncherProbe} plus a third copy of the
 * Flatpak ids inline in {@link LaunchCommands} — the very "each consumer keeps its own switch" this enum's
 * first paragraph says it exists to prevent.
 */
public enum LaunchKind {

    /** A Steam game, keyed by the numeric appId from its store URL. */
    STEAM("steam", "Steam game", "Steam", Set.of("steam", "steamwebhelper"), "com.valvesoftware.Steam"),
    /** An Epic Games title, keyed by the {@code AppName} launch token from the launcher's manifest. */
    EPIC("epic", "Epic game"),
    /** A Heroic Games Launcher title — the practical way to run Epic/GOG games on Linux. */
    HEROIC("heroic", "Heroic game", "Heroic", Set.of("heroic", "heroicgameslauncher"),
            "com.heroicgameslauncher.hgl"),
    /** A Faugus Launcher entry, keyed by its {@code gameid}; runs non-Steam Windows games under umu/Proton. */
    FAUGUS("faugus", "Faugus game", "Faugus Launcher", Set.of("faugus-launcher", "faugus"),
            "io.github.Faugus.faugus-launcher"),
    /** An arbitrary command line — the escape hatch for any launcher not modelled directly. */
    CLI("cli", "Command"),
    /** A plain executable, launched directly. */
    EXE("exe", "Executable"),
    /** An app inside a named Android emulator instance, spelled {@code <package>@<instance>}. */
    EMULATOR_APP("emu-app", "Emulator app"),
    /** A kind we don't recognise — parsed rather than rejected, so an unknown spec is still printable. */
    UNKNOWN("", "Launch target");

    /** What a kind with no launcher daemon of its own is called when a message has to name one. */
    private static final String GENERIC_PRODUCT = "the launcher";

    private final String id;
    private final String displayName;
    private final String productName;
    private final Set<String> processNames;
    private final String flatpakAppId;

    LaunchKind(String id, String displayName) {
        this(id, displayName, GENERIC_PRODUCT, Set.of(), null);
    }

    LaunchKind(String id, String displayName, String productName, Set<String> processNames, String flatpakAppId) {
        this.id = id;
        this.displayName = displayName;
        this.productName = productName;
        this.processNames = processNames;
        this.flatpakAppId = flatpakAppId;
    }

    /** The persisted wire id — the text before the colon in a {@code launch.target} spec. */
    public String id() {
        return id;
    }

    /** The human-readable name of the <em>target</em>, e.g. {@code "Steam game"}. */
    public String displayName() {
        return displayName;
    }

    /**
     * The human-readable name of the <em>launcher product</em>, e.g. {@code "Steam"} — a different fact from
     * {@link #displayName()}, which names what is being launched rather than what launches it. Kinds with no
     * launcher of their own answer {@code "the launcher"}, so a message can be composed without a null check.
     */
    public String productName() {
        return productName;
    }

    /**
     * The executable names this kind's launcher UI runs under (its own binary, its Electron shell, the script
     * an interpreter runs). Empty for every kind that isn't routed through a launcher daemon:
     * {@code exe:}/{@code cli:} are our own child, and an emulator app is driven over ADB.
     */
    public Set<String> processNames() {
        return processNames;
    }

    /**
     * The launcher's Flatpak application id, or {@code null} if this kind has no launcher. Spelled in the
     * <b>canonical case</b> {@code flatpak run} needs ({@code com.valvesoftware.Steam}); a consumer matching it
     * against a lowercased command line lowercases it at the compare site, which is why one copy suffices.
     */
    public String flatpakAppId() {
        return flatpakAppId;
    }

    /**
     * Whether launching this kind goes through a launcher daemon rather than straight to a child process —
     * derived from {@link #processNames()} rather than declared, so the two can't disagree.
     */
    public boolean routesThroughDaemon() {
        return !processNames.isEmpty();
    }

    /**
     * Whether this kind's target lives <em>outside</em> any X display we could give it — true only for
     * {@link #EMULATOR_APP}, which is started, captured and clicked over ADB inside an Android emulator.
     *
     * <p>The distinction matters because "has no child command" is two different facts wearing one answer.
     * Epic also has no command form ({@link LaunchCommands#childLadder} yields an empty ladder for both), but
     * an Epic game <em>does</em> end up on a desktop — just not one we handed it, which is a failure worth
     * refusing. An emulator app never maps a window on any desktop at all: a private {@code :N} display has
     * nothing to offer it, so being unable to isolate it is the normal case rather than a problem. Consumers
     * ask this instead of testing the kind, so the fact lives on the closed set (the {@code PlatformId}
     * pattern) rather than in each launch surface's {@code if}.
     */
    public boolean runsOffDesktop() {
        return this == EMULATOR_APP;
    }

    /** The kind for {@code id}, case-insensitively; {@link #UNKNOWN} for anything else. Never throws. */
    public static LaunchKind fromId(String id) {
        if (id == null || id.isBlank()) {
            return UNKNOWN;
        }
        String needle = id.trim().toLowerCase();
        for (LaunchKind kind : values()) {
            if (kind != UNKNOWN && kind.id.equals(needle)) {
                return kind;
            }
        }
        return UNKNOWN;
    }
}
