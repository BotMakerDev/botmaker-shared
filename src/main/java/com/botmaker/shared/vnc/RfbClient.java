package com.botmaker.shared.vnc;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * The client side of RFB (RFC 6143), as much of it as a bot's screen needs: versions 3.3 to 3.8, security None
 * or VNC Authentication, Raw and CopyRect pictures, the DesktopSize and Cursor pseudo-encodings, pointer and
 * key events.
 *
 * <p>Pixels are decoded in the format the server <em>sends</em>. When the server announces 32-bit true colour,
 * that format is kept and none is asked for; otherwise the client asks for {@link #ASKED} and decodes that.
 * VMware's server ignores SetPixelFormat and sends its own format whatever the client asked: a client that
 * decodes as asked shows white as magenta there (live, with the library this class replaced).
 *
 * <p>The Cursor pseudo-encoding is asked for and its shapes dropped: the server then leaves the pointer out
 * of the frames, so a capture holds no cursor for a picture search to trip on.
 */
final class RfbClient implements AutoCloseable {

    /** What the client asks for when the server's own format is not 32-bit true colour. */
    static final PixelFormat ASKED = new PixelFormat(32, 24, false, true, 255, 255, 255, 16, 8, 0);

    private static final int ENCODING_RAW = 0;
    private static final int ENCODING_COPY_RECT = 1;
    private static final int ENCODING_DESKTOP_SIZE = -223;
    private static final int ENCODING_CURSOR = -239;
    private static final int SECURITY_NONE = 1;
    private static final int SECURITY_VNC = 2;

    /** How pixels are laid out on the wire, as RFB's PIXEL_FORMAT says. */
    record PixelFormat(int bitsPerPixel, int depth, boolean bigEndian, boolean trueColour,
                       int redMax, int greenMax, int blueMax, int redShift, int greenShift, int blueShift) {

        static PixelFormat read(DataInputStream in) throws IOException {
            PixelFormat format = new PixelFormat(in.readUnsignedByte(), in.readUnsignedByte(), in.readUnsignedByte() != 0,
                    in.readUnsignedByte() != 0, in.readUnsignedShort(), in.readUnsignedShort(), in.readUnsignedShort(),
                    in.readUnsignedByte(), in.readUnsignedByte(), in.readUnsignedByte());
            in.readFully(new byte[3]);
            return format;
        }

        void write(DataOutputStream out) throws IOException {
            out.writeByte(bitsPerPixel);
            out.writeByte(depth);
            out.writeByte(bigEndian ? 1 : 0);
            out.writeByte(trueColour ? 1 : 0);
            out.writeShort(redMax);
            out.writeShort(greenMax);
            out.writeShort(blueMax);
            out.writeByte(redShift);
            out.writeByte(greenShift);
            out.writeByte(blueShift);
            out.write(new byte[3]);
        }

        /** 32 bits per pixel, true colour, eight bits a channel: what this class decodes without asking. */
        boolean decodable() {
            return bitsPerPixel == 32 && trueColour && redMax == 255 && greenMax == 255 && blueMax == 255
                    && redShift <= 24 && greenShift <= 24 && blueShift <= 24;
        }

        int bytesPerPixel() {
            return bitsPerPixel / 8;
        }

        /** The pixel at {@code at} in {@code bytes}, as {@code 0xRRGGBB}. */
        int rgb(byte[] bytes, int at) {
            int value = bigEndian
                    ? (bytes[at] & 0xFF) << 24 | (bytes[at + 1] & 0xFF) << 16 | (bytes[at + 2] & 0xFF) << 8 | bytes[at + 3] & 0xFF
                    : (bytes[at + 3] & 0xFF) << 24 | (bytes[at + 2] & 0xFF) << 16 | (bytes[at + 1] & 0xFF) << 8 | bytes[at] & 0xFF;
            return (value >>> redShift & 0xFF) << 16 | (value >>> greenShift & 0xFF) << 8 | value >>> blueShift & 0xFF;
        }
    }

    /** What the reader thread tells its owner: each finished update, and the one failure that ends it. */
    interface Listener {
        /** A copy of the whole screen after an update. */
        void frame(BufferedImage screen);

        /** The connection ended; {@code why} is a sentence a UI can show. */
        void failed(String why);
    }

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final PixelFormat format;
    private final String name;
    private BufferedImage screen;
    private volatile boolean running = true;

    private RfbClient(Socket socket, DataInputStream in, DataOutputStream out, PixelFormat format, String name,
                      int width, int height) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.format = format;
        this.name = name;
        this.screen = new BufferedImage(Math.max(width, 1), Math.max(height, 1), BufferedImage.TYPE_INT_RGB);
    }

    /**
     * Greets the server on {@code socket}, authenticates with {@code password} ({@code null} for none), shares
     * the screen with other viewers and says which encodings it reads. Throws with a sentence a UI can show.
     * Blocks while the server is silent: the caller bounds it.
     */
    static RfbClient handshake(Socket socket, String password) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        byte[] greeting = new byte[12];
        in.readFully(greeting);
        String said = new String(greeting, StandardCharsets.US_ASCII);
        if (!said.matches("RFB \\d{3}\\.\\d{3}\n")) throw new IOException("This isn't a VNC server: it said " + said.strip());
        int minor = Integer.parseInt(said.substring(8, 11));
        int major = Integer.parseInt(said.substring(4, 7));
        int version = major > 3 || minor >= 8 ? 8 : minor == 7 ? 7 : 3;
        out.write(("RFB 003.00" + version + "\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();

        int security;
        if (version == 3) {
            security = in.readInt();
            if (security == 0) throw new IOException("VNC: " + reason(in));
        } else {
            int count = in.readUnsignedByte();
            if (count == 0) throw new IOException("VNC: " + reason(in));
            byte[] offered = new byte[count];
            in.readFully(offered);
            security = choose(offered, password);
            out.writeByte(security);
            out.flush();
        }
        if (security == SECURITY_VNC) {
            if (password == null) throw new IOException("VNC asks for a password and none was given.");
            byte[] challenge = new byte[16];
            in.readFully(challenge);
            out.write(respond(challenge, password));
            out.flush();
        } else if (security != SECURITY_NONE) {
            throw new IOException("VNC offers no security this client speaks (type " + security + ").");
        }
        if (security == SECURITY_VNC || version == 8) {
            if (in.readInt() != 0) {
                if (version == 8) reason(in);
                throw new IOException(security == SECURITY_VNC ? "VNC refused the password." : "VNC refused the connection.");
            }
        }

        out.writeByte(1); // shared: the hypervisor's own viewer may stay connected
        out.flush();
        int width = in.readUnsignedShort();
        int height = in.readUnsignedShort();
        PixelFormat announced = PixelFormat.read(in);
        byte[] name = new byte[in.readInt()];
        in.readFully(name);

        PixelFormat format = announced.decodable() ? announced : ASKED;
        if (format == ASKED) {
            out.writeByte(0); // SetPixelFormat
            out.write(new byte[3]);
            ASKED.write(out);
        }
        int[] encodings = {ENCODING_COPY_RECT, ENCODING_RAW, ENCODING_DESKTOP_SIZE, ENCODING_CURSOR};
        out.writeByte(2); // SetEncodings
        out.writeByte(0);
        out.writeShort(encodings.length);
        for (int encoding : encodings) out.writeInt(encoding);
        out.flush();
        return new RfbClient(socket, in, out, format, new String(name, StandardCharsets.UTF_8), width, height);
    }

    private static int choose(byte[] offered, String password) throws IOException {
        boolean none = false;
        boolean vnc = false;
        for (byte type : offered) {
            none |= type == SECURITY_NONE;
            vnc |= type == SECURITY_VNC;
        }
        if (vnc && (password != null || !none)) return SECURITY_VNC;
        if (none) return SECURITY_NONE;
        throw new IOException("VNC offers no security this client speaks.");
    }

    private static String reason(DataInputStream in) throws IOException {
        byte[] text = new byte[in.readInt()];
        in.readFully(text);
        return new String(text, StandardCharsets.UTF_8);
    }

    /** VNC Authentication: the challenge encrypted with DES under the password, each key byte's bits reversed. */
    static byte[] respond(byte[] challenge, String password) throws IOException {
        byte[] key = new byte[8];
        byte[] typed = password.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i < key.length && i < typed.length; i++) key[i] = (byte) (Integer.reverse(typed[i] & 0xFF) >>> 24);
        try {
            Cipher des = Cipher.getInstance("DES/ECB/NoPadding");
            des.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "DES"));
            return des.doFinal(challenge);
        } catch (GeneralSecurityException e) {
            throw new IOException("This Java has no DES for VNC's password check.", e);
        }
    }

    /** The format pixels are decoded in. */
    PixelFormat format() {
        return format;
    }

    String name() {
        return name;
    }

    /**
     * Reads updates on a daemon thread until the connection ends, asking for at most {@code framesPerSecond}:
     * the first request is for the whole screen, the next ones for what changed.
     */
    void start(int framesPerSecond, Listener listener) {
        long spacingNs = 1_000_000_000L / Math.max(1, framesPerSecond);
        Thread.ofPlatform().daemon().name("vnc-reader-" + socket.getPort()).start(() -> {
            try {
                request(false);
                while (running) {
                    long asked = System.nanoTime();
                    if (read()) {
                        listener.frame(copy(screen));
                        long wait = spacingNs - (System.nanoTime() - asked);
                        if (wait > 0) Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                        request(true);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // RuntimeException: a rectangle outside the screen, a negative length — the stream is lost either way
                if (running) listener.failed(ended(e));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running = false;
            }
        });
    }

    /** One server message; {@code true} when it was a screen update. */
    private boolean read() throws IOException {
        int type = in.readUnsignedByte();
        switch (type) {
            case 0 -> {
                in.readUnsignedByte();
                int rects = in.readUnsignedShort();
                for (int i = 0; i < rects; i++) rect();
                return true;
            }
            case 1 -> { // SetColourMapEntries: true colour only, nothing to keep
                in.readUnsignedByte();
                in.readUnsignedShort();
                in.readFully(new byte[6 * in.readUnsignedShort()]);
            }
            case 2 -> { } // Bell
            case 3 -> { // ServerCutText: the guest's clipboard stays there
                in.readFully(new byte[3]);
                in.readFully(new byte[in.readInt()]);
            }
            default -> throw new IOException("the server sent message type " + type + ", which RFB doesn't have");
        }
        return false;
    }

    private void rect() throws IOException {
        int x = in.readUnsignedShort();
        int y = in.readUnsignedShort();
        int w = in.readUnsignedShort();
        int h = in.readUnsignedShort();
        int encoding = in.readInt();
        switch (encoding) {
            case ENCODING_RAW -> {
                int bpp = format.bytesPerPixel();
                byte[] row = new byte[w * bpp];
                int[] rgb = new int[w];
                for (int line = 0; line < h; line++) {
                    in.readFully(row);
                    for (int i = 0; i < w; i++) rgb[i] = format.rgb(row, i * bpp);
                    if (y + line < screen.getHeight()) {
                        int fits = Math.min(w, screen.getWidth() - x);
                        if (fits > 0) screen.setRGB(x, y + line, fits, 1, rgb, 0, w);
                    }
                }
            }
            case ENCODING_COPY_RECT -> {
                int fromX = in.readUnsignedShort();
                int fromY = in.readUnsignedShort();
                // a copy first: the source and the destination may overlap
                screen.getRaster().setRect(x - fromX, y - fromY, screen.getData(new Rectangle(fromX, fromY, w, h)));
            }
            case ENCODING_DESKTOP_SIZE -> {
                BufferedImage resized = new BufferedImage(Math.max(w, 1), Math.max(h, 1), BufferedImage.TYPE_INT_RGB);
                resized.getRaster().setRect(screen.getRaster());
                screen = resized;
            }
            case ENCODING_CURSOR -> in.readFully(new byte[w * h * format.bytesPerPixel() + (w + 7) / 8 * h]);
            default -> throw new IOException("the server sent encoding " + encoding + ", which wasn't asked for");
        }
    }

    private void request(boolean incremental) throws IOException {
        synchronized (out) {
            out.writeByte(3);
            out.writeByte(incremental ? 1 : 0);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(screen.getWidth());
            out.writeShort(screen.getHeight());
            out.flush();
        }
    }

    /** A PointerEvent: {@code buttons} is RFB's mask, bit 0 the left button. */
    void pointer(int buttons, int x, int y) throws IOException {
        synchronized (out) {
            out.writeByte(5);
            out.writeByte(buttons);
            out.writeShort(x);
            out.writeShort(y);
            out.flush();
        }
    }

    void key(int keysym, boolean down) throws IOException {
        synchronized (out) {
            out.writeByte(4);
            out.writeByte(down ? 1 : 0);
            out.writeShort(0);
            out.writeInt(keysym);
            out.flush();
        }
    }

    boolean running() {
        return running && !socket.isClosed();
    }

    @Override
    public void close() throws IOException {
        running = false;
        socket.close();
    }

    /** Why the connection ended, as a sentence: a server that hangs up says nothing more than that. */
    static String ended(Exception e) {
        if (e instanceof java.io.EOFException) return "VNC: the server closed the connection.";
        String said = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return "VNC: the connection ended (" + said + ").";
    }

    static BufferedImage copy(BufferedImage image) {
        return new BufferedImage(image.getColorModel(), image.copyData(null), image.isAlphaPremultiplied(), null);
    }
}
