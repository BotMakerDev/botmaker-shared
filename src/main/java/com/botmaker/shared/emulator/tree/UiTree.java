package com.botmaker.shared.emulator.tree;

import java.awt.Point;
import java.awt.Rectangle;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * An Android screen's accessibility tree at one moment: one root per window, in the dump's order. Immutable, and
 * never written anywhere: a dump holds what the screen shows, an account's address included.
 *
 * @param roots the windows' top nodes
 */
public record UiTree(List<UiNode> roots) {

    private static final Pattern BOUNDS = Pattern.compile("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]");
    /** Built once (a lookup per read otherwise); creating readers from a configured factory is thread-safe. */
    private static final XMLInputFactory XML = factory();

    private static XMLInputFactory factory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }

    public UiTree {
        roots = roots == null ? List.of() : List.copyOf(roots);
    }

    /**
     * Reads the XML {@code uiautomator dump} and {@code u2.jar}'s {@code dumpWindowHierarchy} both answer. Text after
     * the closing {@code </hierarchy>} (the dump's status line) is ignored.
     *
     * @throws IllegalArgumentException when the text is no hierarchy
     */
    public static UiTree parse(String hierarchyXml) {
        try {
            XMLStreamReader xml = XML.createXMLStreamReader(new StringReader(hierarchyXml));
            try {
                return read(xml);
            } finally {
                xml.close();
            }
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("not a hierarchy: " + e.getMessage(), e);
        }
    }

    private static UiTree read(XMLStreamReader xml) throws XMLStreamException {
        boolean inHierarchy = false;
        List<UiNode> roots = new ArrayList<>();
        // Each open node's attributes and the children read so far; a node is built when it closes.
        Deque<Open> open = new ArrayDeque<>();
        while (xml.hasNext()) {
            int event = xml.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String name = xml.getLocalName();
                if (!inHierarchy) {
                    if (!name.equals("hierarchy")) throw new IllegalArgumentException("not a hierarchy: <" + name + ">");
                    inHierarchy = true;
                } else if (name.equals("node")) {
                    open.push(new Open(xml));
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String name = xml.getLocalName();
                if (name.equals("node") && !open.isEmpty()) {
                    UiNode node = open.pop().build();
                    if (open.isEmpty()) roots.add(node);
                    else open.peek().children.add(node);
                } else if (name.equals("hierarchy")) {
                    return new UiTree(roots);
                }
            }
        }
        throw new IllegalArgumentException("not a hierarchy: no closed <hierarchy>");
    }

    /** A node being read: its attributes, and its children so far. */
    private static final class Open {
        final String id;
        final String text;
        final String label;
        final String className;
        final String packageName;
        final Rectangle bounds;
        final Set<Trait> traits = EnumSet.noneOf(Trait.class);
        final List<UiNode> children = new ArrayList<>();

        Open(XMLStreamReader xml) {
            id = xml.getAttributeValue(null, "resource-id");
            text = xml.getAttributeValue(null, "text");
            label = xml.getAttributeValue(null, "content-desc");
            className = xml.getAttributeValue(null, "class");
            packageName = xml.getAttributeValue(null, "package");
            bounds = bounds(xml.getAttributeValue(null, "bounds"));
            for (Trait trait : Trait.values()) {
                if ("true".equals(xml.getAttributeValue(null, trait.attribute()))) traits.add(trait);
            }
        }

        UiNode build() {
            return new UiNode(id, text, label, className, packageName, bounds, traits, children);
        }
    }

    /** {@code [108,138][300,170]} as {@code Rectangle(108,138,192,32)}; empty when unreadable. */
    static Rectangle bounds(String value) {
        Matcher m = value == null ? null : BOUNDS.matcher(value);
        if (m == null || !m.matches()) return new Rectangle();
        int left = Integer.parseInt(m.group(1));
        int top = Integer.parseInt(m.group(2));
        return new Rectangle(left, top, Integer.parseInt(m.group(3)) - left, Integer.parseInt(m.group(4)) - top);
    }

    /**
     * Only the windows of {@code packageName}, the app in focus: the status bar, the notification shade and the
     * keyboard are other packages' windows, and a bot never means them. An app's WebView content is in its own
     * window, so it stays.
     */
    public UiTree app(String packageName) {
        return new UiTree(roots.stream().filter(root -> root.packageName().equals(packageName)).toList());
    }

    /** Every node, depth first in the dump's order. */
    public List<UiNode> all() {
        List<UiNode> nodes = new ArrayList<>();
        Deque<UiNode> stack = new ArrayDeque<>(roots.reversed());
        while (!stack.isEmpty()) {
            UiNode node = stack.pop();
            nodes.add(node);
            for (UiNode child : node.children().reversed()) stack.push(child);
        }
        return nodes;
    }

    /** Every node whose bounds hold {@code point}, the smallest first; of two the same size, the deeper first. */
    public List<UiNode> at(Point point) {
        List<Hit> hits = new ArrayList<>();
        collect(roots, 0, point, hits);
        return hits.stream()
                .sorted(Comparator.comparingLong(Hit::area).thenComparing(Hit::depth, Comparator.reverseOrder()))
                .map(Hit::node)
                .toList();
    }

    private record Hit(UiNode node, int depth, long area) {}

    private static void collect(List<UiNode> nodes, int depth, Point point, List<Hit> hits) {
        for (UiNode node : nodes) {
            Rectangle bounds = node.bounds();
            if (bounds.contains(point)) hits.add(new Hit(node, depth, (long) bounds.width * bounds.height));
            collect(node.children(), depth + 1, point, hits);
        }
    }

    /** The node {@code child} sits in, found by identity; {@code null} for a root or a node not in this tree. */
    public UiNode parentOf(UiNode child) {
        Deque<UiNode> stack = new ArrayDeque<>(roots);
        while (!stack.isEmpty()) {
            UiNode node = stack.pop();
            for (UiNode c : node.children()) {
                if (c == child) return node;
                stack.push(c);
            }
        }
        return null;
    }
}
