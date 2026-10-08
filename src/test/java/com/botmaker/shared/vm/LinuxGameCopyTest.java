package com.botmaker.shared.vm;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinuxGameCopyTest {

    @Test
    void theGuestFetchesEachFileFromThisPcSkipsWholeOnesAndRefusesWithoutRoom() {
        String script = LinuxGameCopy.copyScript(5123, "tok", "/home/botmaker/Games/It's Here");
        assertTrue(script.contains("base='http://10.0.2.2:5123/tok'"), script);
        assertTrue(script.contains("dest='/home/botmaker/Games/It'\\''s Here'"), script);
        assertTrue(script.contains("tab=$(printf '\\t')"), script);
        assertTrue(script.contains("to=\"$dest/$(printf '%s' \"$rel\" | tr '\\\\' '/')\""), "the list names files with \\");
        assertTrue(script.contains("[ \"$(stat -c %s \"$to\" 2>/dev/null)\" = \"$size\" ] && continue"), script);
        assertTrue(script.contains("printf \"The VM has %.1f GB free, and the game needs %.1f GB more.\\n\""), script);
        assertTrue(script.contains("exit " + GameCopy.NO_ROOM + "\n"), script);
        assertTrue(script.contains("curl -fsS -G --data-urlencode \"p=$rel\" \"$base/file\" -o \"$to\" < /dev/null"),
                "the path is encoded, and curl leaves the list to the loop");
        assertTrue(script.contains("runuser -u botmaker -- mkdir -p \"$dest\""), "the folders are the user's");
        assertTrue(script.contains("chown -R botmaker:botmaker \"$dest\""), script);
        assertFalse(script.contains("%%"), script);
    }

    @Test
    void wineIsInstalledOnceWithIts32BitHalf() {
        assertTrue(LinuxGameCopy.WINE.startsWith("command -v wine >/dev/null || "), LinuxGameCopy.WINE);
        assertTrue(LinuxGameCopy.WINE.contains("apt-get install -y -q -o DPkg::Lock::Timeout=600 wine wine64 "
                + "wine32:i386"), "apt's own runs after a start hold the lock for minutes: " + LinuxGameCopy.WINE);
        assertTrue(LinuxAutoinstall.setupScript().contains(LinuxGameCopy.WINE), "a new VM has it from its setup");
    }

    @Test
    void theSignInCodeIsReadFromTheCodeOrThePagesWholeAnswer() {
        String code = "0123456789abcdef0123456789abcdef";
        assertEquals(Optional.of(code), Legendary.code("  " + code + "\n"));
        assertEquals(Optional.of(code), Legendary.code("{\"warning\":\"Do not share this code with any 3rd party "
                + "service.\",\"redirectUrl\":\"https://localhost/launcher/authorized?code=" + code
                + "\",\"authorizationCode\":\"" + code + "\",\"exchangeCode\":null,\"sid\":null}"));
        assertEquals(Optional.empty(), Legendary.code("my password"));
    }

    @Test
    void theCodeReachesLegendaryThroughAFileThatGoesAtOnce() {
        String script = Legendary.signInScript();
        assertEquals("""
                #!/bin/sh
                code=$(cat "$1")
                rm -f "$1"
                /usr/sbin/runuser -u botmaker -- /usr/bin/env HOME=/home/botmaker /usr/local/bin/legendary auth \
                --delete >/dev/null 2>&1
                exec /usr/sbin/runuser -u botmaker -- /usr/bin/env HOME=/home/botmaker /usr/local/bin/legendary auth \
                --code "$code"
                """, script);
        assertEquals(List.of("-u", "botmaker", "--", "/usr/bin/env", "HOME=/home/botmaker", "/usr/local/bin/legendary",
                "-y", "import"), Legendary.asUser("-y", "import"));
    }

    @Test
    void eachOutcomeSaysWhatTheUserDoesNext() {
        GameCopy.Source game = new GameCopy.Source(GuestLauncher.EPIC, "abc", "Firestone", Path.of("Firestone"),
                Path.of("x.item"));
        assertTrue(GameCopy.Outcome.SIGN_IN_FIRST.said(game).startsWith("Firestone's files are in the VM. Sign in to "
                + "Epic here, then copy it again"), GameCopy.Outcome.SIGN_IN_FIRST.said(game));
        assertTrue(GameCopy.Outcome.IMPORTED.said(game).startsWith("✓ Firestone is in the VM, and Legendary has it"));
    }
}
