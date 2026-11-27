// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.capture;

import com.sun.jna.Pointer;
import com.sun.jna.PointerType;

import java.awt.Rectangle;
import java.util.Optional;

public class GenericWindow {

    /** A native handle that says its window's id itself (macOS's window number). */
    public interface Identified {
        /** The window's id on its desktop, as {@link #id()} gives it. */
        String windowId();
    }

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

    /**
     * The window's id on its desktop, which stays while the window is open whatever its title or place: the X11 window
     * id or the {@code HWND} in hex, macOS's window number. Empty for a handle that has none (a game VM's guest
     * window). Two runs on one desktop tell their windows apart by it (spec {@code 2026-11-26-run-places-design.md}).
     */
    public Optional<String> id() {
        return switch (nativeHandle) {
            case Pointer pointer -> Optional.of(Long.toHexString(Pointer.nativeValue(pointer)));
            case PointerType handle -> Optional.of(Long.toHexString(Pointer.nativeValue(handle.getPointer())));
            case Identified identified -> Optional.of(identified.windowId());
            case Long number -> Optional.of(Long.toHexString(number));
            case Integer number -> Optional.of(Integer.toHexString(number));
            case null, default -> Optional.empty();
        };
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
