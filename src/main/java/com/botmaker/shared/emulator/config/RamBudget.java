package com.botmaker.shared.emulator.config;

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
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether one more instance fits in this PC's memory (spec {@code 2026-11-14-end-to-end-instances-design.md} § The RAM
 * budget): its free physical memory, less a reserve for Windows, Studio and the core, less what the instances already
 * starting will take, must leave room for the next one's configured memory plus its emulator's own. {@link ApplyHost}'s
 * start waits here, so neither Apply nor a batch starts an instance past it. One per process ({@link #live()}): the
 * starts it holds are this process's.
 */
public final class RamBudget {

    /** Kept for Windows, Studio and the core. */
    public static final long RESERVE_MB = 5 * 1024;
    /** What an emulator takes beside its Android's memory. */
    public static final long OVERHEAD_MB = 512;
    /** What an instance whose memory was not read is taken to need. */
    public static final int UNREAD_MB = 4096;

    static final Duration POLL = Duration.ofSeconds(5);
    private static final long MB = 1024 * 1024;

    /** Waits between two looks; tests give their own. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** An instance's room, held from when it may start until it is up; closing gives it back. */
    public final class Hold implements AutoCloseable {
        private final long mb;

        private Hold(long mb) {
            this.mb = mb;
        }

        @Override
        public void close() {
            synchronized (RamBudget.this) {
                starting.remove(this);
            }
        }
    }

    /** An instance this PC can never give its memory to, however long it waits; the message is the user's words. */
    public static final class NoRoom extends Exception {
        NoRoom(String why) {
            super(why);
        }
    }

    /**
     * This PC's memory. Windows' free memory is its available memory (standby pages included); Linux's free memory
     * leaves the page cache out, so there it is {@code MemAvailable}; elsewhere nothing measures it and nothing waits.
     */
    private static final class Live {
        private static final long UNLIMITED = Long.MAX_VALUE / MB;
        private static final Pattern AVAILABLE = Pattern.compile("(?m)^MemAvailable:\\s+(\\d+) kB");

        static final RamBudget INSTANCE = new RamBudget(Live::freeMb, totalMb(), Thread::sleep);

        private static long freeMb() {
            if (Os.current().isWindows()) return bean().map(os -> os.getFreeMemorySize() / MB).orElse(UNLIMITED);
            if (Os.current().isLinux()) {
                try {
                    Matcher m = AVAILABLE.matcher(Files.readString(Path.of("/proc/meminfo")));
                    if (m.find()) return Long.parseLong(m.group(1)) / 1024;
                } catch (Exception e) {
                    return UNLIMITED;
                }
            }
            return UNLIMITED;
        }

        private static long totalMb() {
            return bean().map(os -> os.getTotalMemorySize() / MB).orElse(UNLIMITED);
        }

        private static Optional<OperatingSystemMXBean> bean() {
            return ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os
                    ? Optional.of(os) : Optional.empty();
        }
    }

    private final LongSupplier freeMb;
    private final long totalMb;
    private final Sleeper sleeper;
    private final List<Hold> starting = new ArrayList<>();

    RamBudget(LongSupplier freeMb, long totalMb, Sleeper sleeper) {
        this.freeMb = freeMb;
        this.totalMb = totalMb;
        this.sleeper = sleeper;
    }

    /** This PC's. */
    public static RamBudget live() {
        return Live.INSTANCE;
    }

    /** What an instance of {@code configuredMb} takes; 0 or less is a memory not read. */
    public static long takes(int configuredMb) {
        return (configuredMb > 0 ? configuredMb : UNREAD_MB) + OVERHEAD_MB;
    }

    /**
     * What an instance of {@code configuredMb} is counted as on this PC: {@link #takes}, except that one whose memory was
     * not read is never counted as more than the PC can give instances, so it is neither refused nor kept waiting for
     * good on a small PC.
     */
    long need(int configuredMb) {
        return configuredMb > 0 ? takes(configuredMb)
                : Math.min(takes(0), Math.max(OVERHEAD_MB, totalMb - RESERVE_MB));
    }

    /** How many more instances of {@code configuredMb} fit now. */
    public synchronized int room(int configuredMb) {
        long left = freeMb.getAsLong() - RESERVE_MB - startingMb();
        return left <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, left / need(configuredMb));
    }

    /** Why an instance of {@code configuredMb} never fits on this PC, or null when it may. */
    public String never(int configuredMb) {
        long takes = need(configuredMb);
        if (takes <= totalMb - RESERVE_MB) return null;
        return "An instance needing " + gb(takes) + " never fits: this PC has " + gb(totalMb) + ", and BotMaker keeps "
                + gb(RESERVE_MB) + " for Windows and itself. Give it less memory, such as the light profile's 2 GB.";
    }

    /**
     * Waits until one more instance of {@code configuredMb} fits, saying so in {@code context}, then holds its room
     * until the hold is closed, once the instance is up.
     *
     * @return the hold, or null when the user cancelled the wait
     * @throws NoRoom when it never fits ({@link #never})
     */
    public Hold await(int configuredMb, Operation.Context context) throws InterruptedException, NoRoom {
        String never = never(configuredMb);
        if (never != null) throw new NoRoom(never);
        String said = null;
        while (true) {
            synchronized (this) {
                if (room(configuredMb) >= 1) {
                    Hold hold = new Hold(need(configuredMb));
                    starting.add(hold);
                    return hold;
                }
            }
            if (context.cancelled()) return null;
            String now = waiting(configuredMb);
            if (!now.equals(said)) {
                context.step(now);
                said = now;
            }
            sleeper.sleep(POLL);
        }
    }

    /** What a wait says, e.g. "Waiting for memory: 1.1 GB free, 2.5 GB needed beside the 5 GB kept for Windows and BotMaker, 1 instance starting." */
    synchronized String waiting(int configuredMb) {
        int n = starting.size();
        return "Waiting for memory: " + gb(Math.max(0, freeMb.getAsLong())) + " free, " + gb(need(configuredMb))
                + " needed beside the " + gb(RESERVE_MB) + " kept for Windows and BotMaker"
                + (n == 0 ? "" : ", " + n + (n == 1 ? " instance" : " instances") + " starting") + ".";
    }

    private long startingMb() {
        return starting.stream().mapToLong(hold -> hold.mb).sum();
    }

    /** 1.1 GB, or 5 GB when whole. */
    static String gb(long mb) {
        double gb = Math.round(mb / 102.4) / 10.0;
        return (gb == Math.rint(gb) ? String.valueOf((long) gb) : String.format(Locale.ROOT, "%.1f", gb)) + " GB";
    }
}
