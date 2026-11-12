package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.tree.UiNode;
import com.botmaker.shared.emulator.tree.UiTree;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Finding a sign-in or sign-out screen's nodes: by id first, the words Google and Settings show after. */
final class Screens {

    private Screens() {}

    /** The first node whose id (without its package) is one of {@code ids}. */
    static Optional<UiNode> byId(UiTree tree, String... ids) {
        Set<String> wanted = Set.of(ids);
        return tree.all().stream().filter(n -> wanted.contains(n.shortId())).findFirst();
    }

    /** The first node whose full id is {@code id}. */
    static Optional<UiNode> byFullId(UiTree tree, String id) {
        return tree.all().stream().filter(n -> n.id().equals(id)).findFirst();
    }

    /** The first node whose text or label is {@code words}, case and apostrophes aside. */
    static Optional<UiNode> saying(UiTree tree, String... words) {
        for (String w : words) {
            String wanted = plain(w);
            Optional<UiNode> found = tree.all().stream()
                    .filter(n -> plain(n.text()).equals(wanted) || plain(n.label()).equals(wanted)).findFirst();
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    /** Whether a node's text or label holds any of {@code words}, case and apostrophes aside. */
    static boolean shows(UiTree tree, String... words) {
        for (UiNode n : tree.all()) {
            String text = plain(n.text());
            String label = plain(n.label());
            for (String w : words) {
                String wanted = plain(w);
                if (text.contains(wanted) || label.contains(wanted)) return true;
            }
        }
        return false;
    }

    /** Whether no node has words yet: a page still loading. */
    static boolean blank(UiTree tree) {
        return tree.all().stream().allMatch(n -> n.text().isBlank() && n.label().isBlank());
    }

    /** Lower case, the typographic apostrophe Google writes ({@code Couldn’t}) as a plain one, spaces collapsed. */
    static String plain(String text) {
        return text.replace('’', '\'').replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }
}
