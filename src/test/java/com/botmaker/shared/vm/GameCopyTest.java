package com.botmaker.shared.vm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A game of this PC found, served to the guest, and recorded in the guest's launcher. */
class GameCopyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GUEST_STEAM = "C:\\Program Files\\Steam\\steam.exe";

    @Test
    void aSteamGameIsItsLibraryFolderAndGoesToTheLibraryBesideTheGuestsSteam(@TempDir Path steamapps) throws Exception {
        Path acf = steamapps.resolve("appmanifest_570.acf");
        Files.writeString(acf, "\"AppState\"\n{\n\t\"appid\"\t\t\"570\"\n\t\"name\"\t\t\"Dota 2\"\n"
                + "\t\"installdir\"\t\t\"dota 2 beta\"\n}\n");
        assertTrue(GameCopy.steam(acf).isEmpty(), "no folder: nothing to copy");

        Files.createDirectories(steamapps.resolve("common/dota 2 beta"));
        GameCopy.Source dota = GameCopy.steam(acf).orElseThrow();
        assertEquals("570", dota.id());
        assertEquals("Dota 2", dota.name());
        String into = dota.guestFolder(GuestLauncher.STEAM.gamesFolder(GUEST_STEAM));
        assertEquals("C:\\Program Files\\Steam\\steamapps\\common\\dota 2 beta", into);

        Map<String, byte[]> record = GameCopy.record(dota, into, null);
        assertArrayEquals(Files.readAllBytes(acf), record.get("C:\\Program Files\\Steam\\steamapps\\appmanifest_570.acf"));
        assertEquals(1, record.size());
    }

    @Test
    void anEpicGameIsRecordedAtItsGuestFolderInPlaceOfAnOlderEntry(@TempDir Path dir) throws Exception {
        Path folder = Files.createDirectories(dir.resolve("FirestoneOnlineIdleRPG"));
        String here = folder.toString().replace("\\", "\\\\");
        Path item = dir.resolve("79EE.item");
        Files.writeString(item, "{\"AppName\":\"43d4\",\"DisplayName\":\"Firestone\",\"MainGameAppName\":\"\","
                + "\"InstallLocation\":\"" + here + "\",\"ManifestLocation\":\"" + here + "\\\\.egstore\","
                + "\"StagingLocation\":\"" + here + "\\\\.egstore/bps\",\"CatalogNamespace\":\"ns\","
                + "\"CatalogItemId\":\"item\",\"AppVersionString\":\"739\",\"bRequiresAuth\":true}");
        GameCopy.Source game = GameCopy.epic(item).orElseThrow();
        assertEquals("Firestone", game.name());
        String into = game.guestFolder(GuestLauncher.EPIC.gamesFolder(GuestLauncher.EPIC.executables().getFirst()));
        assertEquals("C:\\Program Files\\Epic Games\\FirestoneOnlineIdleRPG", into);

        byte[] guestList = ("\uFEFF{\"InstallationList\":[{\"AppName\":\"other\",\"InstallLocation\":\"C:\\\\x\"},"
                + "{\"AppName\":\"43d4\",\"InstallLocation\":\"D:\\\\old\"}]}").getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> record = GameCopy.record(game, into, guestList);

        JsonNode manifest = JSON.readTree(record.get(GameCopy.EPIC_MANIFESTS + "\\79EE.item"));
        assertEquals(into, manifest.path("InstallLocation").asText());
        assertEquals(into + "\\.egstore", manifest.path("ManifestLocation").asText());
        assertEquals(into + "\\.egstore/bps", manifest.path("StagingLocation").asText());
        assertTrue(manifest.path("bRequiresAuth").asBoolean(), "the rest of the manifest is kept");

        JsonNode list = JSON.readTree(record.get(GameCopy.EPIC_INSTALLED)).path("InstallationList");
        assertEquals(2, list.size());
        assertEquals("other", list.get(0).path("AppName").asText());
        assertEquals(into, list.get(1).path("InstallLocation").asText());
        assertEquals("ns", list.get(1).path("NamespaceId").asText());
        assertEquals("739", list.get(1).path("AppVersion").asText());

        JsonNode fresh = JSON.readTree(GameCopy.record(game, into, null).get(GameCopy.EPIC_INSTALLED));
        assertEquals(1, fresh.path("InstallationList").size(), "a guest Epic never started has no list yet");

        String manifestText = Files.readString(item);
        Files.writeString(item, manifestText.replace("\"MainGameAppName\":\"\"", "\"MainGameAppName\":\"base\""));
        assertTrue(GameCopy.epic(item).isEmpty(), "an add-on goes with its game");
        Files.writeString(item, manifestText.replace("\"InstallLocation\":\"" + here + "\"", "\"InstallLocation\":\"\""));
        assertTrue(GameCopy.epic(item).isEmpty(), "no location: not the working directory");
    }

    @Test
    void theServerGivesTheListedFilesToWhoeverHasTheTokenAndNothingElse(@TempDir Path folder) throws Exception {
        Files.createDirectories(folder.resolve("Données/Sub dir"));
        Files.writeString(folder.resolve("game.exe"), "exe!");
        Files.writeString(folder.resolve("Données/Sub dir/a+b.pak"), "pak bytes");
        HttpClient http = HttpClient.newHttpClient();
        try (FolderServer server = FolderServer.serve(folder)) {
            String base = "http://127.0.0.1:" + server.port() + "/" + server.token();
            assertEquals(13, server.total());
            HttpResponse<byte[]> list = http.send(HttpRequest.newBuilder(URI.create(base + "/list")).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals("9\tDonnées\\Sub dir\\a+b.pak\n4\tgame.exe\n", new String(list.body(), StandardCharsets.UTF_8));

            HttpResponse<String> pak = get(http, base + "/file?p=" + "Donn%C3%A9es%5CSub%20dir%5Ca%2Bb.pak");
            assertEquals(200, pak.statusCode());
            assertEquals("pak bytes", pak.body());
            assertEquals(9, server.sent());

            assertEquals(404, get(http, base + "/file?p=..%5C..%5Csecret").statusCode());
            assertEquals(404, get(http, "http://127.0.0.1:" + server.port() + "/wrong/list").statusCode());
        }
    }

    @Test
    void theGuestScriptQuotesTheFolderReadsUtf8AndRefusesWithoutRoom(@TempDir Path dir) throws Exception {
        Path folder = Files.createDirectories(dir.resolve("Bob's Game"));
        GameCopy.Source game = new GameCopy.Source(GuestLauncher.EPIC, "bob", "Bob's Game", folder, dir.resolve("x.item"));
        String script = GameCopy.copyScript(40123, "t0k", game, game.guestFolder("C:\\Program Files\\Epic Games"));
        assertTrue(script.contains("$base = 'http://10.0.2.2:40123/t0k'"), script);
        assertTrue(script.contains("$dest = 'C:\\Program Files\\Epic Games\\Bob''s Game'"), script);
        assertTrue(script.contains("$web.Encoding = [Text.Encoding]::UTF8"), script);
        assertTrue(script.contains("exit " + GameCopy.NO_ROOM), script);
        assertTrue(script.contains("CreateDirectory('" + GameCopy.EPIC_MANIFESTS + "')"), script);
    }

    private static HttpResponse<String> get(HttpClient http, String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
