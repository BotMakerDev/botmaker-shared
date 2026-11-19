// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What a form's tries showed (web discovery spec §4): a rule is only what a try showed, with the tries that showed it,
 * never guessed from the HTML alone. {@link #of} reads them off {@link FormProbe}'s tries.
 */
public sealed interface FormRule {

    /** The rule as a person says it: {@code Create account is enabled when Email is a valid email}. */
    String line();

    /** The tries that showed it, the one with every field valid first. */
    List<Try> tries();

    /**
     * {@code input}, the form's submit, is enabled only when every one of {@code needs} holds: with every field valid
     * it was enabled, and with each need broken alone it was not.
     */
    record Enables(String input, List<Need> needs, List<Try> tries) implements FormRule {
        public Enables {
            Objects.requireNonNull(input, "input");
            needs = List.copyOf(needs);
            tries = List.copyOf(tries);
        }

        @Override
        public String line() {
            return input + " is enabled when " + String.join(" and ", needs.stream().map(Need::line).toList());
        }
    }

    /** {@code field} shows {@code message} when {@code need} is broken, and did not with every field valid. */
    record Shows(String field, String message, Need need, List<Try> tries) implements FormRule {
        public Shows {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(need, "need");
            tries = List.copyOf(tries);
        }

        @Override
        public String line() {
            return field + " shows \"" + message + "\" when it " + need.broken();
        }
    }

    /** What one field must hold, named by the field's label. */
    sealed interface Need {
        /** The field's label. */
        String field();

        /** The need held: {@code Email is a valid email}. */
        String line();

        /** The need broken, after "it": {@code holds no @}. */
        String broken();

        /** It holds something. */
        record Filled(String field) implements Need {
            @Override
            public String line() {
                return field + " is filled";
            }

            @Override
            public String broken() {
                return "is empty";
            }
        }

        /** It holds a valid value of {@code kind}, as the browser judges one: an email, a URL. */
        record Valid(String field, FieldKind kind) implements Need {
            @Override
            public String line() {
                return field + " is a valid " + kind.displayName().toLowerCase(Locale.ROOT);
            }

            @Override
            public String broken() {
                return "is not a valid " + kind.displayName().toLowerCase(Locale.ROOT);
            }
        }

        /** It holds {@code text}: an email's {@code @}. */
        record Holds(String field, String text) implements Need {
            @Override
            public String line() {
                return field + " holds " + text;
            }

            @Override
            public String broken() {
                return "holds no " + text;
            }
        }

        /** It has {@code characters} characters or more. */
        record AtLeast(String field, int characters) implements Need {
            @Override
            public String line() {
                return field + " has " + characters + " characters or more";
            }

            @Override
            public String broken() {
                return "has fewer than " + characters + " characters";
            }
        }

        /** A number from {@code min} to {@code max}, either empty when the field gives none. */
        record InRange(String field, String min, String max) implements Need {
            public InRange {
                min = min == null ? "" : min;
                max = max == null ? "" : max;
            }

            @Override
            public String line() {
                if (min.isEmpty()) return field + " is " + max + " or less";
                if (max.isEmpty()) return field + " is " + min + " or more";
                return field + " is from " + min + " to " + max;
            }

            @Override
            public String broken() {
                return "is out of " + (min.isEmpty() ? "…" : min) + "–" + (max.isEmpty() ? "…" : max);
            }
        }
    }

    /**
     * One try: what was typed into each field, by label, in order, and what the page answered. {@code need} is the
     * one need its sample breaks, null for the try with every field valid.
     */
    record Try(Need need, Map<String, String> typed, Answer answer) {
        public Try {
            typed = Collections.unmodifiableMap(new LinkedHashMap<>(typed));
            Objects.requireNonNull(answer, "answer");
        }

        /** The field whose need it breaks, null for the try with every field valid. */
        public String field() {
            return need == null ? null : need.field();
        }

        /** {@code Email="probe@", Password="Probe-pass-1234" -> disabled}. */
        public String line() {
            List<String> parts = new ArrayList<>();
            typed.forEach((field, text) -> parts.add(field + "=\"" + text + "\""));
            return String.join(", ", parts) + " -> " + answer.line();
        }
    }

    /**
     * What the page answered a try: whether the submit was enabled, each field's message (by label, only the fields
     * that showed one), the fields marked invalid and the fields shown.
     */
    record Answer(boolean submitEnabled, Map<String, String> messages, Set<String> invalid, List<String> shown) {
        public Answer {
            messages = Collections.unmodifiableMap(new LinkedHashMap<>(messages));
            invalid = Set.copyOf(invalid);
            shown = List.copyOf(shown);
        }

        public String line() {
            StringBuilder line = new StringBuilder(submitEnabled ? "enabled" : "disabled");
            messages.forEach((field, message) -> line.append(", ").append(field).append(" says \"")
                    .append(message).append('"'));
            return line.toString();
        }
    }

    /**
     * The rules {@code tries} show: the first try with no need is every field valid, and each field's tries come
     * strongest need first (an email's {@code probe@} before its {@code probe.example.com}), so a field's need is the
     * strongest whose break disabled {@code submit}, and a message's the strongest whose break showed it. With no
     * submit, or one disabled even with every field valid, no {@link Enables} is shown.
     */
    static List<FormRule> of(String submit, List<Try> tries) {
        Try valid = tries.stream().filter(t -> t.need() == null).findFirst().orElse(null);
        if (valid == null) return List.of();
        List<FormRule> rules = new ArrayList<>();

        if (submit != null && !submit.isEmpty() && valid.answer().submitEnabled()) {
            List<Need> needs = new ArrayList<>();
            List<Try> showing = new ArrayList<>(List.of(valid));
            Set<String> decided = new HashSet<>();
            for (Try t : tries) {
                if (t.need() == null || t.answer().submitEnabled() || !decided.add(t.field())) continue;
                needs.add(t.need());
                showing.add(t);
            }
            if (!needs.isEmpty()) rules.add(new Enables(submit, needs, showing));
        }

        Map<List<String>, Need> needOf = new LinkedHashMap<>();
        Map<List<String>, List<Try>> showingOf = new LinkedHashMap<>();
        for (Try t : tries) {
            if (t.need() == null) continue;
            String message = t.answer().messages().get(t.field());
            if (message == null || message.isEmpty() || message.equals(valid.answer().messages().get(t.field()))) {
                continue;
            }
            List<String> key = List.of(t.field(), message);
            needOf.putIfAbsent(key, t.need());
            showingOf.computeIfAbsent(key, k -> new ArrayList<>(List.of(valid))).add(t);
        }
        needOf.forEach((key, need) -> rules.add(new Shows(key.get(0), key.get(1), need, showingOf.get(key))));
        return List.copyOf(rules);
    }
}
