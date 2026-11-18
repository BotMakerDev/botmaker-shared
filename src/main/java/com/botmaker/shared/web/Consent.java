// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * <b>A cookie dialog's answer</b> (spec §3): <i>reject</i>, <i>necessary only</i> or <i>decline</i> when the dialog
 * offers one; a stop when it offers none, since accepting is never ours to give. A consent ask is a dialog, or a
 * region, whose own text speaks of cookies or consent or which offers an accept button.
 */
public final class Consent {

    private Consent() {}

    /** The buttons that refuse what is not needed. */
    public static final List<String> REJECT = List.of("reject all", "reject", "decline", "deny", "refuse",
            "necessary only", "only necessary", "essential only", "only essential", "strictly necessary");

    /** One list per reject word, in {@link #REJECT}'s order of preference. */
    private static final List<Words> REJECTS = REJECT.stream().map(word -> new Words(List.of(word))).toList();
    private static final Words ACCEPTS = new Words(List.of("accept", "allow all", "allow cookies", "agree"));
    private static final Words TOPIC = new Words(List.of("cookie", "cookies", "consent", "gdpr"));

    private static final Set<Role> CONTAINERS = Set.of(Role.DIALOG, Role.ALERT_DIALOG, Role.REGION, Role.BANNER,
            Role.CONTENT_INFO);
    private static final Set<Role> PRESSABLE = Set.of(Role.BUTTON, Role.LINK);

    /** What the page asks. */
    public enum Kind {
        NONE("none", "No consent dialog"),
        REJECT("reject", "Reject"),
        STOP("stop", "No way to reject");

        private final String id;
        private final String displayName;

        Kind(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }
    }

    /**
     * The answer: the button to press for {@link Kind#REJECT}, the dialog's name for {@link Kind#STOP}.
     *
     * @param button the reject button; empty unless {@code REJECT}
     * @param dialog the consent ask's name, as the tree gives it
     */
    public record Answer(Kind kind, Optional<WebNode> button, String dialog) {

        static final Answer NONE = new Answer(Kind.NONE, Optional.empty(), "");

        public Answer {
            dialog = dialog == null ? "" : dialog;
        }
    }

    /** What to do with {@code tree}'s consent ask, if it has one. */
    public static Answer of(WebTree tree) {
        for (WebNode container : tree.nodes()) {
            if (!CONTAINERS.contains(container.role())) {
                continue;
            }
            List<WebNode> inside = descendants(tree, container);
            List<WebNode> buttons = inside.stream().filter(n -> PRESSABLE.contains(n.role())).toList();
            boolean speaks = TOPIC.any(container.name()) || inside.stream().anyMatch(n -> TOPIC.any(n.name()));
            boolean offersAccept = buttons.stream().anyMatch(n -> ACCEPTS.any(n.name()));
            if (!offersAccept || !speaks && !isDialog(container)) {
                continue;
            }
            Optional<WebNode> reject = rejectButton(buttons);
            return reject.isPresent()
                    ? new Answer(Kind.REJECT, reject, container.name())
                    : new Answer(Kind.STOP, Optional.empty(), container.name());
        }
        return Answer.NONE;
    }

    /**
     * The first enabled button by {@link #REJECT}'s order: <i>Reject all</i> before a lone <i>Reject</i> link. A
     * disabled one is no way to reject.
     */
    private static Optional<WebNode> rejectButton(List<WebNode> buttons) {
        for (Words one : REJECTS) {
            for (WebNode button : buttons) {
                if (one.any(button.name()) && !button.has(WebState.DISABLED)) {
                    return Optional.of(button);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isDialog(WebNode node) {
        return node.role() == Role.DIALOG || node.role() == Role.ALERT_DIALOG;
    }

    private static List<WebNode> descendants(WebTree tree, WebNode node) {
        List<WebNode> all = new ArrayList<>();
        Deque<WebNode> todo = new ArrayDeque<>(tree.children(node));
        while (!todo.isEmpty()) {
            WebNode next = todo.pop();
            all.add(next);
            tree.children(next).reversed().forEach(todo::push);
        }
        return all;
    }
}
