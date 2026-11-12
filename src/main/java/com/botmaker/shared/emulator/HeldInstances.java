package com.botmaker.shared.emulator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Instances BotMaker is preparing, which nothing else may start or drive meanwhile: a clone between its copy and the
 * removal of the Google accounts it copied (a copied session looks stolen to Google). The thread that holds one starts
 * it itself; {@link EmulatorLauncher#launch} and {@link EmulatorReadiness#bringUp} refuse it to every other thread.
 */
public final class HeldInstances {

    private static final Map<String, Thread> HELD = new ConcurrentHashMap<>();

    private HeldInstances() {}

    /**
     * Held by the calling thread until the hold is closed. Not re-entrant: a second hold, even the holder's, is refused,
     * so no inner close can release an outer hold.
     */
    public static Hold hold(EmulatorInstance instance) {
        String key = key(instance);
        if (HELD.putIfAbsent(key, Thread.currentThread()) != null) {
            throw new IllegalStateException(instance.name() + " is already being prepared.");
        }
        return new Hold(key);
    }

    /** Whether another thread than the caller holds {@code instance}. */
    public static boolean heldElsewhere(EmulatorInstance instance) {
        if (instance == null) return false;
        Thread holder = HELD.get(key(instance));
        return holder != null && holder != Thread.currentThread();
    }

    /** What a refused start, rename or delete says. */
    public static String why(EmulatorInstance instance) {
        return "BotMaker is preparing " + instance.name() + ": it can be used once that ends.";
    }

    private static String key(EmulatorInstance instance) {
        return instance.platformId().id() + "/" + instance.reference();
    }

    /** A hold; closing it lets others start the instance again. */
    public record Hold(String key) implements AutoCloseable {
        @Override
        public void close() {
            HELD.remove(key);
        }
    }
}
