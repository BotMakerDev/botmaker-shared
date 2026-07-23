package com.botmaker.shared.capture.linux.input;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for {@link Keymap}'s spare-keycode borrowing and restore bookkeeping, driven through a fake
 * {@link KeymapOps} backed by an in-memory table — so the logic that makes out-of-map Unicode typing
 * deterministic is verified without a live X server.
 */
class KeymapTest {

	/** An in-memory keyboard mapping: keycodes {@code min..max}, each row {@code perKeycode} keysyms wide. */
	private static final class FakeKeymapOps implements KeymapOps {
		private final int min;
		private final int max;
		private final int per;
		private final Map<Integer, long[]> table = new HashMap<>();
		int syncs;

		FakeKeymapOps(int min, int max, int per) {
			this.min = min;
			this.max = max;
			this.per = per;
			for (int kc = min; kc <= max; kc++) {
				table.put(kc, new long[per]); // all NoSymbol (unbound) to start
			}
		}

		/** Bind {@code keycode} to real symbols so it is no longer a spare candidate. */
		void occupy(int keycode, long... syms) {
			long[] row = new long[per];
			System.arraycopy(syms, 0, row, 0, Math.min(syms.length, per));
			table.put(keycode, row);
		}

		@Override public int minKeycode() { return min; }
		@Override public int maxKeycode() { return max; }
		@Override public int keysymsPerKeycode() { return per; }
		@Override public long[] keysymsFor(int keycode) { return table.get(keycode).clone(); }
		@Override public void rebind(int keycode, long[] keysyms) { table.put(keycode, keysyms.clone()); }
		@Override public void sync() { syncs++; }
	}

	@Test
	void rebindBorrowsHighestSpareAndBindsEveryShiftLevel() {
		FakeKeymapOps ops = new FakeKeymapOps(8, 12, 2);
		ops.occupy(8, 'a', 'A');
		ops.occupy(9, 'b', 'B');
		Keymap keymap = new Keymap(ops);

		long eacute = 0x0E9; // 'é' — assume unmapped by the active layout
		int kc = keymap.rebind(eacute);

		assertEquals(12, kc, "spares are searched high-to-low, so the top free keycode is taken");
		assertTrue(keymap.isBorrowed(eacute));
		assertArrayEquals(new long[]{eacute, eacute}, ops.keysymsFor(12),
			"every shift level is bound to the keysym so a held modifier can't change it");
	}

	@Test
	void rebindIsIdempotentPerKeysym() {
		FakeKeymapOps ops = new FakeKeymapOps(8, 10, 2);
		Keymap keymap = new Keymap(ops);

		int first = keymap.rebind(0x20AC);  // '€'
		int again = keymap.rebind(0x20AC);
		assertEquals(first, again, "the same keysym reuses its borrowed keycode, not a second spare");
	}

	@Test
	void distinctKeysymsTakeDistinctSpares() {
		FakeKeymapOps ops = new FakeKeymapOps(8, 10, 2);
		Keymap keymap = new Keymap(ops);

		int a = keymap.rebind(0x0E9);
		int b = keymap.rebind(0x20AC);
		assertNotEquals(a, b, "a reserved spare is not handed out again while still borrowed");
	}

	@Test
	void restorePutsTheOriginalMappingBackAndFreesTheSpare() {
		FakeKeymapOps ops = new FakeKeymapOps(8, 10, 2);
		ops.occupy(10, 0x100, 0x101); // keycode 10 already carries symbols — must come back intact
		Keymap keymap = new Keymap(ops);
		long[] before = ops.keysymsFor(10);

		// Only keycodes 8 and 9 are free; force keysym onto one, then restore.
		int kc = keymap.rebind(0x0E9);
		assertTrue(kc == 8 || kc == 9);
		keymap.restore(0x0E9);

		assertFalse(keymap.isBorrowed(0x0E9));
		assertArrayEquals(before, ops.keysymsFor(10), "an occupied keycode is never disturbed");
		assertArrayEquals(new long[]{0, 0}, ops.keysymsFor(kc), "the borrowed keycode is back to NoSymbol");
	}

	@Test
	void restoreAllUndoesEveryOutstandingBorrow() {
		FakeKeymapOps ops = new FakeKeymapOps(8, 10, 2);
		Keymap keymap = new Keymap(ops);
		int a = keymap.rebind(0x0E9);
		int b = keymap.rebind(0x20AC);

		keymap.restoreAll();

		assertFalse(keymap.isBorrowed(0x0E9));
		assertFalse(keymap.isBorrowed(0x20AC));
		assertArrayEquals(new long[]{0, 0}, ops.keysymsFor(a));
		assertArrayEquals(new long[]{0, 0}, ops.keysymsFor(b));
	}

	@Test
	void afullyPopulatedKeymapYieldsNoSpare() {
		FakeKeymapOps ops = new FakeKeymapOps(8, 9, 2);
		ops.occupy(8, 'a', 'A');
		ops.occupy(9, 'b', 'B');
		Keymap keymap = new Keymap(ops);

		assertEquals(0, keymap.rebind(0x0E9), "no unbound keycode to borrow → 0, so the caller drops the char");
		assertFalse(keymap.isBorrowed(0x0E9));
	}
}
