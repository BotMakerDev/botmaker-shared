// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A link to another app is told from a page's, so a press never opens the user's mail app. */
class WebPageHandOffTest {

    @Test
    void aMailOrPhoneLinkIsHandedOffAndAPagesIsNot() {
        assertTrue(WebPage.handedOff("mailto:hello@example.com"));
        assertTrue(WebPage.handedOff("MAILTO:hello@example.com"));
        assertTrue(WebPage.handedOff("tel:+33100000000"));
        assertTrue(WebPage.handedOff("slack://open"));
        assertFalse(WebPage.handedOff("https://nunu.ai/pricing"));
        assertFalse(WebPage.handedOff("http://localhost:8080/"));
        assertFalse(WebPage.handedOff("javascript:void(0)"));
        assertFalse(WebPage.handedOff(""));
        assertFalse(WebPage.handedOff("/case-studies"));
    }
}
