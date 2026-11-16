// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.config.Operation;
import com.botmaker.shared.tools.UserDirs;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

/**
 * Google sign-ins from this PC, one at a time and paced (the user's call, 2026-11-14): a random 5–10 minutes between
 * one's end and the next one's start, and at most 8 in any 24 hours, whichever accounts they are, as Google links
 * sign-ins by the PC and its address. The count and the next start are kept in {@link UserDirs#config()}, so a restart
 * neither resets the day nor shortens a gap; another BotMaker process shares them through a lock file.
 * <p>
 * A sign-in takes its turn with {@link #await}, which counts it, and ends it with {@link #done}, which rolls the gap.
 * Until {@code done} the next start is {@link #RUNNING} plus the longest gap away: longer than a sign-in can take, so
 * none starts beside it, and a sign-in whose process died still holds the next one back that long.
 */
public final class SignInPace {

    static final Duration GAP_MIN = Duration.ofMinutes(5);
    static final Duration GAP_MAX = Duration.ofMinutes(10);
    /** Longer than a sign-in takes: its screens and the user's second step ({@link InstanceSignIn}). */
    static final Duration RUNNING = Duration.ofMinutes(30);
    static final Duration DAY = Duration.ofHours(24);
    static final int CAP = 8;
    private static final Duration POLL = Duration.ofSeconds(1);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d");

    private static volatile SignInPace live;

    private final Path file;
    private final Pace pace;
    private final RandomGenerator random;
    private final ZoneId zone;

    SignInPace(Path file, Pace pace, RandomGenerator random, ZoneId zone) {
        this.file = file;
        this.pace = pace;
        this.random = random;
        this.zone = zone;
    }

    /** This PC's pace, one per process. */
    public static SignInPace live() {
        SignInPace pace = live;
        if (pace == null) {
            synchronized (SignInPace.class) {
                if (live == null) {
                    live = new SignInPace(UserDirs.config().resolve("google-sign-in-pace.properties"), Pace.REAL,
                            RandomGenerator.getDefault(), ZoneId.systemDefault());
                }
                pace = live;
            }
        }
        return pace;
    }

    /** Why no sign-in can start today (the cap is reached, said with when the next one can), else empty. */
    public Optional<String> capped() {
        long now = pace.nowMillis();
        try {
            return Optional.ofNullable(LockedFile.with(file, () -> capWords(read(), now)));
        } catch (IOException unread) {
            return Optional.of(unkept(unread));
        }
    }

    /** What a look at the file decided: start now, wait, or refused with why. */
    private record Turn(long waitMillis, String refused) {}

    /**
     * Waits for this sign-in's turn, saying so on {@code context}, and counts it. Null when it may start now; else
     * why not: the day's cap reached, the count not kept, or cancelled while waiting.
     */
    public String await(Operation.Context context) throws InterruptedException {
        long said = -1;
        while (true) {
            if (context.cancelled()) return "Cancelled while waiting for the next Google sign-in's turn.";
            long now = pace.nowMillis();
            Turn turn;
            try {
                turn = LockedFile.with(file, () -> {
                    State state = read();
                    String capped = capWords(state, now);
                    if (capped != null) return new Turn(0, capped);
                    // a next start further off than a running sign-in can hold is a clock set back
                    long next = Math.min(state.next, now + RUNNING.toMillis() + GAP_MAX.toMillis());
                    if (now < next) return new Turn(next - now, null);
                    state.attempts = new ArrayList<>(state.inDay(now));
                    state.attempts.add(now);
                    state.next = now + RUNNING.toMillis() + GAP_MAX.toMillis();
                    write(state);
                    return new Turn(0, null);
                });
            } catch (IOException unkept) {
                return unkept(unkept);
            }
            if (turn.refused() != null) return turn.refused();
            if (turn.waitMillis() == 0) return null;
            long minutes = (turn.waitMillis() + 59_999) / 60_000;
            if (minutes != said) {
                said = minutes;
                context.step("Waiting " + minutes + " min for the next Google sign-in from this PC: one at a time, 5"
                        + " to 10 minutes apart, at most " + CAP + " a day.");
            }
            pace.sleep(Duration.ofMillis(Math.min(turn.waitMillis(), POLL.toMillis())));
        }
    }

    /** Ends the sign-in {@link #await} let start: the next one starts a random 5 to 10 minutes from now. */
    public void done() {
        long now = pace.nowMillis();
        long gap = random.nextLong(GAP_MIN.toMillis(), GAP_MAX.toMillis() + 1);
        try {
            LockedFile.with(file, () -> {
                State state = read();
                state.next = now + gap;
                write(state);
                return null;
            });
        } catch (IOException unkept) {
            // the next start stays as far off as await kept it
        }
    }

    /** Why no sign-in can start at {@code now}, or null when one can. */
    private String capWords(State state, long now) {
        List<Long> day = state.inDay(now);
        if (day.size() < CAP) return null;
        // the oldest of the day's last CAP leaves the window first
        long free = day.get(day.size() - CAP) + DAY.toMillis();
        ZonedDateTime at = Instant.ofEpochMilli(Math.max(free, state.next)).atZone(zone);
        String when = at.toLocalDate().equals(LocalDate.ofInstant(Instant.ofEpochMilli(now), zone))
                ? "at " + at.format(TIME) : "on " + at.format(DATE) + " at " + at.format(TIME);
        return "This PC has made " + CAP + " Google sign-ins in 24 hours, the most it makes in a day; the next can go "
                + when + ".";
    }

    private static String unkept(IOException why) {
        return "BotMaker could not keep the count of this PC's Google sign-ins, so it makes none: " + why.getMessage();
    }

    /** What the file holds: each counted start in the last day, and when the next may start. */
    private static final class State {
        List<Long> attempts = new ArrayList<>();
        long next;

        /** The starts within a day of {@code now}, oldest first; one in the future (a clock set back) counts too. */
        List<Long> inDay(long now) {
            return attempts.stream().filter(at -> at > now - DAY.toMillis()).sorted().toList();
        }
    }

    private State read() throws IOException {
        Properties properties = LockedFile.read(file);
        State state = new State();
        try {
            String attempts = properties.getProperty("attempts", "").strip();
            if (!attempts.isEmpty()) {
                state.attempts = new ArrayList<>(Arrays.stream(attempts.split(",")).map(String::strip)
                        .map(Long::parseLong).toList());
            }
            state.next = Long.parseLong(properties.getProperty("next", "0").strip());
        } catch (NumberFormatException unreadable) {
            throw new IOException(file.getFileName() + " does not read: " + unreadable.getMessage(), unreadable);
        }
        return state;
    }

    private void write(State state) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("attempts", state.attempts.stream().map(String::valueOf)
                .collect(Collectors.joining(",")));
        properties.setProperty("next", Long.toString(state.next));
        LockedFile.write(file, properties, "Google sign-ins from this PC: each start in the last day, and the next start");
    }
}
