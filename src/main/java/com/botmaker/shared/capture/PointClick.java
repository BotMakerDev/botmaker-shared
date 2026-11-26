// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.capture;

import java.awt.geom.Point2D;
import java.util.Optional;

/**
 * What a pointer's event hook decides, event by event, while the user points: the first left press is the pick and
 * is swallowed, its release is swallowed too (so neither reaches the window under it), and Esc before the press
 * cancels. Everything else passes. Single-threaded: a hook calls it on the thread that pumps its events.
 */
public final class PointClick {

    /** How long a press waits for its release before the pick ends without it. */
    static final long RELEASE_WAIT_MS = 1_000;

    private final long deadline;
    private Point2D pressedAt;
    private long releaseBy = Long.MAX_VALUE;
    private boolean released;
    private boolean cancelled;

    /** @param deadline the instant, in {@link System#currentTimeMillis()}, past which no press counts */
    public PointClick(long deadline) {
        this.deadline = deadline;
    }

    /** A left press at {@code (x, y)}; true when it is the pick, which the hook swallows. */
    public boolean press(double x, double y, long now) {
        if (pressedAt != null || cancelled || now > deadline) return false;
        pressedAt = new Point2D.Double(x, y);
        releaseBy = now + RELEASE_WAIT_MS;
        return true;
    }

    /** A left release; true when it ends the pick's press, which the hook swallows. */
    public boolean release() {
        if (pressedAt == null || released) return false;
        released = true;
        return true;
    }

    /** Esc pressed; true when it cancels, which the hook swallows. */
    public boolean escape() {
        if (pressedAt != null || cancelled) return false;
        cancelled = true;
        return true;
    }

    /** Whether the hook may stop: cancelled, released, its release waited for long enough, or past the deadline. */
    public boolean over(long now) {
        return cancelled || released || (pressedAt != null ? now > releaseBy : now > deadline);
    }

    /** Where the pick was pressed; empty when cancelled or nothing was pressed in time. */
    public Optional<Point2D> pressedAt() {
        return cancelled ? Optional.empty() : Optional.ofNullable(pressedAt);
    }
}
