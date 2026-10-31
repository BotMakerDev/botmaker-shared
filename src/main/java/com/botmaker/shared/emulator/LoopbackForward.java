package com.botmaker.shared.emulator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A port on this computer's loopback that reaches a TCP port on the device, one ADB {@code tcp:} stream per
 * connection: what {@code adb forward} does, with no adb server.
 *
 * <p><b>Loopback only.</b> dadb's own {@code tcpForward} listens on every interface, which would hand whatever the
 * device serves there (an automation server that taps the screen) to anyone on the network.
 */
public final class LoopbackForward implements AutoCloseable {

    /** Opens a {@code tcp:<port>} stream to the device. */
    interface Opener {
        DeviceStream open() throws IOException;
    }

    private final ServerSocket server;
    private final Opener opener;
    private final Set<AutoCloseable> open = ConcurrentHashMap.newKeySet();

    LoopbackForward(Opener opener) throws IOException {
        this.server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        this.opener = opener;
        Thread.ofVirtual().name("adb-forward-" + port()).start(this::accept);
    }

    /** The loopback port to connect to. */
    public int port() {
        return server.getLocalPort();
    }

    private void accept() {
        while (!server.isClosed()) {
            Socket client;
            try {
                client = server.accept();
                // A request written in two parts (headers, then body) waits 40 ms for an ACK otherwise.
                client.setTcpNoDelay(true);
            } catch (IOException e) {
                // Closed ends the loop; anything else (too many open files) is one lost connection, and a pause.
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    return;
                }
                continue;
            }
            // A slow device open must not hold up the next connection.
            Thread.ofVirtual().start(() -> connect(client));
        }
    }

    private void connect(Socket client) {
        track(client);
        DeviceStream device;
        try {
            device = opener.open();
        } catch (IOException | RuntimeException e) {
            closeQuietly(client);
            open.remove(client);
            return;
        }
        track(device);
        Runnable done = () -> {
            closeQuietly(client);
            device.close();
            open.remove(client);
            open.remove(device);
        };
        Thread.ofVirtual().start(() -> pipe(client, device, done, true));
        pipe(client, device, done, false);
    }

    /** Remembers a connection's end for {@link #close}; one that comes after the close is closed at once. */
    private void track(AutoCloseable end) {
        open.add(end);
        if (server.isClosed()) {
            closeQuietly(end);
            open.remove(end);
        }
    }

    /**
     * Copies one direction until either end closes, then closes both: a half-close is not carried, so a client
     * must not shut its output down while it waits for the answer (an HTTP client never does).
     */
    private static void pipe(Socket client, DeviceStream device, Runnable done, boolean toDevice) {
        byte[] buffer = new byte[64 * 1024];
        try {
            InputStream in = toDevice ? client.getInputStream() : device.in();
            OutputStream out = toDevice ? device.out() : client.getOutputStream();
            for (int n; (n = in.read(buffer)) >= 0; ) {
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (IOException | RuntimeException ignored) {
            // either end went away
        } finally {
            done.run();
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // best effort
        }
    }

    /** Stops listening and closes every connection it carries. */
    @Override
    public void close() {
        closeQuietly(server);
        for (AutoCloseable each : open) closeQuietly(each);
        open.clear();
    }
}
