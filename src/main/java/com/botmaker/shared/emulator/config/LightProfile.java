// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator.config;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import static com.botmaker.shared.emulator.config.ConfigField.CPU_CORES;
import static com.botmaker.shared.emulator.config.ConfigField.FPS;
import static com.botmaker.shared.emulator.config.ConfigField.MEMORY;

/**
 * The light profile (spec {@code 2026-11-14-end-to-end-instances-design.md} § The light profile): 2048 MB, 2 cores and
 * 30 frames a second, so several instances run together within this PC's memory ({@link InstanceMemory}). A preset of
 * the configuration: setting it writes these fields into a layer, which Apply makes so like any other change, and undo
 * takes back. The screen is left as the game's.
 *
 * <p>No memory trim (checked 2026-11-15): LDPlayer's files have no such key, and BlueStacks' trim is a set of
 * edition-wide {@code bst.mem_*} keys whose effect nothing documents, so neither is written.
 */
public final class LightProfile {

    public static final int MEMORY_MB = 2048;
    public static final int CORES = 2;
    public static final int FRAMES = 30;

    private static final Map<ConfigField, ConfigValue> FIELDS;

    static {
        Map<ConfigField, ConfigValue> fields = new EnumMap<>(ConfigField.class);
        fields.put(MEMORY, new ConfigValue.Megabytes(MEMORY_MB));
        fields.put(CPU_CORES, new ConfigValue.Count(CORES));
        fields.put(FPS, new ConfigValue.Count(FRAMES));
        FIELDS = Collections.unmodifiableMap(fields);
    }

    private LightProfile() {}

    /** The profile's fields that {@code product} applies; a product that applies none gets none. */
    public static Map<ConfigField, ConfigValue> fields(ProductSettings product) {
        Map<ConfigField, ConfigValue> out = new EnumMap<>(ConfigField.class);
        FIELDS.forEach((field, value) -> {
            if (product.applies(field)) out.put(field, value);
        });
        return Collections.unmodifiableMap(out);
    }

    /** {@code layer} with the profile's fields set, the rest kept. */
    public static Layer onto(Layer layer, ProductSettings product) {
        Layer out = layer;
        for (Map.Entry<ConfigField, ConfigValue> one : fields(product).entrySet()) {
            out = out.with(one.getKey(), one.getValue());
        }
        return out;
    }

    /** Whether {@code wanted} already holds every field of the profile {@code product} applies. */
    public static boolean in(Map<ConfigField, ConfigValue> wanted, ProductSettings product) {
        Map<ConfigField, ConfigValue> fields = fields(product);
        return !fields.isEmpty() && fields.entrySet().stream()
                .allMatch(one -> one.getValue().equals(wanted.get(one.getKey())));
    }
}
