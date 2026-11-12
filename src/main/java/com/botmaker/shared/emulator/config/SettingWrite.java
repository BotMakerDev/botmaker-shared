package com.botmaker.shared.emulator.config;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * One write of an Apply, said before it runs ({@link ProductWriters}): a console command, an edit of the product's
 * settings file, or shell commands on the running Android. Each says what it does in the user's words.
 */
public sealed interface SettingWrite {

    String what();

    /**
     * The product's console, run with the instance stopped. {@code fields} are the ones it writes: a failed Apply puts
     * them back by writing their old values, which only a field read before has.
     */
    record Console(List<String> command, Set<ConfigField> fields, String what) implements SettingWrite {
        public Console {
            command = List.copyOf(command);
            fields = fields.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(fields));
        }
    }

    /** The product's settings file, changed with the instance stopped; kept first, so a failed Apply puts it back. */
    record FileEdit(Path file, UnaryOperator<String> edit, String what) implements SettingWrite {
    }

    /**
     * Commands on the running Android, in order; through {@code su} when {@code root}. A command that fails stops the
     * Apply when {@code required}; otherwise it is said and the Apply goes on, as {@code QuietRun} runs its own.
     */
    record Shell(List<String> commands, boolean root, boolean required, String what) implements SettingWrite {
        public Shell {
            commands = List.copyOf(commands);
        }
    }
}
