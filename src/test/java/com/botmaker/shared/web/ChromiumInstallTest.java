// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import com.botmaker.shared.tools.Downloads;
import com.botmaker.shared.tools.UserDirs;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Playwright's Chromium is installed into BotMaker's cache, never Playwright's default folder. */
class ChromiumInstallTest {

    @Test
    void theBrowsersFolderIsUnderTheCache() {
        assertTrue(ChromiumInstall.directory().startsWith(UserDirs.cache()), ChromiumInstall.directory().toString());
        assertEquals(ChromiumInstall.directory().toString(),
                ChromiumInstall.environment().get(ChromiumInstall.BROWSERS_PATH));
    }

    /** Slow: the first run downloads Playwright's Chromium (about 300 MB) into the cache. */
    @Test
    @Tag("slow")
    void installsPlaywrightsChromiumUnderTheCache() {
        assertTrue(ChromiumInstall.install(Downloads.Progress.IGNORED));
        assertTrue(ChromiumInstall.installed());
    }
}
