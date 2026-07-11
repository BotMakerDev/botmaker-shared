package com.botmaker.shared.capture.linux;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

/**
 * JNA bindings for the XRecord extension (libXtst — the same shared object as {@link XTest}, which only
 * <em>synthesizes</em> input). XRecord is the <em>passive</em> counterpart: it lets us observe the real
 * pointer/keyboard events the server dispatches, without intercepting or blocking them (there is no way to
 * swallow an event with XRecord — an observer only). This is what backs the Studio macro recorder.
 *
 * <p>Usage pattern (see {@code input.linux.X11InputListener}): open <b>two</b> display connections — a
 * control connection to create/enable/disable the context and a separate <b>data</b> connection on which the
 * blocking {@link #XRecordEnableContext} loop runs — because {@code XRecordEnableContext} monopolises its
 * connection until {@link #XRecordDisableContext} is called on the control connection.
 *
 * <p>To avoid hand-rolling the intricate {@code XRecordRange} struct we use {@link #XRecordAllocRange} (which
 * returns a zeroed range) and poke the two {@code device_events} bytes directly, and we decode the recorded
 * protocol event from raw offsets in the intercept-data buffer rather than mapping the whole struct.
 */
public interface XRecord extends Library {
	XRecord INSTANCE = Native.load("Xtst", XRecord.class);

	// Special client spec — record events from every client (RECORDPROTO XRecordAllClients).
	long XRecordAllClients = 3L;

	// Intercept-data categories (record.h). We only decode XRecordFromServer.
	int XRecordFromServer = 0;
	int XRecordFromClient = 1;
	int XRecordClientStarted = 2;
	int XRecordClientDied = 3;
	int XRecordStartOfData = 4;
	int XRecordEndOfData = 5;

	// ── Byte offsets we read directly (avoids mapping the structs) ────────────────────────────────────────

	/** {@code XRecordRange.device_events.first} — a byte offset into an XRecordAllocRange() buffer. */
	int RANGE_DEVICE_EVENTS_FIRST = 18;
	/** {@code XRecordRange.device_events.last}. */
	int RANGE_DEVICE_EVENTS_LAST = 19;

	/** {@code XRecordInterceptData.category} (int). */
	int INTERCEPT_CATEGORY_OFFSET = 24;
	/** {@code XRecordInterceptData.data} (unsigned char*). */
	int INTERCEPT_DATA_OFFSET = 32;
	/** {@code XRecordInterceptData.data_len} (unsigned long, in 4-byte units). */
	int INTERCEPT_DATALEN_OFFSET = 40;

	// Offsets inside a recorded core protocol event (xEvent, 32 bytes — X11 wire format, NOT XEvent):
	int EVENT_TYPE_OFFSET = 0;   // CARD8 (top bit = synthetic)
	int EVENT_DETAIL_OFFSET = 1; // CARD8 — keycode or button
	int EVENT_ROOTX_OFFSET = 20; // INT16 — pointer position, root-relative (absolute screen)
	int EVENT_ROOTY_OFFSET = 22; // INT16

	/** Callback matching {@code void (*)(XPointer closure, XRecordInterceptData *data)}. */
	interface XRecordInterceptProc extends Callback {
		void invoke(Pointer closure, Pointer recordedData);
	}

	int XRecordQueryVersion(Pointer display, IntByReference majorReturn, IntByReference minorReturn);

	/** Allocates a zeroed {@code XRecordRange}; caller pokes {@code device_events} and passes it to create. */
	Pointer XRecordAllocRange();

	/**
	 * Creates a recording context. {@code clients} is a native array of {@code XRecordClientSpec}
	 * (unsigned long) and {@code ranges} a native array of {@code XRecordRange*}. Returns the context XID.
	 */
	NativeLong XRecordCreateContext(Pointer display, int datumFlags,
									Pointer clients, int nClients,
									Pointer ranges, int nRanges);

	/** Blocks on {@code display}, invoking {@code callback} for each recorded datum until disabled. */
	int XRecordEnableContext(Pointer display, NativeLong context, XRecordInterceptProc callback, Pointer closure);

	/** Stops an enabled context — call on the <em>control</em> display to unblock {@link #XRecordEnableContext}. */
	int XRecordDisableContext(Pointer display, NativeLong context);

	int XRecordFreeContext(Pointer display, NativeLong context);

	/** Frees one intercept-data buffer handed to the callback; must be called for every datum. */
	void XRecordFreeData(Pointer data);
}
