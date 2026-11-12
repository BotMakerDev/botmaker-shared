package com.botmaker.shared.emulator.config;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * One thing the Emulators window does to an instance (an Apply, an Undo, later a sign-in or an install), queued in
 * {@link EmulatorOperations}: its steps as they go, its progress, whether it needs the user, and its end. Its body
 * runs on the queue's thread and looks at {@link Context#cancelled()} between steps.
 */
public final class Operation {

    /** Where an operation is. */
    public enum State {
        QUEUED("queued", "Waiting"),
        RUNNING("running", "Running"),
        NEEDS_YOU("needs_you", "Needs you"),
        DONE("done", "Done"),
        FAILED("failed", "Failed"),
        CANCELLED("cancelled", "Cancelled"),
        UNKNOWN("unknown", "Unknown");

        private final String id;
        private final String displayName;

        State(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        public boolean ended() {
            return this == DONE || this == FAILED || this == CANCELLED;
        }

        public static State fromId(String id) {
            for (State state : values()) {
                if (state.id.equals(id)) return state;
            }
            return UNKNOWN;
        }
    }

    /** What the window shows of an operation at one moment. */
    public record Snapshot(String id, String title, InstanceKey instance, State state, String step, double progress,
                           String needsYou, String outcome) {
    }

    /** What a body tells the queue as it goes. */
    public interface Context {
        void step(String words);

        /** 0 to 1. */
        void progress(double fraction);

        /** The user has to act (a captcha, a screen BotMaker does not know); {@link #resumed()} once they did. */
        void needsYou(String why);

        void resumed();

        boolean cancelled();
    }

    /** The work: null when it ended well, else why not, in the user's words. */
    @FunctionalInterface
    public interface Body {
        String run(Context context) throws Exception;
    }

    private final String id = UUID.randomUUID().toString();
    private final String title;
    private final InstanceKey instance;
    private final String lane;
    private final Body body;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Snapshot> now;

    Operation(String title, InstanceKey instance, String lane, Body body) {
        this.title = title;
        this.instance = instance;
        this.lane = lane;
        this.body = body;
        this.now = new AtomicReference<>(new Snapshot(id, title, instance, State.QUEUED, "", 0, null, null));
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public InstanceKey instance() {
        return instance;
    }

    /** Operations of one lane run one at a time: the ADB port products share. */
    String lane() {
        return lane;
    }

    public Snapshot snapshot() {
        return now.get();
    }

    /**
     * Asks the operation to stop: one still queued never starts; a running one stops at its next step, and an Apply
     * then puts back what it changed.
     */
    public void cancel() {
        cancelled.set(true);
    }

    public boolean cancelled() {
        return cancelled.get();
    }

    /** Runs the body on the caller's (the queue's) thread, telling {@code changed} each new snapshot. */
    void run(Consumer<Snapshot> changed) {
        if (cancelled.get()) {
            set(changed, s -> new Snapshot(id, title, instance, State.CANCELLED, s.step(), s.progress(), null,
                    "Cancelled before it started."));
            return;
        }
        set(changed, s -> new Snapshot(id, title, instance, State.RUNNING, s.step(), s.progress(), null, null));
        Context context = new Context() {
            @Override
            public void step(String words) {
                set(changed, s -> new Snapshot(id, title, instance, s.state(), words, s.progress(), s.needsYou(),
                        null));
            }

            @Override
            public void progress(double fraction) {
                double clamped = Math.max(0, Math.min(1, fraction));
                set(changed, s -> new Snapshot(id, title, instance, s.state(), s.step(), clamped, s.needsYou(), null));
            }

            @Override
            public void needsYou(String why) {
                set(changed, s -> new Snapshot(id, title, instance, State.NEEDS_YOU, s.step(), s.progress(), why,
                        null));
            }

            @Override
            public void resumed() {
                set(changed, s -> new Snapshot(id, title, instance, State.RUNNING, s.step(), s.progress(), null, null));
            }

            @Override
            public boolean cancelled() {
                return cancelled.get();
            }
        };
        String failed;
        try {
            failed = body.run(context);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failed = "Stopped.";
        } catch (Exception e) {
            failed = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        String outcome = failed;
        State end = failed == null ? State.DONE : cancelled.get() ? State.CANCELLED : State.FAILED;
        set(changed, s -> new Snapshot(id, title, instance, end, s.step(), end == State.DONE ? 1 : s.progress(), null,
                outcome));
    }

    private void set(Consumer<Snapshot> changed, UnaryOperator<Snapshot> next) {
        changed.accept(now.updateAndGet(next));
    }
}
