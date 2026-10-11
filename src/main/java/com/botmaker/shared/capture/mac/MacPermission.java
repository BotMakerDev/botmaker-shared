package com.botmaker.shared.capture.mac;

import com.botmaker.shared.Diag;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The two macOS privacy grants the controller needs, and what to tell the user when one is missing.
 *
 * <p>macOS never fails these calls: without Screen Recording a window image shows the desktop instead of the
 * window, and without Accessibility a posted event simply vanishes. So the controller asks first, and when the
 * answer is no it says so once, by name, with the pane that grants it, and asks macOS for its own prompt.
 *
 * <p>A grant belongs to the app that launched the process: BotMaker Studio for a bot it runs, the terminal for
 * a bot started from one. A grant takes effect when that app is restarted.
 */
public enum MacPermission {

    SCREEN_RECORDING("Screen Recording", "Privacy_ScreenCapture"),
    ACCESSIBILITY("Accessibility", "Privacy_Accessibility");

    private final String displayName;
    private final String pane;
    private static final long GRANT_FRESH_MS = 2000;

    private final AtomicBoolean told = new AtomicBoolean();
    private volatile long grantedAt = Long.MIN_VALUE / 2;

    MacPermission(String displayName, String pane) {
        this.displayName = displayName;
        this.pane = pane;
    }

    public String displayName() {
        return displayName;
    }

    /** Opens the right pane of System Settings, with {@code open}. */
    public String settingsUri() {
        return "x-apple.systempreferences:com.apple.preference.security?" + pane;
    }

    public boolean granted() {
        return switch (this) {
            case SCREEN_RECORDING -> CoreGraphics.INSTANCE.CGPreflightScreenCaptureAccess() != 0;
            case ACCESSIBILITY -> Accessibility.INSTANCE.AXIsProcessTrusted() != 0;
        };
    }

    /**
     * Whether it is granted; when it is not, says so once per process and asks macOS to prompt. Callers then go
     * ahead or give up as their contract says (a capture answers {@code null}).
     */
    boolean check() {
        // A grant held a moment ago still holds: every posted event checks, and each check is a call into TCC.
        long now = System.currentTimeMillis();
        if (now - grantedAt < GRANT_FRESH_MS) {
            return true;
        }
        if (granted()) {
            grantedAt = now;
            return true;
        }
        if (told.compareAndSet(false, true)) {
            Diag.warn("macOS", hint());
            prompt();
        }
        return false;
    }

    String hint() {
        return "BotMaker needs the " + displayName + " permission: System Settings › Privacy & Security › "
                + displayName + ", turn on the app that started this run (BotMaker Studio, or your terminal), "
                + "then restart that app.";
    }

    private void prompt() {
        switch (this) {
            case SCREEN_RECORDING -> CoreGraphics.INSTANCE.CGRequestScreenCaptureAccess();
            case ACCESSIBILITY -> MacAx.promptForTrust();
        }
    }
}
