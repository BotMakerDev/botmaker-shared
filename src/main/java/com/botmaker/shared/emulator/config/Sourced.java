package com.botmaker.shared.emulator.config;

/** A wanted value and the layer it comes from, which the window shows beside it. */
public record Sourced<T>(T value, Source source) {

    public enum Source {
        EMULATOR("emulator", "The emulator's"),
        INSTANCE("instance", "This instance's");

        private final String id;
        private final String displayName;

        Source(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }
    }
}
