package com.botmaker.shared.emulator.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The emulators folder's JSON files: read whole, changed under a lock, written atomically, so a reader never sees half
 * a file and two writers (Studio, the core, another process) never lose each other's change.
 */
final class JsonFiles {

    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** The lock one JVM's threads take before the file lock, which the JVM grants only once per process. */
    private static final ReentrantLock IN_PROCESS = new ReentrantLock();

    private JsonFiles() {}

    /** A change to a file's tree, which may throw what reading its parts throws. */
    @FunctionalInterface
    interface Change<T> {
        T apply(ObjectNode root) throws IOException;
    }

    /** The file's root object; an empty one when there is no file. A file that is not a JSON object is an error. */
    static ObjectNode read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return JSON.createObjectNode();
        }
        JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        if (root == null || root.isMissingNode()) {
            return JSON.createObjectNode();
        }
        if (!(root instanceof ObjectNode object)) {
            throw new IOException(file.getFileName() + " is not a JSON object");
        }
        return object;
    }

    /**
     * Reads the file, lets the change edit its tree, and writes it back, under the folder's lock. A file that cannot
     * be read is left as it is: the change is refused rather than writing over it.
     */
    static <T> T change(Path file, Change<T> change) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        if (IN_PROCESS.isHeldByCurrentThread()) {
            throw new IllegalStateException("a change to the emulators folder made inside another one: make it after");
        }
        IN_PROCESS.lock();
        try (FileChannel channel = FileChannel.open(dir.resolve(".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            ObjectNode root = read(file);
            T result = change.apply(root);
            write(file, root);
            return result;
        } finally {
            IN_PROCESS.unlock();
        }
    }

    private static void write(Path file, ObjectNode root) throws IOException {
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, JSON.writeValueAsString(root), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
