package com.botmaker.shared.emulator.config;

/** A field's value, of the shape its {@link ConfigField#kind()} says. */
public sealed interface ConfigValue {

    ConfigField.Kind kind();

    /** What the window shows. */
    String said();

    record Screen(int width, int height, int dpi) implements ConfigValue {
        public Screen {
            if (width <= 0 || height <= 0 || dpi <= 0) {
                throw new IllegalArgumentException("a screen needs a positive width, height and dpi");
            }
        }

        @Override
        public ConfigField.Kind kind() {
            return ConfigField.Kind.SCREEN;
        }

        @Override
        public String said() {
            return width + "×" + height + ", " + dpi + " dpi";
        }
    }

    record Count(int value) implements ConfigValue {
        public Count {
            if (value <= 0) {
                throw new IllegalArgumentException("a count is positive");
            }
        }

        @Override
        public ConfigField.Kind kind() {
            return ConfigField.Kind.COUNT;
        }

        @Override
        public String said() {
            return Integer.toString(value);
        }
    }

    record Megabytes(int value) implements ConfigValue {
        public Megabytes {
            if (value <= 0) {
                throw new IllegalArgumentException("a memory size is positive");
            }
        }

        @Override
        public ConfigField.Kind kind() {
            return ConfigField.Kind.MEGABYTES;
        }

        @Override
        public String said() {
            return value % 1024 == 0 ? value / 1024 + " GB" : value + " MB";
        }
    }

    record Toggle(boolean on) implements ConfigValue {
        @Override
        public ConfigField.Kind kind() {
            return ConfigField.Kind.TOGGLE;
        }

        @Override
        public String said() {
            return on ? "on" : "off";
        }
    }

    record Text(String value) implements ConfigValue {
        public Text {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("a text value is not blank");
            }
        }

        @Override
        public ConfigField.Kind kind() {
            return ConfigField.Kind.TEXT;
        }

        @Override
        public String said() {
            return value;
        }
    }
}
