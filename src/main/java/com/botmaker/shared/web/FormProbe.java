// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * <b>The logic of a page's forms</b> (web discovery spec §4), found by trying them: for each form, or the fields no
 * form holds, it reads each field's kind, {@code required}, {@code pattern}, {@code min}/{@code max},
 * {@code minlength}/{@code maxlength} and label; then types a valid sample into every field, and for each field each
 * of its invalid samples alone (an email without {@code @}, a number out of range, a required field left empty),
 * clearing between tries and leaving each field to make it judge. What the page answers — the submit's
 * {@code disabled}, a field's message and its {@code invalid}, the fields shown — makes the {@link FormRule}s.
 *
 * <p><b>It never submits</b>: it never presses the submit nor types Enter, and types through the {@link Guard}, so a
 * write the page tries anyway is failed. Every field it typed into is left empty.
 *
 * <p>A field's {@code pattern} is read but no sample is made from it: a pattern the valid sample misses leaves the
 * submit disabled with every field valid, and then no {@link FormRule.Enables} is shown.
 */
public final class FormProbe {

    /** How long nothing must happen before a try's answer is read, and how long that waits at most. */
    private static final Duration QUIET = Duration.ofMillis(200);
    private static final Duration SETTLE_MAX = Duration.ofSeconds(3);

    /** The fields of a scope: a form's own, or, for the page's body, those no form holds. */
    private static final String FIELDS = """
            const fields = scope => [...scope.querySelectorAll('input, textarea, select')].filter(el =>
                (scope.tagName === 'FORM' ? el.form === scope : !el.form)
                && !['hidden', 'submit', 'button', 'image', 'reset', 'file'].includes((el.type || '').toLowerCase()));
            const visible = el => !!el && el.getClientRects().length > 0 && getComputedStyle(el).visibility !== 'hidden';
            const words = text => (text || '').replace(/\\s+/g, ' ').trim();
            const submitOf = scope => scope.tagName !== 'FORM' ? null : scope.querySelector(
                'button[type=submit], button:not([type]), input[type=submit], input[type=image]');
            """;

    /** The scopes: each form, then the body when a field outside every form exists. */
    private static final String SCOPES = "(() => {" + FIELDS + """
              const scopes = [...document.forms].filter(f => fields(f).length > 0);
              if (document.body && fields(document.body).length > 0) scopes.push(document.body);
              return scopes;
            })()""";

    /** What a scope holds, by value. */
    private static final String DESCRIBE = "function() {" + FIELDS + """
              const label = el => words((el.labels && el.labels[0] && el.labels[0].textContent)
                  || el.getAttribute('aria-label')
                  || (el.getAttribute('aria-labelledby') || '').split(/\\s+/).filter(Boolean)
                      .map(id => document.getElementById(id)).filter(Boolean).map(l => l.textContent).join(' ')
                  || el.getAttribute('placeholder') || el.name || el.id);
              const submit = submitOf(this);
              return {
                submit: submit ? words(submit.getAttribute('aria-label') || submit.textContent || submit.value
                    || submit.getAttribute('title') || submit.getAttribute('alt')) || 'Submit' : '',
                fields: fields(this).map(el => ({
                  type: el.tagName === 'INPUT' ? (el.getAttribute('type') || 'text').toLowerCase()
                      : el.tagName === 'TEXTAREA' ? 'textarea' : el.type,
                  label: label(el), required: el.required, pattern: el.getAttribute('pattern') || '',
                  min: el.getAttribute('min') || '', max: el.getAttribute('max') || '',
                  minLength: el.minLength === undefined ? -1 : el.minLength,
                  maxLength: el.maxLength === undefined ? -1 : el.maxLength, shown: visible(el)}))
              };
            }""";

    /** The scope's {@code i}th field, as an object; {@code -1} its submit. */
    private static final String ELEMENT = "function(i) {" + FIELDS + "return i < 0 ? submitOf(this) : fields(this)[i]; }";

    /** Empties a field as a person's select-all and delete would, through the value's own setter. */
    private static final String CLEAR = """
            function() {
              const proto = this instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype
                  : this instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
              Object.getOwnPropertyDescriptor(proto, 'value').set.call(this, '');
              this.dispatchEvent(new Event('input', {bubbles: true}));
              this.dispatchEvent(new Event('change', {bubbles: true}));
            }""";

    /** Gives a field the focus and takes it away: what makes a page judge it. */
    private static final String LEAVE = "function() { this.focus(); this.blur(); }";

    /**
     * What a field answers: its message (what its {@code aria-errormessage} or {@code aria-describedby} show, else an
     * alert beside it when it is the only field there), its {@code invalid} and whether it is shown.
     */
    private static final String FIELD_ANSWER = "function() {" + FIELDS + """
              const ids = ((this.getAttribute('aria-errormessage') || '') + ' '
                  + (this.getAttribute('aria-describedby') || '')).split(/\\s+/).filter(Boolean);
              let said = ids.map(id => document.getElementById(id)).filter(visible).map(el => words(el.textContent))
                  .filter(Boolean);
              const parent = this.parentElement;
              if (!said.length && parent && parent.querySelectorAll('input, textarea, select').length === 1) {
                said = [...parent.querySelectorAll('[role=alert]')].filter(visible).map(el => words(el.textContent))
                    .filter(Boolean);
              }
              return {message: [...new Set(said)].join(' '), shown: visible(this),
                      invalid: this.getAttribute('aria-invalid') === 'true' || (this.willValidate && !this.validity.valid)};
            }""";

    private static final String DISABLED = "function() { return this.disabled || this.getAttribute('aria-disabled') === 'true'; }";

    /**
     * One field as the page gave it.
     *
     * @param label     what a person reads beside it, unique in its form
     * @param minLength {@code -1} when the field sets none, as {@code maxLength}
     * @param shown     whether it was shown when read
     * @param backendId its DOM node
     */
    public record Field(String label, FieldKind kind, boolean required, String pattern, String min, String max,
                        int minLength, int maxLength, boolean shown, int backendId) {}

    /**
     * A form, or the fields no form holds.
     *
     * @param submit   its submit's name, {@code ""} when it has none
     * @param submitId the submit's DOM node, {@code 0} when it has none
     */
    public record Form(String submit, int submitId, List<Field> fields) {
        public Form {
            submit = submit == null ? "" : submit;
            fields = List.copyOf(fields);
        }
    }

    /** What trying {@code form} showed: every try, and the rules they show. */
    public record Result(Form form, List<FormRule.Try> tries, List<FormRule> rules) {
        public Result {
            tries = List.copyOf(tries);
            rules = List.copyOf(rules);
        }
    }

    /** One sample: what is typed and the need it breaks, null for a valid one. */
    record Sample(String text, FormRule.Need breaks) {}

    private FormProbe() {
    }

    /** The page's forms, each with a field, then the fields no form holds as one more. */
    public static List<Form> forms(WebPage page) {
        PageCdp session = page.session();
        JsonNode scopes = session.call("Runtime.evaluate", Map.of("expression", SCOPES)).path("result");
        String array = scopes.path("objectId").asText("");
        if (array.isEmpty()) return List.of();
        List<Form> forms = new ArrayList<>();
        try {
            for (JsonNode property : session.call("Runtime.getProperties", Map.of("objectId", array, "ownProperties", true))
                    .path("result")) {
                if (!property.path("name").asText().matches("\\d+")) continue;
                String scope = property.path("value").path("objectId").asText("");
                if (scope.isEmpty()) continue;
                try {
                    forms.add(form(session, scope));
                } finally {
                    session.send("Runtime.releaseObject", Map.of("objectId", scope));
                }
            }
        } finally {
            session.send("Runtime.releaseObject", Map.of("objectId", array));
        }
        return List.copyOf(forms);
    }

    /** Tries {@code form} on {@code page}, typing through {@code guard}, and leaves every field it typed into empty. */
    public static Result probe(Guard guard, WebPage page, Form form) {
        List<Field> tried = form.fields().stream().filter(f -> f.shown() && f.kind().typed()).toList();
        Map<Field, String> valid = new LinkedHashMap<>();
        for (Field field : tried) valid.put(field, valid(field));

        // Armed before the first clear: a page that writes on a field's input, change or blur is failed from the start.
        guard.arm();
        List<FormRule.Try> tries = new ArrayList<>();
        try {
            tries.add(attempt(guard, page, form, valid, null, null));
            for (Field field : tried) {
                for (Sample sample : invalid(field)) {
                    tries.add(attempt(guard, page, form, valid, field, sample));
                }
            }
        } finally {
            for (Field field : tried) clear(page, field.backendId());
        }
        return new Result(form, tries, FormRule.of(form.submit(), tries));
    }

    /** Every field its valid sample, but {@code broken}, which gets {@code sample}; then what the page answers. */
    private static FormRule.Try attempt(Guard guard, WebPage page, Form form, Map<Field, String> valid, Field broken,
                                        Sample sample) {
        Map<String, String> typed = new LinkedHashMap<>();
        for (Map.Entry<Field, String> entry : valid.entrySet()) {
            Field field = entry.getKey();
            String text = field.equals(broken) ? sample.text() : entry.getValue();
            clear(page, field.backendId());
            if (!text.isEmpty()) guard.type(node(field), text);
            call(page, field.backendId(), LEAVE);
            typed.put(field.label(), text);
        }
        page.settled(QUIET, SETTLE_MAX);
        return new FormRule.Try(sample == null ? null : sample.breaks(), typed, answer(page, form));
    }

    private static FormRule.Answer answer(WebPage page, Form form) {
        Map<String, String> messages = new LinkedHashMap<>();
        Set<String> invalid = new HashSet<>();
        List<String> shown = new ArrayList<>();
        for (Field field : form.fields()) {
            JsonNode said = call(page, field.backendId(), FIELD_ANSWER);
            if (!said.path("message").asText("").isEmpty()) messages.put(field.label(), said.path("message").asText());
            if (said.path("invalid").asBoolean(false)) invalid.add(field.label());
            if (said.path("shown").asBoolean(false)) shown.add(field.label());
        }
        boolean enabled = form.submitId() != 0 && !call(page, form.submitId(), DISABLED).asBoolean(true);
        return new FormRule.Answer(enabled, messages, invalid, shown);
    }

    /** A sample the field takes: long enough for its {@code minlength}, short enough for its {@code maxlength}. */
    static String valid(Field field) {
        String text = switch (field.kind()) {
            case EMAIL -> "probe@example.com";
            case PASSWORD -> "Probe-pass-1234";
            case URL -> "https://example.com/";
            case TEL -> "0123456789";
            case NUMBER -> !field.min().isEmpty() ? field.min() : !field.max().isEmpty() ? field.max() : "1";
            default -> "Probe";
        };
        if (field.kind() != FieldKind.NUMBER) {
            while (text.length() < field.minLength()) text = text + "x";
            if (field.maxLength() >= 0 && text.length() > field.maxLength()) text = text.substring(0, field.maxLength());
        }
        return text;
    }

    /** The samples that break one need of {@code field} each, the strongest need first. */
    static List<Sample> invalid(Field field) {
        String label = field.label();
        List<Sample> samples = new ArrayList<>();
        switch (field.kind()) {
            case EMAIL -> {
                samples.add(new Sample("probe@", new FormRule.Need.Valid(label, FieldKind.EMAIL)));
                samples.add(new Sample("probe.example.com", new FormRule.Need.Holds(label, "@")));
            }
            case URL -> samples.add(new Sample("probe", new FormRule.Need.Valid(label, FieldKind.URL)));
            case NUMBER -> {
                FormRule.Need range = new FormRule.Need.InRange(label, field.min(), field.max());
                step(field.min(), -1).ifPresent(s -> samples.add(new Sample(s, range)));
                step(field.max(), 1).ifPresent(s -> samples.add(new Sample(s, range)));
            }
            default -> {
            }
        }
        // A shorter email or URL would break its kind too, and a maxlength under the minlength leaves nothing to cut.
        boolean ownLength = field.kind() != FieldKind.NUMBER && field.kind() != FieldKind.EMAIL
                && field.kind() != FieldKind.URL;
        if (ownLength && field.minLength() > 1 && field.minLength() - 1 <= valid(field).length()) {
            samples.add(new Sample(valid(field).substring(0, field.minLength() - 1),
                    new FormRule.Need.AtLeast(label, field.minLength())));
        }
        if (field.required()) samples.add(new Sample("", new FormRule.Need.Filled(label)));
        return List.copyOf(samples);
    }

    /** {@code bound} moved by {@code by}, empty when there is no bound or it is no number. */
    private static Optional<String> step(String bound, int by) {
        if (bound.isEmpty()) return Optional.empty();
        try {
            return Optional.of(new BigDecimal(bound).add(BigDecimal.valueOf(by)).toPlainString());
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    private static Form form(PageCdp session, String scope) {
        JsonNode described = session.call("Runtime.callFunctionOn", Map.of("objectId", scope, "returnByValue", true,
                "functionDeclaration", DESCRIBE)).path("result").path("value");
        List<Field> fields = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        int i = 0;
        for (JsonNode f : described.path("fields")) {
            String label = unique(f.path("label").asText("").isEmpty() ? "Field " + (i + 1) : f.path("label").asText(),
                    labels);
            fields.add(new Field(label, FieldKind.fromId(f.path("type").asText("")), f.path("required").asBoolean(false),
                    f.path("pattern").asText(""), f.path("min").asText(""), f.path("max").asText(""),
                    f.path("minLength").asInt(-1), f.path("maxLength").asInt(-1), f.path("shown").asBoolean(false),
                    backendId(session, scope, i)));
            i++;
        }
        String submit = described.path("submit").asText("");
        int submitId = submit.isEmpty() ? 0 : backendId(session, scope, -1);
        return new Form(submit, submitId, fields);
    }

    /** The DOM node of {@code scope}'s {@code i}th field ({@code -1} its submit), {@code 0} when there is none. */
    private static int backendId(PageCdp session, String scope, int i) {
        String element = session.call("Runtime.callFunctionOn", Map.of("objectId", scope, "functionDeclaration", ELEMENT,
                "arguments", List.of(Map.of("value", i)))).path("result").path("objectId").asText("");
        if (element.isEmpty()) return 0;
        try {
            return session.call("DOM.describeNode", Map.of("objectId", element)).path("node").path("backendNodeId")
                    .asInt(0);
        } finally {
            session.send("Runtime.releaseObject", Map.of("objectId", element));
        }
    }

    private static String unique(String label, Set<String> taken) {
        String name = label;
        for (int n = 2; !taken.add(name); n++) name = label + " " + n;
        return name;
    }

    private static void clear(WebPage page, int backendId) {
        call(page, backendId, CLEAR);
    }

    /** {@code function} called on the element {@code backendId}, by value; a node gone or detached answers null. */
    private static JsonNode call(WebPage page, int backendId, String function) {
        PageCdp session = page.session();
        String object;
        try {
            object = session.call("DOM.resolveNode", Map.of("backendNodeId", backendId)).path("object")
                    .path("objectId").asText("");
        } catch (PageCdp.CdpException goneOrDetached) {
            return NullNode.getInstance();
        }
        if (object.isEmpty()) return NullNode.getInstance();
        try {
            JsonNode answer = session.call("Runtime.callFunctionOn", Map.of("objectId", object, "returnByValue", true,
                    "functionDeclaration", function));
            return answer.has("exceptionDetails") ? NullNode.getInstance() : answer.path("result").path("value");
        } catch (PageCdp.CdpException goneOrDetached) {
            return NullNode.getInstance();
        } finally {
            session.send("Runtime.releaseObject", Map.of("objectId", object));
        }
    }

    /** The node {@link Guard#type} focuses: only its DOM node counts. */
    private static WebNode node(Field field) {
        return new WebNode(0, -1, List.of(), Role.UNKNOWN, field.label(), "", Set.of(), "", "", "", null,
                field.backendId());
    }
}
