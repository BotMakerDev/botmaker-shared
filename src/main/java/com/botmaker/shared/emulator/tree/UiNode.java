package com.botmaker.shared.emulator.tree;

import java.awt.Rectangle;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * One node of an Android screen's accessibility tree, as a hierarchy dump states it. Every string is
 * {@code ""} when the dump leaves it empty or out, never {@code null}.
 *
 * @param id          the full resource id ({@code com.example:id/title}, {@code android:id/title}), or {@code ""}
 * @param text        the node's own text
 * @param label       its accessibility label ({@code content-desc}), what a screen reader says for an icon
 * @param className   the view class Android reports; a hint only, see {@link Trait}
 * @param packageName the app the node belongs to
 * @param bounds      where it is on the screen, in device pixels; empty when the dump's value is unreadable
 * @param traits      what the dump says is true of it
 * @param children    its children, in the dump's order
 */
public record UiNode(String id, String text, String label, String className, String packageName, Rectangle bounds,
                     Set<Trait> traits, List<UiNode> children) {

    public UiNode {
        id = orEmpty(id);
        text = orEmpty(text);
        label = orEmpty(label);
        className = orEmpty(className);
        packageName = orEmpty(packageName);
        bounds = bounds == null ? new Rectangle() : new Rectangle(bounds);
        traits = traits == null || traits.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(traits));
        children = children == null ? List.of() : List.copyOf(children);
    }

    /** A copy, so the node stays as it was read. */
    @Override
    public Rectangle bounds() {
        return new Rectangle(bounds);
    }

    public boolean has(Trait trait) {
        return traits.contains(trait);
    }

    /**
     * The id without its package: {@code title} for both {@code com.example:id/title} and {@code android:id/title}.
     * {@code ""} when the node has no id.
     */
    public String shortId() {
        int at = id.indexOf(":id/");
        return at < 0 ? id : id.substring(at + ":id/".length());
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
