package com.botmaker.shared.emulator.tree;

/**
 * A yes-or-no fact Android's accessibility tree states about a node, one per boolean attribute of a hierarchy
 * dump. What a node <em>is</em> (a button, a tab, a field) is read from these, never from its class name: a
 * Compose screen's checkable tabs are plain {@code android.view.View}s.
 */
public enum Trait {
    CLICKABLE("clickable"),
    LONG_CLICKABLE("long-clickable"),
    CHECKABLE("checkable"),
    CHECKED("checked"),
    SELECTED("selected"),
    ENABLED("enabled"),
    FOCUSED("focused"),
    SCROLLABLE("scrollable"),
    PASSWORD("password");

    private final String attribute;

    Trait(String attribute) {
        this.attribute = attribute;
    }

    /** The hierarchy XML attribute that holds it ({@code long-clickable}). */
    public String attribute() {
        return attribute;
    }
}
