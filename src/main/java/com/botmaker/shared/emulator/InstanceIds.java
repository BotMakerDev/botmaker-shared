// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator;

import com.botmaker.shared.emulator.config.InstanceKey;

import java.util.Optional;

/**
 * Where this PC keeps each instance's BotMaker ID, the one {@link EmulatorIdentity} expects in the instance's ID file:
 * the SDK's values of this PC ({@code Instances.java}, an {@code instance.id} row). A run reads no value of this PC, so
 * it has {@link #NONE}, and checks an instance by its product and the name its ID file says.
 */
public interface InstanceIds {

    /** The ID {@code key}'s instance was given, if it was. */
    Optional<String> expected(InstanceKey key);

    /** The instance that was given {@code id}, if any. */
    Optional<InstanceKey> owner(String id);

    /**
     * Keeps {@code id} as {@code key}'s.
     *
     * @return empty when kept, else why not
     */
    Optional<String> remember(InstanceKey key, String id);

    /** Whether IDs are kept at all; {@link #NONE} keeps none, and a check then writes no ID file. */
    default boolean keeps() {
        return true;
    }

    /** No ID kept: a run, a test, a host with no values of this PC. */
    InstanceIds NONE = new InstanceIds() {
        @Override
        public Optional<String> expected(InstanceKey key) {
            return Optional.empty();
        }

        @Override
        public Optional<InstanceKey> owner(String id) {
            return Optional.empty();
        }

        @Override
        public Optional<String> remember(InstanceKey key, String id) {
            return Optional.of("This process keeps no values of this PC.");
        }

        @Override
        public boolean keeps() {
            return false;
        }
    };
}
