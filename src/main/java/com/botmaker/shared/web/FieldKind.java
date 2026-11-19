// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

/**
 * What a form field takes, by the {@code type} its element gives ({@code textarea} is {@link #TEXT}): which samples
 * {@link FormProbe} types into it. Only the kinds that take typed text are {@link #typed()}.
 */
public enum FieldKind {
    EMAIL("email", "Email", true),
    PASSWORD("password", "Password", true),
    TEXT("text", "Text", true),
    SEARCH("search", "Search", true),
    NUMBER("number", "Number", true),
    URL("url", "URL", true),
    TEL("tel", "Phone number", true),
    CHECKBOX("checkbox", "Checkbox", false),
    RADIO("radio", "Radio button", false),
    SELECT("select", "List", false),
    DATE("date", "Date", false),
    UNKNOWN("unknown", "Unknown", false);

    private final String id;
    private final String displayName;
    private final boolean typed;

    FieldKind(String id, String displayName, boolean typed) {
        this.id = id;
        this.displayName = displayName;
        this.typed = typed;
    }

    /** The element's {@code type}. */
    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** Whether the probe types samples into it; the others it reads only. */
    public boolean typed() {
        return typed;
    }

    /**
     * The kind of an element whose {@code type} is {@code type}: {@code textarea} and an input with no type are
     * {@link #TEXT}, {@code select-one} and {@code select-multiple} {@link #SELECT}; {@link #UNKNOWN} for any other.
     */
    public static FieldKind fromId(String type) {
        if (type == null || type.isEmpty() || type.equals("textarea")) {
            return TEXT;
        }
        if (type.startsWith("select")) {
            return SELECT;
        }
        for (FieldKind kind : values()) {
            if (kind.id.equals(type)) {
                return kind;
            }
        }
        return UNKNOWN;
    }
}
