package com.botmaker.shared.vm;

import com.botmaker.shared.launch.LaunchSpec;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The Windows command line that starts a launch target inside a game VM, on the guest's desktop. The game is the
 * guest's: a path names a file in the guest, and Steam or Epic is the guest's own launcher, signed in through the
 * VM's screen. {@code start ""} hands each one off, so the command returns at once and the launch task is free
 * for the next launch.
 */
public final class GuestLaunch {

    private static final Pattern STEAM_ID = Pattern.compile("\\d+");
    private static final Pattern EPIC_ID = Pattern.compile("[A-Za-z0-9._-]+");

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
     * {@code command} as the launch script's bytes: a UTF-8 batch file, which switches cmd to UTF-8 so a path
     * outside ASCII survives. {@code %VAR%} expands, as it does in VMware's {@code cmd /c}, so a command reads the
     * same under both hypervisors.
     */
    static byte[] script(String command) {
        return ("@echo off\r\nchcp 65001 >nul\r\n" + command + "\r\n").getBytes(StandardCharsets.UTF_8);
    }
}
