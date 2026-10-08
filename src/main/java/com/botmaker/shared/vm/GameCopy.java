package com.botmaker.shared.vm;

import com.botmaker.shared.Spawn;
import com.botmaker.shared.game.EpicLibraryScanner;
import com.botmaker.shared.game.SteamLibraryScanner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A Steam or Epic game of this PC, copied into a game VM once, so the VM's launcher has it without downloading
 * it again. The guest copies the game's folder into the folder its launcher installs games in; then the
 * launcher's own record of the game is written beside it (Steam's {@code appmanifest_<id>.acf}, Epic's {@code
 * .item} manifest and its entry in {@code LauncherInstalled.dat}), so the launcher lists it installed and checks
 * the files rather than fetching them. No account is made on this PC.
 * <ul>
 *   <li><b>QEMU:</b> the guest fetches the folder from a {@link FolderServer} at {@code 10.0.2.2}, QEMU's user
 *       network's name for this PC's loopback. Nothing is shared.</li>
 *   <li><b>VMware:</b> its guest reaches this PC through a network of its own, which the server doesn't listen
 *       on; the folder is shared with that VM alone, read-only, through VMware Tools for as long as the copy
 *       takes.</li>
 * </ul>
 */
public final class GameCopy {

    static final String EPIC_MANIFESTS = "C:\\ProgramData\\Epic\\EpicGamesLauncher\\Data\\Manifests";
    static final String EPIC_INSTALLED = "C:\\ProgramData\\Epic\\UnrealEngineLauncher\\LauncherInstalled.dat";

    /** How long the guest may take over a copy: 20 GB at QEMU's user-network speed, with room. */
    private static final Duration COPY = Duration.ofHours(4);
    private static final Duration REPORT_EVERY = Duration.ofSeconds(2);
    private static final int AGENT_TIMEOUT_MS = 30_000;
    /**
     * How long the agent may take over one answer while the copy runs: a guest busy with its launcher's first
     * start answered a status call in over 30 s, live, and the copy is hours long.
     */
    private static final int COPY_REPLY_MS = 120_000;
    /** The copy script's exit code when the guest's disk lacks the room. */
    static final int NO_ROOM = 3;
    private static final String POWERSHELL = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
    /** The name a VMware VM is given the game's folder by while it copies it. */
    static final String SHARE = "botmaker-game";
    /**
     * Where a VMware guest's copy writes the bytes it has copied, for this PC to read: no server here counts
     * them.
     */
    static final String PROGRESS_FILE = GuestUnattend.GUEST_FOLDER + "\\copy-progress";

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Pattern INSTALL_DIR = Pattern.compile("\"installdir\"\\s+\"([^\"]+)\"");
    private static final Pattern NAME = Pattern.compile("\"name\"\\s+\"([^\"]*)\"");

    private GameCopy() {}

    /**
     * A game of this PC that can go into a VM: its launcher, its launch id (the one {@code steam:}/{@code epic:}
     * names), its title, its folder here, and the launcher's record of it here.
     */
    public record Source(GuestLauncher launcher, String id, String name, Path folder, Path manifest) {

        /** Where it goes in a guest whose launcher installs games in {@code gamesFolder}. */
        public String guestFolder(String gamesFolder) {
            return gamesFolder + "\\" + folder.getFileName();
        }

        @Override
        public String toString() {
            return name + " (" + launcher.displayName() + ")";
        }
    }

    /** This PC's Steam and Epic games, by title. Reads the launchers' records, no picture; never throws. */
    public static List<Source> onThisPc() {
        List<Source> all = new ArrayList<>();
        SteamLibraryScanner.appManifests().forEach(acf -> steam(acf).ifPresent(all::add));
        EpicLibraryScanner.manifests().forEach(item -> epic(item).ifPresent(all::add));
        all.sort(Comparator.comparing(Source::name, String.CASE_INSENSITIVE_ORDER));
        return all;
    }

    /** The Steam game {@code acf} records, when its folder is there. */
    static Optional<Source> steam(Path acf) {
        try {
            String text = Files.readString(acf);
            Matcher dir = INSTALL_DIR.matcher(text);
            Matcher name = NAME.matcher(text);
            String id = acf.getFileName().toString().replaceAll("^appmanifest_|\\.acf$", "");
            if (!dir.find() || dir.group(1).isBlank()) return Optional.empty();
            Path folder = acf.resolveSibling("common").resolve(dir.group(1));
            String title = name.find() && !name.group(1).isBlank() ? name.group(1) : id;
            return Files.isDirectory(folder)
                    ? Optional.of(new Source(GuestLauncher.STEAM, id, title, folder, acf)) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The Epic game {@code item} records, when its folder is there; an add-on of another game isn't one. */
    static Optional<Source> epic(Path item) {
        try {
            JsonNode m = JSON.readTree(item.toFile());
            String id = m.path("AppName").asText("");
            String location = m.path("InstallLocation").asText("");
            if (id.isBlank() || location.isBlank() || !m.path("MainGameAppName").asText("").isBlank()) {
                return Optional.empty();
            }
            Path folder = Path.of(location);
            if (!folder.isAbsolute() || folder.getFileName() == null || !Files.isDirectory(folder)) return Optional.empty();
            String name = m.path("DisplayName").asText(id);
            return Optional.of(new Source(GuestLauncher.EPIC, id, name, folder, item));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Copies {@code game} into the running {@code vm} and records it in the VM's launcher, telling
     * {@code progress} one sentence every few seconds (the bytes copied, counted per file under VMware: a resumed copy skips the files already
     * there whole, and finishes short of the total). The VM's launcher is closed for the record to be written:
     * the user opens it again, and it checks the files.
     *
     * @throws IOException with a sentence: no launcher in the VM, not enough room, or the copy failing
     */
    public static void copy(VmRecord vm, VmCredentials credentials, Source game, Consumer<String> progress)
            throws IOException, InterruptedException {
        VmSetup.requireWindows(vm);
        Guest guest = switch (vm.hypervisor()) {
            case QEMU -> new QemuGuest(vm.agentPort());
            case VMWARE -> VmwareGuest.of(vm, credentials);
            case UNKNOWN -> throw new IOException("This VM's hypervisor is unknown.");
        };
        String into = game.guestFolder(gamesFolder(guest, game.launcher()).orElseThrow(() -> new IOException(
                game.launcher().displayName() + " isn't installed in the game VM " + vm.name()
                        + ": install it there first.")));
        GuestAgent.Ran ran = guest.copy(game, into, progress);
        if (ran.exitCode() == NO_ROOM) throw new IOException(ran.output().strip());
        if (ran.exitCode() != 0) {
            throw new IOException("Copying " + game.name() + " into the VM failed (exit code " + ran.exitCode()
                    + "): " + ran.output().strip());
        }
        progress.accept("Recording " + game.name() + " in the VM's " + game.launcher().displayName() + "…");
        guest.end(game.launcher().process());
        byte[] installed = game.launcher() == GuestLauncher.EPIC ? guest.read(EPIC_INSTALLED).orElse(null) : null;
        for (Map.Entry<String, byte[]> file : record(game, into, installed).entrySet()) {
            guest.write(file.getKey(), file.getValue());
        }
    }

    /** Where the guest's {@code launcher} installs games, from where its program is there; empty when it isn't. */
    private static Optional<String> gamesFolder(Guest guest, GuestLauncher launcher)
            throws IOException, InterruptedException {
        for (String exe : launcher.executables()) {
            if (guest.exists(exe)) return Optional.of(launcher.gamesFolder(exe));
        }
        return Optional.empty();
    }

    /** What a copy asks of the guest, through QEMU's guest agent or VMware Tools. */
    private interface Guest {

        boolean exists(String path) throws IOException, InterruptedException;

        /** Runs the copy script for {@code game} into {@code into}, telling {@code progress} how far it is. */
        GuestAgent.Ran copy(Source game, String into, Consumer<String> progress) throws IOException, InterruptedException;

        /** Ends {@code process} and the processes it started, if it runs. */
        void end(String process) throws IOException, InterruptedException;

        Optional<byte[]> read(String path) throws IOException, InterruptedException;

        void write(String path, byte[] bytes) throws IOException, InterruptedException;
    }

    /** A QEMU guest, through its agent; one connection per call, as a copy lasts longer than one should. */
    private record QemuGuest(int agentPort) implements Guest {

        @Override
        public boolean exists(String path) throws IOException {
            try (GuestAgent agent = GuestAgent.connect(agentPort, AGENT_TIMEOUT_MS)) {
                return agent.fileExists(path);
            }
        }

        @Override
        public GuestAgent.Ran copy(Source game, String into, Consumer<String> progress)
                throws IOException, InterruptedException {
            try (FolderServer server = FolderServer.serve(game.folder())) {
                Thread reporter = reporter(game, server.total(), server::sent, progress);
                try (GuestAgent agent = GuestAgent.connect(agentPort, COPY_REPLY_MS)) {
                    return agent.run(POWERSHELL, encoded(copyScript(From.server(server.port(), server.token()), game,
                            into)), COPY);
                } finally {
                    stop(reporter);
                }
            }
        }

        @Override
        public void end(String process) throws IOException, InterruptedException {
            try (GuestAgent agent = GuestAgent.connect(agentPort, AGENT_TIMEOUT_MS)) {
                agent.run("C:\\Windows\\System32\\taskkill.exe", List.of("/F", "/T", "/IM", process),
                        Duration.ofSeconds(30));
            }
        }

        @Override
        public Optional<byte[]> read(String path) throws IOException {
            try (GuestAgent agent = GuestAgent.connect(agentPort, AGENT_TIMEOUT_MS)) {
                return agent.readFile(path);
            }
        }

        @Override
        public void write(String path, byte[] bytes) throws IOException {
            try (GuestAgent agent = GuestAgent.connect(agentPort, AGENT_TIMEOUT_MS)) {
                agent.writeFile(path, bytes);
            }
        }
    }

    /** A VMware guest, through {@code vmrun} and VMware Tools, as the VM's own user. */
    private record VmwareGuest(VmwareWorkstation ws, Path vmx, String user, String password) implements Guest {

        static VmwareGuest of(VmRecord vm, VmCredentials credentials) throws IOException, InterruptedException {
            VmwareWorkstation ws = VmwareWorkstation.find()
                    .orElseThrow(() -> new IOException("VMware Workstation isn't installed."));
            // vmrun's file check reads every failure as "no such file": a guest whose Tools aren't up yet would
            // look as if it lacked the launcher.
            if (!ws.toolsRunning(vm.vmx())) throw new IOException("VMware Tools isn't answering in the VM yet.");
            return new VmwareGuest(ws, vm.vmx(), VmSetup.GUEST_USER, credentials.guest());
        }

        @Override
        public boolean exists(String path) throws IOException, InterruptedException {
            return ws.fileExistsInGuest(vmx, user, password, path);
        }

        @Override
        public GuestAgent.Ran copy(Source game, String into, Consumer<String> progress)
                throws IOException, InterruptedException {
            long total;
            try (Stream<Path> walk = Files.walk(game.folder())) {
                total = walk.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
            }
            // The last copy's count, left in the guest, is no count of this one.
            ws.deleteInGuest(vmx, user, password, PROGRESS_FILE);
            boolean wereOn = ws.shareFolder(vmx, SHARE, game.folder());
            try {
                Thread reporter = reporter(game, total, this::copied, progress);
                try {
                    Spawn.Completed ran = ws.runPowerShell(vmx, user, password,
                            copyScript(From.share(VmwareWorkstation.sharedFolderInGuest(SHARE)), game, into), COPY);
                    return new GuestAgent.Ran(ran.exitCode(), ran.output());
                } finally {
                    stop(reporter);
                }
            } finally {
                ws.unshareFolder(vmx, SHARE, wereOn);
            }
        }

        /**
         * The bytes the guest's copy says it has copied; -1 when that can't be read (not written yet, among
         * others). One vmrun call, no existence check first: it runs every few seconds for the whole copy.
         */
        private long copied() {
            Path file = null;
            try {
                file = Files.createTempFile("botmaker-guest", ".txt");
                return ws.copyFromGuest(vmx, user, password, PROGRESS_FILE, file).ok()
                        ? Long.parseLong(Files.readString(file, StandardCharsets.US_ASCII).strip()) : -1;
            } catch (IOException | RuntimeException e) {
                return -1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            } finally {
                if (file != null) file.toFile().delete();
            }
        }

        @Override
        public void end(String process) throws IOException, InterruptedException {
            ws.runPowerShell(vmx, user, password, "& taskkill.exe /F /T /IM " + quoted(process)
                    + " 2>&1 | Out-Null\nexit 0\n", Duration.ofSeconds(30));
        }

        @Override
        public Optional<byte[]> read(String path) throws IOException, InterruptedException {
            if (!exists(path)) return Optional.empty();
            Path file = Files.createTempFile("botmaker-guest", ".bin");
            try {
                Commands.require(ws.copyFromGuest(vmx, user, password, path, file), "VMware couldn't read " + path
                        + " in the VM");
                return Optional.of(Files.readAllBytes(file));
            } finally {
                Files.deleteIfExists(file);
            }
        }

        @Override
        public void write(String path, byte[] bytes) throws IOException, InterruptedException {
            Path file = Files.createTempFile("botmaker-guest", ".bin");
            try {
                Files.write(file, bytes);
                Commands.require(ws.copyToGuest(vmx, user, password, file, path), "VMware couldn't write " + path
                        + " in the VM");
            } finally {
                Files.deleteIfExists(file);
            }
        }
    }

    /**
     * Tells {@code progress}, every few seconds, the bytes {@code copied} of {@code total}, until stopped; a
     * count of -1, unknown, and one read as the copy ended, say nothing.
     */
    private static Thread reporter(Source game, long total, LongSupplier copied, Consumer<String> progress) {
        String of = " MB copied, of " + megabytes(total) + " MB.";
        return Thread.ofPlatform().daemon().name("game-copy-progress").start(() -> {
            try {
                while (true) {
                    Thread.sleep(REPORT_EVERY);
                    long bytes = copied.getAsLong();
                    if (Thread.currentThread().isInterrupted()) return;
                    if (bytes >= 0) progress.accept("Copying " + game.name() + " into the VM: " + megabytes(bytes) + of);
                }
            } catch (InterruptedException e) {
                // the copy is over
            }
        });
    }

    private static void stop(Thread reporter) throws InterruptedException {
        reporter.interrupt();
        reporter.join(); // its last sentence before the next one
    }

    private static List<String> encoded(String script) {
        return List.of("-NoProfile", "-ExecutionPolicy", "Bypass", "-EncodedCommand",
                Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)));
    }

    private static long megabytes(long bytes) {
        return Math.round(bytes / 1_048_576.0);
    }

    /** The guest folder the launcher's record goes in, for a game copied {@code into}. */
    private static String recordFolder(Source game, String into) {
        return game.launcher() == GuestLauncher.STEAM
                ? into.substring(0, into.lastIndexOf("\\common\\")) : EPIC_MANIFESTS;
    }

    /**
     * Where the guest's copy reads the game: its PowerShell that sets the source up, an expression that is the
     * list of {@code <size>\t<relative path>} lines, a statement that copies {@code $f[0]} (relative) to
     * {@code $f[1]}, and the guest file it writes its bytes copied to, or {@code null}.
     */
    record From(String setup, String list, String fetch, String progressFile) {

        /** QEMU: this PC's {@link FolderServer}, at {@code 10.0.2.2}. */
        static From server(int port, String token) {
            return new From("$base = 'http://10.0.2.2:" + port + "/" + token + "'\n"
                    + "$web = New-Object Net.WebClient\n"
                    + "$web.Encoding = [Text.Encoding]::UTF8\n",
                    "($web.DownloadString(\"$base/list\") -split \"`n\")",
                    "$web.DownloadFile(\"$base/file?p=\" + [Uri]::EscapeDataString($f[0]), $f[1])",
                    null);
        }

        /** VMware: the folder shared with the guest at {@code guestPath}. */
        static From share(String guestPath) {
            // The last copy's count, left in the guest, is no count of this one.
            return new From("$src = " + quoted(guestPath) + "\nSet-Content -LiteralPath " + quoted(PROGRESS_FILE)
                    + " 0\n",
                    "(Get-ChildItem -LiteralPath $src -Recurse -File -Force"
                            + " | ForEach-Object { '' + $_.Length + \"`t\" + $_.FullName.Substring($src.Length + 1) })",
                    "[IO.File]::Copy([IO.Path]::Combine($src, $f[0]), $f[1], $true)",
                    PROGRESS_FILE);
        }
    }

    /**
     * The guest's PowerShell for the copy into {@code into}: lists the files {@code from} has, skips those
     * already there whole, refuses (exit {@value #NO_ROOM}, one sentence) when the disk lacks the room, then
     * copies the rest, each tried three times, writing its bytes copied to {@code from}'s progress file every
     * second or so when it has one. It also makes the folders the launcher's record goes in, which a launcher not
     * yet started may lack.
     */
    static String copyScript(From from, Source game, String into) {
        List<String> folders = game.launcher() == GuestLauncher.EPIC
                ? List.of(EPIC_MANIFESTS, EPIC_INSTALLED.substring(0, EPIC_INSTALLED.lastIndexOf('\\')))
                : List.of(recordFolder(game, into));
        StringBuilder make = new StringBuilder();
        for (String f : folders) make.append("[void][IO.Directory]::CreateDirectory(").append(quoted(f)).append(")\n");
        String report = from.progressFile() == null ? ""
                : "  if ($clock.ElapsedMilliseconds -ge 1000) { Set-Content -LiteralPath " + quoted(from.progressFile())
                        + " $done; $clock.Restart() }\n";
        return "$ErrorActionPreference = 'Stop'\n"
                + from.setup()
                + "$dest = " + quoted(into) + "\n"
                + "$todo = New-Object Collections.ArrayList\n"
                + "$need = [long]0\n"
                + "foreach ($line in " + from.list() + ") {\n"
                + "  if (-not $line) { continue }\n"
                + "  $size, $rel = $line -split \"`t\", 2\n"
                + "  $to = [IO.Path]::Combine($dest, $rel)\n"
                + "  $have = New-Object IO.FileInfo $to\n"
                + "  if ($have.Exists -and $have.Length -eq [long]$size) { continue }\n"
                + "  [void]$todo.Add(@($rel, $to, [long]$size)); $need += [long]$size\n"
                + "}\n"
                + "$free = (New-Object IO.DriveInfo 'C').AvailableFreeSpace\n"
                + "if ($free -lt $need + 1GB) {\n"
                + "  Write-Output ('The VM has {0:N1} GB free, and the game needs {1:N1} GB more.' -f ($free / 1GB), "
                + "($need / 1GB))\n"
                + "  exit " + NO_ROOM + "\n"
                + "}\n"
                + "$done = [long]0\n"
                + "$clock = [Diagnostics.Stopwatch]::StartNew()\n"
                + "foreach ($f in $todo) {\n"
                + "  [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($f[1]))\n"
                + "  for ($try = 1; ; $try++) {\n"
                + "    try { " + from.fetch() + "; break }\n"
                + "    catch { if ($try -ge 3) { Write-Output ($f[0] + ': ' + $_.Exception.Message); exit 1 } }\n"
                + "  }\n"
                + "  $done += $f[2]\n"
                + report
                + "}\n"
                + make
                + "exit 0\n";
    }

    private static String quoted(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    /**
     * The files that tell the guest's launcher {@code game}, copied {@code into}, is installed, by guest path.
     * Steam: this PC's {@code .acf}, which names the folder relative to its library. Epic: the {@code .item}
     * manifest with its locations moved to {@code into}, and {@code installedList} (the guest's
     * {@code LauncherInstalled.dat}, {@code null} for none) with the game's entry in place of any older one.
     */
    static Map<String, byte[]> record(Source game, String into, byte[] installedList) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        if (game.launcher() == GuestLauncher.STEAM) {
            files.put(recordFolder(game, into) + "\\appmanifest_" + game.id() + ".acf",
                    Files.readAllBytes(game.manifest()));
            return files;
        }
        ObjectNode manifest = (ObjectNode) JSON.readTree(game.manifest().toFile());
        String here = manifest.path("InstallLocation").asText();
        for (String key : List.of("InstallLocation", "ManifestLocation", "StagingLocation")) {
            String value = manifest.path(key).asText("");
            if (!here.isEmpty() && value.startsWith(here)) manifest.put(key, into + value.substring(here.length()));
        }
        files.put(EPIC_MANIFESTS + "\\" + game.manifest().getFileName(), JSON.writeValueAsBytes(manifest));

        ObjectNode list = installedList == null || installedList.length == 0 ? JSON.createObjectNode()
                : (ObjectNode) JSON.readTree(new String(installedList, StandardCharsets.UTF_8).replaceFirst("^\uFEFF", ""));
        ArrayNode entries = list.withArray("InstallationList");
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (game.id().equals(entries.get(i).path("AppName").asText())) entries.remove(i);
        }
        entries.addObject()
                .put("InstallLocation", into)
                .put("NamespaceId", manifest.path("CatalogNamespace").asText(""))
                .put("ItemId", manifest.path("CatalogItemId").asText(""))
                .put("ArtifactId", game.id())
                .put("AppVersion", manifest.path("AppVersionString").asText(""))
                .put("AppName", game.id());
        files.put(EPIC_INSTALLED, JSON.writeValueAsBytes(list));
        return files;
    }
}
