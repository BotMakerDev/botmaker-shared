// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.host;

import com.botmaker.shared.platform.Os;
import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether one more thing this PC starts fits in its memory: an emulator instance first, a game VM or a private display
 * when they ask. Each starter asks by its {@link Need}, what it commits and what it keeps in physical memory once up,
 * and holds that room until it is up, so two starts never count the same memory.
 * <p>
 * Only <b>what the OS can still commit</b> (the page file included), less a reserve for Windows, Studio and the core
 * and less what is already starting, holds a start back: a VM that cannot commit its memory fails to start. Low free
 * <b>physical</b> memory does not (the user's calls: "it can run well despite hitting 100% memory", 2026-11-15, as
 * Windows pages out what sits idle; commit only, 2026-11-17); the start says the PC may page, and goes on.
 * <p>
 * The reserve scales with the PC: a tenth of its memory, from 1 to 2 GB. One per process ({@link #live()}): the starts
 * it holds are this process's.
 */
public final class MemoryBudget {

    /** The reserve's bounds; between them it is a tenth of the PC's memory. */
    public static final long RESERVE_MIN_MB = 1024;
    public static final long RESERVE_MAX_MB = 2048;

    static final Duration POLL = Duration.ofSeconds(5);
    private static final long MB = 1024 * 1024;

    /**
     * What one start asks of this PC.
     *
     * @param who       what is starting, in the user's words ("Instance 3")
     * @param commitsMb what it commits, page file included: all of it must fit
     * @param usesMb    what it keeps in physical memory once up: short of it, the PC only pages
     */
    public record Need(String who, long commitsMb, long usesMb) {}

    /** Where a wait says what holds it, and whether the user cancelled it. */
    public record Waiter(Consumer<String> step, BooleanSupplier cancelled) {}

    /** Waits between two looks; tests give their own. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** A start's room, held from when it may start until it is up; closing gives it back. */
    public final class Hold implements AutoCloseable {
        private final Need need;

        private Hold(Need need) {
            this.need = need;
        }

        @Override
        public void close() {
            synchronized (MemoryBudget.this) {
                starting.remove(this);
            }
        }
    }

    /** A start this PC can never give its memory to, however long it waits; the message is the user's words. */
    public static final class NoRoom extends Exception {
        NoRoom(String why) {
            super(why);
        }
    }

    /**
     * This PC's memory. Windows' free memory is its available memory (standby pages included) and its free swap what it
     * can still commit; Linux's free memory leaves the page cache out, so there it is {@code MemAvailable}, and what it
     * can commit that plus free swap; elsewhere nothing measures it and nothing waits.
     */
    private static final class Live {
        private static final long UNLIMITED = Long.MAX_VALUE / MB;
        private static final Pattern AVAILABLE = Pattern.compile("(?m)^MemAvailable:\\s+(\\d+) kB");

        static final MemoryBudget INSTANCE =
                new MemoryBudget(Live::freeMb, Live::commitMb, totalMb(), commitLimitMb(), Thread::sleep);

        private static long freeMb() {
            if (Os.current().isWindows()) return bean().map(os -> os.getFreeMemorySize() / MB).orElse(UNLIMITED);
            if (Os.current().isLinux()) return linuxAvailableMb();
            return UNLIMITED;
        }

        private static long commitMb() {
            if (Os.current().isWindows()) return bean().map(os -> os.getFreeSwapSpaceSize() / MB).orElse(UNLIMITED);
            if (Os.current().isLinux()) {
                long available = linuxAvailableMb();
                return available == UNLIMITED ? UNLIMITED
                        : available + bean().map(os -> os.getFreeSwapSpaceSize() / MB).orElse(0L);
            }
            return UNLIMITED;
        }

        private static long linuxAvailableMb() {
            try {
                Matcher m = AVAILABLE.matcher(Files.readString(Path.of("/proc/meminfo")));
                return m.find() ? Long.parseLong(m.group(1)) / 1024 : UNLIMITED;
            } catch (Exception e) {
                return UNLIMITED;
            }
        }

        private static long totalMb() {
            return bean().map(os -> os.getTotalMemorySize() / MB).orElse(UNLIMITED);
        }

        /** Windows' total swap is its commit limit, page file included; Linux's is the swap beside the memory. */
        private static long commitLimitMb() {
            long swap = bean().map(os -> os.getTotalSwapSpaceSize() / MB).orElse(0L);
            if (Os.current().isWindows()) return Math.max(totalMb(), swap);
            long total = totalMb();
            return total == UNLIMITED ? UNLIMITED : total + swap;
        }

        private static Optional<OperatingSystemMXBean> bean() {
            return ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os
                    ? Optional.of(os) : Optional.empty();
        }
    }

    private final LongSupplier freeMb;
    private final LongSupplier commitMb;
    private final long totalMb;
    private final long commitLimitMb;
    private final long reserveMb;
    private final Sleeper sleeper;
    private final List<Hold> starting = new ArrayList<>();

    /** A PC with no page file: it can commit only its memory. */
    MemoryBudget(LongSupplier freeMb, LongSupplier commitMb, long totalMb, Sleeper sleeper) {
        this(freeMb, commitMb, totalMb, totalMb, sleeper);
    }

    MemoryBudget(LongSupplier freeMb, LongSupplier commitMb, long totalMb, long commitLimitMb, Sleeper sleeper) {
        this.freeMb = freeMb;
        this.commitMb = commitMb;
        this.totalMb = totalMb;
        this.commitLimitMb = Math.max(totalMb, commitLimitMb);
        this.reserveMb = Math.clamp(totalMb / 10, RESERVE_MIN_MB, RESERVE_MAX_MB);
        this.sleeper = sleeper;
    }

    /** This PC's. */
    public static MemoryBudget live() {
        return Live.INSTANCE;
    }

    /** What this PC keeps for Windows, Studio and the core. */
    long reserveMb() {
        return reserveMb;
    }

    /** The most one start may ever commit on this PC: all it can commit, page file included, but the reserve. */
    public long mostMb() {
        return commitLimitMb - reserveMb;
    }

    /** How many more starts of {@code need} fit now, by what the OS can still commit. */
    public synchronized int room(Need need) {
        long commit = commitLeft();
        if (commit <= 0) return 0;
        return (int) Math.min(Integer.MAX_VALUE, commit / Math.max(1, need.commitsMb()));
    }

    /** Why {@code need} never fits on this PC, or null when it may. */
    public String never(Need need) {
        if (need.commitsMb() <= mostMb()) return null;
        return need.who() + " needs " + gb(need.commitsMb()) + " and never fits: this PC can commit "
                + gb(commitLimitMb) + ", page file included, and BotMaker keeps " + gb(reserveMb)
                + " for Windows and itself.";
    }

    /**
     * Waits until {@code need} can be committed, saying so in {@code waiter}, then holds its room until the hold is
     * closed, once what started is up. Short of physical memory it does not wait: it says the PC may page.
     *
     * @return the hold, or null when the user cancelled the wait
     * @throws NoRoom when it never fits ({@link #never})
     */
    public Hold await(Need need, Waiter waiter) throws InterruptedException, NoRoom {
        String never = never(need);
        if (never != null) throw new NoRoom(never);
        String said = null;
        while (true) {
            String paging;
            Hold hold = null;
            synchronized (this) {
                if (room(need) >= 1) {
                    paging = paging(need);
                    hold = new Hold(need);
                    starting.add(hold);
                } else {
                    paging = null;
                }
            }
            if (hold != null) {
                try {
                    if (paging != null) waiter.step().accept(paging);
                } catch (RuntimeException e) {
                    hold.close();
                    throw e;
                }
                return hold;
            }
            if (waiter.cancelled().getAsBoolean()) return null;
            String now = waiting(need);
            if (!now.equals(said)) {
                waiter.step().accept(now);
                said = now;
            }
            sleeper.sleep(POLL);
        }
    }

    /**
     * What a wait says, by what blocks it: "Waiting for memory to start Instance 3: 2 GB left to commit, 2.5 GB needed,
     * beside the 1.4 GB kept for Windows and BotMaker and what Instance 2 takes as it starts."
     */
    synchronized String waiting(Need need) {
        List<String> who = startingWho();
        return "Waiting for memory to start " + need.who() + ": " + gb(Math.max(0, commitLeft()))
                + " left to commit, " + gb(need.commitsMb()) + " needed, beside the " + gb(reserveMb)
                + " kept for Windows and BotMaker"
                + (who.isEmpty() ? "" : " and what " + names(who) + (who.size() == 1 ? " takes" : " take")
                        + " as " + (who.size() == 1 ? "it starts" : "they start")) + ".";
    }

    /**
     * What a start short of physical memory says, or null when it is not: "Instance 3 starts with 1.1 GB of memory
     * free, short of the 1.5 GB it uses beside the 1.4 GB kept for Windows and BotMaker: the PC may page and slow
     * down."
     */
    synchronized String paging(Need need) {
        long free = freeMb.getAsLong();
        long startingUses = starting.stream().mapToLong(hold -> hold.need.usesMb()).sum();
        if (free - reserveMb - startingUses >= need.usesMb()) return null;
        return need.who() + " starts with " + gb(Math.max(0, free)) + " of memory free, short of the "
                + gb(need.usesMb()) + " it uses beside the " + gb(reserveMb) + " kept for Windows and BotMaker"
                + whileStarting() + ": the PC may page and slow down.";
    }

    private long commitLeft() {
        return commitMb.getAsLong() - reserveMb - starting.stream().mapToLong(hold -> hold.need.commitsMb()).sum();
    }

    private List<String> startingWho() {
        return starting.stream().map(hold -> hold.need.who()).toList();
    }

    /** ", while Instance 1 and Instance 2 start", or nothing when nothing is starting. */
    private String whileStarting() {
        List<String> who = startingWho();
        if (who.isEmpty()) return "";
        return ", while " + names(who) + (who.size() == 1 ? " starts" : " start");
    }

    /** "A", "A and B", "A, B and C". */
    private static String names(List<String> who) {
        return who.size() == 1 ? who.getFirst()
                : String.join(", ", who.subList(0, who.size() - 1)) + " and " + who.getLast();
    }

    /** 1.1 GB, or 5 GB when whole. */
    public static String gb(long mb) {
        double gb = Math.round(mb / 102.4) / 10.0;
        return (gb == Math.rint(gb) ? String.valueOf((long) gb) : String.format(Locale.ROOT, "%.1f", gb)) + " GB";
    }
}
