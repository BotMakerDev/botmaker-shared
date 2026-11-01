package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuietRunTest {

    @Test
    void doNotDisturbIsPutBackAsItWas() {
        assertEquals("off", QuietRun.dnd("0"));
        assertEquals("priority", QuietRun.dnd("1"));
        assertEquals("none", QuietRun.dnd("2"));
        assertEquals("alarms", QuietRun.dnd("3"));
        assertEquals("off", QuietRun.dnd(""), "a setting never read is put back off");
    }
}
