// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import java.time.Duration;
import java.util.Optional;

/**
 * <b>How much a discovery may do</b> (spec §3): {@link #LOADS} page loads and {@link #TIME} from its start, whichever
 * ends first.
 */
public final class Budget {

    public static final int LOADS = 200;
    public static final Duration TIME = Duration.ofMinutes(30);

    /** Which limit ended a budget. */
    public enum Limit {
        LOADS("loads", "Page loads"),
        TIME("time", "Time"),
        UNKNOWN("unknown", "Unknown");

        private final String id;
        private final String displayName;

        Limit(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        /** The limit of {@code id}; {@link #UNKNOWN} for one this version does not know. */
        public static Limit fromId(String id) {
            for (Limit limit : values()) {
                if (limit.id.equals(id)) {
                    return limit;
                }
            }
            return UNKNOWN;
        }
    }

    private final int loads;
    private final Duration time;
    private final Pace.Clock clock;
    private final long start;
    private int spent;

    public Budget() {
        this(LOADS, TIME, Pace.Clock.SYSTEM);
    }

    public Budget(int loads, Duration time, Pace.Clock clock) {
        this.loads = loads;
        this.time = time;
        this.clock = clock;
        this.start = clock.nanos();
    }

    /** Spends one load; {@code false}, spending nothing, when the budget is out. */
    public synchronized boolean load() {
        if (out().isPresent()) {
            return false;
        }
        spent++;
        return true;
    }

    public synchronized int spent() {
        return spent;
    }

    /** The limit reached, if one is. */
    public synchronized Optional<Limit> out() {
        if (spent >= loads) {
            return Optional.of(Limit.LOADS);
        }
        if (clock.nanos() - start >= time.toNanos()) {
            return Optional.of(Limit.TIME);
        }
        return Optional.empty();
    }

    /** What a limit means here: {@code 200 page loads}, {@code 30 min}. */
    public String line(Limit limit) {
        return switch (limit) {
            case LOADS -> loads + " page loads";
            case TIME -> time.toMinutes() + " min";
            case UNKNOWN -> limit.displayName();
        };
    }
}
