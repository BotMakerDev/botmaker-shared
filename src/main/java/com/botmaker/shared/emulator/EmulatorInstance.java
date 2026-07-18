package com.botmaker.shared.emulator;

/**
 * A single, discovered emulator instance: which product it belongs to, its user-facing name, and the
 * {@code host:port} where its {@code adbd} listens. Discovery ({@link EmulatorPlatform#discover()}) produces
 * these; a consumer turns one into a live {@link AdbDevice} via {@link AdbDevice#connect(String, int)}.
 *
 * @param platformId stable product key, e.g. {@code "bluestacks"} / {@code "ldplayer"}
 * @param name       the instance name shown in the emulator's multi-instance manager
 * @param host       ADB host — always loopback for a local emulator ({@code "127.0.0.1"})
 * @param adbPort    the instance's ADB port
 */
public record EmulatorInstance(String platformId, String name, String host, int adbPort) {

    public String endpoint() {
        return host + ":" + adbPort;
    }
}
