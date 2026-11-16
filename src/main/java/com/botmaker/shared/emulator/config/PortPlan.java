// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator.config;

import com.botmaker.shared.emulator.AdbEndpoint;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.PlatformId;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Chooses the ADB port of each instance that shares its address (spec {@code 2026-11-14-end-to-end-instances-design.md}
 * § Addresses). It only chooses: the choice is written as the instance's {@link ConfigField#ADB_PORT} row, and Apply
 * writes it into the product's file like any other setting.
 *
 * <p>BlueStacks' and MSI's instances move; LDPlayer, MEmu and MuMu number their own and keep them, and LDPlayer's
 * numbering ({@code 5555 + 2 × index}) is kept free for its instances to come. A free port is the first from
 * {@link #FIRST} up, two apart (ADB takes the one above too), that no instance found nor any row names.
 */
public final class PortPlan {

    /** Where the choice starts: above LDPlayer's first instances, BlueStacks' own second port. */
    public static final int FIRST = 5565;
    /** The lowest and highest port a row may name: an odd one above the system's, with the one above it free. */
    public static final int LOWEST = 1025;
    public static final int LAST = 65533;
    /** LDPlayer's first port, and how many of its instances to come stay reserved beyond those found. */
    private static final int LDPLAYER_FIRST = 5555;
    private static final int LDPLAYER_SPARE = 2;

    private PortPlan() {}

    /** One instance's port, moved. */
    public record Move(InstanceKey key, String name, int from, int to) {

        /** "Pie64: 5555 → 5565". */
        public String said() {
            return name + ": " + from + " → " + to;
        }
    }

    /** Whether a row may name {@code port}. */
    public static boolean usable(int port) {
        return port >= LOWEST && port <= LAST && port % 2 == 1;
    }

    /**
     * The moves that give each BlueStacks or MSI instance found its own address: one whose port (its row's, else the
     * one found) another instance holds, or LDPlayer's numbering reaches, gets the first free one. The first instance
     * of the product on a port no other product holds keeps it.
     */
    public static List<Move> plan(List<EmulatorInstance> found, EmulatorConfig config) {
        Set<Integer> taken = new HashSet<>(reserved(found));
        List<EmulatorInstance> movable = new ArrayList<>();
        for (EmulatorInstance instance : found) {
            Optional<Integer> port = port(instance);
            if (port.isEmpty()) continue;
            if (moves(instance)) movable.add(instance);
            else taken.add(port.get());
        }
        for (InstanceKey key : config.instances().keySet()) {
            if (found.stream().noneMatch(i -> keyOf(i).filter(key::equals).isPresent())) {
                row(config, key).ifPresent(taken::add);
            }
        }
        List<Move> out = new ArrayList<>();
        for (EmulatorInstance instance : movable) {
            Optional<InstanceKey> key = keyOf(instance);
            if (key.isEmpty()) continue;
            int now = row(config, key.get()).orElse(port(instance).orElseThrow());
            if (!taken.contains(now)) {
                taken.add(now);
                continue;
            }
            int to = free(taken);
            if (to < 0) continue;
            taken.add(to);
            out.add(new Move(key.get(), instance.name(), now, to));
        }
        return out;
    }

    /** {@code config} with each move written as its instance's row. */
    public static EmulatorConfig apply(EmulatorConfig config, List<Move> moves) {
        EmulatorConfig out = config;
        for (Move move : moves) {
            out = out.withInstance(move.key(), out.instance(move.key())
                    .with(ConfigField.ADB_PORT, new ConfigValue.Count(move.to())));
        }
        return out;
    }

    /** The first free port from {@link #FIRST}, two apart, or -1 when none is. */
    static int free(Set<Integer> taken) {
        for (int port = FIRST; port <= LAST; port += 2) {
            if (!taken.contains(port) && !taken.contains(port - 1) && !taken.contains(port + 1)) return port;
        }
        return -1;
    }

    /** LDPlayer's numbering, for the instances it has and a couple more. */
    static Set<Integer> reserved(List<EmulatorInstance> found) {
        long ldPlayers = found.stream().filter(i -> i.platformId() == PlatformId.LDPLAYER).count();
        Set<Integer> out = new HashSet<>();
        if (ldPlayers == 0) return out;
        for (int index = 0; index < ldPlayers + LDPLAYER_SPARE; index++) out.add(LDPLAYER_FIRST + 2 * index);
        return out;
    }

    private static boolean moves(EmulatorInstance instance) {
        return ProductSettings.of(instance.platformId()).applies(ConfigField.ADB_PORT);
    }

    private static Optional<Integer> port(EmulatorInstance instance) {
        return instance.adb() instanceof AdbEndpoint.Tcp tcp && tcp.port() > 0 ? Optional.of(tcp.port())
                : Optional.empty();
    }

    private static Optional<Integer> row(EmulatorConfig config, InstanceKey key) {
        return config.instance(key).fields().get(ConfigField.ADB_PORT) instanceof ConfigValue.Count(int port)
                ? Optional.of(port) : Optional.empty();
    }

    private static Optional<InstanceKey> keyOf(EmulatorInstance instance) {
        try {
            return Optional.of(InstanceKey.of(instance));
        } catch (IllegalArgumentException unnamed) {
            return Optional.empty();
        }
    }
}
