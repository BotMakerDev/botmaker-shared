package com.botmaker.shared.capture.windows;

import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.RobotCapture;
import com.botmaker.shared.capture.ScreenCapture;
import com.botmaker.shared.capture.ScreenGeometry;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinGDI;

import java.awt.AWTException;
import java.awt.HeadlessException;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.List;

/**
 * One window's pixels on Windows: its <b>client area</b>, in physical pixels — the same rectangle
 * {@link WindowsController} reports as the window's rect, so a pixel found in the frame plus the rect's corner is
 * the screen pixel a click goes to.
 *
 * <p>The ladder, first usable frame wins:
 * <ol>
 *   <li>Windows.Graphics.Capture, when the run asks for it ({@link WgcCapture}, opt-in): the compositor's copy,
 *       right for DirectX games and for covered windows.</li>
 *   <li>A screen copy, first for a window that fills a screen and is on top: {@code PrintWindow} is often black
 *       for a fullscreen game, and the screen shows exactly it.</li>
 *   <li>{@code PrintWindow(PW_CLIENTONLY | PW_RENDERFULLCONTENT)}: works on a covered or background window.</li>
 *   <li>A screen copy for any window that is on top at its own rect.</li>
 * </ol>
 * A screen copy is only ever taken of a window that nothing covers: it reads whatever is visible there, and a
 * covered window's copy is the covering window's pixels — what the old fallback returned. A window nothing can
 * read comes back black from {@code PrintWindow} or {@code null}, never as another window.
 */
public final class WindowCapture {

    private WindowCapture() {}

    public static BufferedImage capture(HWND hWnd) {
        try (WindowsDpi.Scope dpi = WindowsDpi.physical()) {
            Rectangle client = WindowsController.clientRect(hWnd);
            if (client == null || client.isEmpty()) {
                return null;
            }
            if (WgcCapture.requested()) {
                BufferedImage wgc = WgcCapture.capture(hWnd);
                if (!WindowFrames.isAllBlack(wgc)) {
                    return wgc;
                }
            }
            boolean onTop = onTop(hWnd, client);
            if (onTop && WindowFrames.coversAScreen(client, screens())) {
                BufferedImage screen = screenCopy(client);
                if (!WindowFrames.isAllBlack(screen)) {
                    return screen;
                }
            }
            BufferedImage printed = printWindow(hWnd, client.width, client.height);
            if (!WindowFrames.isAllBlack(printed) || !onTop) {
                return printed;
            }
            BufferedImage screen = screenCopy(client);
            return WindowFrames.isAllBlack(screen) ? printed : screen;
        }
    }

    /** Whether {@code hWnd} itself is what shows at every probe point of {@code client}. */
    private static boolean onTop(HWND hWnd, Rectangle client) {
        HWND root = User32.INSTANCE.GetAncestor(hWnd, User32.GA_ROOT);
        for (Point p : WindowFrames.probes(client)) {
            HWND there = User32.INSTANCE.WindowFromPoint(new POINT.ByValue(p.x, p.y));
            if (there == null || !root.equals(User32.INSTANCE.GetAncestor(there, User32.GA_ROOT))) {
                return false;
            }
        }
        return true;
    }

    private static List<Rectangle> screens() {
        try {
            return ScreenCapture.screens().stream().map(ScreenGeometry.Screen::device).toList();
        } catch (HeadlessException e) {
            return List.of();
        }
    }

    private static BufferedImage printWindow(HWND hWnd, int width, int height) {
        HDC hdcWindow = User32.INSTANCE.GetDC(hWnd);
        HDC hdcMemDC = GDI32.INSTANCE.CreateCompatibleDC(hdcWindow);
        HBITMAP hBitmap = GDI32.INSTANCE.CreateCompatibleBitmap(hdcWindow, width, height);
        try {
            GDI32.INSTANCE.SelectObject(hdcMemDC, hBitmap.getPointer());
            // PW_CLIENTONLY: without it the whole window, title bar included, is drawn from the bitmap's corner,
            // and a client-sized bitmap holds the title bar and loses the client area's bottom edge.
            User32.INSTANCE.PrintWindow(hWnd, hdcMemDC, PW_CLIENTONLY | PW_RENDERFULLCONTENT);

            WinGDI.BITMAPINFO bmi = new WinGDI.BITMAPINFO();
            bmi.bmiHeader.biWidth = width;
            bmi.bmiHeader.biHeight = -height; // top-down
            bmi.bmiHeader.biPlanes = 1;
            bmi.bmiHeader.biBitCount = 32;
            bmi.bmiHeader.biCompression = WinGDI.BI_RGB;

            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            GDI32.INSTANCE.GetDIBits(hdcWindow, hBitmap, 0, height,
                    ((DataBufferInt) image.getRaster().getDataBuffer()).getData(), bmi, WinGDI.DIB_RGB_COLORS);
            return image;
        } finally {
            GDI32.INSTANCE.DeleteObject(hBitmap);
            GDI32.INSTANCE.DeleteDC(hdcMemDC);
            User32.INSTANCE.ReleaseDC(hWnd, hdcWindow);
        }
    }

    private static final int PW_CLIENTONLY = 1;
    private static final int PW_RENDERFULLCONTENT = 2;

    /** {@code rect} of the screen, in device pixels whatever AWT's scale. */
    private static BufferedImage screenCopy(Rectangle rect) {
        try {
            return RobotCapture.capture(new Robot(), rect);
        } catch (AWTException | HeadlessException e) {
            Diag.error("[Windows] screen copy failed: " + e.getMessage(), e);
            return null;
        }
    }
}
