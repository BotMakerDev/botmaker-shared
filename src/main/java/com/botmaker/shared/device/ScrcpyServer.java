package com.botmaker.shared.device;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Finding the {@code scrcpy-server} binary, and knowing which version it is.</b> Both halves matter: the
 * server refuses to start if the version string the client passes does not equal its own, so a located file
 * whose version is unknown is a file we cannot use.
 *
 * <h2>Located, not vendored — and why that is a deliberate departure</h2>
 *
 * <p>The plan for this phase said "bundled". It is not bundled, for reasons that only became visible with the
 * file in front of us:
 *
 * <ul>
 *   <li><b>Nothing in this repo can produce it.</b> {@code scrcpy-server} is a compiled Android dex. There is
 *       no Android toolchain in this build, so a vendored copy would be a binary no step of {@code mvn
 *       install} reproduces or checks — a blob in git that the build takes on faith.</li>
 *   <li><b>It would pin a version we cannot re-derive.</b> The version has to match the file exactly; a
 *       vendored jar and a hard-coded string can drift apart silently, and the failure mode is the hang this
 *       phase is supposed to avoid.</li>
 *   <li><b>The repo already has the right shape for this.</b> {@code AdbTools} treats platform-tools as an
 *       optional capability with an {@code installHint()}, and {@code SessionBackends} does the same for
 *       gamescope. A missing {@code scrcpy} is the same kind of absence, and it degrades to the same place:
 *       the Phase 2 ADB floor, which needs none of this.</li>
 * </ul>
 *
 * <p>So: install {@code scrcpy} (any distro package, Homebrew, or the release zip) and this finds it. The cost
 * relative to vendoring is one dependency the user installs; the gain is that the version is whatever they
 * actually have rather than whatever we guessed.
 *
 * <p>Nothing here throws. An absent server is {@link Optional#empty()} and {@link #installHint()}.
 */
public final class ScrcpyServer {

    private ScrcpyServer() {}

    /** Overrides everything: point straight at a {@code scrcpy-server} file. */
    public static final String SERVER_PROPERTY = "botmaker.scrcpy.server";

    /** Overrides the detected version, for a server found somewhere this cannot work it out. */
    public static final String VERSION_PROPERTY = "botmaker.scrcpy.version";

    /** scrcpy's own environment variable for the same thing — honoured so an existing setup just works. */
    public static final String SERVER_ENV = "SCRCPY_SERVER_PATH";

    /**
     * A scrcpy version, as the two numbers that decide wire layout.
     *
     * <p>Typed rather than passed as a {@code String} because it is compared, not printed: {@link
     * #supported()} is a range check, and {@link ScrcpyControl} branches on {@link #atLeast} for the one
     * touch-message field that moved. A bare version string would make both of those substring work.
     *
     * @param major the leading number, e.g. 2 in {@code 2.7}
     * @param minor the second number, or 0 for a bare {@code "3"}
     * @param text  the version exactly as it was read — this, not {@code major.minor}, is what is handed to
     *              the server, because a {@code 2.7.1} that we rendered back as {@code 2.7} would be rejected
     */
    public record Version(int major, int minor, String text) implements Comparable<Version> {

        /** Whether this is at least {@code major.minor} — the shape every wire-layout question takes. */
        public boolean atLeast(int otherMajor, int otherMinor) {
            return major != otherMajor ? major > otherMajor : minor >= otherMinor;
        }

        /**
         * Whether this stack can speak to it. <b>2.1 is the floor and it is not arbitrary:</b> the socket
         * layout changed at 2.0 (arguments became {@code key=value}, the scid was introduced), and {@code
         * INJECT_TOUCH_EVENT} gained its {@code action_button} field at 2.1. Encoding a message one field
         * short does not error — the server reads the next message's bytes as the tail of this one — so a
         * version this cannot encode exactly is one it must refuse rather than approximate.
         */
        public boolean supported() {
            return atLeast(2, 1);
        }

        @Override
        public int compareTo(Version other) {
            return major != other.major ? Integer.compare(major, other.major)
                    : Integer.compare(minor, other.minor);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /** A usable server: the file to push, and the version string to hand it. */
    public record Located(File jar, Version version) {}

    /** {@code scrcpy 2.7}, {@code scrcpy 3.1 <https://…>}, or a bare {@code 2.7} — the leading numbers. */
    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.\\d+)?");

    /**
     * The server binary and its version, or empty when either is missing.
     *
     * <p>Empty when the <em>version</em> cannot be determined even though the file exists, which is not
     * over-strictness: passing a wrong version makes the server exit with a mismatch message the client only
     * sees as a socket that never accepts. Better to say "not available" and fall back.
     */
    public static Optional<Located> locate() {
        File jar = findJar();
        if (jar == null) {
            return Optional.empty();
        }
        Version version = findVersion(jar);
        if (version == null || !version.supported()) {
            return Optional.empty();
        }
        return Optional.of(new Located(jar, version));
    }

    /** Whether the fast path can even be attempted here. False means {@link #installHint()}. */
    public static boolean available() {
        return locate().isPresent();
    }

    private static File findJar() {
        for (String candidate : new String[] {
                System.getProperty(SERVER_PROPERTY), System.getenv(SERVER_ENV)}) {
            if (candidate != null && !candidate.isBlank()) {
                File file = new File(candidate.trim());
                // An explicit pointer that is wrong is worth failing on rather than silently searching past.
                return file.isFile() ? file : null;
            }
        }
        for (File candidate : searchPath()) {
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Where a {@code scrcpy} install puts its server, in the order they should win. Package-visible so a test
     * can assert the list is non-empty and absolute without depending on what this machine has installed.
     */
    static List<File> searchPath() {
        List<File> candidates = new ArrayList<>();
        File binary = binaryOnPath();
        if (binary != null) {
            // A release zip and a Windows install keep the server next to the executable; a package install
            // puts it in ../share/scrcpy relative to bin/.
            candidates.add(new File(binary.getParentFile(), "scrcpy-server"));
            File prefix = binary.getParentFile() == null ? null : binary.getParentFile().getParentFile();
            if (prefix != null) {
                candidates.add(new File(new File(new File(prefix, "share"), "scrcpy"), "scrcpy-server"));
            }
        }
        for (String prefix : new String[] {"/usr", "/usr/local", "/opt/homebrew", "/opt/local"}) {
            candidates.add(new File(prefix + "/share/scrcpy/scrcpy-server"));
        }
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            candidates.add(new File(home + "/.local/share/scrcpy/scrcpy-server"));
        }
        return candidates;
    }

    /**
     * The version, from — in order — the override property, the {@code scrcpy} binary's own {@code --version},
     * and finally the file name (release zips ship {@code scrcpy-server-v2.7}).
     *
     * <p>Asking the binary is the reliable one and is why it is tried before the name: a distro package names
     * the file {@code scrcpy-server} with no version in it at all.
     */
    static Version findVersion(File jar) {
        Version fromProperty = parse(System.getProperty(VERSION_PROPERTY));
        if (fromProperty != null) {
            return fromProperty;
        }
        Version fromBinary = parse(binaryVersionOutput());
        if (fromBinary != null) {
            return fromBinary;
        }
        return jar == null ? null : parse(jar.getName());
    }

    /** The first {@code major.minor} in {@code text}, or null. Pure, so the shapes above are testable. */
    static Version parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = VERSION.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return new Version(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                matcher.group());
    }

    /** {@code scrcpy --version}'s first line, or {@code ""}. Bounded — a hung binary must not hang a picker. */
    private static String binaryVersionOutput() {
        File binary = binaryOnPath();
        if (binary == null) {
            return "";
        }
        Process process = null;
        try {
            process = new ProcessBuilder(binary.getAbsolutePath(), "--version")
                    .redirectErrorStream(true)
                    .start();
            String line;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                line = reader.readLine();
            }
            process.waitFor(2, TimeUnit.SECONDS);
            return line == null ? "" : line;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception e) {
            return "";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /** The first {@code scrcpy} on {@code PATH}, or null. Walks {@code PATH} rather than spawning {@code which}. */
    private static File binaryOnPath() {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "scrcpy.exe" : "scrcpy";
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            File candidate = new File(dir, name);
            if (candidate.isFile() && candidate.canExecute()) {
                return candidate;
            }
        }
        return null;
    }

    /** The one-line, user-facing sentence for a machine with no scrcpy. */
    public static String installHint() {
        return "install `scrcpy` (2.1 or newer) for continuous video and direct input injection — without it "
                + "a phone still works over plain ADB, one screenshot and one `input tap` at a time";
    }
}
