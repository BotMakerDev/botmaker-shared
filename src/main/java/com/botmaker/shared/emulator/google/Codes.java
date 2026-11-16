// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.config.AccountRecord.Verification;

/**
 * Asks the user for a second step's code: a texted or authenticator code, or one of the account's backup codes (spec
 * {@code 2026-11-14-google-signin-design.md}). The window's dialog answers it; the sign-in types the code by ADB and
 * clears it. A code is never stored, logged or shown: a step says its length only.
 */
@FunctionalInterface
public interface Codes {

    /** No dialog: every code is handed to the user, on the instance's screen. */
    Codes NONE = (email, method, attempt) -> null;

    /**
     * The code the user typed for {@code email}'s sign-in, or null when they would rather answer on the instance's
     * screen. The sign-in clears the array.
     *
     * @param method  {@link Verification#CODE} or {@link Verification#BACKUP_CODE}
     * @param attempt which try this is, from 1 to {@link InstanceSignIn#CODE_TRIES}
     */
    char[] ask(String email, Verification method, int attempt) throws InterruptedException;
}
