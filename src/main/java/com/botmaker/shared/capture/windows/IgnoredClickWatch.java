package com.botmaker.shared.capture.windows;

import java.awt.image.BufferedImage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Notices when background clicks do nothing, and says what to do about it.
 *
 * <p>A posted click is invisible to a game that reads raw input or DirectInput: no error, no exception, the bot
 * just clicks and nothing happens. The first {@value #CLICKS} background clicks of the process are therefore
 * watched — of the run, since a bot and each ▶ Try run in a JVM of their own (Studio's Remote Pilot takes over
 * the input, so it posts nothing): the target window is captured before the click and again {@value #SETTLE_MS} ms after.
 * If not one of them changed a pixel, the {@code hint} is given once, naming the window and the setting that
 * fixes it. One click that did change something ends the watch for good.
 *
 * <p>It is a hint, not a decision: a click on empty space changes nothing either, and a game that animates all
 * the time changes every frame whatever the click did, so it never fires there. Nothing is switched for the
 * user — taking over the mouse is theirs to choose.
 *
 * @param <W> the window, as the caller captures it
 */
final class IgnoredClickWatch<W> {

    static final int CLICKS = 3;
    static final long SETTLE_MS = 400;
    /** A channel difference below this is compression or dithering noise, not a change. */
    private static final int NOISE = 24;

    private final Function<W, BufferedImage> capture;
    private final Consumer<String> hint;
    private final BiConsumer<Runnable, Long> later;

    private int unchanged;
    /** Clicks watched whose answer has not come back yet. */
    private int pending;
    private boolean done;

    /**
     * @param capture the window's frame now, or {@code null} when it can't be had (that click is not counted)
     * @param hint    told once, when every watched click changed nothing
     * @param later   runs the runnable after the given number of milliseconds, off the clicking thread
     */
    IgnoredClickWatch(Function<W, BufferedImage> capture, Consumer<String> hint, BiConsumer<Runnable, Long> later) {
        this.capture = capture;
        this.hint = hint;
        this.later = later;
    }

    /** Whether the next click is still worth watching; asked first, so a finished watch costs no capture. */
    synchronized boolean watching() {
        return !done;
    }

    /** Run {@code click} on {@code window} (named {@code title} in the hint), watching it while the watch lasts. */
    void around(W window, String title, Runnable click) {
        if (window == null || !begin()) {
            click.run();
            return;
        }
        BufferedImage before = capture.apply(window);
        click.run();
        if (before == null) {
            record(title, null);
            return;
        }
        later.accept(() -> record(title, changed(before, capture.apply(window))), SETTLE_MS);
    }

    /**
     * Whether to watch this click: only while the clicks already watched, answered or not, could still fall
     * short of {@value #CLICKS}. A bot clicking faster than the answers come back pays for no extra captures.
     */
    private synchronized boolean begin() {
        if (done || unchanged + pending >= CLICKS) {
            return false;
        }
        pending++;
        return true;
    }

    private synchronized void record(String title, Boolean changed) {
        pending--;
        if (done || changed == null) {
            return;
        }
        if (changed) {
            done = true;
            return;
        }
        if (++unchanged >= CLICKS) {
            done = true;
            hint.accept(CLICKS + " clicks in a row changed nothing in \"" + title + "\". Some games ignore "
                    + "input sent in the background: turn on \"Take over the mouse and keyboard\" in Bot Settings.");
        }
    }

    /** Whether {@code after} differs from {@code before} beyond noise; {@code null} when there is no after. */
    static Boolean changed(BufferedImage before, BufferedImage after) {
        if (after == null) {
            return null;
        }
        int w = before.getWidth();
        int h = before.getHeight();
        if (w != after.getWidth() || h != after.getHeight()) {
            return true;
        }
        int[] a = before.getRGB(0, 0, w, h, null, 0, w);
        int[] b = after.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i] && differs(a[i], b[i])) {
                return true;
            }
        }
        return false;
    }

    private static boolean differs(int p, int q) {
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs(((p >> shift) & 0xFF) - ((q >> shift) & 0xFF)) >= NOISE) {
                return true;
            }
        }
        return false;
    }
}
