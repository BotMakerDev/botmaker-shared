package com.botmaker.shared.launch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Mac's {@code .app} bundles read as menu entries, on any OS: the bundles are folders made here. */
class MacAppsTest {

    @TempDir
    Path root;

    private static final String HADES_PLIST = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0">
            <dict>
              <key>CFBundleExecutable</key>
              <string>Hades-Mac</string>
              <key>CFBundleName</key>
              <string>Hades</string>
              <key>CFBundleDisplayName</key>
              <string>Hades &amp; Friends</string>
              <key>LSApplicationCategoryType</key>
              <string>public.app-category.action-games</string>
            </dict>
            </plist>
            """;

    @Test
    void theStringValuesOfAPlistAreRead() {
        Map<String, String> info = MacApps.strings(HADES_PLIST);
        assertEquals("Hades-Mac", info.get("CFBundleExecutable"));
        assertEquals("Hades & Friends", info.get("CFBundleDisplayName"));
        assertTrue(MacApps.strings(null).isEmpty());
    }

    @Test
    void aNestedDictsStringsAreNotTheBundles() {
        String plist = """
                <plist version="1.0"><dict>
                  <key>CFBundleDocumentTypes</key>
                  <array><dict><key>CFBundleName</key><string>Save file</string></dict></array>
                  <key>CFBundleName</key>
                  <string>Hades</string>
                </dict></plist>
                """;
        assertEquals("Hades", MacApps.strings(plist).get("CFBundleName"));
    }

    @Test
    void aBundleRunsItsOwnExecutableAndAnythingElseRunsAsGiven() throws IOException {
        Path app = bundle(root, "Hades.app", "Hades");
        String program = app.resolve("Contents/MacOS/Hades").toString();
        assertEquals(program, MacApps.program(app.toString()));
        assertEquals(program, MacApps.program(app + "/"));
        assertEquals("/usr/games/hades", MacApps.program("/usr/games/hades"));
    }

    @Test
    void aBundleIsAnEntryNamedAfterItsDisplayNameRunningItsExecutable() throws IOException {
        Path app = bundle(root, "Hades.app", "Hades-Mac");
        DesktopEntries.Entry entry = MacApps.entry(app, f -> HADES_PLIST).orElseThrow();
        assertEquals(app.toString(), entry.id());
        assertEquals("Hades & Friends", entry.name());
        assertEquals(Set.of("Game"), entry.categories());
        // What a running copy shows in the process table: the executable's whole path, never a bare app name.
        assertEquals(List.of(app.resolve("Contents/MacOS/Hades-Mac").toString()), DesktopEntries.tokensOf(entry));
        assertEquals(new LaunchSpec(LaunchKind.DESKTOP, app.toString()), entry.spec());
    }

    @Test
    void withoutAPlistTheBundlesNameAndItsOnlyProgramStand() throws IOException {
        Path app = bundle(root, "Notes Plus.app", "notes");
        DesktopEntries.Entry entry = MacApps.entry(app, f -> null).orElseThrow();
        assertEquals("Notes Plus", entry.name());
        assertEquals(Set.of(), entry.categories());
        assertEquals(Optional.of(app.resolve("Contents/MacOS/notes")), MacApps.executable(app));
    }

    @Test
    void aBundleWithNothingToRunIsNoEntry() throws IOException {
        Path app = Files.createDirectories(root.resolve("Broken.app/Contents/MacOS"));
        assertEquals(Optional.empty(), MacApps.entry(app, f -> null));
    }

    @Test
    void bundlesAreFoundOneFolderDeepButNotInsideAnotherBundle() throws IOException {
        Path top = bundle(root, "Game.app", "Game");
        Path nested = bundle(root.resolve("Utilities"), "Tool.app", "Tool");
        bundle(top.resolve("Contents/Helpers"), "Helper.app", "Helper");
        Files.createDirectories(root.resolve("Readme.app.txt"));
        assertEquals(List.of(top, nested), MacApps.bundles(root));
        assertEquals(List.of(), MacApps.bundles(root.resolve("missing")));
    }

    private static Path bundle(Path parent, String name, String program) throws IOException {
        Path app = parent.resolve(name);
        Path macos = Files.createDirectories(app.resolve("Contents/MacOS"));
        Files.writeString(macos.resolve(program), "#!/bin/sh\n");
        return app;
    }
}
