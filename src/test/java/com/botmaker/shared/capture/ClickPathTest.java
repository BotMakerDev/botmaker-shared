package com.botmaker.shared.capture;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two click paths on {@link NativeController} and the one difference between them: {@link
 * NativeController#click} leaves the pointer on the target, {@link NativeController#clickRestoringCursor} puts
 * it back. Everything else — the order of the calls and the press hold — has to be identical, which is why the
 * restoring one delegates rather than keeping its own copy of the sequence.
 */
class ClickPathTest {

    @Test
    void aPlainClickLeavesThePointerOnTheTarget() {
        Recording nc = new Recording();
        nc.cursor = new Point(7, 9); // readable, so a restore would be visible in the call list

        nc.click(100, 120, 1);

        assertEquals(List.of("move 100,120", "button 1 true", "button 1 false"), nc.calls);
    }

    @Test
    void theRestoringClickIsTheSameSequencePlusTheWarpBack() {
        Recording nc = new Recording();
        nc.cursor = new Point(7, 9);

        nc.clickRestoringCursor(100, 120, 1);

        assertEquals(List.of("move 100,120", "button 1 true", "button 1 false", "move 7,9"), nc.calls);
    }

    @Test
    void anUnreadableCursorMeansDoNotRestore() {
        // A null position is "don't know", not an origin: inventing one would park the user's cursor somewhere
        // they never left it.
        Recording nc = new Recording();
        nc.cursor = null;

        nc.clickRestoringCursor(100, 120, 1);

        assertEquals(List.of("move 100,120", "button 1 true", "button 1 false"), nc.calls);
    }

    @Test
    void thePressOutlastsAFrame() {
        // One frame at 60 fps is ~16 ms; a shorter press can be sampled away entirely by a game that reads
        // input once per frame. This asserts the elapsed time, not just the constant, since the hold only
        // counts if the default implementation actually waits.
        Recording nc = new Recording();
        long start = System.nanoTime();
        nc.click(10, 10, 1);
        long heldMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(heldMs >= NativeController.CLICK_SETTLE_MS + NativeController.CLICK_HOLD_MS,
            "click returned in " + heldMs + "ms, too fast to have settled and held");
    }

    /** Records the input calls; everything the click paths don't touch is a stub. */
    private static final class Recording implements NativeController {
        final List<String> calls = new ArrayList<>();
        Point cursor;

        @Override public GenericWindow getForegroundWindow() { return null; }
        @Override public List<GenericWindow> getChildWindows(GenericWindow parent) { return List.of(); }
        @Override public List<GenericWindow> getAllWindows() { return List.of(); }
        @Override public BufferedImage captureWindow(GenericWindow window) { return null; }
        @Override public void postLeftClick(GenericWindow window, int x, int y) { }
        @Override public void focusWindow(GenericWindow window) { }
        @Override public void moveWindow(GenericWindow window, int x, int y) { }
        @Override public void resizeWindow(GenericWindow window, int width, int height) { }
        @Override public void keyDown(int nativeKeyCode) { }
        @Override public void keyUp(int nativeKeyCode) { }
        @Override public void typeText(String text) { }
        @Override public void mouseMove(int xAbs, int yAbs) { calls.add("move " + xAbs + "," + yAbs); }
        @Override public void mouseButton(int button, boolean press) { calls.add("button " + button + " " + press); }
        @Override public void scroll(int amount) { calls.add("scroll " + amount); }
        @Override public Point cursorPosition() { return cursor; }
    }
}
