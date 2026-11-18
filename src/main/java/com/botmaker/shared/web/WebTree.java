// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import com.botmaker.shared.emulator.tree.Trait;
import com.botmaker.shared.emulator.tree.UiNode;
import com.botmaker.shared.emulator.tree.UiTree;
import com.fasterxml.jackson.databind.JsonNode;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A web page's accessibility tree at one moment, the web's {@code UiTree}: the nodes in tree order, node {@code 0} the
 * page. Immutable, and never written anywhere by itself: it holds what the page shows.
 *
 * @param nodes every node kept, each at its {@link WebNode#index()}
 */
public record WebTree(List<WebNode> nodes) {

    /** Roles Chromium reports that carry nothing a tester reads: their text is already their parent's. */
    private static final Set<String> SKIPPED_ROLES = Set.of("InlineTextBox", "LineBreak", "none");

    public WebTree {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    /**
     * What {@code DOM} adds to a node the AX tree names by its backend id: its box and the attributes a locator
     * reads.
     *
     * @param box        its border box in the viewport, or {@code null} when it is not laid out
     * @param attributes its element's attributes by name
     */
    public record Extra(Rectangle box, Map<String, String> attributes) {

        public Extra {
            box = box == null ? null : new Rectangle(box);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }

        /** A copy. */
        @Override
        public Rectangle box() {
            return box == null ? null : new Rectangle(box);
        }
    }

    /**
     * Builds the tree from {@code Accessibility.getFullAXTree}'s result and the extras measured for some of its
     * nodes. Ignored nodes and inline text boxes are left out, their children kept under the nearest node kept.
     *
     * @param axTree the result, holding {@code nodes}
     * @param extras by backend DOM node id
     */
    public static WebTree from(JsonNode axTree, Map<Integer, Extra> extras) {
        JsonNode raw = axTree.path("nodes");
        Map<String, JsonNode> byId = new HashMap<>();
        String rootId = null;
        for (JsonNode node : raw) {
            String id = node.path("nodeId").asText();
            byId.put(id, node);
            if (rootId == null && (node.path("parentId").isMissingNode() || node.path("parentId").isNull())) {
                rootId = id;
            }
        }
        if (rootId == null) {
            return new WebTree(List.of());
        }
        List<Builder> kept = new ArrayList<>();
        Deque<Visit> stack = new ArrayDeque<>();
        stack.push(new Visit(rootId, -1));
        while (!stack.isEmpty()) {
            Visit visit = stack.pop();
            JsonNode node = byId.get(visit.id);
            if (node == null) {
                continue;
            }
            int parent = visit.parent;
            if (!skipped(node) || kept.isEmpty()) {
                Builder builder = builder(kept.size(), parent, node, extras);
                if (parent >= 0) {
                    kept.get(parent).children.add(builder.index);
                }
                kept.add(builder);
                parent = builder.index;
            }
            List<String> children = new ArrayList<>();
            node.path("childIds").forEach(child -> children.add(child.asText()));
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(new Visit(children.get(i), parent));
            }
        }
        return new WebTree(kept.stream().map(Builder::build).toList());
    }

    /** The page itself. */
    public Optional<WebNode> root() {
        return nodes.isEmpty() ? Optional.empty() : Optional.of(nodes.getFirst());
    }

    public Optional<WebNode> parent(WebNode node) {
        return node.parent() < 0 ? Optional.empty() : Optional.of(nodes.get(node.parent()));
    }

    public List<WebNode> children(WebNode node) {
        return node.children().stream().map(nodes::get).toList();
    }

    /** Every node a person acts on, in tree order. */
    public List<WebNode> interactive() {
        return nodes.stream().filter(WebNode::interactive).toList();
    }

    /** The node a press at {@code at} lands on: the smallest laid-out node a person acts on whose box holds it. */
    public Optional<WebNode> pressedAt(Point at) {
        return nodes.stream().filter(n -> n.interactive() && n.laidOut() && n.box().contains(at))
                .min(Comparator.comparingLong(n -> (long) n.box().width * n.box().height));
    }

    /** The nodes of one role whose name is {@code name}, ignoring case and surrounding space. */
    public List<WebNode> find(Role role, String name) {
        String wanted = name == null ? "" : name.strip();
        return nodes.stream().filter(n -> n.role() == role && n.name().strip().equalsIgnoreCase(wanted)).toList();
    }

    /** The tree as indented lines, one per node but static text, for a log or a person. */
    public String outline() {
        StringBuilder out = new StringBuilder();
        if (nodes.isEmpty()) {
            return "";
        }
        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[] {0, 0});
        while (!stack.isEmpty()) {
            int[] at = stack.pop();
            WebNode node = nodes.get(at[0]);
            boolean shown = node.role() != Role.STATIC_TEXT && node.role() != Role.GENERIC
                    || !node.name().isEmpty() && node.role() == Role.GENERIC;
            int depth = at[1];
            if (shown) {
                out.append("  ".repeat(depth)).append(node.line()).append('\n');
                depth++;
            }
            List<Integer> children = node.children();
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(new int[] {children.get(i), depth});
            }
        }
        return out.toString();
    }

    /** What {@link #asUiTree} puts before a role's id in a node's {@link UiNode#className()}. */
    public static final String CLASS_PREFIX = "web:";

    /**
     * This tree as an emulator's {@link UiTree}, so what finds an element on a phone finds it on a page: each node a
     * {@link UiNode} whose {@code id} is its test id, else its DOM id; whose {@code label} is its accessible name and
     * whose {@code text} is a field's value, else its name; whose {@code className} is {@link #CLASS_PREFIX} and its
     * role's id; whose {@code bounds} is its box. A node a person acts on is clickable unless disabled.
     */
    public UiTree asUiTree() {
        if (nodes.isEmpty()) {
            return new UiTree(List.of());
        }
        // Tree order puts a child after its parent: built from the last node back, each child is ready first.
        UiNode[] built = new UiNode[nodes.size()];
        for (int i = nodes.size() - 1; i >= 0; i--) {
            WebNode node = nodes.get(i);
            List<UiNode> children = node.children().stream().map(c -> built[c]).toList();
            built[i] = new UiNode(node.testId().isEmpty() ? node.domId() : node.testId(),
                    field(node.role()) ? node.value() : node.name(), node.name(), CLASS_PREFIX + node.role().id(), "",
                    node.box(), traits(node), children);
        }
        return new UiTree(List.of(built[0]));
    }

    /** The role {@link #asUiTree} spelled in {@code className}; empty for a node no web tree gave. */
    public static Optional<Role> roleOf(String className) {
        return className != null && className.startsWith(CLASS_PREFIX)
                ? Optional.of(Role.fromId(className.substring(CLASS_PREFIX.length()))) : Optional.empty();
    }

    /** Whether a person types into a node of {@code role}. */
    public static boolean field(Role role) {
        return role == Role.TEXTBOX || role == Role.SEARCHBOX || role == Role.COMBOBOX || role == Role.SPINBUTTON;
    }

    private static Set<Trait> traits(WebNode node) {
        EnumSet<Trait> traits = EnumSet.noneOf(Trait.class);
        boolean enabled = !node.has(WebState.DISABLED);
        if (enabled) traits.add(Trait.ENABLED);
        // A control with no box (a collapsed menu's link) cannot be pressed: its point would be the page's corner.
        if (enabled && node.interactive() && node.laidOut()) traits.add(Trait.CLICKABLE);
        if (node.has(WebState.CHECKED)) traits.add(Trait.CHECKED);
        if (node.has(WebState.SELECTED)) traits.add(Trait.SELECTED);
        if (node.role() == Role.CHECKBOX || node.role() == Role.SWITCH || node.role() == Role.RADIO) {
            traits.add(Trait.CHECKABLE);
        }
        return traits;
    }

    private static boolean skipped(JsonNode node) {
        return node.path("ignored").asBoolean(false) || SKIPPED_ROLES.contains(node.path("role").path("value").asText());
    }

    private static Builder builder(int index, int parent, JsonNode node, Map<Integer, Extra> extras) {
        Builder builder = new Builder(index, parent);
        builder.role = Role.fromId(node.path("role").path("value").asText(null));
        builder.name = node.path("name").path("value").asText("");
        builder.value = node.path("value").path("value").asText("");
        builder.backendId = node.path("backendDOMNodeId").asInt(0);
        for (JsonNode property : node.path("properties")) {
            String key = property.path("name").asText();
            String value = property.path("value").path("value").asText("");
            if (key.equals("url")) {
                builder.href = value;
                continue;
            }
            WebState state = WebState.fromId(key);
            if (state != WebState.UNKNOWN && WebState.holds(value)) {
                builder.states.add(state);
            }
        }
        Extra extra = extras == null ? null : extras.get(builder.backendId);
        if (extra != null) {
            builder.box = extra.box();
            builder.domId = extra.attributes().getOrDefault("id", "");
            builder.testId = extra.attributes().getOrDefault("data-testid", "");
            if (builder.href.isEmpty() && builder.role == Role.LINK) {
                builder.href = extra.attributes().getOrDefault("href", "");
            }
        }
        return builder;
    }

    private record Visit(String id, int parent) {}

    private static final class Builder {
        final int index;
        final int parent;
        final List<Integer> children = new ArrayList<>();
        final EnumSet<WebState> states = EnumSet.noneOf(WebState.class);
        Role role = Role.UNKNOWN;
        String name = "";
        String value = "";
        String href = "";
        String domId = "";
        String testId = "";
        Rectangle box;
        int backendId;

        Builder(int index, int parent) {
            this.index = index;
            this.parent = parent;
        }

        WebNode build() {
            return new WebNode(index, parent, children, role, name, value, states, href, domId, testId, box,
                    backendId);
        }
    }
}
