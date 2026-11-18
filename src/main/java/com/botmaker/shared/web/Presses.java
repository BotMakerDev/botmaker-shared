// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * <b>What a discovery never presses</b> (spec §3, never submits): a submit button, a button inside a {@code <form>}
 * whose action is not a {@code #part}, Enter in a field, and a node whose name holds one of {@link #WORDS}. Asked
 * before the press; a refusal says why. A node whose element cannot be read is refused too. {@code Guard}'s
 * {@code Fetch} rule is the last line behind it, for what the list misses.
 */
public final class Presses {

    private Presses() {}

    /** The words that say a press acts on the world: a closed list, here alone. */
    public static final List<String> WORDS = List.of("buy", "pay", "checkout", "check out", "sign up", "signup",
            "subscribe", "send", "delete", "book", "order");

    private static final Words LIST = new Words(WORDS);

    /** The fields Enter would submit from. */
    private static final Set<Role> FIELDS = Set.of(Role.TEXTBOX, Role.SEARCHBOX, Role.COMBOBOX, Role.SPINBUTTON);

    /** Why a press is refused. */
    public enum Reason {
        SUBMIT("submit", "Submits a form"),
        FORM("form", "Inside a form that sends"),
        ENTER("enter", "Enter in a field"),
        WORD("word", "Its name says it acts"),
        UNREAD("unread", "Its element could not be read"),
        UNKNOWN("unknown", "Unknown");

        private final String id;
        private final String displayName;

        Reason(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        /** The reason of {@code id}; {@link #UNKNOWN} for one this version does not know. */
        public static Reason fromId(String id) {
            for (Reason reason : values()) {
                if (reason.id.equals(id)) {
                    return reason;
                }
            }
            return UNKNOWN;
        }
    }

    /** A press not made, and why: the reason and what showed it (the word, the form's action). */
    public record Refusal(Reason reason, String detail) {

        public Refusal {
            detail = detail == null ? "" : detail;
        }

        /** {@code Its name says it acts: subscribe}. */
        public String line() {
            return detail.isEmpty() ? reason.displayName() : reason.displayName() + ": " + detail;
        }
    }

    /**
     * What the page says of a node's element, which its tree does not: read by {@link WebPage#pressFacts}.
     *
     * @param submit     a form's submit control ({@code <button>} of type submit, {@code <input type=submit|image>})
     * @param inForm     inside a {@code <form>}
     * @param formAction that form's {@code action} attribute as written, {@code ""} when it has none
     */
    public record Facts(boolean submit, boolean inForm, String formAction) {

        public Facts {
            formAction = formAction == null ? "" : formAction.strip();
        }

        /**
         * A form that sends: any but one whose action is a {@code #part} or {@code javascript:}. A form with no
         * action sends to its own page.
         */
        public boolean formSends() {
            String action = formAction.toLowerCase(Locale.ROOT);
            return inForm && !action.startsWith("#") && !action.startsWith("javascript:");
        }
    }

    /** Whether pressing {@code node} is refused, given what the page says of it; empty facts are a refusal. */
    public static Optional<Refusal> press(WebNode node, Optional<Facts> read) {
        if (read.isEmpty()) {
            return Optional.of(new Refusal(Reason.UNREAD, node.name()));
        }
        Facts facts = read.get();
        if (facts.submit()) {
            return Optional.of(new Refusal(Reason.SUBMIT, node.name()));
        }
        if (facts.formSends()) {
            return Optional.of(new Refusal(Reason.FORM, facts.formAction()));
        }
        return word(node.name());
    }

    /** Whether pressing {@code key} in {@code node} is refused: Enter in a field. */
    public static Optional<Refusal> key(WebNode node, String key) {
        if ("Enter".equalsIgnoreCase(key) && FIELDS.contains(node.role())) {
            return Optional.of(new Refusal(Reason.ENTER, node.name()));
        }
        return Optional.empty();
    }

    /** Whether a name holds one of {@link #WORDS}. */
    public static Optional<Refusal> word(String name) {
        return LIST.in(name).map(word -> new Refusal(Reason.WORD, word));
    }
}
