// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The rules a form's tries show, and the samples a field gets: no browser. */
class FormRuleTest {

    private static final FormRule.Need VALID_EMAIL = new FormRule.Need.Valid("Email", FieldKind.EMAIL);
    private static final FormRule.Need EMAIL_AT = new FormRule.Need.Holds("Email", "@");
    private static final FormRule.Need PASSWORD_8 = new FormRule.Need.AtLeast("Password", 8);
    private static final FormRule.Need PASSWORD_FILLED = new FormRule.Need.Filled("Password");

    @Test
    void theStrongestBrokenNeedOfEachFieldEnablesTheSubmitAndAMessageNamesTheNeedThatShowedIt() {
        FormRule.Try valid = attempt(null, true, Map.of());
        List<FormRule.Try> tries = List.of(valid,
                attempt(VALID_EMAIL, false, Map.of()),
                attempt(EMAIL_AT, false, Map.of("Email", "Enter a valid email")),
                attempt(PASSWORD_8, false, Map.of()),
                attempt(PASSWORD_FILLED, false, Map.of()));

        List<FormRule> rules = FormRule.of("Create account", tries);

        assertEquals(List.of("Create account is enabled when Email is a valid email and Password has 8 characters or "
                        + "more", "Email shows \"Enter a valid email\" when it holds no @"),
                rules.stream().map(FormRule::line).toList());
        assertEquals(List.of(valid, tries.get(1), tries.get(3)), rules.getFirst().tries(), "the tries that showed it");
    }

    @Test
    void aSubmitDisabledEvenWithEveryFieldValidShowsNoEnablingRuleAndABreakThatChangesNothingIsNoNeed() {
        assertEquals(List.of(), FormRule.of("Go", List.of(attempt(null, false, Map.of()),
                attempt(PASSWORD_8, false, Map.of()))));
        assertEquals(List.of(), FormRule.of("Go", List.of(attempt(null, true, Map.of()),
                attempt(PASSWORD_8, true, Map.of()))));
        assertEquals(List.of(), FormRule.of("", List.of(attempt(null, true, Map.of()),
                attempt(PASSWORD_8, false, Map.of()))), "no submit, nothing enabled");
    }

    @Test
    void aFieldGetsAValidSampleItsLengthsAllowAndOneInvalidSamplePerNeedStrongestFirst() {
        FormProbe.Field email = field("Email", FieldKind.EMAIL, true, "", "", -1, -1);
        FormProbe.Field password = field("Password", FieldKind.PASSWORD, true, "", "", 20, 24);
        FormProbe.Field age = field("Age", FieldKind.NUMBER, false, "18", "120", -1, -1);

        assertEquals("probe@example.com", FormProbe.valid(email));
        assertEquals(List.of(VALID_EMAIL, EMAIL_AT, new FormRule.Need.Filled("Email")),
                FormProbe.invalid(email).stream().map(FormProbe.Sample::breaks).toList());
        assertEquals(20, FormProbe.valid(password).length());
        assertEquals(19, FormProbe.invalid(password).getFirst().text().length());
        assertEquals("18", FormProbe.valid(age));
        assertEquals(List.of("17", "121"), FormProbe.invalid(age).stream().map(FormProbe.Sample::text).toList());
        assertEquals("Age is from 18 to 120", FormProbe.invalid(age).getFirst().breaks().line());

        FormProbe.Field longEmail = field("Email", FieldKind.EMAIL, false, "", "", 30, -1);
        FormProbe.Field odd = field("Code", FieldKind.TEXT, false, "", "", 10, 5);
        assertEquals(List.of(VALID_EMAIL, EMAIL_AT), FormProbe.invalid(longEmail).stream()
                .map(FormProbe.Sample::breaks).toList(), "a shorter email would break its kind too");
        assertEquals(List.of(), FormProbe.invalid(odd), "a maxlength under the minlength leaves nothing to cut");
    }

    private static FormRule.Try attempt(FormRule.Need need, boolean enabled, Map<String, String> messages) {
        return new FormRule.Try(need, Map.of(), new FormRule.Answer(enabled, messages, Set.of(), List.of()));
    }

    private static FormProbe.Field field(String label, FieldKind kind, boolean required, String min, String max,
                                         int minLength, int maxLength) {
        return new FormProbe.Field(label, kind, required, "", min, max, minLength, maxLength, true, 1);
    }
}
