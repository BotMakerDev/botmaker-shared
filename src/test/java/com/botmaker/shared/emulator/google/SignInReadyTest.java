package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.PlatformId;
import com.botmaker.shared.emulator.config.InstanceKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignInReadyTest {

    private static final InstanceKey KEY = new InstanceKey(PlatformId.LDPLAYER, "LDPlayer-1");
    private static final String ANDROID_ID = "a1b2c3d4e5f60718";

    @TempDir
    Path dir;

    private final TestScreens.Clock clock = new TestScreens.Clock();

    /** An instance booted {@link #bootedAt}, with a clock {@link #clockOff} seconds from this PC's. */
    private class Phone implements SignInReady.Shell {
        final Map<String, String> props = new HashMap<>(Map.of("ro.product.model", "ASUS_Z01QD",
                "persist.sys.timezone", "Asia/Shanghai", "persist.sys.locale", "zh-CN"));
        final List<String> ran = new ArrayList<>();
        String androidId = ANDROID_ID;
        String gsf = "Row: 0 value=3987654321012345678";
        boolean gms = true;
        long bootedAt = -10 * 60_000L;
        long clockOff;
        boolean clockSets = true;

        @Override
        public String run(String command) {
            ran.add(command);
            if (command.startsWith("getprop ")) return props.getOrDefault(command.substring(8), "");
            if (command.startsWith("setprop ")) {
                String[] parts = command.split(" ");
                props.put(parts[1], parts[2]);
                return "";
            }
            if (command.equals(SignInReady.Part.ANDROID_ID.command)) return androidId;
            if (command.equals(SignInReady.Part.GSF_ID.command)) return gsf;
            if (command.contains("dumpsys package " + SignInReady.GMS)) return gms ? "    versionName=24.26.32" : "";
            if (command.contains("dumpsys package " + SignInReady.STORE)) return "    versionName=41.2.21";
            if (command.equals("date +%s")) return Long.toString(clock.now / 1000 + clockOff);
            if (command.startsWith("date @") && clockSets) clockOff = 0;
            if (command.equals("cat /proc/uptime")) return (clock.now - bootedAt) / 1000.0 + " 100.0";
            return "";
        }
    }

    private SignInReady ready(Phone phone) {
        return new SignInReady(phone, clock, dir.resolve("identities.properties"), ZoneId.of("Europe/Paris"),
                Locale.FRANCE);
    }

    @Test
    void theFirstSignInKeepsTheIdentityAndAChangedOneIsRefusedWithoutItsValue() throws Exception {
        Phone phone = new Phone();
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        assertNull(ready(phone).ready("LDPlayer-1", KEY, ctx));
        ready(phone).keep(KEY);
        assertNull(ready(phone).ready("LDPlayer-1", KEY, new TestScreens.Ctx()), "the same device");

        phone.androidId = "ffffffffffffffff";
        String refused = ready(phone).ready("LDPlayer-1", KEY, new TestScreens.Ctx());
        assertTrue(refused.contains("Android ID changed"), refused);
        assertFalse(refused.contains("ffff") || refused.contains(ANDROID_ID), refused);

        String kept = Files.readString(dir.resolve("identities.properties"));
        assertFalse(kept.contains(ANDROID_ID) || kept.contains("3987654321012345678") || kept.contains("ASUS"), kept);
        for (String step : ctx.steps) assertFalse(step.contains(ANDROID_ID), step);
    }

    @Test
    void aPartThatDoesNotAnswerNowIsNotTakenForAChange() throws Exception {
        Phone phone = new Phone();
        ready(phone).keep(KEY);
        phone.gsf = "Error while accessing provider:com.google.android.gsf.gservices";
        assertNull(ready(phone).ready("LDPlayer-1", KEY, new TestScreens.Ctx()));
    }

    @Test
    void aRenameKeepsTheIdentityAndAForgottenOneSignsInAsNew() throws Exception {
        Phone phone = new Phone();
        ready(phone).keep(KEY);
        InstanceKey renamed = new InstanceKey(PlatformId.LDPLAYER, "Farm");
        assertNull(SignInReady.moved(dir.resolve("identities.properties"), KEY, renamed));
        phone.androidId = "ffffffffffffffff";
        assertTrue(ready(phone).ready("Farm", renamed, new TestScreens.Ctx()).contains("Android ID changed"),
                "the identity moved with the name");
        assertNull(ready(phone).ready("LDPlayer-1", KEY, new TestScreens.Ctx()), "nothing left under the old name");

        assertNull(SignInReady.moved(dir.resolve("identities.properties"), renamed, null));
        assertNull(ready(phone).ready("Farm", renamed, new TestScreens.Ctx()), "made again: a first sign-in");
    }

    @Test
    void theNetworksTimeIsWaitedForBeforeTheClockIsSet() throws Exception {
        Phone phone = new Phone() {
            int reads;

            @Override
            public String run(String command) {
                if (command.equals("date +%s") && ++reads == 3) clockOff = 0;
                return super.run(command);
            }
        };
        phone.clockOff = 3600;
        assertNull(ready(phone).ready("LDPlayer-1", KEY, new TestScreens.Ctx()));
        assertFalse(phone.ran.stream().anyMatch(c -> c.startsWith("date @")), phone.ran.toString());
    }

    @Test
    void anotherInstancesIdentityIsItsOwn() throws Exception {
        Phone phone = new Phone();
        ready(phone).keep(KEY);
        phone.androidId = "0011223344556677";
        assertNull(ready(phone).ready("LDPlayer-2", new InstanceKey(PlatformId.LDPLAYER, "LDPlayer-2"),
                new TestScreens.Ctx()));
    }

    @Test
    void theClockTimeZoneAndRegionAreThisPcsAndPlayIsChecked() throws Exception {
        Phone phone = new Phone();
        phone.clockOff = 3600;
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        assertNull(ready(phone).ready("LDPlayer-1", KEY, ctx));
        assertTrue(phone.ran.contains("settings put global auto_time 1"));
        assertEquals(0, phone.clockOff, "set from this PC's clock");
        assertEquals("Europe/Paris", phone.props.get("persist.sys.timezone"));
        assertEquals("en-FR", phone.props.get("persist.sys.locale"), "English, which the sign-in reads");
        assertTrue(ctx.steps.stream().anyMatch(s -> s.contains("Google Play services 24.26.32")), ctx.steps.toString());
    }

    @Test
    void aClockThatCannotBeSetOrNoPlayServicesRefuses() throws Exception {
        Phone phone = new Phone();
        phone.clockOff = -600;
        phone.clockSets = false;
        String refused = ready(phone).ready("LDPlayer-1", KEY, new TestScreens.Ctx());
        assertTrue(refused.contains("10 min behind"), refused);

        Phone bare = new Phone();
        bare.gms = false;
        assertTrue(ready(bare).ready("LDPlayer-1", KEY, new TestScreens.Ctx()).contains("no Google Play services"));
    }

    @Test
    void itWaitsThreeMinutesAfterBoot() throws Exception {
        Phone phone = new Phone();
        phone.bootedAt = clock.now - 60_000;
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        assertNull(ready(phone).ready("LDPlayer-1", KEY, ctx));
        assertTrue(clock.now - phone.bootedAt >= SignInReady.AFTER_BOOT.toMillis());
        assertTrue(ctx.steps.stream().anyMatch(s -> s.startsWith("Letting LDPlayer-1 settle for 2 min")),
                ctx.steps.toString());
    }
}
