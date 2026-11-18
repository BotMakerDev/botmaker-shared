// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import java.time.Duration;

/**
 * <b>How fast a discovery goes</b> (spec §3, polite): one action a second at most, and page loads at least the
 * site's {@code Crawl-delay} apart, which slows the pace and never speeds it. A load is an action too. One page loads
 * at a time because the walk is one thread: each call waits until its turn.
 */
public final class Pace {

    /** The least time between two actions. */
    public static final Duration ACTION = Duration.ofSeconds(1);

    /** Time, so a test runs its seconds at once. */
    public interface Clock {
        long nanos();

        void sleep(Duration duration) throws InterruptedException;

        Clock SYSTEM = new Clock() {
            @Override
            public long nanos() {
                return System.nanoTime();
            }

            @Override
            public void sleep(Duration duration) throws InterruptedException {
                Thread.sleep(duration);
            }
        };
    }

    private final Clock clock;
    private Duration loadGap = ACTION;
    private Long lastAction;
    private Long lastLoad;

    public Pace() {
        this(Clock.SYSTEM);
    }

    public Pace(Clock clock) {
        this.clock = clock;
    }

    /** Spaces loads at least {@code delay} apart; a shorter delay than the pace changes nothing. */
    public synchronized void slowTo(Duration delay) {
        if (delay.compareTo(loadGap) > 0) {
            loadGap = delay;
        }
    }

    /** The least time between two loads now. */
    public synchronized Duration loadGap() {
        return loadGap;
    }

    /** Waits for an action's turn: a press, a key, a scroll. */
    public synchronized void action() throws InterruptedException {
        waitFor(lastAction, ACTION);
        lastAction = clock.nanos();
    }

    /** Waits for a page load's turn. */
    public synchronized void load() throws InterruptedException {
        waitFor(lastAction, ACTION);
        waitFor(lastLoad, loadGap);
        lastAction = clock.nanos();
        lastLoad = lastAction;
    }

    private void waitFor(Long since, Duration gap) throws InterruptedException {
        if (since == null) {
            return;
        }
        long left = since + gap.toNanos() - clock.nanos();
        if (left > 0) {
            clock.sleep(Duration.ofNanos(left));
        }
    }
}
