// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

/** What the accessibility tree says is true of a node, by the property Chromium names it with. */
public enum WebState {
    DISABLED("disabled", "Disabled"),
    CHECKED("checked", "Checked"),
    EXPANDED("expanded", "Expanded"),
    SELECTED("selected", "Selected"),
    INVALID("invalid", "Invalid"),
    REQUIRED("required", "Required"),
    UNKNOWN("unknown", "Unknown");

    private final String id;
    private final String displayName;

    WebState(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** The AX property's name. */
    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** The state the AX property {@code id} gives; {@link #UNKNOWN} for any other property. */
    public static WebState fromId(String id) {
        for (WebState state : values()) {
            if (state.id.equals(id)) {
                return state;
            }
        }
        return UNKNOWN;
    }

    /**
     * Whether the property's value makes the state true: {@code "false"}, empty and absent do not; a tristate's
     * {@code "mixed"} and an invalid field's {@code "grammar"} or {@code "spelling"} do.
     */
    static boolean holds(String value) {
        return value != null && !value.isEmpty() && !value.equals("false");
    }
}
