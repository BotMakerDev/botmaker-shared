package com.botmaker.shared.emulator;

import java.util.List;

/**
 * Where a phone stands with this computer, as a picker says it: what the adb server reports for its serial, or, for
 * a saved address the server doesn't own, whether it answers. One type so every picker words a phone alike
 * (2026-10-19: a plugged-in phone that showed nothing, because its "Allow USB debugging?" prompt was waiting).
 */
public enum PhoneState {
    /** The server drives it: its apps and screen can be read. */
    ONLINE("online", "Phone · online", ""),
    /** Plugged in, but its "Allow USB debugging?" prompt hasn't been accepted. */
    UNAUTHORIZED("unauthorized", "Allow USB debugging on the phone",
            "Accept \"Allow USB debugging?\" on the phone: its apps and screen are read once it's allowed."),
    /** The server knows it but can't reach it: a cable unplugged mid-session, or the phone asleep on the network. */
    OFFLINE("offline", "Phone · offline",
            "Plug the cable in again or wake the phone: its apps are read once it's back."),
    /** A saved address nothing answers at: Wireless debugging off, or the phone elsewhere. */
    NOT_CONNECTED("not-connected", "Phone · not connected",
            "Turn Wireless debugging on, on the phone, or connect it again with Connect a phone…"),
    /** Any other word of the server's: {@code no permissions} (a Linux udev rule), {@code authorizing}, … */
    UNKNOWN("unknown", "Phone · not ready",
            "adb can't use the phone yet: Connect a phone… shows what the adb server says about it.");

    private final String id;
    private final String displayName;
    private final String advice;

    PhoneState(String id, String displayName, String advice) {
        this.id = id;
        this.displayName = displayName;
        this.advice = advice;
    }

    /** What to do so its apps can be read; empty when online. */
    public String advice() {
        return advice;
    }

    public String id() {
        return id;
    }

    /** The picker's line under the phone's name. */
    public String displayName() {
        return displayName;
    }

    public static PhoneState fromId(String id) {
        for (PhoneState state : values()) {
            if (state.id.equals(id)) return state;
        }
        return UNKNOWN;
    }

    /** Whether its apps and screen can be read now. */
    public boolean online() {
        return this == ONLINE;
    }

    /**
     * {@code phone}'s state among the server's {@code devices}; a phone the server doesn't own is {@link #ONLINE}
     * when {@code reachable} (a legacy {@code adb tcpip} address dialled directly), else {@link #NOT_CONNECTED}.
     * Pure, for the tests.
     */
    public static PhoneState of(EmulatorInstance phone, List<AdbTools.ServerDevice> devices, boolean reachable) {
        for (AdbTools.ServerDevice device : devices) {
            if (!device.serial().equals(phone.reference())) continue;
            if (device.online()) return ONLINE;
            return switch (device.state()) {
                case "unauthorized" -> UNAUTHORIZED;
                case "offline" -> OFFLINE;
                default -> UNKNOWN;
            };
        }
        return reachable ? ONLINE : NOT_CONNECTED;
    }

    /** {@link #of} with the server asked now. Blocking: one local socket, plus a dial when the server lacks it. */
    public static PhoneState of(EmulatorInstance phone) {
        List<AdbTools.ServerDevice> devices = AdbTools.devices();
        boolean owned = devices.stream().anyMatch(d -> d.serial().equals(phone.reference()));
        return of(phone, devices, !owned && phone.reachable());
    }
}
