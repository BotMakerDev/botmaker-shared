package com.botmaker.shared.emulator.config;

/** A game a configuration says an instance should have installed. */
public record GameRecord(String packageName, String label) {

    public GameRecord {
        if (packageName == null || packageName.isBlank()) {
            throw new IllegalArgumentException("a game has a package name");
        }
        packageName = packageName.strip();
        label = label == null || label.isBlank() ? packageName : label.strip();
    }
}
