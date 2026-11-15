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
 * budget). Two measures, each less a reserve for Windows, Studio and the core and less what the instances already
 * starting will take:
 * <ul>
 *   <li><b>free physical memory</b> must hold what an instance really uses: Android touches about half the memory it
 *       is given, plus its emulator's own ({@link #uses}). A PC near 100% still runs well, as Windows pages out what
 *       sits idle;</li>
 *   <li><b>what the OS can still commit</b> (the page file included) must hold all of it ({@link #takes}): a VM that
 *       cannot commit its memory fails to start.</li>
 * </ul>
 * The reserve scales with the PC: a tenth of its memory, from 1 to 2 GB. {@link ApplyHost}'s start waits here, so
 * neither Apply nor a batch starts an instance past it. One per process ({@link #live()}): the starts it holds are this
 * process's.
 */
public final class RamBudget {

    /** The reserve's bounds; between them it is a tenth of the PC's memory. */
    public static final long RESERVE_MIN_MB = 1024;
    public static final long RESERVE_MAX_MB = 2048;
    /** What an emulator takes beside its Android's memory. */
    public static final long OVERHEAD_MB = 512;
    /** What an instance whose memory was not read is taken to be given. */
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
        private final long usesMb;
        private final long takesMb;

        private Hold(long usesMb, long takesMb) {
            this.usesMb = usesMb;
            this.takesMb = takesMb;
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
     * This PC's memory. Windows' free memory is its available memory (standby pages included) and its free swap what it
     * can still commit; Linux's free memory leaves the page cache out, so there it is {@code MemAvailable}, and what it
     * can commit that plus free swap; elsewhere nothing measures it and nothing waits.
     */
    private static final class Live {
        private static final long UNLIMITED = Long.MAX_VALUE / MB;
        private static final Pattern AVAILABLE = Pattern.compile("(?m)^MemAvailable:\\s+(\\d+) kB");

        static final RamBudget INSTANCE = new RamBudget(Live::freeMb, Live::commitMb, totalMb(), Thread::sleep);

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

        private static Optional<OperatingSystemMXBean> bean() {
            return ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os
                    ? Optional.of(os) : Optional.empty();
        }
    }

    private final LongSupplier freeMb;
    private final LongSupplier commitMb;
    private final long totalMb;
    private final long reserveMb;
    private final Sleeper sleeper;
    private final List<Hold> starting = new ArrayList<>();

    RamBudget(LongSupplier freeMb, LongSupplier commitMb, long totalMb, Sleeper sleeper) {
        this.freeMb = freeMb;
        this.commitMb = commitMb;
        this.totalMb = totalMb;
        this.reserveMb = Math.clamp(totalMb / 10, RESERVE_MIN_MB, RESERVE_MAX_MB);
        this.sleeper = sleeper;
    }

    /** This PC's. */
    public static RamBudget live() {
        return Live.INSTANCE;
    }

    /** What an instance of {@code configuredMb} commits; 0 or less is a memory not read. */
    public static long takes(int configuredMb) {
        return (configuredMb > 0 ? configuredMb : UNREAD_MB) + OVERHEAD_MB;
    }

    /** What an instance of {@code configuredMb} is expected to keep in physical memory once up. */
    public static long uses(int configuredMb) {
        return (configuredMb > 0 ? configuredMb : UNREAD_MB) / 2 + OVERHEAD_MB;
    }

    /** What this PC keeps for Windows, Studio and the core. */
    long reserveMb() {
        return reserveMb;
    }

    /**
     * What an instance of {@code configuredMb} is counted as committing on this PC: {@link #takes}, except that one
     * whose memory was not read is never counted as more than the PC can give instances, so it is neither refused nor
     * kept waiting for good on a small PC.
     */
    long need(int configuredMb) {
        return configuredMb > 0 ? takes(configuredMb)
                : Math.min(takes(0), Math.max(OVERHEAD_MB, totalMb - reserveMb));
    }

    /** {@link #uses}, bounded as {@link #need} is. */
    long resident(int configuredMb) {
        return Math.min(uses(configuredMb), need(configuredMb));
    }

    /** How many more instances of {@code configuredMb} fit now. */
    public synchronized int room(int configuredMb) {
        long free = freeMb.getAsLong() - reserveMb - starting.stream().mapToLong(hold -> hold.usesMb).sum();
        long commit = commitMb.getAsLong() - reserveMb - starting.stream().mapToLong(hold -> hold.takesMb).sum();
        if (free <= 0 || commit <= 0) return 0;
        return (int) Math.min(Integer.MAX_VALUE,
                Math.min(free / resident(configuredMb), commit / need(configuredMb)));
    }

    /** Why an instance of {@code configuredMb} never fits on this PC, or null when it may. */
    public String never(int configuredMb) {
        long takes = need(configuredMb);
        if (takes <= totalMb - reserveMb) return null;
        return "An instance needing " + gb(takes) + " never fits: this PC has " + gb(totalMb) + ", and BotMaker keeps "
                + gb(reserveMb) + " for Windows and itself. Give it less memory, such as the light profile's 2 GB.";
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
                    Hold hold = new Hold(resident(configuredMb), need(configuredMb));
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

    /**
     * What a wait says, by the measure that holds it: "Waiting for memory: 1.1 GB free, 1.5 GB needed beside the 1.4 GB
     * kept for Windows and BotMaker, 1 instance starting.", or "… 2 GB left to commit, 2.5 GB needed …".
     */
    synchronized String waiting(int configuredMb) {
        long startingUses = starting.stream().mapToLong(hold -> hold.usesMb).sum();
        long free = freeMb.getAsLong();
        boolean physical = free - reserveMb - startingUses < resident(configuredMb);
        int n = starting.size();
        return "Waiting for memory: "
                + (physical ? gb(Math.max(0, free)) + " free, " + gb(resident(configuredMb))
                        : gb(Math.max(0, commitMb.getAsLong())) + " left to commit, " + gb(need(configuredMb)))
                + " needed beside the " + gb(reserveMb) + " kept for Windows and BotMaker"
                + (n == 0 ? "" : ", " + n + (n == 1 ? " instance" : " instances") + " starting") + ".";
    }

    /** 1.1 GB, or 5 GB when whole. */
    static String gb(long mb) {
        double gb = Math.round(mb / 102.4) / 10.0;
        return (gb == Math.rint(gb) ? String.valueOf((long) gb) : String.format(Locale.ROOT, "%.1f", gb)) + " GB";
    }
}
