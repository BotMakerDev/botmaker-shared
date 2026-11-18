// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

/**
 * One line a page wrote to its console, an exception it left uncaught, or an entry the browser logged for it (a
 * resource that failed to load).
 *
 * @param level how bad
 * @param text  what it says
 * @param url   the script or resource it came from, {@code ""} when unknown
 */
public record ConsoleMessage(Level level, String text, String url) {

    public ConsoleMessage {
        level = level == null ? Level.UNKNOWN : level;
        text = text == null ? "" : text;
        url = url == null ? "" : url;
    }

    /** A console line's level, by the names CDP's {@code Runtime} and {@code Log} domains give it. */
    public enum Level {
        DEBUG("debug", "Debug"),
        LOG("log", "Log"),
        INFO("info", "Info"),
        WARNING("warning", "Warning"),
        ERROR("error", "Error"),
        UNKNOWN("unknown", "Unknown");

        private final String id;
        private final String displayName;

        Level(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        /** {@code warn} is {@code Runtime}'s name of a warning, {@code verbose} {@code Log}'s of debug. */
        public static Level fromId(String id) {
            if (id == null) {
                return UNKNOWN;
            }
            return switch (id) {
                case "debug", "verbose", "trace" -> DEBUG;
                case "log", "dir", "table" -> LOG;
                case "info" -> INFO;
                case "warning", "warn" -> WARNING;
                case "error", "assert" -> ERROR;
                default -> UNKNOWN;
            };
        }
    }
}
