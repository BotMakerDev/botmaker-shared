// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

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

    /**
     * A booted instance with a clock {@link #clockOff} seconds from this PC's, Play services running and its CPU
     * {@link #busy} busy until {@link #busyUntil}, then 5 %.
     */
    private class Phone implements SignInReady.Shell {
        final Map<String, String> props = new HashMap<>(Map.of("ro.product.model", "ASUS_Z01QD",
                "persist.sys.timezone", "Asia/Shanghai", "persist.sys.locale", "zh-CN", "sys.boot_completed", "1"));
        final List<String> ran = new ArrayList<>();
        String androidId = ANDROID_ID;
        String gsf = "Row: 0 value=3987654321012345678";
        boolean gms = true;
        boolean playUp = true;
        double busy = 0.05;
        long busyUntil = Long.MIN_VALUE;
        long clockOff;
        boolean clockSets = true;
        /** Core 0's share while {@link #busy} holds, when set; the other three cores idle then. */
        Double coreZero;
        boolean answers = true;
        private long statAt = Long.MIN_VALUE;
        private final long[] total = new long[4];
        private final long[] idle = new long[4];

        /** {@code /proc/stat}'s CPU lines for four cores, their counters grown by the time since the last reading. */
        private String stat() {
            if (statAt != Long.MIN_VALUE) {
                long ticks = (clock.now - statAt) / 10;
                boolean busyNow = clock.now <= busyUntil;
                for (int core = 0; core < 4; core++) {
                    double share = !busyNow ? 0.05 : coreZero == null ? busy : core == 0 ? coreZero : 0.0;
                    total[core] += ticks;
                    idle[core] += Math.round(ticks * (1 - share));
                }
            }
            statAt = clock.now;
            long allTotal = 0;
            long allIdle = 0;
            StringBuilder cores = new StringBuilder();
            for (int core = 0; core < 4; core++) {
                allTotal += total[core];
                allIdle += idle[core];
                cores.append("\ncpu").append(core).append(' ').append(total[core] - idle[core]).append(" 0 0 ")
                        .append(idle[core]).append(" 0 0 0 0 0 0");
            }
            return "cpu  " + (allTotal - allIdle) + " 0 0 " + allIdle + " 0 0 0 0 0 0" + cores;
        }

        @Override
        public String run(String command) {
            ran.add(command);
            if (!answers && (command.equals(SignInReady.BOOTED) || command.equals(SignInReady.PLAY_UP)
                    || command.equals(SignInReady.CPU))) return "";
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
            if (command.equals(SignInReady.PLAY_UP)) return playUp ? "1234 1201" : "";
            if (command.equals(SignInReady.CPU)) return stat();
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
    void aBusyThenQuietCpuIsWaitedForThenItSignsIn() throws Exception {
        Phone phone = new Phone();
        phone.busy = 0.9;
        phone.busyUntil = clock.now + 60_000;
        long start = clock.now;
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        assertNull(ready(phone).ready("LDPlayer-1", KEY, ctx));
        long waited = clock.now - start;
        assertTrue(waited >= 60_000 + SignInReady.QUIET.toMillis(), "busy a minute, then quiet 20 s: " + waited);
        assertTrue(waited < SignInReady.SETTLE_CAP.toMillis(), "settled before the cap: " + waited);
        assertTrue(ctx.steps.contains("Letting LDPlayer-1 settle before it signs in: its CPU is busy…"),
                ctx.steps.toString());
        assertTrue(ctx.steps.contains("LDPlayer-1 has settled."), ctx.steps.toString());
    }

    @Test
    void aCpuThatNeverSettlesEndsAtTheCapSaidAndSignsIn() throws Exception {
        Phone phone = new Phone();
        phone.busy = 0.9;
        phone.busyUntil = Long.MAX_VALUE;
        long start = clock.now;
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        assertNull(ready(phone).ready("LDPlayer-1", KEY, ctx));
        assertTrue(clock.now - start >= SignInReady.SETTLE_CAP.toMillis());
        assertTrue(ctx.steps.contains("LDPlayer-1 did not settle within 5 min (its CPU is busy); it signs in all the"
                + " same."), ctx.steps.toString());
    }

    @Test
    void playServicesNotUpYetIsWaitedForEvenWithAQuietCpu() throws Exception {
        Phone phone = new Phone();
        phone.playUp = false;
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        assertNull(ready(phone).ready("LDPlayer-1", KEY, ctx));
        assertTrue(ctx.steps.contains("Letting LDPlayer-1 settle before it signs in: Google Play services are not up"
                + " yet…"), ctx.steps.toString());
        assertTrue(ctx.steps.stream().anyMatch(s -> s.startsWith("LDPlayer-1 did not settle within 5 min (Google Play")),
                ctx.steps.toString());
        assertFalse(ctx.steps.contains("LDPlayer-1 has settled."));
    }

    @Test
    void oneSaturatedCoreIsBusyThoughTheWholeIsQuiet() throws Exception {
        Phone phone = new Phone();
        phone.coreZero = 0.95;
        phone.busyUntil = clock.now + 60_000;
        long start = clock.now;
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        assertNull(ready(phone).ready("LDPlayer-1", KEY, ctx));
        assertTrue(clock.now - start >= 60_000 + SignInReady.QUIET.toMillis(), "a quarter of the whole, one core full");
        assertTrue(ctx.steps.contains("Letting LDPlayer-1 settle before it signs in: its CPU is busy…"),
                ctx.steps.toString());
    }

    @Test
    void anAndroidThatAnswersNothingIsNotWaitedFor() throws Exception {
        Phone phone = new Phone();
        phone.answers = false;
        long start = clock.now;
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        assertNull(ready(phone).ready("LDPlayer-1", KEY, ctx));
        assertTrue(clock.now - start < SignInReady.QUIET.toMillis(), "not waited: " + (clock.now - start));
        assertTrue(ctx.steps.contains("LDPlayer-1's Android does not say whether it has settled; it signs in now."),
                ctx.steps.toString());
    }

    @Test
    void theCpusShareIsItsTimeNotIdle() {
        assertEquals(0.25, SignInReady.busy(new long[] {100, 50}, new long[] {200, 125}), 1e-9);
        assertEquals(1.0, SignInReady.busy(new long[] {100, 50}, new long[] {100, 50}), "no time passed: not quiet");
    }
}
