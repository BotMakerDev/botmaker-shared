package com.botmaker.shared.capture;

import java.awt.Rectangle;

public class GenericWindow {
    /** The {@link #getPid()} of a window whose process is not known. */
    public static final long NO_PID = -1;

    private final Object nativeHandle; // Will hold HWND on Windows, Integer/Long on Linux
    private final String title;
    private final Rectangle rect;
    private final long pid;
    private final String windowClass;

    public GenericWindow(Object nativeHandle, String title, Rectangle rect) {
        this(nativeHandle, title, rect, NO_PID, null);
    }

    /**
     * {@code pid}: the process that owns the window, or {@link #NO_PID}. {@code windowClass}: which application
     * the window belongs to, or null — see {@link #getWindowClass()}. Together they tell the game's window from
     * one named after it (2026-10-13, {@code launch.GameWindows}).
     */
    public GenericWindow(Object nativeHandle, String title, Rectangle rect, long pid, String windowClass) {
        this.nativeHandle = nativeHandle;
        this.title = title;
        this.rect = rect;
        this.pid = pid > 0 ? pid : NO_PID;
        this.windowClass = windowClass == null || windowClass.isBlank() ? null : windowClass.trim();
    }

    public Object getNativeHandle() {
        return nativeHandle;
    }

    public String getTitle() {
        return title;
    }

    public Rectangle getRect() {
        return rect;
    }

    /** The owning process's id, or {@link #NO_PID} when the platform did not say. */
    public long getPid() {
        return pid;
    }

    /**
     * The application the window belongs to, the same on every computer whatever its title says: X11's
     * {@code WM_CLASS} (Wine gives the game's exe name, Proton {@code steam_app_<id>}); on Windows the owning
     * program's file name, since a Win32 class name is the toolkit's ({@code UnityWndClass}); on macOS the
     * owning app's name. Null when the platform did not say.
     */
    public String getWindowClass() {
        return windowClass;
    }
}
