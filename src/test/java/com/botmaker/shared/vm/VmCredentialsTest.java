package com.botmaker.shared.vm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A VM's passwords: random, never in a string a log could print, and back only for this Windows user. */
class VmCredentialsTest {

    @Test
    void randomPasswordsAreLettersAndDigitsOfTheRightLength() {
        VmCredentials a = VmCredentials.random();
        assertEquals(20, a.guest().length());
        assertEquals(8, a.vnc().length());
        assertTrue(a.guest().matches("[A-Za-z0-9]+"));
        assertNotEquals(a, VmCredentials.random());
        assertFalse(a.toString().contains(a.guest()), "toString hides them");
        assertThrows(IllegalArgumentException.class, () -> new VmCredentials("g", "123456789"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void savedPasswordsComeBackAndAreNotInTheFileAsText(@TempDir Path folder) throws Exception {
        VmCredentials saved = VmCredentials.random();
        assertEquals(Optional.empty(), VmCredentials.load(folder));
        saved.save(folder);
        String onDisk = new String(Files.readAllBytes(folder.resolve(VmCredentials.FILE)), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(onDisk.contains(saved.guest()));
        assertEquals(Optional.of(saved), VmCredentials.load(folder));
    }
}
