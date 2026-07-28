package com.botmaker.shared.launch;

import java.util.List;

/**
 * The <em>child-launchable</em> argv ladders for a {@link LaunchSpec} — the command lines that run a target
 * as our own child process, so it inherits whatever environment we hand it (notably a private {@code DISPLAY}
 * for a nested session). This is the single source both launch paths draw from, so they can't drift on how a
 * store launcher is spelled:
 *
 * <ul>
 *   <li>the on-host {@code :0} path ({@link GameLauncher}, which tries a protocol URL first and these as its
 *       CLI fallback), and</li>
 *   <li>the nested-display path ({@code NestedSession.commandFor}), which cannot use a daemon-routed URL at
 *       all — the URL would be handed to a launcher already running on {@code :0}, ignoring our
 *       {@code DISPLAY}. It runs these directly.</li>
 * </ul>
 *
 * <p>Each store kind yields an <em>ordered ladder</em> of candidate argvs, most-preferred first (a native
 * binary, then its Flatpak form); a caller runs them in order until one works. A kind with no CLI form at all
 * (Epic offers none; an emulator app runs over ADB, not on the host desktop) yields an <b>empty</b> ladder,
 * which the nested session reads as "can't be launched into a private display".
 */
public final class LaunchCommands {

    private LaunchCommands() {}

    /** Heroic's CLI ladder: the native binary, then the common Flatpak install. */
    public static List<List<String>> heroic(String appName) {
        String id = require(appName);
        return List.of(
                List.of("heroic", "--no-gui", "launch", id),
                List.of("flatpak", "run", "com.heroicgameslauncher.hgl", "--no-gui", "launch", id));
    }

    /** Steam's CLI form: {@code steam -applaunch <appId>} (requires {@code steam} on {@code PATH}). */
    public static List<List<String>> steam(String appId) {
        String id = require(appId);
        return List.of(List.of("steam", "-applaunch", id));
    }

    /** Faugus's CLI ladder: the native launcher, then its Flatpak form. */
    public static List<List<String>> faugus(String gameId) {
        String id = require(gameId);
        return List.of(
                List.of("faugus-launcher", "--game", id),
                List.of("flatpak", "run", "io.github.Faugus.faugus-launcher", "--game", id));
    }

    /**
     * The full child-launch ladder for {@code spec}: the store ladders above for the launcher kinds, a single
     * argv for {@code exe:}/{@code cli:} (they already <em>are</em> the child process), and an empty ladder for
     * any kind with no way to inherit a child environment — Epic's URL-only handoff, an emulator app over ADB,
     * or an unknown/blank spec. Empty means "not launchable into a private display".
     */
    public static List<List<String>> childLadder(LaunchSpec spec) {
        if (spec == null) {
            return List.of();
        }
        return switch (spec.kind()) {
            case EXE -> spec.token().isBlank() ? List.of() : List.of(List.of(spec.token()));
            case CLI -> {
                String[] tokens = spec.commandTokens();
                yield tokens.length == 0 ? List.of() : List.of(List.of(tokens));
            }
            case HEROIC -> heroic(spec.token());
            case STEAM -> steam(spec.token());
            case FAUGUS -> faugus(spec.token());
            case EPIC, EMULATOR_APP, UNKNOWN -> List.of();
        };
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("launch token must not be empty");
        }
        return value.trim();
    }
}
