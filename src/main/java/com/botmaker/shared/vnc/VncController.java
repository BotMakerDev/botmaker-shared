package com.botmaker.shared.vnc;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.function.Supplier;

/**
 * A screen served over VNC, driven as a {@link NativeController}: what a bot's capture, clicks and keys go
 * through when its game runs in a virtual machine that VMware or QEMU serves on this computer. The protocol is
 * {@link RfbClient}'s; this class is the bot's side of it. Coordinates are the screen's pixels, so a point in a
 * capture of the {@link #screen()} is the point clicked.
 *
 * <p><b>The guest's windows.</b> VNC carries pixels only, so which windows are on the screen comes from the
 * guest, through the {@code windows} given to {@link #connect}: each is a {@link GenericWindow} at its rectangle
 * in the screen, captured as that part of the frame and clicked relative to it. With none listed (a guest that
 * lists nothing), the whole screen is the one window, as it was before guests listed theirs.
 *
 * <p>Nothing here touches this computer's own cursor, keyboard or windows: every gesture is a VNC message, and
 * the hypervisor delivers it through the guest's virtual mouse and keyboard — hence
 * {@link #supportsBackgroundInput()}. A window is focused by a click on its title bar; move, resize and restore
 * have nothing to act through.
 * The mouse pointer is left out of the frames (the client takes the pointer's shape and drops it), so a capture
 * holds no cursor for a picture search to trip on.
 *
 * <p>Once the server is gone it degrades rather than throws: no windows, the last frame, input dropped;
 * {@link #alive()} says which, and {@link #failure()} why.
 */
public final class VncController implements NativeController, AutoCloseable {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;
    /**
     * How often updates are asked for. Each one is copied whole for its readers, so this is what an idle screen
     * costs; a bot's captures and the preview need no more.
     */
    private static final int FRAMES_PER_SECOND = 15;
    /** RFB carries coordinates as unsigned 16-bit. */
    private static final int MAX_COORDINATE = 0xFFFF;
    private static final int SHIFT = 0xFFE1;
    /** RFB's pointer mask: the wheel is buttons 4 (up) and 5 (down), pressed and released. */
    private static final int WHEEL_UP = 1 << 3;
    private static final int WHEEL_DOWN = 1 << 4;
    /** How far below a window's top edge its title bar is clicked to focus it. */
    private static final int TITLE_BAR = 8;

    private final Keysyms.NativeKeys keys;
    private final String title;
    private final Supplier<List<GuestWindow>> windows;
    private final Object frameLock = new Object();
    private final Object pointerLock = new Object();
    private RfbClient client;
    private BufferedImage latest;
    private long frames;
    private volatile String failure;
    private int x;
    private int y;
    private int buttons;

    private VncController(Keysyms.NativeKeys keys, String title, Supplier<List<GuestWindow>> windows) {
        this.keys = keys;
        this.title = title;
        this.windows = windows;
    }

    /**
     * Connects to the VNC server at {@code host:port}, with {@code password} when it asks for one ({@code null}
     * for none), and waits up to {@code firstFrameMs} for its first frame. {@code keys} says what the bot's key
     * codes are on this host; {@code title} names the whole {@link #screen()}; {@code windows} lists the guest's
     * windows as they stand ({@code List::of} for a guest that can't), and is asked on each call that needs them,
     * so it caches what it reads. Throws with a sentence a UI can show when the server can't be reached, refuses
     * the password, or sends no picture.
     */
    public static VncController connect(String host, int port, String password, Keysyms.NativeKeys keys,
                                        String title, Supplier<List<GuestWindow>> windows, long firstFrameMs)
            throws IOException {
        VncController controller = new VncController(keys, title, windows);
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
            // A silent server would hold the greeting forever: bound it, then read with no timeout.
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            try {
                controller.client = RfbClient.handshake(socket, password);
            } catch (java.net.SocketTimeoutException e) {
                throw new IOException(where + " didn't finish its greeting.", e);
            } catch (java.io.EOFException e) {
                throw new IOException(where + " closed the connection.", e);
            }
            socket.setSoTimeout(0);
            controller.client.start(FRAMES_PER_SECOND, new RfbClient.Listener() {
                @Override
                public void frame(BufferedImage screen) {
                    controller.frame(screen);
                }

                @Override
                public void failed(String why) {
                    controller.failed(why);
                }
            });
            if (controller.awaitFrame(0, firstFrameMs) == 0) {
                throw new IOException(!controller.alive() && controller.failure != null ? controller.failure
                        : where + " sent no picture within " + firstFrameMs + " ms.");
            }
            return controller;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            socket.close();
            throw new IOException("Stopped while connecting to the VM's screen.", e);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    private void failed(String why) {
        synchronized (frameLock) {
            failure = why;
            frameLock.notifyAll(); // a waiter in awaitFrame learns now, not at its timeout
        }
    }

    private void frame(BufferedImage screen) {
        synchronized (frameLock) {
            latest = screen;
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
        return failure == null && client != null && client.running();
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
            return latest == null ? null : RfbClient.copy(latest);
        }
    }

    /** The whole screen as a window at {@code (0, 0)}, named by the {@code title} given to {@link #connect}. */
    public GenericWindow screen() {
        Dimension size = screenSize();
        return new GenericWindow(this, title, new Rectangle(0, 0, size.width, size.height));
    }

    /** The guest's windows as it lists them now; empty when it lists none or the screen is gone. */
    public List<GuestWindow> guestWindows() {
        if (!alive()) return List.of();
        try {
            List<GuestWindow> listed = windows.get();
            return listed == null ? List.of() : listed;
        } catch (RuntimeException e) {
            return List.of(); // the guest's list is best-effort; the screen still works without it
        }
    }

    /**
     * The window the guest says has the focus; the whole screen when none of those it lists has it (the desktop or
     * the taskbar, which it doesn't list) or it lists none.
     */
    @Override
    public GenericWindow getForegroundWindow() {
        if (!alive()) return null;
        return guestWindows().stream().filter(GuestWindow::foreground).findFirst().map(this::window)
                .orElseGet(this::screen);
    }

    @Override
    public List<GenericWindow> getChildWindows(GenericWindow parent) {
        return List.of();
    }

    /** The guest's windows, topmost first; the whole screen alone when it lists none. */
    @Override
    public List<GenericWindow> getAllWindows() {
        if (!alive()) return List.of();
        List<GuestWindow> listed = guestWindows();
        return listed.isEmpty() ? List.of(screen()) : listed.stream().map(this::window).toList();
    }

    /**
     * {@code listed} as a window, at the part of its frame on the screen: a maximised window's frame reaches past
     * the screen's edges, and its capture, its clicks and the corner a bot adds a match to must be the same
     * rectangle.
     */
    private GenericWindow window(GuestWindow listed) {
        Dimension size = screenSize();
        return new GenericWindow(listed, listed.title(),
                listed.rect().intersection(new Rectangle(0, 0, size.width, size.height)));
    }

    /**
     * Where {@code window} was when it was found, which is what its corner says too; the whole screen for anything
     * but a guest window. A bot following a window that moves finds it again ({@code window("…")} does at each
     * use).
     */
    private Rectangle where(GenericWindow window) {
        if (window != null && window.getNativeHandle() instanceof GuestWindow) return window.getRect();
        Dimension size = screenSize();
        return new Rectangle(0, 0, size.width, size.height);
    }

    /**
     * That part of the frame where {@code window} is; the whole screen for {@link #screen()}. It is the screen's
     * pixels, so a window behind another shows what covers it.
     */
    @Override
    public BufferedImage captureWindow(GenericWindow window) {
        Rectangle wanted = where(window);
        synchronized (frameLock) {
            if (latest == null) return null;
            // The frame may have changed size since the window was found: a game switching resolution.
            Rectangle r = wanted.intersection(new Rectangle(0, 0, latest.getWidth(), latest.getHeight()));
            if (r.isEmpty()) return null;
            if (r.width == latest.getWidth() && r.height == latest.getHeight()) return RfbClient.copy(latest);
            BufferedImage part = latest.getSubimage(r.x, r.y, r.width, r.height);
            ColorModel model = part.getColorModel();
            WritableRaster pixels = model.createCompatibleWritableRaster(r.width, r.height);
            part.copyData(pixels); // a sub-image's own raster starts at the parent's corner, so it's copied out
            return new BufferedImage(model, pixels, model.isAlphaPremultiplied(), null);
        }
    }

    @Override
    public void postLeftClick(GenericWindow window, int relativeX, int relativeY) {
        Rectangle r = where(window);
        click(r.x + relativeX, r.y + relativeY, 1);
    }

    @Override
    public boolean supportsBackgroundInput() {
        return true;
    }

    /**
     * Clicks {@code window}'s title bar, unless the guest says it has the focus already: the click is what a person
     * would do, and Windows lets a program other than the one in front take the focus only that way. A borderless
     * window has no title bar, so the click lands near its top edge.
     */
    @Override
    public void focusWindow(GenericWindow window) {
        if (window == null || !(window.getNativeHandle() instanceof GuestWindow found)) return;
        boolean inFront = guestWindows().stream().anyMatch(w -> w.id() == found.id() && w.foreground());
        if (inFront) return;
        Rectangle r = where(window);
        if (!r.isEmpty()) click(r.x + r.width / 2, r.y + Math.min(TITLE_BAR, r.height - 1), 1);
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
        if (keysym != 0) key(keysym, true);
    }

    @Override
    public void keyUp(int nativeKeyCode) {
        int keysym = Keysyms.of(nativeKeyCode, keys);
        if (keysym != 0) key(keysym, false);
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
            if (shift) key(SHIFT, true);
            key(keysym, true);
            key(keysym, false);
            if (shift) key(SHIFT, false);
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
            pointer(buttons);
        }
    }

    /** {@code button} is 1 left, 2 middle, 3 right: VNC's own numbering. */
    @Override
    public void mouseButton(int button, boolean press) {
        if (button < 1 || button > 3) return;
        synchronized (pointerLock) {
            int bit = 1 << (button - 1);
            buttons = press ? buttons | bit : buttons & ~bit;
            pointer(buttons);
        }
    }

    /** One wheel step per unit: up for a positive amount. */
    @Override
    public void scroll(int amount) {
        int wheel = amount > 0 ? WHEEL_UP : WHEEL_DOWN;
        synchronized (pointerLock) {
            for (int i = 0; i < Math.abs(amount) && alive(); i++) {
                pointer(buttons | wheel);
                pointer(buttons);
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
        try {
            if (client != null) client.close();
        } catch (IOException e) {
            // closing a socket that is already gone
        }
    }

    /** Called under {@link #pointerLock}. */
    private void pointer(int mask) {
        if (!alive()) return;
        try {
            client.pointer(mask, x, y);
        } catch (IOException e) {
            failed(RfbClient.ended(e));
        }
    }

    private void key(int keysym, boolean down) {
        if (!alive()) return;
        try {
            client.key(keysym, down);
        } catch (IOException e) {
            failed(RfbClient.ended(e));
        }
    }
}
