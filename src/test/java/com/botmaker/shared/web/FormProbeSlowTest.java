// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import com.botmaker.shared.tools.Downloads;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fixture's sign-up form tried in the pinned Chromium: its two rules, and no request that writes. */
@Tag("slow")
class FormProbeSlowTest {

    private static FixtureSite site;
    private static Chromium chromium;

    @BeforeAll
    static void start() throws Exception {
        site = new FixtureSite();
        if (!ChromiumInstall.install(Downloads.Progress.IGNORED)) {
            throw new IllegalStateException("no Chromium for this platform, or its download failed");
        }
        chromium = Chromium.start(false, List.of());
    }

    @AfterAll
    static void stop() {
        if (chromium != null) chromium.close();
        if (site != null) site.close();
    }

    @Test
    void theSignUpFormShowsWhatEnablesCreateAccountAndWhatEmailSaysWithoutSubmitting() {
        String home = site.url("/auth/signup");
        try (WebPage page = chromium.newPage();
             Guard guard = Guard.on(page, Scope.of(URI.create(home)), robots())) {
            assertTrue(page.open(home).ok());

            List<FormProbe.Form> forms = FormProbe.forms(page);
            assertEquals(1, forms.size(), forms.toString());
            FormProbe.Form form = forms.getFirst();
            assertEquals("Create account", form.submit());
            assertEquals(List.of("Email", "Password", "Company"),
                    form.fields().stream().map(FormProbe.Field::label).toList());

            FormProbe.Result result = FormProbe.probe(guard, page, form);

            assertEquals(List.of("Create account is enabled when Email is a valid email and Password has 8 characters "
                            + "or more", "Email shows \"Enter a valid email\" when it holds no @"),
                    result.rules().stream().map(FormRule::line).toList(),
                    String.join("\n", result.tries().stream().map(FormRule.Try::line).toList()));
            assertTrue(guard.stopped().isEmpty(), "the page tried no write");
            assertEquals("", page.session().call("Runtime.evaluate", Map.of("expression",
                    "document.getElementById('email').value", "returnByValue", true)).path("result").path("value")
                    .asText(), "left empty");
        }
        assertEquals(0, site.writes(), "no request left on any try");
    }

    private static Robots robots() {
        String agent = Robots.userAgent("test");
        return new Robots(agent, Robots.http(agent));
    }
}
