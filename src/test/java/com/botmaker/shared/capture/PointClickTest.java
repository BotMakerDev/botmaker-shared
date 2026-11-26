// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.capture;

import org.junit.jupiter.api.Test;

import java.awt.geom.Point2D;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The first left press is the pick; its release ends it; Esc before it cancels; the deadline gives up. */
class PointClickTest {

    @Test
    void thePressAndItsReleaseAreSwallowedAndThePressIsThePick() {
        PointClick watch = new PointClick(10_000);
        assertTrue(watch.press(120, 40, 1_000));
        assertFalse(watch.over(1_100));
        assertFalse(watch.press(5, 5, 1_200), "a second press is not the pick");
        assertTrue(watch.release());
        assertFalse(watch.release());
        assertTrue(watch.over(1_300));
        assertEquals(Optional.of(new Point2D.Double(120, 40)), watch.pressedAt());
    }

    @Test
    void escBeforeThePressCancelsAndAfterItPasses() {
        PointClick cancelled = new PointClick(10_000);
        assertFalse(cancelled.release(), "a release with no press passes");
        assertTrue(cancelled.escape());
        assertTrue(cancelled.over(0));
        assertFalse(cancelled.press(1, 1, 0));
        assertEquals(Optional.empty(), cancelled.pressedAt());

        PointClick picked = new PointClick(10_000);
        picked.press(1, 1, 0);
        assertFalse(picked.escape());
        assertTrue(picked.pressedAt().isPresent());
    }

    @Test
    void aPressWhoseReleaseNeverComesStillPicksAndThePastDeadlineGivesUp() {
        PointClick watch = new PointClick(10_000);
        watch.press(7, 8, 9_000);
        assertFalse(watch.over(9_000 + PointClick.RELEASE_WAIT_MS));
        assertTrue(watch.over(9_001 + PointClick.RELEASE_WAIT_MS), "the release wait outlives the deadline");
        assertTrue(watch.pressedAt().isPresent());

        PointClick late = new PointClick(10_000);
        assertFalse(late.over(10_000));
        assertTrue(late.over(10_001));
        assertFalse(late.press(1, 1, 10_001));
        assertEquals(Optional.empty(), late.pressedAt());
    }
}
