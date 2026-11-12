package com.botmaker.shared.emulator.config;

import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.steps.DeviceSteps;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * What one instance has now, against which {@link ConfigDiff} says what Apply would change: its product's own settings,
 * read whether it runs or not, and what its Android said, read while it runs and kept, with its time, once it stops.
 *
 * @param settings    the product's file or console, read at {@code settingsAt}
 * @param android     Android's last answer, or empty when it never answered
 * @param androidLive whether {@code android} was read just now, not kept from an earlier read
 */
public record InstanceFacts(InstanceKey key, Map<ConfigField, ConfigValue> settings, Instant settingsAt,
                            Optional<AndroidState> android, boolean androidLive) {

    public InstanceFacts {
        Map<ConfigField, ConfigValue> copy = new EnumMap<>(ConfigField.class);
        copy.putAll(settings);
        settings = Collections.unmodifiableMap(copy);
        android = android == null ? Optional.empty() : android;
        androidLive = androidLive && android.isPresent();
    }

    /** Where a fact was read. */
    public enum Origin {
        SETTINGS("settings", "The emulator's settings"),
        ANDROID("android", "Android"),
        UNKNOWN("unknown", "Unknown");

        private final String id;
        private final String displayName;

        Origin(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        public static Origin fromId(String id) {
            for (Origin origin : values()) {
                if (origin.id.equals(id)) return origin;
            }
            return UNKNOWN;
        }
    }

    /** One fact, where and when it was read: the window says "read N s ago" for an old one. */
    public record Read(ConfigValue value, Origin origin, Instant at) {
    }

    /**
     * The field's value now. The screen is Android's while it answers live, as that is what apps see (a {@code wm size}
     * override included), else the settings'; every other field is the settings' when the product says it, else
     * Android's, live or kept.
     */
    public Optional<Read> field(ConfigField field) {
        Optional<Read> fromAndroid = android.flatMap(a -> Optional.ofNullable(a.fields().get(field))
                .map(value -> new Read(value, Origin.ANDROID, a.at())));
        Optional<Read> fromSettings = Optional.ofNullable(settings.get(field))
                .map(value -> new Read(value, Origin.SETTINGS, settingsAt));
        if (field == ConfigField.SCREEN && androidLive && fromAndroid.isPresent()) return fromAndroid;
        return fromSettings.isPresent() ? fromSettings : fromAndroid;
    }

    /**
     * Reads {@code instance}: its settings always; its Android over {@code device} when given (a running instance),
     * else {@code last}'s Android answer is kept with its time.
     *
     * @param device the running instance's connection, or null when it is stopped
     * @param last   what the previous read found, or null
     */
    public static InstanceFacts read(EmulatorInstance instance, AdbDevice device, InstanceFacts last, Clock clock) {
        Instant now = clock.instant();
        Map<ConfigField, ConfigValue> settings = ProductSettings.of(instance.platformId()).read(instance);
        Optional<AndroidState> kept = last == null ? Optional.empty() : last.android();
        if (device == null) return new InstanceFacts(InstanceKey.of(instance), settings, now, kept, false);
        AndroidState read = AndroidState.read(command -> shell(device, command), now);
        if (read.android().isEmpty() && read.fields().isEmpty()) {
            // Android answered nothing (gone mid-read): no accounts or games is not a fact to diff against
            return new InstanceFacts(InstanceKey.of(instance), settings, now, kept, false);
        }
        return new InstanceFacts(InstanceKey.of(instance), settings, now, Optional.of(read), true);
    }

    public static InstanceFacts read(EmulatorInstance instance, AdbDevice device) {
        return read(instance, device, null, Clock.systemUTC());
    }

    /** {@code dumpsys account}'s step says how many Google accounts it found, never their emails. */
    private static String shell(AdbDevice device, String command) {
        if (!command.equals(AndroidState.ACCOUNTS)) return device.shell(command);
        return DeviceSteps.step(device.endpoint(), command, () -> device.shell(command),
                out -> {
                    var found = AndroidState.googleAccounts(out);
                    return found == null ? "no account list" : found.size() + " Google accounts";
                });
    }
}
