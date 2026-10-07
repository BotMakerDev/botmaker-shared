package com.botmaker.shared.vnc;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.shinyhut.vernacular.client.VernacularClient;
import com.shinyhut.vernacular.client.VernacularConfig;
import com.shinyhut.vernacular.client.exceptions.AuthenticationFailedException;
import com.shinyhut.vernacular.client.exceptions.AuthenticationRequiredException;
import com.shinyhut.vernacular.client.exceptions.VncException;
import com.shinyhut.vernacular.client.rendering.ColorDepth;

import java.awt.Dimension;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * A screen served over VNC, driven as a {@link NativeController}: what a bot's capture, clicks and keys go
 * through when its game runs in a virtual machine that VMware or QEMU serves on this computer. The protocol is
 * Vernacular's; this class is the bot's side of it. The whole screen is one window at {@code (0, 0)}, so a
 * point in a capture is the point clicked.
 *
 * <p>Nothing here touches this computer's own cursor, keyboard or windows: every gesture is a VNC message, and
 * the hypervisor delivers it through the guest's virtual mouse and keyboard — hence
 * {@link #supportsBackgroundInput()}. The window methods (focus, move, resize, restore) have nothing to act on.
 * The mouse pointer is drawn by the viewer, not into the frames (Vernacular's local pointer), so a capture
 * holds no cursor for a picture search to trip on.
 *
 * <p>Once the server is gone it degrades rather than throws: no windows, the last frame, input dropped;
 * {@link #alive()} says which, and {@link #failure()} why.
 */
public final class VncController implements NativeController, AutoCloseable {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;
    /**
     * How often updates are asked for. Vernacular copies the whole frame on each one, so this is what an idle
     * screen costs; a bot's captures and the preview need no more.
     */
    private static final int FRAMES_PER_SECOND = 15;
    /** RFB carries coordinates as unsigned 16-bit. */
    private static final int MAX_COORDINATE = 0xFFFF;
    private static final int SHIFT = 0xFFE1;

    private final Keysyms.NativeKeys keys;
    private final String title;
    private final Object frameLock = new Object();
    private final Object pointerLock = new Object();
    private VernacularClient client;
    private BufferedImage latest;
    private long frames;
    private volatile String failure;
    private int x;
    private int y;

    private VncController(Keysyms.NativeKeys keys, String title) {
        this.keys = keys;
        this.title = title;
    }

    /**
     * Connects to the VNC server at {@code host:port}, with {@code password} when it asks for one ({@code null}
     * for none), and waits up to {@code firstFrameMs} for its first frame. {@code keys} says what the bot's key
     * codes are on this host; {@code title} names the one window. Throws with a sentence a UI can show when the
     * server can't be reached, refuses the password, or sends no picture.
     */
    public static VncController connect(String host, int port, String password, Keysyms.NativeKeys keys,
                                        String title, long firstFrameMs) throws IOException {
        VncController controller = new VncController(keys, title);
        VernacularConfig config = new VernacularConfig();
        config.setColorDepth(ColorDepth.BPP_24_TRUE);
        config.setShared(true); // the hypervisor's own viewer may stay connected
        config.setUseLocalMousePointer(true);
        config.setTargetFramesPerSecond(FRAMES_PER_SECOND);
        // No supplier at all when there is no password: that is what makes Vernacular say a password is needed.
        config.setPasswordSupplier(password == null ? null : () -> password);
        config.setErrorListener(controller::failed);
        config.setScreenUpdateListener(controller::frame);
        VernacularClient client = new VernacularClient(config);
        controller.client = client;

        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
        } catch (IOException e) {
            socket.close();
            throw new IOException("Nothing answers VNC on " + host + ":" + port + ".", e);
        }
        String where = "VNC on " + host + ":" + port;
        try {
            // Vernacular handshakes on the calling thread with no timeout of its own: a silent server would hang
            // it, and an unexpected throw there would leave it "running" with nothing behind it.
            Thread handshake = Thread.ofPlatform().daemon().name("vnc-handshake-" + port).start(() -> {
                try {
                    client.start(socket);
                } catch (RuntimeException e) {
                    controller.failure = where + " failed during its greeting: " + e;
                }
            });
            handshake.join(HANDSHAKE_TIMEOUT_MS);
            if (handshake.isAlive()) throw new IOException(where + " didn't finish its greeting.");
            if (!controller.alive()) {
                throw new IOException(controller.failure != null ? controller.failure : where + " closed the connection.");
            }
            if (controller.awaitFrame(0, firstFrameMs) == 0) {
                throw new IOException(!controller.alive() && controller.failure != null ? controller.failure
                        : where + " sent no picture within " + firstFrameMs + " ms.");
            }
            return controller;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            client.stop();
            socket.close();
            throw new IOException("Stopped while connecting to the VM's screen.", e);
        } catch (IOException e) {
            client.stop();
            socket.close();
            throw e;
        }
    }

    private void failed(VncException e) {
        String why = switch (e) {
            case AuthenticationFailedException ignored -> "VNC refused the password.";
            case AuthenticationRequiredException ignored -> "VNC asks for a password and none was given.";
            default -> "VNC: " + (e.getMessage() != null ? e.getMessage()
                    : e.getCause() != null ? e.getCause().getMessage() : e.getClass().getSimpleName());
        };
        synchronized (frameLock) {
            failure = why;
            frameLock.notifyAll(); // a waiter in awaitFrame learns now, not at its timeout
        }
    }

    private void frame(Image image) {
        BufferedImage frame = image instanceof BufferedImage b ? b : copy(image);
        synchronized (frameLock) {
            latest = frame;
            frames++;
            frameLock.notifyAll();
        }
    }

    /**
     * Waits until more than {@code seen} frames have arrived, or {@code timeoutMs} has passed; returns how many
     * have. {@code awaitFrame(0, …)} waits for the first.
     */
    public long awaitFrame(long seen, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        synchronized (frameLock) {
            while (frames <= seen && alive()) {
                long left = (deadline - System.nanoTime()) / 1_000_000L;
                if (left <= 0) break;
                frameLock.wait(left);
            }
            return frames;
        }
    }

    /** How many updates have arrived so far: the {@code seen} to pass {@link #awaitFrame}. */
    public long frames() {
        synchronized (frameLock) {
            return frames;
        }
    }

    public boolean alive() {
        return failure == null && client.isRunning();
    }

    /** Why the connection ended, or {@code null} while it hasn't. */
    public String failure() {
        return failure;
    }

    public Dimension screenSize() {
        synchronized (frameLock) {
            return latest == null ? new Dimension(0, 0) : new Dimension(latest.getWidth(), latest.getHeight());
        }
    }

    /** A copy of the whole screen as it stands, or {@code null} before the first frame. */
    public BufferedImage captureScreen() {
        synchronized (frameLock) {
            return latest == null ? null : copy(latest);
        }
    }

    /** The one window: the whole screen. */
    public GenericWindow screen() {
        Dimension size = screenSize();
        return new GenericWindow(this, title, new Rectangle(0, 0, size.width, size.height));
    }

    @Override
    public GenericWindow getForegroundWindow() {
        return alive() ? screen() : null;
    }

    @Override
    public List<GenericWindow> getChildWindows(GenericWindow parent) {
        return List.of();
    }

    @Override
    public List<GenericWindow> getAllWindows() {
        return alive() ? List.of(screen()) : List.of();
    }

    @Override
    public BufferedImage captureWindow(GenericWindow window) {
        return captureScreen();
    }

    @Override
    public void postLeftClick(GenericWindow window, int relativeX, int relativeY) {
        click(relativeX, relativeY, 1);
    }

    @Override
    public boolean supportsBackgroundInput() {
        return true;
    }

    @Override
    public void focusWindow(GenericWindow window) {
    }

    @Override
    public void moveWindow(GenericWindow window, int x, int y) {
    }

    @Override
    public void resizeWindow(GenericWindow window, int width, int height) {
    }

    @Override
    public void keyDown(int nativeKeyCode) {
        int keysym = Keysyms.of(nativeKeyCode, keys);
        if (keysym != 0 && alive()) client.updateKey(keysym, true);
    }

    @Override
    public void keyUp(int nativeKeyCode) {
        int keysym = Keysyms.of(nativeKeyCode, keys);
        if (keysym != 0 && alive()) client.updateKey(keysym, false);
    }

    /**
     * Types {@code text} one key at a time. A character that needs Shift on a US keyboard is sent with Shift
     * held: some servers (QEMU's) turn a keysym into a key position and would otherwise type it unshifted.
     */
    @Override
    public void typeText(String text) {
        if (text == null) return;
        text.codePoints().forEach(c -> {
            int keysym = Keysyms.ofChar(c);
            if (keysym == 0 || !alive()) return;
            boolean shift = Keysyms.needsShift(c);
            if (shift) client.updateKey(SHIFT, true);
            client.type(keysym);
            if (shift) client.updateKey(SHIFT, false);
        });
    }

    /** Moves to {@code (xAbs, yAbs)}, kept on the screen. */
    @Override
    public void mouseMove(int xAbs, int yAbs) {
        Dimension size = screenSize();
        int maxX = size.width > 0 ? size.width - 1 : MAX_COORDINATE;
        int maxY = size.height > 0 ? size.height - 1 : MAX_COORDINATE;
        synchronized (pointerLock) {
            x = Math.clamp(xAbs, 0, maxX);
            y = Math.clamp(yAbs, 0, maxY);
            if (alive()) client.moveMouse(x, y);
        }
    }

    /** {@code button} is 1 left, 2 middle, 3 right: VNC's own numbering. */
    @Override
    public void mouseButton(int button, boolean press) {
        if (button < 1 || button > 3) return;
        synchronized (pointerLock) {
            if (alive()) client.updateMouseButton(button, press);
        }
    }

    /** One wheel step per unit: up for a positive amount. */
    @Override
    public void scroll(int amount) {
        synchronized (pointerLock) {
            for (int i = 0; i < Math.abs(amount) && alive(); i++) {
                if (amount > 0) client.scrollUp();
                else client.scrollDown();
            }
        }
    }

    @Override
    public Point cursorPosition() {
        synchronized (pointerLock) {
            return new Point(x, y);
        }
    }

    @Override
    public void close() {
        client.stop();
    }

    private static BufferedImage copy(Image image) {
        if (image instanceof BufferedImage b) {
            return new BufferedImage(b.getColorModel(), b.copyData(null), b.isAlphaPremultiplied(), null);
        }
        BufferedImage copy = new BufferedImage(image.getWidth(null), image.getHeight(null),
                BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics g = copy.getGraphics();
        try {
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return copy;
    }
}
