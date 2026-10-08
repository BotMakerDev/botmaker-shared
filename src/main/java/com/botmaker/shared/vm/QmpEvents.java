package com.botmaker.shared.vm;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Listens to a QEMU's events on its own QMP port ({@link VmRecord#eventsPort()}), so that when the VM stops, the
 * listener knows why: QEMU says it in its {@code SHUTDOWN} event, then closes the connection as it exits. QEMU
 * serves one client per QMP port, which is why the events have a port of their own and commands keep theirs.
 */
public final class QmpEvents implements AutoCloseable {

    /** Why QEMU stopped, as its {@code SHUTDOWN} event names it; the ones a game VM meets, the rest {@link #UNKNOWN}. */
    public enum Reason {
        /** Windows restarted: under {@code -no-reboot} that ends QEMU, and it is to be started again. */
        GUEST_RESET("guest-reset", "Windows restarted"),
        /** Windows shut down: from its Start menu, or because something pressed the power button. */
        GUEST_SHUTDOWN("guest-shutdown", "Windows shut down"),
        GUEST_PANIC("guest-panic", "Windows crashed"),
        HOST_QMP_QUIT("host-qmp-quit", "BotMaker ended it"),
        HOST_SIGNAL("host-signal", "this computer ended it"),
        /** The connection closed without an event: QEMU was ended from outside, as Task Manager does. */
        ENDED("ended", "it was ended"),
        UNKNOWN("unknown", "it stopped");

        private final String id;
        private final String displayName;

        Reason(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        /**
         * Whether whoever runs the VM starts it again: after a Windows restart, and after a crash nobody chose.
         * Everything else was someone stopping it, which starting it again would undo. {@link #ENDED} is both
         * Task Manager and a QEMU that died without a word; it is read as the first.
         */
        public boolean restarts() {
            return this == GUEST_RESET || this == GUEST_PANIC;
        }

        public static Reason fromId(String id) {
            for (Reason r : values()) {
                if (r.id.equals(id)) return r;
            }
            return UNKNOWN;
        }
    }

    private static final int GREETING_TIMEOUT_MS = 5_000;

    private final JsonChannel channel;
    private final Thread reader;
    private volatile Reason reason;
    private volatile boolean ended;
    private volatile boolean closedHere;

    private QmpEvents(JsonChannel channel) {
        this.channel = channel;
        this.reader = Thread.ofPlatform().daemon().name("qmp-events").unstarted(this::read);
    }

    /**
     * Connects to {@code port}, enters command mode (events come only then) and starts listening. Throws when
     * QEMU doesn't greet within a few seconds: it serves one client per port, so another listener holds it.
     */
    public static QmpEvents listen(int port) throws IOException {
        JsonChannel channel = JsonChannel.connect(port, GREETING_TIMEOUT_MS);
        try {
            if (!channel.read().has("QMP")) throw new IOException("Port " + port + " doesn't speak QMP.");
            channel.execute("qmp_capabilities", Map.of());
            channel.readTimeout(0); // the next event may be hours away
        } catch (IOException e) {
            channel.close();
            throw e;
        }
        QmpEvents events = new QmpEvents(channel);
        events.reader.start();
        return events;
    }

    private void read() {
        try {
            while (true) {
                JsonNode line = channel.read();
                if ("SHUTDOWN".equals(line.path("event").asText())) {
                    reason = Reason.fromId(line.path("data").path("reason").asText());
                }
            }
        } catch (IOException e) {
            // QEMU exited, or this listener was closed
        } finally {
            ended = true;
            synchronized (this) {
                notifyAll();
            }
        }
    }

    /**
     * Waits up to {@code wait} for QEMU to end, and says why it did; empty when it is still running after that.
     * A connection that closed without a {@code SHUTDOWN} event is {@link Reason#ENDED}.
     */
    public Optional<Reason> awaitEnd(Duration wait) throws InterruptedException {
        long until = System.nanoTime() + wait.toNanos();
        synchronized (this) {
            while (!ended) {
                long left = until - System.nanoTime();
                if (left <= 0) return Optional.empty();
                java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, left);
            }
        }
        Reason said = reason;
        return Optional.of(said != null ? said : Reason.ENDED);
    }

    /**
     * Whether this listener was closed here, before QEMU ended: its connection then ended without saying anything
     * about the VM, and {@link #awaitEnd} answers only what it heard before.
     */
    public boolean closedHere() {
        return closedHere;
    }

    @Override
    public void close() {
        closedHere = !ended;
        try {
            channel.close();
        } catch (IOException e) {
            // closing anyway
        }
    }
}
