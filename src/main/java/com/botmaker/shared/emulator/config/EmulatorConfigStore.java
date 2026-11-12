package com.botmaker.shared.emulator.config;

import com.botmaker.shared.tools.UserDirs;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

/**
 * Where this PC's emulator configurations are kept: {@code UserDirs.config()/emulators/configurations.json}
 * ({@code %LOCALAPPDATA%\BotMaker\emulators\} on Windows). It is what the user told BotMaker, so the config folder,
 * never the cache; it is in no bot, commit, publish or {@code .bmk}, and it holds no password (there is no field for
 * one). Every write re-reads the file under a lock and changes only what this build understands ({@link ConfigJson}).
 */
public final class EmulatorConfigStore {

    /** A system property naming another folder, for tests. */
    public static final String DIR_PROPERTY = "botmaker.emulators.dir";

    private final Path dir;

    public EmulatorConfigStore(Path dir) {
        this.dir = dir;
    }

    /** The store in this PC's emulators folder. */
    public static EmulatorConfigStore standard() {
        return new EmulatorConfigStore(folder());
    }

    /** The emulators folder, which the window's other files share. */
    public static Path folder() {
        String override = System.getProperty(DIR_PROPERTY);
        return override != null && !override.isBlank() ? Path.of(override) : UserDirs.config().resolve("emulators");
    }

    public Path file() {
        return dir.resolve("configurations.json");
    }

    /** The configurations; empty when there is no file. A file that cannot be read is an error, never "empty". */
    public EmulatorConfig load() throws IOException {
        return ConfigJson.read(JsonFiles.read(file()));
    }

    /** Applies the change to the configurations as they are on disk now, writes them, and answers the result. */
    public EmulatorConfig update(UnaryOperator<EmulatorConfig> change) throws IOException {
        return JsonFiles.change(file(), root -> {
            EmulatorConfig before = ConfigJson.read(root);
            EmulatorConfig next = change.apply(before);
            ConfigJson.write(before, next, root);
            return next;
        });
    }

    /**
     * Moves an instance's configuration to its new name after the instance was renamed, the whole node with what this
     * build does not understand, replacing any the new name had. Nothing happens when the old name had none.
     */
    public EmulatorConfig rename(InstanceKey from, InstanceKey to) throws IOException {
        return JsonFiles.change(file(), root -> {
            if (root.get("instances") instanceof ObjectNode instances && instances.has(from.text())
                    && !from.equals(to)) {
                instances.set(to.text(), instances.remove(from.text()));
            }
            return ConfigJson.read(root);
        });
    }
}
