// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import com.botmaker.shared.tools.Downloads;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pins are well-formed, over TLS, at the pinned version; the platform is read from the OS and processor. */
class ChromiumInstallTest {

    @Test
    void everyPinIsSecureWellFormedAndAtTheVersion() {
        for (ChromiumInstall.Platform platform : ChromiumInstall.Platform.values()) {
            Downloads.Remote remote = platform.remote();
            assertTrue(remote.wellFormed(), platform.name());
            assertTrue(remote.secure(), platform.name());
            assertEquals(Downloads.Digest.SHA_256, remote.digest());
            assertTrue(remote.url().contains("/" + ChromiumInstall.VERSION + "/" + platform.id() + "/"), remote.url());
            assertTrue(remote.size() > 100_000_000L, platform.name());
        }
    }

    @Test
    void thePlatformComesFromTheOsAndProcessor() {
        assertEquals(Optional.of(ChromiumInstall.Platform.WIN64),
                ChromiumInstall.Platform.of("Windows 11", "amd64"));
        assertEquals(Optional.of(ChromiumInstall.Platform.LINUX64), ChromiumInstall.Platform.of("Linux", "amd64"));
        assertEquals(Optional.of(ChromiumInstall.Platform.MAC_ARM64),
                ChromiumInstall.Platform.of("Mac OS X", "aarch64"));
        assertEquals(Optional.of(ChromiumInstall.Platform.MAC_X64), ChromiumInstall.Platform.of("Mac OS X", "x86_64"));
        assertEquals(Optional.empty(), ChromiumInstall.Platform.of("Linux", "aarch64"));
        assertEquals(Optional.empty(), ChromiumInstall.Platform.of("Windows 11", "aarch64"));
    }

    @Test
    void eachPlatformsBinaryIsInsideItsOwnRoot() {
        assertEquals("chrome-win64/chrome.exe", ChromiumInstall.Platform.WIN64.binary().toString().replace('\\', '/'));
        assertEquals("chrome-linux64/chrome", ChromiumInstall.Platform.LINUX64.binary().toString());
        assertTrue(ChromiumInstall.Platform.MAC_ARM64.binary().toString()
                .endsWith("Contents/MacOS/Google Chrome for Testing"));
    }
}
