package com.botmaker.shared.emulator.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A field's value to and from JSON, for the applies log ({@link Applies}). The configuration itself is no longer JSON:
 * it is its owner's values of this PC (2026-11-12), written as text by {@link ConfigValue#written()}.
 */
final class ConfigJson {

    private ConfigJson() {}

    /** The value, or null when the field is unknown or the JSON is not of its kind. */
    static ConfigValue value(ConfigField field, JsonNode json) {
        try {
            return switch (field.kind()) {
                case SCREEN -> json.isObject() && json.path("w").isInt() && json.path("h").isInt()
                        && json.path("dpi").isInt()
                        ? new ConfigValue.Screen(json.get("w").asInt(), json.get("h").asInt(), json.get("dpi").asInt())
                        : null;
                case COUNT -> json.isInt() ? new ConfigValue.Count(json.asInt()) : null;
                case MEGABYTES -> json.isInt() ? new ConfigValue.Megabytes(json.asInt()) : null;
                case TOGGLE -> json.isBoolean() ? new ConfigValue.Toggle(json.asBoolean()) : null;
                case TEXT -> field != ConfigField.UNKNOWN && json.isTextual() ? new ConfigValue.Text(json.asText()) : null;
            };
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static JsonNode json(ConfigValue value, ObjectNode factory) {
        return switch (value) {
            case ConfigValue.Screen screen -> factory.objectNode()
                    .put("w", screen.width()).put("h", screen.height()).put("dpi", screen.dpi());
            case ConfigValue.Count count -> factory.numberNode(count.value());
            case ConfigValue.Megabytes megabytes -> factory.numberNode(megabytes.value());
            case ConfigValue.Toggle toggle -> factory.booleanNode(toggle.on());
            case ConfigValue.Text text -> factory.textNode(text.value());
        };
    }
}
