package com.botmaker.shared.capture.linux.input;

import java.util.HashMap;
import java.util.Map;

/**
 * Makes device-level (XTest) typing <b>deterministic across the whole Unicode range</b>, not just the
 * characters the user's current layout happens to bind. XTest injects <em>keycodes</em>, so a keysym the
 * active layout doesn't map to any keycode ({@code XKeysymToKeycode} → 0) is simply undeliverable — that is
 * why typing an accented or CJK character silently produced nothing. This borrows a <b>spare keycode</b> (one
 * the server leaves unbound), points it at the wanted keysym via {@code XChangeKeyboardMapping}, hands the
 * caller that keycode to inject, and {@link #restore restores} the original mapping afterwards so the user's
 * layout is never left altered.
 *
 * <p>Bookkeeping is keyed by keysym so a press/release pair reuses the same borrowed keycode ({@link #rebind}
 * is idempotent per keysym) and only the release restores it. All native work goes through {@link KeymapOps},
 * so the spare-selection and restore logic here is exercised without a live server.
 *
 * <p><b>Not thread-safe</b> by itself — one {@code Keymap} belongs to one backend driving one display, and the
 * borrowed-keycode set must not be raced. Best-effort throughout: no spare available (a fully-populated
 * keymap) means the character is skipped, not an exception.
 */
final class Keymap {

    private final KeymapOps ops;
    /** keysym → the keycode currently borrowed for it, plus that keycode's original mapping for restore. */
    private final Map<Long, Reservation> borrowed = new HashMap<>();

    Keymap(KeymapOps ops) {
        this.ops = ops;
    }

    private record Reservation(int keycode, long[] original) { }

    /**
     * Bind a spare keycode to {@code keysym} and return it (idempotent: a keysym already borrowed returns its
     * existing keycode without touching the server again). Returns {@code 0} when the keymap has no unbound
     * keycode to borrow, in which case the caller drops the character.
     */
    int rebind(long keysym) {
        Reservation existing = borrowed.get(keysym);
        if (existing != null) {
            return existing.keycode();
        }
        int spare = findSpare();
        if (spare == 0) {
            return 0;
        }
        long[] original = ops.keysymsFor(spare);
        long[] replacement = new long[Math.max(1, ops.keysymsPerKeycode())];
        // Bind every shift level to the same keysym so the injected key produces it whether or not a modifier
        // happens to be held — a borrowed key carries no layout semantics of its own.
        for (int i = 0; i < replacement.length; i++) {
            replacement[i] = keysym;
        }
        ops.rebind(spare, replacement);
        ops.sync();
        borrowed.put(keysym, new Reservation(spare, original));
        return spare;
    }

    /** Put the keycode borrowed for {@code keysym} back the way it was and release the reservation. No-op if none. */
    void restore(long keysym) {
        Reservation r = borrowed.remove(keysym);
        if (r == null) {
            return;
        }
        ops.rebind(r.keycode(), r.original());
        ops.sync();
    }

    /** Restore every outstanding borrowed keycode — the safety net when a session or backend is torn down. */
    void restoreAll() {
        if (borrowed.isEmpty()) {
            return;
        }
        for (Reservation r : borrowed.values()) {
            ops.rebind(r.keycode(), r.original());
        }
        ops.sync();
        borrowed.clear();
    }

    /** Whether {@code keysym} is currently served by a borrowed keycode (so the caller knows to {@link #restore}). */
    boolean isBorrowed(long keysym) {
        return borrowed.containsKey(keysym);
    }

    /**
     * The first keycode in the server's range whose every shift level is NoSymbol (unbound) and that we haven't
     * already borrowed — searched high-to-low because unused keycodes cluster at the top of the range. Returns
     * {@code 0} when the keymap is fully populated.
     */
    private int findSpare() {
        for (int kc = ops.maxKeycode(); kc >= ops.minKeycode(); kc--) {
            if (isReserved(kc) || !isUnbound(kc)) {
                continue;
            }
            return kc;
        }
        return 0;
    }

    private boolean isReserved(int keycode) {
        for (Reservation r : borrowed.values()) {
            if (r.keycode() == keycode) {
                return true;
            }
        }
        return false;
    }

    private boolean isUnbound(int keycode) {
        for (long sym : ops.keysymsFor(keycode)) {
            if (sym != 0L) {
                return false;
            }
        }
        return true;
    }
}
