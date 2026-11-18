// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import java.util.Locale;

/**
 * A web page node's accessibility role, as Chromium's {@code Accessibility.getFullAXTree} names it. A closed set: a
 * role Chromium adds later reads as {@link #UNKNOWN}, never as an error.
 */
public enum Role {
    LINK("link", "Link", true),
    BUTTON("button", "Button", true),
    TAB("tab", "Tab", true),
    MENU_ITEM("menuitem", "Menu item", true),
    MENU_ITEM_CHECKBOX("menuitemcheckbox", "Menu checkbox", true),
    MENU_ITEM_RADIO("menuitemradio", "Menu radio", true),
    TEXTBOX("textbox", "Text field", true),
    SEARCHBOX("searchbox", "Search field", true),
    CHECKBOX("checkbox", "Checkbox", true),
    RADIO("radio", "Radio button", true),
    SWITCH("switch", "Switch", true),
    COMBOBOX("combobox", "Combo box", true),
    LISTBOX("listbox", "List box", true),
    OPTION("option", "Option", true),
    SLIDER("slider", "Slider", true),
    SPINBUTTON("spinbutton", "Number field", true),
    DIALOG("dialog", "Dialog", false),
    ALERT_DIALOG("alertdialog", "Alert dialog", false),
    HEADING("heading", "Heading", false),
    IMAGE("image", "Image", false),
    NAVIGATION("navigation", "Navigation", false),
    MAIN("main", "Main", false),
    BANNER("banner", "Banner", false),
    CONTENT_INFO("contentinfo", "Footer", false),
    REGION("region", "Region", false),
    FORM("form", "Form", false),
    GROUP("group", "Group", false),
    ARTICLE("article", "Article", false),
    LIST("list", "List", false),
    LIST_ITEM("listitem", "List item", false),
    TAB_LIST("tablist", "Tab list", false),
    TAB_PANEL("tabpanel", "Tab panel", false),
    MENU("menu", "Menu", false),
    MENU_BAR("menubar", "Menu bar", false),
    PARAGRAPH("paragraph", "Paragraph", false),
    STATIC_TEXT("StaticText", "Text", false),
    GENERIC("generic", "Generic", false),
    ROOT_WEB_AREA("RootWebArea", "Page", false),
    UNKNOWN("unknown", "Unknown", false);

    private final String id;
    private final String displayName;
    private final boolean interactive;

    Role(String id, String displayName, boolean interactive) {
        this.id = id;
        this.displayName = displayName;
        this.interactive = interactive;
    }

    /** Chromium's own name for the role. */
    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** Whether a person acts on a node of this role: one of the page's inputs. */
    public boolean interactive() {
        return interactive;
    }

    /** The role Chromium calls {@code id}; {@code img} is the older name of {@link #IMAGE}. Never throws. */
    public static Role fromId(String id) {
        if (id == null) {
            return UNKNOWN;
        }
        if (id.equals("img")) {
            return IMAGE;
        }
        for (Role role : values()) {
            if (role.id.equals(id) || role.id.toLowerCase(Locale.ROOT).equals(id.toLowerCase(Locale.ROOT))) {
                return role;
            }
        }
        return UNKNOWN;
    }
}
