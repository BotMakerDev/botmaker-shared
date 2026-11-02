package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.util.List;

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

    /** The assistant (Gemini) and heads-up notifications are both shut off, by commands safe to repeat. */
    @Test
    void popupsAreShutOff() {
        assertEquals(List.of(
                "pm disable-user --user 0 com.google.android.googlequicksearchbox",
                "settings put secure assist_gesture_enabled 0",
                "settings put secure assist_long_press_home_enabled 0",
                "settings put secure assist_touch_gesture_enabled 0",
                "settings delete secure assistant",
                "settings delete secure voice_interaction_service",
                "settings put global heads_up_notifications_enabled 0"), QuietRun.CALM);
    }

    /** Only the permissions not granted yet are granted, each once (Android 13's dumpsys package). */
    @Test
    void theNotGrantedPermissionsAreRead() {
        String dumpsys = """
                    install permissions:
                      android.permission.INTERNET: granted=true
                    User 0: ceDataInode=1 installed=true
                      runtime permissions:
                        android.permission.POST_NOTIFICATIONS: granted=false, flags=[ USER_SET|USER_FIXED]
                        android.permission.RECORD_AUDIO: granted=false, flags=[ ]
                        android.permission.CAMERA: granted=true, flags=[ USER_SET]
                        android.permission.RECORD_AUDIO: granted=false, flags=[ ]
                """;
        assertEquals(List.of("android.permission.POST_NOTIFICATIONS", "android.permission.RECORD_AUDIO"),
                List.copyOf(QuietRun.notGranted(dumpsys)));
        assertEquals(List.of(), List.copyOf(QuietRun.notGranted(null)));
    }
}
