package com.botmaker.shared.emulator;

import com.botmaker.shared.capture.windows.User32;
import com.botmaker.shared.capture.windows.WindowFinder;
import com.botmaker.shared.capture.windows.WindowInfo;
import com.botmaker.shared.emulator.checks.CheckResult;
import com.botmaker.shared.emulator.checks.EmulatorChecks;
import com.botmaker.shared.launch.AndroidInstall;
import com.botmaker.shared.launch.EmulatorAppLauncher;
import com.botmaker.shared.launch.GameInstall;
import com.botmaker.shared.launch.LaunchSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Windows emulators driven for real, one at a time (2026-11-10): for each instance of BlueStacks, LDPlayer, MEmu,
 * MuMu and Gameloop on this computer, the steps a bot's run takes on Waydroid — start, ADB, install the game
 * {@code -Dbotmaker.live.package} names, launch it, focus, capture, a minimised window, a crash, a force-stop, the
 * emulator's own process killed, stop — each recorded pass or fail with its time, and the table written to
 * {@code target/emulators-live/report.md} with a frame per instance. One at a time because a PC rarely holds the RAM
 * of two (MEmu alone asks 6 GB). {@code -Dbotmaker.live.emulators=LDPlayer,MEmu} picks instances by reference.
 * It starts and kills emulators and installs a game, so only on purpose.
 */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "botmaker.live.package", matches = ".+")
class WindowsEmulatorsLiveTest {

    private static final List<PlatformId> PRODUCTS = List.of(PlatformId.BLUESTACKS, PlatformId.LDPLAYER,
            PlatformId.MEMU, PlatformId.MUMU, PlatformId.GAMELOOP);
    /** The process whose death is an emulator crashing or its window closed, per product. */
    private static final Map<PlatformId, String> PLAYER = Map.of(PlatformId.BLUESTACKS, "HD-Player.exe",
            PlatformId.LDPLAYER, "dnplayer.exe", PlatformId.MEMU, "MEmu.exe", PlatformId.MUMU, "MuMuNxDevice.exe",
            PlatformId.GAMELOOP, "GameLoopEmulator.exe");
    private static final Pattern SIZE = Pattern.compile("(\\d+)x(\\d+)");
    private static final Path OUT = Path.of("target", "emulators-live");
    /**
     * {@code -Dbotmaker.live.apk=<file>} adds an .apk/.xapk to the library when it holds no version of the game, as a
     * file the user downloaded would be. {@code -Dbotmaker.live.apkpure=true}: the user accepted APKPure's dangers ({@link ApkDownload#DANGERS}) for this
     * run, so a game Play can't give comes from there; nothing is saved as their answer.
     */
    private static final boolean APKPURE = Boolean.getBoolean("botmaker.live.apkpure");
    /** {@code -Dbotmaker.live.apkpureFirst=true}: the game comes from APKPure even when Play's files would give it. */
    private static final boolean APKPURE_FIRST = Boolean.getBoolean("botmaker.live.apkpureFirst");
    /** {@code -Dbotmaker.live.library=<dir>}: an APK library of the run's own, empty to start, not the user's. */
    private static final String LIBRARY = System.getProperty("botmaker.live.library", "");
    /** {@code -Dbotmaker.live.gameOnly=true}: install, launch and see the title screen, no crash or kill after. */
    private static final boolean GAME_ONLY = Boolean.getBoolean("botmaker.live.gameOnly");
    /** A title screen fades in: a frame taken this long after the launch shows it. */
    private static final Duration SETTLE = Duration.ofSeconds(20);
    /** How long after its settle the game may take to draw its first frame and take the focus. */
    private static final Duration DRAWN = Duration.ofSeconds(90);

    private final String pkg = System.getProperty("botmaker.live.package").strip();

    @Test
    void eachEmulatorRunsTheGameAsWaydroidDoes() throws Exception {
        Files.createDirectories(OUT);
        List<EmulatorInstance> found = Platforms.discoverAll().stream()
                .filter(one -> PRODUCTS.contains(one.platformId())).toList();
        String picked = System.getProperty("botmaker.live.emulators", "");
        List<String> refs = picked.isBlank() ? List.of() : Arrays.stream(picked.split(",")).map(String::strip).toList();
        List<String> unknown = refs.stream().filter(ref -> EmulatorInstances.find(found, ref).isEmpty()).toList();
        assertTrue(unknown.isEmpty(), "no instance " + unknown + " among " + found.stream()
                .map(EmulatorInstance::reference).toList());
        List<EmulatorInstance> chosen = refs.isEmpty() ? found
                : refs.stream().map(ref -> EmulatorInstances.find(found, ref)).flatMap(Optional::stream).toList();
        assertFalse(chosen.isEmpty(), "no Windows emulator instance on this computer");
        say("instances: " + chosen.stream().map(EmulatorInstance::caption).toList());

        StringBuilder report = new StringBuilder("# Windows emulators, " + pkg + "\n\n");
        boolean allPassed = true;
        for (EmulatorInstance instance : chosen) {
            List<Step> steps = new Rows(instance).run();
            report.append("## ").append(instance.caption()).append("\n\n| Step | | Time | Detail |\n|---|---|---|---|\n");
            for (Step step : steps) {
                report.append("| ").append(step.name).append(" | ").append(step.ok ? "pass" : "**fail**").append(" | ")
                        .append(step.millis / 1000.0).append(" s | ").append(step.detail.replace("|", "\\|")
                                .replace("\n", " ")).append(" |\n");
                allPassed &= step.ok;
            }
            report.append('\n');
            Files.writeString(OUT.resolve("report.md"), report);
        }
        say("report: " + OUT.resolve("report.md").toAbsolutePath());
        assertTrue(allPassed, "a step failed; see " + OUT.resolve("report.md").toAbsolutePath());
    }

    /** One instance's steps, in order; a step another needs ends the instance's run when it fails. */
    private final class Rows {

        private final List<Step> steps = new ArrayList<>();
        private EmulatorInstance instance;

        Rows(EmulatorInstance instance) {
            this.instance = instance;
        }

        List<Step> run() throws InterruptedException {
            String ref = instance.reference();
            boolean wasRunning = EmulatorLiveness.running(instance);
            if (!step("start and boot", () -> {
                Optional<EmulatorInstance> up = EmulatorReadiness.bringUp(instance, WindowsEmulatorsLiveTest::say);
                up.ifPresent(live -> instance = live);
                return up.isPresent() ? ok((wasRunning ? "was running; " : "") + "adb " + instance.endpoint())
                        : no(EmulatorReadiness.notReady(instance));
            })) return steps;
            step("liveness", () -> {
                EmulatorLiveness liveness = EmulatorLiveness.check(instance);
                String problem = liveness.problem(instance);
                return liveness.running() && problem == null ? ok(liveness.label()) : no(liveness.label() + "; " + problem);
            });
            step("setup checks", () -> {
                List<CheckResult> results = EmulatorChecks.run(instance, null);
                return results.isEmpty() ? no("no checks for " + instance.brand())
                        : results.stream().anyMatch(CheckResult::failed) ? no(results.toString()) : ok(results.size() + " ok");
            });
            if (!step("game installed", this::install)) return finish(wasRunning);
            if (!step("launch", () -> {
                EmulatorAppLauncher.Outcome outcome = EmulatorAppLauncher.start(pkg, ref, WindowsEmulatorsLiveTest::say);
                return outcome.ok() ? ok(outcome.message()) : no(outcome.message());
            })) return finish(wasRunning);
            Thread.sleep(SETTLE.toMillis());
            step("still running", this::stillRunning);
            // Android 9 on BlueStacks drew the game's first frame only after 20-40 s (2026-11-10): until then the frame
            // is black and no window has the focus.
            waitFor(DRAWN, this::inFront);
            step("focus", this::focus);
            step("capture", () -> capture("launched"));
            if (GAME_ONLY) return finish(wasRunning);
            step("minimised window", this::minimised);
            step("crash seen", this::crash);
            step("force-stop then restart", this::forceStopAndRestart);
            step("player killed, then up again", this::killed);
            return finish(wasRunning);
        }

        /** The game installed the way Studio's Game dialog installs it: {@link AndroidInstall}, every source in order. */
        private Result install() throws IOException {
            EmulatorInstall.DeviceFacts facts = EmulatorInstall.facts(instance, pkg, WindowsEmulatorsLiveTest::say);
            if (!facts.ok()) return no(facts.problem());
            if (facts.installedVersion() >= 0) return ok("already installed, version " + facts.installedVersion()
                    + "; abis " + facts.abis() + (facts.hasPlay() ? "; Play" : "; no Play"));
            ApkLibrary library = LIBRARY.isBlank() ? ApkLibrary.shared() : new ApkLibrary(Path.of(LIBRARY));
            String given = System.getProperty("botmaker.live.apk", "");
            if (!given.isBlank() && library.newest(pkg).isEmpty()) library.add(Path.of(given));
            if (APKPURE_FIRST && library.newest(pkg).isEmpty()) {
                // APKPure's own way in, the ABI walk included, ahead of Play's files that would otherwise answer first.
                ApkDownload.Result fetched = ApkDownload.fetch(pkg, facts.abis(), true, library,
                        WindowsEmulatorsLiveTest::say);
                if (!fetched.ok()) return no("APKPure: " + fetched.message());
            }
            AndroidInstall install = new AndroidInstall(LaunchSpec.parse("emu-app:" + pkg + "@" + instance.reference()),
                    APKPURE, library);
            GameInstall.Result result = install.install(WindowsEmulatorsLiveTest::say);
            String said = result.message() + " Abis " + facts.abis() + ".";
            return result.outcome() == GameInstall.Outcome.INSTALLED ? ok(said) : no(result.outcome() + ": " + said);
        }

        /**
         * Whether the game outlived its settle; once dead, it is launched again and given another: a fresh install's
         * first start died in its WebView on BlueStacks (2026-11-10) and the next stayed up, as a run's restart would.
         */
        private Result stillRunning() throws InterruptedException {
            if (running()) return ok("pid " + pid());
            EmulatorAppLauncher.Outcome again = EmulatorAppLauncher.start(pkg, instance.reference(),
                    WindowsEmulatorsLiveTest::say);
            if (!again.ok()) return no("died after its first start, and the second didn't start: " + again.message());
            Thread.sleep(SETTLE.toMillis());
            return running() ? ok("died after its first start; the second stayed up, pid " + pid())
                    : no("died after its first start and again after the second");
        }

        private boolean inFront() {
            try (AdbDevice device = AdbDevice.connect(instance.adb())) {
                return device.focusedPackage().filter(pkg::equals).isPresent();
            } catch (RuntimeException e) {
                return false;
            }
        }

        private boolean running() {
            return !pid().isEmpty();
        }

        private String pid() {
            try (AdbDevice device = AdbDevice.connect(instance.adb())) {
                return device.shell("pidof " + pkg).strip();
            }
        }

        private Result focus() {
            try (AdbDevice device = AdbDevice.connect(instance.adb())) {
                Optional<String> focused = device.focusedPackage();
                return focused.filter(pkg::equals).isPresent() ? ok(pkg)
                        : no("focused " + focused.orElse("nothing") + "; current " + device.currentApp()
                        + device.systemDialog().map(shown -> "; dialog " + shown.dialog()).orElse(""));
            }
        }

        private Result capture(String name) {
            try (AdbDevice device = AdbDevice.connect(instance.adb())) {
                BufferedImage frame = device.screencap();
                if (frame == null) return no("no frame");
                Path file = OUT.resolve(instance.platformId().id() + "-"
                        + instance.name().replaceAll("[^A-Za-z0-9._-]", "_") + "-" + name + ".png");
                ImageIO.write(frame, "png", file.toFile());
                Matcher size = SIZE.matcher(lastLine(device.shell("wm size")));
                String wm = size.find() ? size.group() : "?";
                String got = frame.getWidth() + "x" + frame.getHeight();
                boolean sized = wm.equals(got) || wm.equals(frame.getHeight() + "x" + frame.getWidth());
                double lit = litShare(frame);
                String detail = got + " (wm size " + wm + "), " + Math.round(lit * 100) + "% lit, " + file.getFileName();
                return sized && lit > 0.05 ? ok(detail) : no(detail);
            } catch (Exception e) {
                return no(e.toString());
            }
        }

        private Result minimised() throws InterruptedException {
            Optional<WindowInfo> window = WindowFinder.getAllWindows().stream()
                    .filter(one -> one.getTitle() != null && one.getTitle().contains(instance.name())).findFirst();
            if (window.isEmpty()) return no("no window titled with \"" + instance.name() + "\"");
            User32.INSTANCE.ShowWindow(window.get().getHWnd(), 6); // SW_MINIMIZE
            Thread.sleep(3000);
            Result first;
            Result second;
            try {
                first = capture("minimised-1");
                Thread.sleep(2000);
                second = capture("minimised-2");
            } finally {
                User32.INSTANCE.ShowWindow(window.get().getHWnd(), User32.SW_RESTORE);
            }
            return first.ok && second.ok ? ok("\"" + window.get().getTitle() + "\": " + second.detail)
                    : no("\"" + window.get().getTitle() + "\": " + first.detail + " / " + second.detail);
        }

        /**
         * The game's own process crashed, by its pid: {@code am crash <package>} crashed the game's WebView renderer
         * instead on LDPlayer's Android 14 (2026-11-10), an isolated process of the same package, and the game stayed.
         */
        private Result crash() throws InterruptedException {
            try (AdbDevice device = AdbDevice.connect(instance.adb())) {
                String main = device.shell("pidof " + pkg).strip();
                if (main.isEmpty()) return no("not running before the crash");
                String said = device.shell("am crash " + main.split("\\s+")[0]);
                long start = System.nanoTime();
                while (System.nanoTime() - start < Duration.ofSeconds(15).toNanos()) {
                    Optional<SystemDialog.Shown> dialog = device.systemDialog();
                    if (dialog.isPresent()) return ok(dialog.get().dialog() + (dialog.get().theApps() ? " (the game's)" : ""));
                    String pids = device.shell("pidof " + pkg);
                    if (pids == null || pids.isBlank()) return ok("gone with no dialog; am crash said " + oneLine(said));
                    // The game's crash reporter (CrashSight) caught it on BlueStacks (2026-11-10): the process lived on,
                    // its screen closed, and the emulator's own app came to the front, as a run's focus guard sees it.
                    Optional<String> focused = device.focusedPackage();
                    if (focused.isPresent() && !focused.get().equals(pkg)) {
                        return ok("its screen closed, the process kept; in front " + focused.get());
                    }
                    Thread.sleep(500);
                }
                // On BlueStacks' Android 9 (2026-11-11) CrashSight also caught it with the game left in front and
                // drawing: no crash reaches a run, so there is nothing to recover, and shell has no root to kill it.
                String pid = main.split("\\s+")[0];
                if (device.shell("logcat -d --pid=" + pid).contains("shell-induced crash")) {
                    return ok("the game caught it itself and kept running (its crash reporter); nothing to recover");
                }
                return no("still running 15 s after am crash; it said " + oneLine(said));
            }
        }

        private Result forceStopAndRestart() throws InterruptedException {
            String ref = instance.reference();
            boolean emulatorStopped = EmulatorAppLauncher.stop(pkg, ref);
            boolean stillRunning = EmulatorAppLauncher.isRunning(pkg, ref);
            EmulatorAppLauncher.Outcome again = EmulatorAppLauncher.start(pkg, ref, WindowsEmulatorsLiveTest::say);
            Thread.sleep(5000);
            String detail = "stopped" + (stillRunning ? ", but still in front" : "")
                    + (emulatorStopped ? ", with the emulator" : "") + "; restart: " + again.message();
            return !stillRunning && again.ok() ? ok(detail) : no(detail);
        }

        private Result killed() throws InterruptedException {
            String image = PLAYER.get(instance.platformId());
            String said = host("taskkill", "/F", "/IM", image);
            long killedAt = System.nanoTime();
            boolean seen = waitFor(Duration.ofSeconds(60), () -> !EmulatorReadiness.isReady(instance));
            long toSee = (System.nanoTime() - killedAt) / 1_000_000;
            if (!seen) return no("taskkill " + image + ": " + oneLine(said) + "; still ready 60 s later");
            Optional<EmulatorInstance> up = EmulatorReadiness.bringUp(instance, WindowsEmulatorsLiveTest::say);
            up.ifPresent(live -> instance = live);
            String detail = "taskkill " + image + ": " + oneLine(said) + "; seen down in " + toSee + " ms; "
                    + (up.isPresent() ? "up again" : EmulatorReadiness.notReady(instance));
            return up.isPresent() ? ok(detail) : no(detail);
        }

        private List<Step> finish(boolean wasRunning) throws InterruptedException {
            if (wasRunning) return steps;
            step("stop", () -> {
                if (!instance.canStop()) return no("no stop command");
                EmulatorLauncher.stop(instance);
                return waitFor(Duration.ofSeconds(60), () -> !EmulatorLiveness.running(instance)) ? ok("stopped")
                        : no("still running 60 s later");
            });
            return steps;
        }

        private boolean step(String name, Action action) throws InterruptedException {
            say(instance.name() + ": " + name + "…");
            long start = System.nanoTime();
            Result result;
            try {
                result = action.run();
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                result = no(e.toString());
            }
            Step step = new Step(name, result.ok, result.detail, (System.nanoTime() - start) / 1_000_000);
            steps.add(step);
            say(instance.name() + ": " + name + " " + (step.ok ? "pass" : "FAIL") + " — " + step.detail);
            return step.ok;
        }
    }

    @FunctionalInterface
    private interface Action {
        Result run() throws Exception;
    }

    private record Result(boolean ok, String detail) {}

    private record Step(String name, boolean ok, String detail, long millis) {}

    private static Result ok(String detail) {
        return new Result(true, detail);
    }

    private static Result no(String detail) {
        return new Result(false, detail);
    }

    private static boolean waitFor(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < until) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(1000);
        }
        return false;
    }

    private static String host(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes());
            process.waitFor();
            return out.strip();
        } catch (Exception e) {
            return e.toString();
        }
    }

    private static String lastLine(String text) {
        if (text == null) return "";
        String[] lines = text.strip().split("\\R");
        return lines[lines.length - 1];
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ");
    }

    /** The share of pixels, on a 64×64 grid, brighter than near-black. */
    private static double litShare(BufferedImage frame) {
        int lit = 0;
        for (int gy = 0; gy < 64; gy++) {
            for (int gx = 0; gx < 64; gx++) {
                int rgb = frame.getRGB(gx * frame.getWidth() / 64, gy * frame.getHeight() / 64);
                int max = Math.max((rgb >> 16) & 0xff, Math.max((rgb >> 8) & 0xff, rgb & 0xff));
                if (max > 24) lit++;
            }
        }
        return lit / 4096.0;
    }

    private static void say(String line) {
        System.out.println(LocalTime.now().truncatedTo(ChronoUnit.SECONDS) + " " + line);
    }
}
