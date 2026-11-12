package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.config.InstanceKey;
import com.botmaker.shared.tools.UserDirs;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The instances BotMaker is signing in to Google or out of it now: while one is typed into, the core's {@code device}
 * tool neither looks at it nor touches it, as a password is on its screen. {@link AccountDevice#of} holds its instance
 * for as long as it is open; {@code device} asks {@link #on} first.
 * <p>
 * A hold is known in this process, and to another BotMaker process by a file naming this one by pid and start time (a
 * sign-in runs in Studio while the assistant's core may be a process of its own). A file whose process is gone, or
 * whose pid another process has since, holds nothing.
 */
public final class SignInsRunning {

    private SignInsRunning() {}

    /** Holds by file name; guarded by the class, as is each file's write and delete. */
    private static final Map<String, Integer> HELD = new HashMap<>();

    /** One sign-in's hold on its instance, released when closed. */
    public interface Hold extends AutoCloseable {
        @Override
        void close();
    }

    /** Holds {@code instance} until the answer is closed; an instance of no known product is held by nothing. */
    public static Hold hold(EmulatorInstance instance) {
        InstanceKey key = key(instance);
        return key == null ? () -> {} : hold(key, dir());
    }

    /** Whether a sign-in or sign-out runs on {@code instance} now, in this process or another. */
    public static boolean on(EmulatorInstance instance) {
        InstanceKey key = key(instance);
        return key != null && on(key, dir());
    }

    /** Whether any instance may be held: false is certain, and saves finding the instance to ask {@link #on}. */
    public static boolean any() {
        return any(dir());
    }

    static boolean any(Path dir) {
        synchronized (SignInsRunning.class) {
            if (!HELD.isEmpty()) return true;
        }
        try (var files = Files.list(dir)) {
            return files.findAny().isPresent();
        } catch (IOException | RuntimeException none) {
            return false;
        }
    }

    static Hold hold(InstanceKey key, Path dir) {
        String name = name(key);
        synchronized (SignInsRunning.class) {
            HELD.merge(name, 1, Integer::sum);
            try {
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(name), stamp(ProcessHandle.current()));
            } catch (IOException | RuntimeException unwritten) {
                // this process still knows; another one would not
            }
        }
        return new Hold() {
            private boolean closed;

            @Override
            public void close() {
                synchronized (SignInsRunning.class) {
                    if (closed) return;
                    closed = true;
                    if (HELD.merge(name, -1, Integer::sum) > 0) return;
                    HELD.remove(name);
                    forget(dir, name);
                }
            }
        };
    }

    static boolean on(InstanceKey key, Path dir) {
        String name = name(key);
        synchronized (SignInsRunning.class) {
            if (HELD.getOrDefault(name, 0) > 0) return true;
            String stamp;
            try {
                stamp = Files.readString(dir.resolve(name)).strip();
            } catch (IOException | RuntimeException none) {
                return false;
            }
            // this process's own file with no hold is one a failed sign-in left
            boolean held = !stamp.equals(stamp(ProcessHandle.current())) && alive(stamp);
            if (!held) forget(dir, name);
            return held;
        }
    }

    /** Whether the process {@code stamp} names still runs: its pid alive and started when the stamp says. */
    private static boolean alive(String stamp) {
        try {
            long pid = Long.parseLong(stamp.split("@", 2)[0]);
            Optional<ProcessHandle> process = ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
            return process.isPresent() && stamp.equals(stamp(process.get()));
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /** {@code pid@start}: a pid alone is given to another process once its own ends. */
    static String stamp(ProcessHandle process) {
        return process.pid() + "@" + process.info().startInstant().map(start -> Long.toString(start.toEpochMilli()))
                .orElse("");
    }

    private static void forget(Path dir, String name) {
        try {
            Files.deleteIfExists(dir.resolve(name));
        } catch (IOException | RuntimeException kept) {
            // its process is checked on each read: once it ends the file holds nothing
        }
    }

    private static Path dir() {
        return UserDirs.cache().resolve("signing-in");
    }

    private static InstanceKey key(EmulatorInstance instance) {
        try {
            return InstanceKey.of(instance);
        } catch (IllegalArgumentException unnamed) {
            return null;
        }
    }

    /** A file name any system takes: the encoder leaves {@code *}, which Windows refuses. */
    static String name(InstanceKey key) {
        return URLEncoder.encode(key.text(), StandardCharsets.UTF_8).replace("*", "%2A") + ".pid";
    }
}
