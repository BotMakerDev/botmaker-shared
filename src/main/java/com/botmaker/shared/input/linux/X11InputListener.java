package com.botmaker.shared.input.linux;

import com.botmaker.shared.capture.linux.X11;
import com.botmaker.shared.capture.linux.XRecord;
import com.botmaker.shared.input.InputEvent;
import com.botmaker.shared.input.InputListener;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import java.util.function.Consumer;

/**
 * X11 implementation of {@link InputListener} using the XRecord extension. Observes global pointer/keyboard
 * events passively (never blocks them) and streams {@link InputEvent}s.
 *
 * <p>Two X connections are used, per the XRecord contract: a <b>control</b> connection creates the context
 * and later disables it; a separate <b>data</b> connection runs the blocking {@link XRecord#XRecordEnableContext}
 * loop on a named daemon thread (its shape copied from {@code ipc.TelemetryServer}). {@link #close()} calls
 * {@code XRecordDisableContext} on the control connection, which makes the enable loop return, then the
 * thread tears down both connections.
 *
 * <p>Key events are decoded to keysyms honouring the live Shift state (so recorded text is correctly cased);
 * the Shift keys themselves are tracked here and still emitted (consumers decide whether to ignore them).
 */
public final class X11InputListener implements InputListener {

	// X keysyms we care about for shift tracking (keysymdef.h).
	private static final long XK_Shift_L = 0xFFE1L;
	private static final long XK_Shift_R = 0xFFE2L;

	private final X11 x11 = X11.INSTANCE;
	private final XRecord xrecord = XRecord.INSTANCE;

	private Pointer controlDisplay;
	private Pointer dataDisplay;
	private NativeLong context;
	private Thread thread;
	// Strong reference so the JNA callback isn't garbage-collected while native code holds it.
	private XRecord.XRecordInterceptProc callback;
	private volatile boolean closed;
	private volatile boolean shiftDown;

	@Override
	public synchronized void start(Consumer<InputEvent> sink) {
		if (thread != null) throw new IllegalStateException("already started");

		controlDisplay = x11.XOpenDisplay(null);
		dataDisplay = x11.XOpenDisplay(null);
		if (controlDisplay == null || dataDisplay == null) {
			cleanupDisplays();
			throw new IllegalStateException("Couldn't open X display for input recording (is DISPLAY set?).");
		}

		IntByReference major = new IntByReference();
		IntByReference minor = new IntByReference();
		if (xrecord.XRecordQueryVersion(controlDisplay, major, minor) == 0) {
			cleanupDisplays();
			throw new IllegalStateException("The X server does not support the RECORD extension.");
		}

		// Record all device (pointer + key) events from every client.
		Pointer range = xrecord.XRecordAllocRange();
		range.setByte(XRecord.RANGE_DEVICE_EVENTS_FIRST, (byte) X11.KeyPress);      // 2
		range.setByte(XRecord.RANGE_DEVICE_EVENTS_LAST, (byte) X11.MotionNotify);   // 6

		Memory clients = new Memory(NativeLong.SIZE);
		clients.setLong(0, XRecord.XRecordAllClients);
		Memory ranges = new Memory(Native.POINTER_SIZE);
		ranges.setPointer(0, range);

		context = xrecord.XRecordCreateContext(controlDisplay, 0, clients, 1, ranges, 1);
		if (context == null || context.longValue() == 0) {
			cleanupDisplays();
			throw new IllegalStateException("XRecordCreateContext failed.");
		}
		x11.XSync(controlDisplay, false);

		this.callback = (closure, recordedData) -> {
			try {
				decode(recordedData, sink);
			} finally {
				xrecord.XRecordFreeData(recordedData);
			}
		};

		thread = new Thread(this::runLoop, "botmaker-input-recorder");
		thread.setDaemon(true);
		thread.start();
	}

	private void runLoop() {
		// Blocks here until close() disables the context on the control connection.
		xrecord.XRecordEnableContext(dataDisplay, context, callback, Pointer.NULL);
		// Enable returned — the context is disabled. Free it and both connections.
		try {
			xrecord.XRecordFreeContext(controlDisplay, context);
		} catch (Throwable ignored) {
		}
		cleanupDisplays();
	}

	/** Decodes one recorded datum (a raw X protocol event) and, if it's an input event, emits it. */
	private void decode(Pointer recordedData, Consumer<InputEvent> sink) {
		if (recordedData == null) return;
		int category = recordedData.getInt(XRecord.INTERCEPT_CATEGORY_OFFSET);
		if (category != XRecord.XRecordFromServer) return; // start/end-of-data, client bookkeeping — ignore
		Pointer data = recordedData.getPointer(XRecord.INTERCEPT_DATA_OFFSET);
		long dataLen = recordedData.getLong(XRecord.INTERCEPT_DATALEN_OFFSET); // 4-byte units
		if (data == null || dataLen == 0) return;

		int type = data.getByte(XRecord.EVENT_TYPE_OFFSET) & 0x7f;
		int detail = data.getByte(XRecord.EVENT_DETAIL_OFFSET) & 0xff;
		int x = data.getShort(XRecord.EVENT_ROOTX_OFFSET);
		int y = data.getShort(XRecord.EVENT_ROOTY_OFFSET);
		long now = System.currentTimeMillis();

		switch (type) {
			case X11.ButtonPress -> sink.accept(new InputEvent.ButtonPress(detail, x, y, now));
			case X11.ButtonRelease -> sink.accept(new InputEvent.ButtonRelease(detail, x, y, now));
			case X11.MotionNotify -> sink.accept(new InputEvent.Motion(x, y, now));
			case X11.KeyPress -> {
				long base = keysym(detail, 0);
				if (base == XK_Shift_L || base == XK_Shift_R) shiftDown = true;
				long sym = keysym(detail, shiftDown ? 1 : 0);
				if (sym == 0) sym = base;
				sink.accept(new InputEvent.KeyPress(detail, sym, now));
			}
			case X11.KeyRelease -> {
				long base = keysym(detail, 0);
				if (base == XK_Shift_L || base == XK_Shift_R) shiftDown = false;
				long sym = keysym(detail, shiftDown ? 1 : 0);
				if (sym == 0) sym = base;
				sink.accept(new InputEvent.KeyRelease(detail, sym, now));
			}
			default -> {
				// not an input event we translate
			}
		}
	}

	/**
	 * keycode → keysym at the given shift level. Uses the <b>data</b> display because this runs on the
	 * XRecord callback (data) thread; the control display is touched by {@link #close()} on another thread and
	 * Xlib isn't safe to share a display across threads.
	 */
	private long keysym(int keycode, int index) {
		NativeLong ks = x11.XKeycodeToKeysym(dataDisplay, (byte) keycode, index);
		return ks == null ? 0 : ks.longValue();
	}

	@Override
	public synchronized void close() {
		if (closed) return;
		closed = true;
		if (context != null && controlDisplay != null) {
			// Unblock the enable loop on the data connection; the loop thread then frees everything.
			xrecord.XRecordDisableContext(controlDisplay, context);
			x11.XFlush(controlDisplay);
		} else {
			cleanupDisplays();
		}
	}

	private void cleanupDisplays() {
		try { if (dataDisplay != null) x11.XCloseDisplay(dataDisplay); } catch (Throwable ignored) {}
		try { if (controlDisplay != null) x11.XCloseDisplay(controlDisplay); } catch (Throwable ignored) {}
		dataDisplay = null;
		controlDisplay = null;
	}
}
