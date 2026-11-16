// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator.google;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignInPaceTest {

    @TempDir
    Path dir;

    private final TestScreens.Clock clock = new TestScreens.Clock();

    private SignInPace pace() {
        return new SignInPace(dir.resolve("pace.properties"), clock, new Random(7), ZoneOffset.UTC);
    }

    @Test
    void theFirstGoesAtOnceAndTheNextAGapOfFiveToTenMinutesAfterItsEnd() throws Exception {
        SignInPace pace = pace();
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        assertNull(pace.await(ctx));
        assertTrue(ctx.steps.isEmpty(), ctx.steps.toString());
        clock.now += Duration.ofMinutes(2).toMillis(); // the sign-in itself
        pace.done();
        long ended = clock.now;
        assertNull(pace.await(ctx));
        long gap = clock.now - ended;
        assertTrue(gap >= SignInPace.GAP_MIN.toMillis() && gap <= SignInPace.GAP_MAX.toMillis(), gap + " ms");
        assertTrue(ctx.steps.getFirst().startsWith("Waiting "), ctx.steps.toString());
    }

    @Test
    void theDaysCapStopsTheNinthAndSaysWhenItCanGoOn() throws Exception {
        SignInPace pace = pace();
        long first = clock.now;
        for (int i = 0; i < SignInPace.CAP; i++) {
            assertNull(pace.await(new TestScreens.Ctx()));
            pace.done();
        }
        assertTrue(pace.capped().isPresent());
        String refused = pace.await(new TestScreens.Ctx());
        assertTrue(refused.contains("the next can go on Jan 2 at 00:00"), refused);
        clock.now = first + SignInPace.DAY.toMillis() + 1;
        assertFalse(pace.capped().isPresent());
        assertNull(pace.await(new TestScreens.Ctx()));
    }

    @Test
    void aRestartKeepsTheCountAndTheGap() throws Exception {
        SignInPace before = pace();
        for (int i = 0; i < SignInPace.CAP - 1; i++) {
            assertNull(before.await(new TestScreens.Ctx()));
            before.done();
        }
        long ended = clock.now;
        SignInPace after = pace();
        assertNull(after.await(new TestScreens.Ctx()));
        assertTrue(clock.now - ended >= SignInPace.GAP_MIN.toMillis(), "the gap survives the restart");
        after.done();
        assertTrue(pace().capped().isPresent(), "and so does the count");
    }

    @Test
    void noneStartsBesideOneStillRunningAndADeadOneHoldsTheNextBack() throws Exception {
        assertNull(pace().await(new TestScreens.Ctx()));
        long started = clock.now;
        assertNull(pace().await(new TestScreens.Ctx()));
        assertTrue(clock.now - started >= SignInPace.RUNNING.plus(SignInPace.GAP_MAX).toMillis(),
                "longer than a sign-in waiting on the user's second step");
    }

    @Test
    void aCancelledWaitEndsAtOnceAndCountsNothing() throws Exception {
        SignInPace pace = pace();
        assertNull(pace.await(new TestScreens.Ctx()));
        pace.done();
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        ctx.cancel = true;
        assertTrue(pace.await(ctx).startsWith("Cancelled"));
        for (int i = 1; i < SignInPace.CAP; i++) {
            assertNull(pace.await(new TestScreens.Ctx()));
            pace.done();
        }
        assertTrue(pace.capped().isPresent(), "the cancelled one was not counted: 8 made, the cap");
    }
}
