package com.botmaker.shared.vm;

import com.botmaker.shared.Spawn;
import com.botmaker.shared.platform.Os;
import com.botmaker.shared.tools.Downloads;
import com.botmaker.shared.tools.UserDirs;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <a href="https://github.com/openai/tart">Tart</a>, which runs macOS and Linux VMs on an Apple Silicon Mac through
 * Apple's Virtualization framework. BotMaker downloads a pinned release itself ({@link #install}), checked against
 * its SHA-256, and keeps it in the cache; nothing is installed system-wide.
 *
 * <p>Its VMs live under {@link #home()} ({@code TART_HOME}), beside BotMaker's other VM folders, not in the user's
 * own {@code ~/.tart}: a VM BotMaker made is BotMaker's to delete. A guest comes as an OCI image from Cirrus Labs
 * ({@link #image}), already installed, with a signed-in user ({@value #GUEST_USER}) and Tart's guest agent, which is
 * what {@link #command} {@code exec} speaks to.
 *
 * <p>Licence: FSL-1.1-ALv2. Any use but a competing commercial product is permitted, redistribution included.
 */
public final class Tart {

    /** The pinned release: 2.38.0, 2026-09-24. */
    public static final String VERSION = "2.38.0";
    static final Downloads.Remote RELEASE = new Downloads.Remote(
            "https://github.com/openai/tart/releases/download/" + VERSION + "/tart.tar.gz",
            Downloads.Digest.SHA_256, "1712be82b687cc27792d5a2bae3f36fcb5e5dea4d5772231f5508dea567999e2", 22_908_684);

    /** The user every Cirrus Labs image signs in as; its password is the image's published default. */
    public static final String GUEST_USER = "admin";

    /** The line {@code tart run --vnc-experimental} prints once its VNC server listens, before the URL. */
    static final String VNC_LINE = "VNC server is running at ";

    /** Cirrus Labs' Ubuntu 24.04, the arm64 build on a Mac; a tag of its own, so a later LTS doesn't replace it. */
    static final String LINUX_IMAGE = "ghcr.io/cirruslabs/ubuntu:24.04";

    private static final Pattern MAJOR = Pattern.compile("^(\\d+)");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    /** How long {@link #ip} waits for a guest still starting to have an address. */
    private static final int IP_WAIT_SECONDS = 60;
    private static final Duration QUICK = Duration.ofSeconds(30);

    private final Path binary;

    private Tart(Path binary) {
        this.binary = binary;
    }

    /**
     * Whether Tart can run here: an Apple Silicon Mac on macOS 14 or later ({@code tart exec}, which BotMaker uses
     * to reach a guest, needs 14).
     */
    public static boolean supported() {
        return Os.current().isMac() && "aarch64".equals(System.getProperty("os.arch"))
                && macosMajor(System.getProperty("os.version", "")) >= 14;
    }

    /** Where the pinned Tart is unpacked: the cache, as it can always be fetched again. */
    static Path folder() {
        return UserDirs.cache().resolve("tools").resolve("tart-" + VERSION);
    }

    /** Where Tart keeps its VMs and its image cache ({@code TART_HOME}). */
    public static Path home() {
        return VmInventory.root().resolve(".tart");
    }

    /** The pinned Tart, when it is unpacked here. */
    public static Optional<Tart> find() {
        Path binary = folder().resolve("tart.app/Contents/MacOS/tart");
        return Files.isExecutable(binary) ? Optional.of(new Tart(binary)) : Optional.empty();
    }

    /** A Tart at {@code binary}: a stand-in, for the tests. */
    static Tart at(Path binary) {
        return new Tart(binary);
    }

    /** The pinned Tart, which a VM made with it needs: refused with a sentence when it isn't here any more. */
    static Tart required() throws IOException {
        return find().orElseThrow(() -> new IOException("Tart isn't here; set the VM up again."));
    }

    /**
     * Downloads the pinned release (23 MB), checks it, and unpacks it with the system's {@code tar}, which keeps the
     * app's signature. Does nothing when it is already unpacked.
     */
    public static Tart install(Downloads.Progress progress) throws IOException, InterruptedException {
        Optional<Tart> found = find();
        if (found.isPresent()) return found.get();
        Path folder = folder();
        Files.createDirectories(folder);
        Path archive = folder.resolve("tart.tar.gz");
        if (!Downloads.fetch(RELEASE, archive, progress)) {
            throw new IOException("Tart didn't download, or didn't match its pinned checksum. Check the connection "
                    + "and try again.");
        }
        Commands.require(Commands.run(QUICK, "/usr/bin/tar", "-xzf", archive.toString(), "-C", folder.toString()),
                "Tart didn't unpack");
        Files.deleteIfExists(archive);
        return find().orElseThrow(() -> new IOException("Tart unpacked, but its program isn't in " + folder + "."));
    }

    /**
     * The argv that runs Tart with {@code args} on BotMaker's VMs: through {@code env}, which sets
     * {@code TART_HOME}, as a {@link Spawn} carries no environment of its own.
     */
    public List<String> command(String... args) {
        return command(binary, home(), args);
    }

    static List<String> command(Path binary, Path home, String... args) {
        List<String> argv = new ArrayList<>(List.of("/usr/bin/env", "TART_HOME=" + home, binary.toString()));
        argv.addAll(List.of(args));
        return argv;
    }

    /** Runs {@code args} and waits up to {@code timeout}; a timeout is a failure with a sentence. */
    Spawn.Completed run(Duration timeout, String... args) throws IOException, InterruptedException {
        return Commands.run(timeout, command(args));
    }

    /** Whether the VM named {@code name} is running, from {@code tart list}. False when Tart can't say. */
    public boolean running(String name) {
        try {
            Spawn.Completed list = run(QUICK, "list", "--source", "local", "--format", "json");
            return list.ok() && runningIn(list.output(), name);
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Whether {@code tart list --format json} says {@code name} is running: its entry's {@code "Running": true}, or
     * its {@code "State": "running"} in the releases that name it so. Package-private and pure for the tests.
     */
    static boolean runningIn(String json, String name) {
        try {
            for (var vm : new com.fasterxml.jackson.databind.ObjectMapper().readTree(json)) {
                if (!name.equals(vm.path("Name").asText())) continue;
                return vm.path("Running").asBoolean(false) || "running".equalsIgnoreCase(vm.path("State").asText(""));
            }
        } catch (IOException | RuntimeException notJson) {
            // nothing to read: not running, as far as anyone can tell
        }
        return false;
    }

    /**
     * The running VM {@code name}'s address on Tart's shared network, from the Mac's DHCP leases, waiting up to a
     * minute for a guest still starting. This Mac reaches the guest's ports there directly, as no other computer
     * does: the network is behind the Mac's NAT.
     */
    public InetAddress ip(String name) throws IOException, InterruptedException {
        Spawn.Completed said = run(Duration.ofSeconds(IP_WAIT_SECONDS + 30), "ip", name, "--wait",
                Integer.toString(IP_WAIT_SECONDS));
        String ip = said.output().strip();
        if (!said.ok() || !IPV4.matcher(ip).matches()) {
            throw new IOException("Tart didn't give the VM's address: " + ip);
        }
        return InetAddress.getByName(ip); // a literal: no lookup
    }

    /**
     * This Mac's own address on the network {@code guest} is on (the bridge Tart's NAT made): where the guest
     * reaches the Mac. Empty when no interface here is on that network.
     */
    public static Optional<InetAddress> hostSide(InetAddress guest) throws IOException {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback()) continue;
            for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                InetAddress mine = address.getAddress();
                if (mine instanceof Inet4Address && !mine.equals(guest)
                        && onNetwork(mine, guest, address.getNetworkPrefixLength())) {
                    return Optional.of(mine);
                }
            }
        }
        return Optional.empty();
    }

    /** Whether {@code a} and {@code b}, both IPv4, share their first {@code prefix} bits. Pure, for the tests. */
    static boolean onNetwork(InetAddress a, InetAddress b, int prefix) {
        byte[] x = a.getAddress();
        byte[] y = b.getAddress();
        if (x.length != 4 || y.length != 4 || prefix <= 0 || prefix > 32) return false;
        int mask = -1 << (32 - prefix);
        return (ByteBuffer.wrap(x).getInt() & mask) == (ByteBuffer.wrap(y).getInt() & mask);
    }

    /**
     * The Cirrus Labs image for {@code guest}. macOS: the one a Mac on {@code hostVersion} can run, as a macOS guest
     * may not be newer than its host, so the host's own release; the {@code -base} images carry Tart's guest agent,
     * the {@code -vanilla} ones don't. Linux: Ubuntu 24.04 for arm64 ({@value #LINUX_IMAGE}), a server with the
     * agent, which {@link LinuxAutoinstall#tartSetupScript} gives the rest.
     */
    public static String image(GuestOs guest, String hostVersion) {
        if (guest == GuestOs.LINUX) return LINUX_IMAGE;
        if (guest != GuestOs.MACOS) throw new IllegalArgumentException("Tart VMs run macOS or Linux: " + guest);
        int major = macosMajor(hostVersion);
        String release = major >= 26 ? "tahoe" : major == 15 ? "sequoia" : "sonoma";
        return "ghcr.io/cirruslabs/macos-" + release + "-base:latest";
    }

    /** The major release in an {@code os.version} ({@code 15.3.1} is 15), or 0 when there is none. */
    static int macosMajor(String version) {
        Matcher m = MAJOR.matcher(version == null ? "" : version.strip());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** Where {@code tart run --vnc-experimental}'s VNC server listens, and its password for this run. */
    public record Vnc(String host, int port, String password) {

        @Override
        public String toString() {
            return "Vnc[" + host + ":" + port + "]"; // never the password
        }
    }

    /**
     * The last VNC address in Tart's output {@code log}, or empty when it has printed none yet: a line
     * {@code VNC server is running at vnc://:<password>@<host>:<port>}. Package-private and pure for the tests.
     */
    static Optional<Vnc> vnc(String log) {
        if (log == null) return Optional.empty();
        int at = log.lastIndexOf(VNC_LINE);
        if (at < 0) return Optional.empty();
        String rest = log.substring(at + VNC_LINE.length()).strip();
        String url = rest.split("\\s+", 2)[0];
        try {
            URI uri = new URI(url);
            if (!"vnc".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() <= 0) return Optional.empty();
            String info = uri.getRawUserInfo() == null ? "" : uri.getUserInfo();
            String password = info.contains(":") ? info.substring(info.indexOf(':') + 1) : info;
            return Optional.of(new Vnc(uri.getHost(), uri.getPort(), password));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }
}
