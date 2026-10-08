package com.botmaker.shared.vm;

import com.botmaker.shared.launch.LaunchSpec;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The Windows command line that starts a launch target inside a game VM, on the guest's desktop. The game is the
 * guest's: a path names a file in the guest, and Steam or Epic is the guest's own launcher, signed in through the
 * VM's screen. {@code start ""} hands each one off, so the command returns at once and the launch task is free
 * for the next launch.
 */
public final class GuestLaunch {

    /** A Steam app id, and an Epic app name, as a guest command can carry them; {@link GuestGame} finds the same. */
    static final Pattern STEAM_ID = Pattern.compile("\\d+");
    static final Pattern EPIC_ID = Pattern.compile("[A-Za-z0-9._-]+");

    private GuestLaunch() {}

    /**
     * The command for {@code spec}, or empty for a kind a Windows guest can't start (a Linux launcher, an
     * emulator app) or a token that would break the command line.
     */
    public static Optional<String> command(LaunchSpec spec) {
        String token = spec.token().trim();
        if (token.isEmpty() || token.contains("\n") || token.contains("\r")) return Optional.empty();
        return switch (spec.kind()) {
            case STEAM -> STEAM_ID.matcher(token).matches()
                    ? Optional.of("start \"\" \"steam://rungameid/" + token + "\"") : Optional.empty();
            case EPIC -> EPIC_ID.matcher(token).matches()
                    ? Optional.of("start \"\" \"com.epicgames.launcher://apps/" + token + "?action=launch&silent=true\"")
                    : Optional.empty();
            case EXE -> token.contains("\"") ? Optional.empty() : Optional.of("start \"\" \"" + token + "\"");
            case CLI -> Optional.of("start \"\" " + token);
            default -> Optional.empty();
        };
    }

    /**
     * The shell command that starts {@code spec} on a Linux guest's display ({@link LinuxDisplay}), or empty for a
     * kind it can't start or a token that would break the line. Steam's own client starts its game; Legendary
     * starts an Epic game; a Windows program runs in Wine, and any other path as itself.
     *
     * <p>Steam runs one client per account: a second Steam game hands its launch to the client already running,
     * on that client's display.
     */
    public static Optional<String> linuxCommand(LaunchSpec spec) {
        String token = spec.token().trim();
        if (token.isEmpty() || token.contains("\n") || token.contains("\r")) return Optional.empty();
        return switch (spec.kind()) {
            case STEAM -> STEAM_ID.matcher(token).matches() ? Optional.of("steam -applaunch " + token) : Optional.empty();
            case EPIC -> EPIC_ID.matcher(token).matches() ? Optional.of("legendary launch " + token) : Optional.empty();
            case EXE -> Optional.of((token.toLowerCase(Locale.ROOT).endsWith(".exe") ? "wine " : "")
                    + shellQuoted(token));
            case CLI -> Optional.of(token);
            default -> Optional.empty();
        };
    }

    /** {@code s} as one POSIX shell word. */
    static String shellQuoted(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * {@code command} as the launch script's bytes: a UTF-8 batch file, which switches cmd to UTF-8 so a path
     * outside ASCII survives, and {@code %VAR%} expands. Its last line deletes
     * {@value GuestUnattend#LAUNCH_PENDING}, to say the command ran, and exits on the same line: cmd reads a batch
     * file as it goes, and the next launch may already be writing this one.
     */
    static byte[] script(String command) {
        return ("@echo off\r\nchcp 65001 >nul\r\n" + command + "\r\ndel \"" + GuestUnattend.LAUNCH_PENDING
                + "\" & exit /b\r\n").getBytes(StandardCharsets.UTF_8);
    }
}
