// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.ipc;

/**
 * Environment-variable names for the loopback telemetry channel. The Studio (server) binds an ephemeral
 * port and passes these to the launched bot (client) via {@code ProcessBuilder} environment. When
 * {@link #PORT} is absent the bot opens no socket at all — telemetry is fully disabled with zero overhead,
 * so a bot run outside the Studio behaves identically to one with no telemetry code.
 */
public final class IpcEnv {

    /** Port the Studio's {@link TelemetryServer} is listening on (decimal string). */
    public static final String PORT = "BM_IPC_PORT";

    /** Random per-run token the client must send first so the server rejects stray connections. */
    public static final String TOKEN = "BM_IPC_TOKEN";

    private IpcEnv() {}
}
