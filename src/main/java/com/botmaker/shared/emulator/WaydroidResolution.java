package com.botmaker.shared.emulator;

/**
 * The Android framebuffer size Waydroid boots with, and the only remedy in the Waydroid stack that BotMaker
 * can apply itself (it needs no root).
 *
 * <p><b>Why this is not optional.</b> gamescope sizes the window it hosts Waydroid in with {@code -w/-h}. If
 * Android's own framebuffer is a different size, the compositor scales or letterboxes it — and every
 * coordinate a bot matched against a template is then off by that transform, silently. A template authored at
 * 1080×1920 keeps matching (matching is scale-tolerant) while the tap lands somewhere else, which is the worst
 * shape a bug can have. So the size is set on both sides from one place, here.
 *
 * <p><b>Why the ordering is what it is.</b> {@code waydroid prop set} talks to the running container, so the
 * session has to be up to change the value — and the value is only read at session start, so it has to go
 * down and up again for the change to take. That restart is user-visible (the Android UI disappears and
 * reboots), which is exactly why {@link #apply} is a no-op when the values already match: on the normal launch
 * path, where nothing changed, nothing restarts.
 *
 * @param width  the Android framebuffer width in pixels
 * @param height the Android framebuffer height in pixels
 */
public record WaydroidResolution(int width, int height) {

    /** The persistent Android properties Waydroid reads its display size from at session start. */
    public static final String WIDTH_PROP = "persist.waydroid.width";
    public static final String HEIGHT_PROP = "persist.waydroid.height";

    public WaydroidResolution {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("A resolution must be positive, got " + width + "x" + height);
        }
    }

    /** {@code 1080x1920}, for logs and the diagnostics panel. */
    @Override
    public String toString() {
        return width + "x" + height;
    }

    /**
     * The configured size, or {@code null} when the properties are unset (Waydroid then picks its own default)
     * or unreadable. Not an exception and not a fabricated default: "unset" is a real, common state and the
     * callers distinguish it — {@link WaydroidPlatform} omits gamescope's sizing flags rather than guessing.
     */
    public static WaydroidResolution read() {
        if (!WaydroidCli.available()) {
            return null;
        }
        return parse(WaydroidCli.waydroid("prop", "get", WIDTH_PROP),
                WaydroidCli.waydroid("prop", "get", HEIGHT_PROP));
    }

    /**
     * Builds a resolution from two raw {@code waydroid prop get} outputs. An unset property prints an empty
     * line, so a blank — or anything non-numeric — reads as "not configured" rather than as a parse failure.
     * Package-private and pure so the parse is testable without a container.
     */
    static WaydroidResolution parse(String widthOutput, String heightOutput) {
        Integer w = positiveInt(widthOutput);
        Integer h = positiveInt(heightOutput);
        return w == null || h == null ? null : new WaydroidResolution(w, h);
    }

    private static Integer positiveInt(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Makes the container boot at this size, restarting the session so the new value takes effect — and does
     * <b>nothing</b> when it is already configured that way, which is the case on every launch after the
     * first.
     *
     * <p>Requires a running session to set the properties on, so it starts one if there is none. Returns
     * whether the size is now configured as asked; best-effort and never throws, like everything else in this
     * package.
     */
    public boolean apply() {
        if (!WaydroidCli.available()) {
            return false;
        }
        if (this.equals(read())) {
            return true;   // the common path: no restart, nothing user-visible happens
        }
        if (!WaydroidStatus.read().sessionRunning()) {
            // prop set goes to the container, so there has to be one to talk to.
            WaydroidCli.waydroid("session", "start");
        }
        boolean set = WaydroidCli.waydroid("prop", "set", WIDTH_PROP, Integer.toString(width)) != null
                && WaydroidCli.waydroid("prop", "set", HEIGHT_PROP, Integer.toString(height)) != null;
        if (!set) {
            return false;
        }
        // Read at session start only — without the cycle the properties are stored and ignored.
        WaydroidCli.waydroid("session", "stop");
        WaydroidCli.waydroid("session", "start");
        return true;
    }
}
