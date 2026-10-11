package com.botmaker.shared.vm;

import com.botmaker.shared.launch.LaunchSpec;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every shell script this package writes for a guest parses: {@code sh -n}, and {@code shellcheck} at its error
 * level when it is installed. Java builds them as strings, where a quote or a bracket can break without any compiler
 * noticing; the other tests read their content, this one the shell's grammar. A new script goes in {@link #scripts}.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class GuestScriptsTest {

    private static Map<String, String> scripts() {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("LinuxDisplay.newScript", LinuxDisplay.newScript());
        all.put("LinuxDisplay.displayScript", LinuxDisplay.displayScript());
        all.put("LinuxDisplay.listScript", LinuxDisplay.listScript());
        all.put("LinuxDisplay.launchScript", LinuxDisplay.launchScript("steam -applaunch 570"));
        all.put("LinuxDisplay.launchCommand", LinuxDisplay.launchCommand(3));
        all.put("LinuxDisplay.stopCommand", LinuxDisplay.stopCommand(3));
        all.put("LinuxAutoinstall.setupScript", LinuxAutoinstall.setupScript());
        all.put("LinuxAutoinstall.finishScript", LinuxAutoinstall.finishScript());
        all.put("LinuxAutoinstall.tartSetupScript", LinuxAutoinstall.tartSetupScript());
        all.put("LinuxGameCopy.copyScript", LinuxGameCopy.copyScript("192.168.64.1", 5123, "tok", "/home/botmaker/It's"));
        all.put("LinuxGameCopy.WINE", LinuxGameCopy.WINE);
        all.put("Legendary.signInScript", Legendary.signInScript());
        all.put("TartSetup.asDesktopUser", TartSetup.asDesktopUser("open -a '/Applications/It'\\''s.app'"));
        all.put("GuestGame.macScript", GuestGame.macScript(LaunchSpec.parse("desktop:/Applications/A (b)+[1].app"),
                false).orElseThrow());
        all.put("GuestGame.macScript stop", GuestGame.macScript(LaunchSpec.parse("exe:/Users/admin/game"), true)
                .orElseThrow());
        for (String target : List.of("steam:570", "exe:/home/botmaker/It's.exe", "cli:echo hi")) {
            all.put("GuestLaunch.linuxCommand " + target,
                    GuestLaunch.linuxCommand(LaunchSpec.parse(target)).orElseThrow());
        }
        for (String target : List.of("steam:570", "desktop:/Applications/It's.app", "exe:/bin/game", "cli:echo hi")) {
            all.put("GuestLaunch.macCommand " + target, GuestLaunch.macCommand(LaunchSpec.parse(target)).orElseThrow());
        }
        return all;
    }

    @TestFactory
    Stream<DynamicTest> eachGuestScriptParses() {
        boolean shellcheck = onPath("shellcheck");
        return scripts().entrySet().stream().map(script -> DynamicTest.dynamicTest(script.getKey(), () -> {
            Path file = Files.createTempFile("guest-script", ".sh");
            try {
                Files.writeString(file, script.getValue(), StandardCharsets.UTF_8);
                assertParses(List.of("/bin/sh", "-n", file.toString()), script);
                if (shellcheck) {
                    assertParses(List.of("shellcheck", "--shell=sh", "--severity=error", file.toString()), script);
                }
            } finally {
                Files.deleteIfExists(file);
            }
        }));
    }

    private static void assertParses(List<String> command, Map.Entry<String, String> script) throws Exception {
        Process check = new ProcessBuilder(command).redirectErrorStream(true).start();
        String said = new String(check.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(check.waitFor(30, TimeUnit.SECONDS), command.getFirst() + " hung");
        assertEquals(0, check.exitValue(), script.getKey() + " doesn't parse (" + command.getFirst() + "):\n" + said
                + "\n--- the script ---\n" + script.getValue());
    }

    private static boolean onPath(String program) {
        String path = System.getenv("PATH");
        return path != null && Stream.of(path.split(java.io.File.pathSeparator))
                .anyMatch(dir -> Files.isExecutable(Path.of(dir, program)));
    }
}
