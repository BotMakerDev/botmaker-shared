package com.botmaker.shared.vm;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Steam or Epic game of this PC, copied into a game VM once, so the VM's launcher has it without downloading
 * it again. The guest fetches the game's folder from a {@link FolderServer} at {@code 10.0.2.2} (QEMU's user
 * network), into the folder its launcher installs games in; then the launcher's own record of the game is
 * written beside it (Steam's {@code appmanifest_<id>.acf}, Epic's {@code .item} manifest and its entry in
 * {@code LauncherInstalled.dat}), so the launcher lists it installed and checks the files rather than fetching
 * them. Nothing is shared and no account is made on this PC.
 *
 * <p>QEMU only: VMware's guest reaches this PC through a network of its own, which the server doesn't listen on.
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
     * {@code progress} one sentence every few seconds (the bytes fetched: a resumed copy skips the files already
     * there whole, and finishes short of the total). The VM's launcher is closed for the record to be written:
     * the user opens it again, and it checks the files.
     *
     * @throws IOException with a sentence: no launcher in the VM, not enough room, or the copy failing
     */
    public static void copy(VmRecord vm, Source game, Consumer<String> progress)
            throws IOException, InterruptedException {
        if (vm.hypervisor() != Hypervisor.QEMU) {
            throw new IOException("Copying a game into a VMware VM isn't possible yet: install it from the VM's "
                    + game.launcher().displayName() + ".");
        }
        String into;
        try (GuestAgent agent = GuestAgent.connect(vm.agentPort(), AGENT_TIMEOUT_MS)) {
            into = game.guestFolder(gamesFolder(agent, game.launcher()).orElseThrow(() -> new IOException(
                    game.launcher().displayName() + " isn't installed in the game VM " + vm.name()
                            + ": install it there first.")));
        }
        try (FolderServer server = FolderServer.serve(game.folder())) {
            String of = " MB fetched, of " + megabytes(server.total()) + " MB.";
            Thread reporter = Thread.ofPlatform().daemon().name("game-copy-progress").start(() -> {
                try {
                    while (true) {
                        Thread.sleep(REPORT_EVERY);
                        progress.accept("Copying " + game.name() + " into the VM: " + megabytes(server.sent()) + of);
                    }
                } catch (InterruptedException e) {
                    // the copy is over
                }
            });
            GuestAgent.Ran ran;
            try (GuestAgent agent = GuestAgent.connect(vm.agentPort(), COPY_REPLY_MS)) {
                ran = agent.run(POWERSHELL, encoded(copyScript(server.port(), server.token(), game, into)), COPY);
            } finally {
                reporter.interrupt();
                reporter.join(); // its last sentence before the next one
            }
            if (ran.exitCode() == NO_ROOM) throw new IOException(ran.output().strip());
            if (ran.exitCode() != 0) {
                throw new IOException("Copying " + game.name() + " into the VM failed (exit code " + ran.exitCode()
                        + "): " + ran.output().strip());
            }
        }
        progress.accept("Recording " + game.name() + " in the VM's " + game.launcher().displayName() + "…");
        try (GuestAgent agent = GuestAgent.connect(vm.agentPort(), AGENT_TIMEOUT_MS)) {
            agent.run("C:\\Windows\\System32\\taskkill.exe", List.of("/F", "/T", "/IM", game.launcher().process()),
                    Duration.ofSeconds(30));
            byte[] installed = game.launcher() == GuestLauncher.EPIC ? agent.readFile(EPIC_INSTALLED).orElse(null) : null;
            for (Map.Entry<String, byte[]> file : record(game, into, installed).entrySet()) {
                agent.writeFile(file.getKey(), file.getValue());
            }
        }
    }

    /** Where the guest's {@code launcher} installs games, from where its program is there; empty when it isn't. */
    private static Optional<String> gamesFolder(GuestAgent agent, GuestLauncher launcher) throws IOException {
        for (String exe : launcher.executables()) {
            if (agent.fileExists(exe)) return Optional.of(launcher.gamesFolder(exe));
        }
        return Optional.empty();
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
     * The guest's PowerShell for the copy into {@code into}: lists the files (UTF-8), skips those already there
     * whole, refuses (exit {@value #NO_ROOM}, one sentence) when the disk lacks the room, then fetches the rest,
     * each tried three times. It also makes the folders the launcher's record goes in, which a launcher not yet
     * started may lack.
     */
    static String copyScript(int port, String token, Source game, String into) {
        List<String> folders = game.launcher() == GuestLauncher.EPIC
                ? List.of(EPIC_MANIFESTS, EPIC_INSTALLED.substring(0, EPIC_INSTALLED.lastIndexOf('\\')))
                : List.of(recordFolder(game, into));
        StringBuilder make = new StringBuilder();
        for (String f : folders) make.append("[void][IO.Directory]::CreateDirectory(").append(quoted(f)).append(")\n");
        return "$ErrorActionPreference = 'Stop'\n"
                + "$base = 'http://10.0.2.2:" + port + "/" + token + "'\n"
                + "$dest = " + quoted(into) + "\n"
                + "$web = New-Object Net.WebClient\n"
                + "$web.Encoding = [Text.Encoding]::UTF8\n"
                + "$todo = New-Object Collections.ArrayList\n"
                + "$need = [long]0\n"
                + "foreach ($line in ($web.DownloadString(\"$base/list\") -split \"`n\")) {\n"
                + "  if (-not $line) { continue }\n"
                + "  $size, $rel = $line -split \"`t\", 2\n"
                + "  $to = [IO.Path]::Combine($dest, $rel)\n"
                + "  $have = New-Object IO.FileInfo $to\n"
                + "  if ($have.Exists -and $have.Length -eq [long]$size) { continue }\n"
                + "  [void]$todo.Add(@($rel, $to)); $need += [long]$size\n"
                + "}\n"
                + "$free = (New-Object IO.DriveInfo 'C').AvailableFreeSpace\n"
                + "if ($free -lt $need + 1GB) {\n"
                + "  Write-Output ('The VM has {0:N1} GB free, and the game needs {1:N1} GB more.' -f ($free / 1GB), "
                + "($need / 1GB))\n"
                + "  exit " + NO_ROOM + "\n"
                + "}\n"
                + "foreach ($f in $todo) {\n"
                + "  [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($f[1]))\n"
                + "  for ($try = 1; ; $try++) {\n"
                + "    try { $web.DownloadFile(\"$base/file?p=\" + [Uri]::EscapeDataString($f[0]), $f[1]); break }\n"
                + "    catch { if ($try -ge 3) { Write-Output ($f[0] + ': ' + $_.Exception.Message); exit 1 } }\n"
                + "  }\n"
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
