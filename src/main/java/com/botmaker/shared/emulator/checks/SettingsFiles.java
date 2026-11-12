package com.botmaker.shared.emulator.checks;

import com.botmaker.shared.emulator.EmulatorInstance;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.function.UnaryOperator;

/**
 * An emulator's settings file as a configuration's Apply changes it: kept first in {@link WindowsBackups}, changed in
 * place through one UAC prompt where Windows protects it ({@link ElevatedCopy}), and put back whole when the Apply
 * fails. Apply's own Undo writes the values it changed instead, as a Fix's Undo does.
 */
public final class SettingsFiles {

    private SettingsFiles() {}

    /** Keeps {@code file} before an Apply on {@code instance} changes it. */
    public static WindowsBackups.Backup keep(EmulatorInstance instance, Path file) throws IOException {
        return WindowsBackups.save(WindowsBackups.root(), instance, "apply", null, file, LocalDateTime.now());
    }

    /** Reads {@code file}, changes its text and writes it back; null when written, else why not. */
    public static String edit(Path file, UnaryOperator<String> change) throws IOException, InterruptedException {
        return ElevatedCopy.write(file, change.apply(ElevatedCopy.read(file)));
    }

    /** Puts {@code kept} back over its original; null when it is back, else why not. */
    public static String restore(WindowsBackups.Backup kept) throws IOException, InterruptedException {
        return ElevatedCopy.copy(kept.copy(), kept.origin());
    }
}
