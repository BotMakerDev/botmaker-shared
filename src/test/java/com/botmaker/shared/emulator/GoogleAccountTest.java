package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the sign-in script prints, read back; no browser, no network. */
class GoogleAccountTest {

    @Test
    void theAccountIsTheEmailAndTheAasTokenTheScriptPrinted() {
        GoogleAccount.Account account = GoogleAccount.parse(List.of("DevTools listening",
                "BOTMAKER_EMAIL=bot@example.com", "BOTMAKER_AAS=aas_et/abc")).orElseThrow();
        assertEquals("bot@example.com", account.email());
        assertEquals("aas_et/abc", account.token());
    }

    @Test
    void anythingButAnAasTokenIsNoAccount() {
        assertFalse(GoogleAccount.parse(List.of("BOTMAKER_EMAIL=bot@example.com", "BOTMAKER_AAS=oauth2_4/x"))
                .isPresent());
        assertFalse(GoogleAccount.parse(List.of("BOTMAKER_AAS=aas_et/abc")).isPresent(), "no email");
        assertFalse(GoogleAccount.parse(List.of()).isPresent());
    }

    @Test
    void aFailureIsTheScriptsWordsAndNeverTheToken() {
        assertEquals("Browser OAuth capture timed out.", GoogleAccount.error(List.of("noise",
                "BOTMAKER_ERROR=Browser OAuth capture timed out.")));
        assertEquals("ModuleNotFoundError: No module named 'goopdl'", GoogleAccount.error(List.of(
                "Traceback (most recent call last):", "ModuleNotFoundError: No module named 'goopdl'")));
        assertFalse(GoogleAccount.error(List.of("BOTMAKER_AAS=aas_et/secret")).contains("secret"));
    }

    @Test
    void anAccountPrintsWithoutItsToken() {
        String said = new GoogleAccount.Account("bot@example.com", "aas_et/secret").toString();
        assertTrue(said.contains("bot@example.com"));
        assertFalse(said.contains("secret"));
    }

    @Test
    void theScriptRunsIsolatedInGoopdlsVenv() {
        List<String> command = GoogleAccount.command(Path.of("venv", "python"));
        assertEquals(List.of(Path.of("venv", "python").toString(), "-I", "-X", "utf8", "-c"),
                command.subList(0, 5));
        assertTrue(command.get(5).contains("capture_oauth_credentials"));
    }
}
