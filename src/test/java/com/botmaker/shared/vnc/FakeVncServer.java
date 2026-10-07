package com.botmaker.shared.vnc;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One VNC server connection, played from the server's side of RFC 6143: the greeting, the security a test
 * asks for, a screen of the given size, then every client message recorded as its raw bytes. A test pushes
 * screen updates with {@link #raw}, {@link #copyRect} and {@link #resize}.
 */
final class FakeVncServer implements AutoCloseable {

    static final int SECURITY_NONE = 1;
    static final int SECURITY_VNC = 2;
    static final int ENCODING_RAW = 0;
    static final int ENCODING_COPY_RECT = 1;
    static final int ENCODING_DESKTOP_SIZE = -223;

    final ServerSocket listener;
    /** The client's SetPixelFormat, once it has sent one: how {@link #raw} must encode a pixel. */
    private volatile byte[] pixelFormat;
    private final String version;
    private final int security;
    private final String password;
    private final int width;
    private final int height;
    private final BlockingQueue<byte[]> messages = new LinkedBlockingQueue<>();
    /** Whether the first full-screen request gets a (black) screen back; off to play a server that never paints. */
    volatile boolean sendsFirstFrame = true;
    private volatile DataOutputStream out;
    private volatile Socket socket;
    volatile String failure;

    FakeVncServer(String version, int security, String password, int width, int height) throws IOException {
        this.listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        this.version = version;
        this.security = security;
        this.password = password;
        this.width = width;
        this.height = height;
        Thread.ofPlatform().daemon().start(this::serve);
    }

    int port() {
        return listener.getLocalPort();
    }

    private void serve() {
        try (Socket s = listener.accept()) {
            socket = s;
            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream o = new DataOutputStream(s.getOutputStream());
            o.write(version.getBytes(StandardCharsets.US_ASCII));
            byte[] reply = new byte[12];
            in.readFully(reply);
            boolean v33 = new String(reply, StandardCharsets.US_ASCII).equals("RFB 003.003\n");
            if (v33) {
                o.writeInt(security);
            } else {
                o.writeByte(1);
                o.writeByte(security);
                if (in.readUnsignedByte() != security) failure = "client chose another security type";
            }
            if (security == SECURITY_VNC) {
                byte[] challenge = new byte[16];
                Arrays.fill(challenge, (byte) 0x5A);
                challenge[3] = 1;
                o.write(challenge);
                byte[] response = new byte[16];
                in.readFully(response);
                boolean ok = Arrays.equals(response, expected(challenge, password));
                o.writeInt(ok ? 0 : 1);
                if (!ok) {
                    byte[] reason = "Authentication failed".getBytes(StandardCharsets.US_ASCII);
                    o.writeInt(reason.length);
                    o.write(reason);
                    return;
                }
            } else if (!v33 && new String(reply, StandardCharsets.US_ASCII).equals("RFB 003.008\n")) {
                o.writeInt(0);
            }
            in.readUnsignedByte(); // ClientInit
            o.writeShort(width);
            o.writeShort(height);
            o.write(new byte[16]);
            byte[] name = "fake guest".getBytes(StandardCharsets.UTF_8);
            o.writeInt(name.length);
            o.write(name);
            o.flush();
            out = o;
            boolean answered = false;
            while (true) {
                byte[] message = readMessage(in);
                if (message[0] == 0) pixelFormat = message;
                // A real server answers the first full-screen request with the whole screen.
                if (message[0] == 3 && message[1] == 0 && !answered && sendsFirstFrame) {
                    answered = true;
                    raw(0, 0, width, height, new int[width * height]);
                }
                messages.add(message);
            }
        } catch (IOException e) {
            // the client hung up, or the test closed the server
        }
    }

    /** VNC Authentication as a server checks it, with the key's bits reversed byte by byte. */
    static byte[] expected(byte[] challenge, String password) {
        byte[] key = new byte[8];
        byte[] typed = password.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i < 8 && i < typed.length; i++) {
            int b = typed[i] & 0xFF;
            int reversed = 0;
            for (int bit = 0; bit < 8; bit++) {
                if ((b & (1 << bit)) != 0) reversed |= 1 << (7 - bit);
            }
            key[i] = (byte) reversed;
        }
        try {
            Cipher des = Cipher.getInstance("DES/ECB/NoPadding");
            des.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "DES"));
            return des.doFinal(challenge);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] readMessage(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        int length = switch (type) {
            case 0 -> 19;
            case 2 -> -1;
            case 3 -> 9;
            case 4 -> 7;
            case 5 -> 5;
            case 6 -> -2;
            default -> throw new IOException("unexpected client message " + type);
        };
        if (length == -2) { // ClientCutText: 3 padding bytes, a length, the text
            in.readFully(new byte[3]);
            in.readFully(new byte[in.readInt()]);
            return new byte[]{6};
        }
        if (length < 0) {
            in.readUnsignedByte();
            int count = in.readUnsignedShort();
            byte[] body = new byte[4 * count];
            in.readFully(body);
            byte[] message = new byte[4 + body.length];
            message[0] = 2;
            message[2] = (byte) (count >> 8);
            message[3] = (byte) count;
            System.arraycopy(body, 0, message, 4, body.length);
            return message;
        }
        byte[] message = new byte[1 + length];
        message[0] = (byte) type;
        in.readFully(message, 1, length);
        return message;
    }

    /** Every pointer and key message received from now until {@code count} have arrived. */
    List<byte[]> inputs(int count) throws InterruptedException {
        List<byte[]> found = new ArrayList<>();
        while (found.size() < count) {
            byte[] message = messages.poll(5, TimeUnit.SECONDS);
            if (message == null) throw new AssertionError("only " + found.size() + " of " + count + " inputs");
            if (message[0] == 4 || message[0] == 5) found.add(message);
        }
        return found;
    }

    /**
     * A Raw rectangle of {@code rgb} pixels (0xRRGGBB), encoded in the 32-bit true-colour format the client
     * asked for: its byte order and its red, green and blue shifts.
     */
    synchronized void raw(int x, int y, int w, int h, int[] rgb) throws IOException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (pixelFormat == null && System.currentTimeMillis() < deadline) Thread.onSpinWait();
        byte[] format = pixelFormat;
        if (format == null || (format[4] & 0xFF) != 32) throw new IOException("the client asked for no 32-bit format");
        boolean bigEndian = format[6] != 0;
        int redShift = format[14] & 0xFF;
        int greenShift = format[15] & 0xFF;
        int blueShift = format[16] & 0xFF;
        header(1);
        rect(x, y, w, h, ENCODING_RAW);
        for (int pixel : rgb) {
            int value = (pixel >> 16 & 0xFF) << redShift | (pixel >> 8 & 0xFF) << greenShift
                    | (pixel & 0xFF) << blueShift;
            if (bigEndian) out.writeInt(value);
            else out.writeInt(Integer.reverseBytes(value));
        }
        out.flush();
    }

    synchronized void copyRect(int x, int y, int w, int h, int srcX, int srcY) throws IOException {
        header(1);
        rect(x, y, w, h, ENCODING_COPY_RECT);
        out.writeShort(srcX);
        out.writeShort(srcY);
        out.flush();
    }

    synchronized void resize(int w, int h) throws IOException {
        header(1);
        rect(0, 0, w, h, ENCODING_DESKTOP_SIZE);
        out.flush();
    }

    private void header(int rects) throws IOException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (out == null && System.currentTimeMillis() < deadline) Thread.onSpinWait();
        out.writeByte(0);
        out.writeByte(0);
        out.writeShort(rects);
    }

    private void rect(int x, int y, int w, int h, int encoding) throws IOException {
        out.writeShort(x);
        out.writeShort(y);
        out.writeShort(w);
        out.writeShort(h);
        out.writeInt(encoding);
    }

    /** Hangs up, as a VM shutting down would. */
    void hangUp() throws IOException {
        if (socket != null) socket.close();
    }

    @Override
    public void close() throws IOException {
        hangUp();
        listener.close();
    }
}
