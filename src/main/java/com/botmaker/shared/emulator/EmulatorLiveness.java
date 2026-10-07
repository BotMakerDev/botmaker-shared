package com.botmaker.shared.emulator;

import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

/**
 * <b>Is this instance up, and if its port answers while it isn't, who has the port?</b> The answer behind a
 * picker's dot, a start button, and {@link EmulatorReadiness#isReady}.
 *
 * <p>The product's own word ({@link EmulatorInstance#state()}) comes first and the ADB port second, because on
 * Windows the port is shared: BlueStacks, LDPlayer's first instance and GameLoop all ask for
 * {@code 127.0.0.1:5555}. Trusting the port made every one of them look running when one was, and a launch
 * aimed at a stopped LDPlayer then drove the BlueStacks that held its port. A product that says nothing
 * (Waydroid, a phone, a BlueStacks edition with several instances and a player up) is judged by its port, as
 * before, unless another product says Android is up on that port.
 *
 * <p>Two products that both say they are up on one address are both refused: the port answers for only one of
 * them, and nothing here can tell which, so driving either could drive the other.
 *
 * @param state     {@link EmulatorState#RUNNING}, {@link EmulatorState#STARTING} or {@link EmulatorState#STOPPED};
 *                  never {@link EmulatorState#UNKNOWN}
 * @param clash     why the port can't be trusted for this instance — "port 5555 is in use by BlueStacks: Pie64"
 *                  — or {@code null}
 * @param adbClosed the product says Android is up and nothing answers on its port: its ADB is turned off
 *                  ({@link PlatformId#adbSetting()}), or it is a moment from binding it
 */
public record EmulatorLiveness(EmulatorState state, String clash, boolean adbClosed) {

    /** How old a discovery may be for a picker row, which shows the state to a user who just changed it. */
    private static final Duration FOR_DISPLAY = Duration.ofSeconds(2);
    /** How old a discovery may be for a yes/no a bot or a launch asks over and over. */
    private static final Duration FOR_POLLING = Duration.ofSeconds(10);

    /** Whether the instance is up and its port is its own. */
    public boolean running() {
        return state == EmulatorState.RUNNING;
    }

    /** What a picker row says: "running", "starting…", "stopped", "ADB off" or "port clash". */
    public String label() {
        if (adbClosed) return "ADB off";
        if (clash != null && state != EmulatorState.STOPPED) return "port clash";
        return state.displayName();
    }

    /**
     * The sentence under a row when something stands between the user and the instance — who holds its port, or
     * where its ADB is turned on — else {@code null}.
     */
    public String problem(EmulatorInstance instance) {
        if (clash != null) return clash;
        if (!adbClosed) return null;
        String setting = instance.platformId().adbSetting();
        return "Android is up but nothing answers on " + where(instance)
                + (setting == null ? "" : " — turn on ADB in " + instance.brand() + ": " + setting);
    }

    /**
     * The instance's liveness now: its product's current state (a discovery at most a couple of seconds old, see
     * {@link Platforms#recent(Duration)}) and a probe of its port. Never throws; blocks on I/O, so call it off the
     * FX thread.
     */
    public static EmulatorLiveness check(EmulatorInstance instance) {
        if (instance == null) return new EmulatorLiveness(EmulatorState.STOPPED, null, false);
        return check(instance, Platforms.recent(FOR_DISPLAY));
    }

    /** {@link #check(EmulatorInstance)} against a discovery the caller already has. */
    public static EmulatorLiveness check(EmulatorInstance instance, List<EmulatorInstance> now) {
        EmulatorInstance current = current(instance, now);
        return of(current, now, EmulatorReadiness.portOpen(current));
    }

    /**
     * Whether the instance can be driven now — the yes/no a bot's {@code running()} and a launch ask, often in a
     * loop. A closed port answers it without a discovery; an open one is checked against a discovery up to ten
     * seconds old, so polling never runs every product's console tool on each call.
     */
    public static boolean running(EmulatorInstance instance) {
        return running(instance, EmulatorReadiness::portOpen);
    }

    static boolean running(EmulatorInstance instance, Predicate<EmulatorInstance> portOpen) {
        if (instance == null || !portOpen.test(instance)) return false;
        List<EmulatorInstance> now = Platforms.recent(FOR_POLLING);
        return of(current(instance, now), now, true).running();
    }

    /**
     * {@code instance} as {@code now} describes it: the same instance of the same install (two installs of one
     * product can share an address, so the start command tells them apart), else the same address and product,
     * else {@code instance} itself (an address the user typed, a product that has since gone).
     */
    static EmulatorInstance current(EmulatorInstance instance, List<EmulatorInstance> now) {
        EmulatorInstance sameAddress = null;
        for (EmulatorInstance candidate : now) {
            if (!candidate.identity().equals(instance.identity())) continue;
            if (candidate.launchCommand().equals(instance.launchCommand())) return candidate;
            if (sameAddress == null) sameAddress = candidate;
        }
        return sameAddress != null ? sameAddress : instance;
    }

    /**
     * The pure decision, given what discovery saw and whether the port answers.
     *
     * <ul>
     *   <li>The product says it is up ({@link EmulatorState#RUNNING}: Android is; {@link EmulatorState#STARTING}:
     *       its process is): running once the port answers, unless another product says it is up on that
     *       address too. A closed port is a boot still going, or, after Android is up, {@link #adbClosed}.</li>
     *   <li>The product says stopped: stopped, and a port that answers anyway is a clash.</li>
     *   <li>The product doesn't say: the port decides, unless another product says Android is up on that
     *       address, which makes this one stopped with a clash.</li>
     * </ul>
     */
    static EmulatorLiveness of(EmulatorInstance instance, List<EmulatorInstance> all, boolean portOpen) {
        return switch (instance.state()) {
            case RUNNING, STARTING -> {
                if (!portOpen) {
                    boolean androidUp = instance.state() == EmulatorState.RUNNING;
                    yield new EmulatorLiveness(EmulatorState.STARTING, null, androidUp);
                }
                EmulatorInstance other = holder(instance, all, true);
                yield other == null ? new EmulatorLiveness(EmulatorState.RUNNING, null, false)
                        : new EmulatorLiveness(EmulatorState.STARTING,
                        where(instance) + " is also claimed by " + other.caption() + " — stop one of them", false);
            }
            case STOPPED -> new EmulatorLiveness(EmulatorState.STOPPED,
                    portOpen ? clash(instance, holder(instance, all, true)) : null, false);
            case UNKNOWN -> {
                if (!portOpen) yield new EmulatorLiveness(EmulatorState.STOPPED, null, false);
                EmulatorInstance holder = holder(instance, all, false);
                yield holder != null ? new EmulatorLiveness(EmulatorState.STOPPED, clash(instance, holder), false)
                        : new EmulatorLiveness(EmulatorState.RUNNING, null, false);
            }
        };
    }

    /**
     * Another instance at {@code instance}'s address whose product says it is up — Android up, or with
     * {@code starting} also a process still booting — or {@code null}.
     */
    private static EmulatorInstance holder(EmulatorInstance instance, List<EmulatorInstance> all, boolean starting) {
        for (EmulatorInstance other : all) {
            boolean same = other.identity().equals(instance.identity())
                    && other.launchCommand().equals(instance.launchCommand());
            boolean up = other.state() == EmulatorState.RUNNING
                    || (starting && other.state() == EmulatorState.STARTING);
            if (!same && up && other.endpoint().equals(instance.endpoint())) return other;
        }
        return null;
    }

    private static String clash(EmulatorInstance instance, EmulatorInstance holder) {
        return where(instance) + " is in use by " + (holder == null ? "another program" : holder.caption());
    }

    private static String where(EmulatorInstance instance) {
        return instance.adb() instanceof AdbEndpoint.Tcp tcp ? "port " + tcp.port() : instance.endpoint();
    }
}
