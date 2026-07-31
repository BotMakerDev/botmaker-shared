package com.botmaker.shared.capture;

import java.awt.Rectangle;

public class GenericWindow {
    private final Object nativeHandle; // Will hold HWND on Windows, Integer/Long on Linux
    private final String title;
    private final Rectangle rect;

    public GenericWindow(Object nativeHandle, String title, Rectangle rect) {
        this.nativeHandle = nativeHandle;
        this.title = title;
        this.rect = rect;
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
}
