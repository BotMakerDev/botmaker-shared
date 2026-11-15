package com.botmaker.shared.emulator.google;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/**
 * A properties file of {@code UserDirs.config()} read and written under a lock file, so two BotMaker processes, or
 * two copies of this class in one process (another class loader), change it one at a time.
 */
final class LockedFile {

    private LockedFile() {}

    interface Body<T> {
        T run() throws IOException;
    }

    /** Runs {@code body} holding {@code file}'s lock. */
    static <T> T with(Path file, Body<T> body) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
        synchronized (LockedFile.class) {
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = lock(channel)) {
                return body.run();
            }
        }
    }

    /** The channel's lock; another class loader's copy holding it in this process is waited for. */
    private static FileLock lock(FileChannel channel) throws IOException {
        while (true) {
            try {
                return channel.lock();
            } catch (OverlappingFileLockException heldHere) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Interrupted waiting for " + channel);
                }
            }
        }
    }

    /** The file's properties, none when it doesn't exist. */
    static Properties read(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(in);
        } catch (NoSuchFileException none) {
            // nothing kept yet
        }
        return properties;
    }

    /** Replaces the file with {@code properties}, whole or not at all. */
    static void write(Path file, Properties properties, String comment) throws IOException {
        Path written = file.resolveSibling(file.getFileName() + ".new");
        try (Writer out = Files.newBufferedWriter(written, StandardCharsets.UTF_8)) {
            properties.store(out, comment);
        }
        Files.move(written, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
