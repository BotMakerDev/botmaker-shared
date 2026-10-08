package com.botmaker.shared.vnc;

import java.awt.Rectangle;

/**
 * One window on the screen a {@link VncController} shows, as the machine behind that screen lists it: the guest's
 * own window handle, its process, where it sits in the screen's pixels, and whether it has the focus.
 *
 * @param id      the guest's handle for it, which stays the same while the window lives
 * @param process the program's name without its extension ({@code notepad}), empty when unknown
 */
public record GuestWindow(long id, String title, String process, Rectangle rect, boolean foreground) {

    public GuestWindow {
        title = title == null ? "" : title;
        process = process == null ? "" : process;
        rect = new Rectangle(rect);
    }

    @Override
    public Rectangle rect() {
        return new Rectangle(rect);
    }
}
