// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import java.awt.Rectangle;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * One node of a web page's accessibility tree, as {@link WebTree} keeps it: the tree's ignored and inline-text nodes
 * left out, their children given to the nearest node kept. Every string is {@code ""} when the page gives none.
 *
 * @param index     its place in the tree's order, {@code 0} the page itself
 * @param parent    its parent's index, {@code -1} for the page
 * @param children  its children's indexes, in order
 * @param role      what it is
 * @param name      its accessible name, what a screen reader says
 * @param value     its value, a field's text or a slider's number
 * @param states    what is true of it
 * @param href      a link's target, as the page wrote it resolved to a URL
 * @param domId     its element's {@code id} attribute
 * @param testId    its element's {@code data-testid} attribute
 * @param box       where it is in the viewport, in CSS pixels; empty when it is not laid out or was not measured
 * @param backendId Chromium's id of its DOM node, which {@code DOM} and {@code Input} calls take; {@code 0} if none
 */
public record WebNode(int index, int parent, List<Integer> children, Role role, String name, String value,
                      Set<WebState> states, String href, String domId, String testId, Rectangle box, int backendId) {

    public WebNode {
        children = children == null ? List.of() : List.copyOf(children);
        role = role == null ? Role.UNKNOWN : role;
        name = orEmpty(name);
        value = orEmpty(value);
        states = states == null || states.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(states));
        href = orEmpty(href);
        domId = orEmpty(domId);
        testId = orEmpty(testId);
        box = box == null ? new Rectangle() : new Rectangle(box);
    }

    /** A copy, so the node stays as it was read. */
    @Override
    public Rectangle box() {
        return new Rectangle(box);
    }

    public boolean has(WebState state) {
        return states.contains(state);
    }

    /** Whether a person acts on it. */
    public boolean interactive() {
        return role.interactive();
    }

    /** Whether it has a box to press. */
    public boolean laidOut() {
        return box.width > 0 && box.height > 0;
    }

    /** One line: {@code button "Get started" [disabled] #id}. */
    public String line() {
        StringBuilder line = new StringBuilder(role.id());
        if (!name.isEmpty()) {
            line.append(" \"").append(name).append('"');
        }
        if (!value.isEmpty()) {
            line.append(" = \"").append(value).append('"');
        }
        for (WebState state : WebState.values()) {
            if (states.contains(state)) {
                line.append(" [").append(state.id()).append(']');
            }
        }
        if (!href.isEmpty()) {
            line.append(" -> ").append(href);
        }
        if (!testId.isEmpty()) {
            line.append(" testid=").append(testId);
        }
        if (!domId.isEmpty()) {
            line.append(" #").append(domId);
        }
        return line.toString();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
