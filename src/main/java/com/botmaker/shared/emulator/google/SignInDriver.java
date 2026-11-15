package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.config.Operation;

import java.util.Objects;

/**
 * Who signs one Google account in on a running instance, for {@link InstanceChanges}: the batch keeps the queue, the
 * pacing, the passwords and what an end means for them, the driver only drives the instance's screens.
 * {@link #SCREENS} is BotMaker's own reading of Google's pages ({@link InstanceSignIn}).
 */
public interface SignInDriver {

    /**
     * How one sign-in ended.
     *
     * @param passwordAccepted whether Google took the password on the way, signed in or not: a password it took is
     *                         worth keeping even when the step after it failed
     */
    record Attempt(InstanceSignIn.Result result, boolean passwordAccepted) {
        public Attempt {
            Objects.requireNonNull(result, "result");
        }
    }

    /**
     * Signs {@code device}, the running {@code instance}, in to {@code email}. Blocking, until it ends or
     * {@code context} is cancelled.
     *
     * @param password the batch's, which the driver neither keeps nor clears
     * @param second   the account's second step, and the prompt its codes are asked by
     */
    Attempt signIn(EmulatorInstance instance, AccountDevice device, String email, char[] password,
                   InstanceSignIn.SecondStep second, Operation.Context context) throws Exception;

    /** BotMaker's own reading of Google's pages, in this process. */
    SignInDriver SCREENS = InstanceSignIn.driver(Pace.REAL);
}
