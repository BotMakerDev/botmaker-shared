package com.botmaker.shared.emulator.config;

/** A field's value, of the shape its {@link ConfigField#kind()} says. */
public sealed interface ConfigValue {

    ConfigField.Kind kind();

    /** What the window shows. */
    String said();

    /** The value as the text a configuration keeps it in, which {@link #read} reads back: {@code 1080x1920@320}. */
    default String written() {
        return switch (this) {
            case Screen screen -> screen.width() + "x" + screen.height() + "@" + screen.dpi();
            case Count count -> Integer.toString(count.value());
            case Megabytes megabytes -> Integer.toString(megabytes.value());
            case Toggle toggle -> toggle.on() ? "on" : "off";
            case Text text -> text.value();
        };
    }

    /** The value {@link #written()} wrote for a field of {@code kind}, or null for text that is not one. */
    static ConfigValue read(ConfigField.Kind kind, String text) {
        if (text == null || text.isBlank()) return null;
        String written = text.strip();
        try {
            return switch (kind) {
                case SCREEN -> {
                    int by = written.indexOf('x');
                    int at = written.indexOf('@');
                    yield by <= 0 || at <= by ? null : new Screen(Integer.parseInt(written.substring(0, by)),
                            Integer.parseInt(written.substring(by + 1, at)), Integer.parseInt(written.substring(at + 1)));
                }
                case COUNT -> new Count(Integer.parseInt(written));
                case MEGABYTES -> new Megabytes(Integer.parseInt(written));
                case TOGGLE -> written.equals("on") ? new Toggle(true) : written.equals("off") ? new Toggle(false) : null;
                case TEXT -> new Text(written);
            };
        } catch (IllegalArgumentException notOne) {
            return null;
        }
    }

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
