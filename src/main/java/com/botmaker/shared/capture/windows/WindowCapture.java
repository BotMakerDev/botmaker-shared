package com.botmaker.shared.capture.windows;

import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.platform.win32.WinGDI;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * Per-window pixel capture on Windows. GDI {@code PrintWindow(PW_RENDERFULLCONTENT)} is the primary
 * path (works for most GPU/hardware-composited windows); it falls back to AWT {@link Robot} for
 * fullscreen windows and whenever GDI returns a black/invalid frame. Returns {@code null} if no usable
 * frame can be produced, so callers can apply their own full-desktop fallback.
 */
public final class WindowCapture {

	private WindowCapture() {}

	public static BufferedImage capture(HWND hWnd) {
		RECT windowRect = new RECT();
		if (!User32.INSTANCE.GetWindowRect(hWnd.getPointer(), windowRect)
				|| windowRect.right - windowRect.left <= 0 || windowRect.bottom - windowRect.top <= 0) {
			return null;
		}

		boolean foreground = User32.INSTANCE.GetForegroundWindow().equals(hWnd);

		// A foreground window that fills a whole monitor is (borderless-)fullscreen. GDI PrintWindow
		// frequently returns black for such D3D/OpenGL game surfaces, so the on-screen framebuffer (Robot)
		// is the reliable source. (True *exclusive*-fullscreen bypasses the DWM and can't be captured by
		// either GDI or Robot — the borderless-windowed workaround is required; see ROADMAP.)
		if (foreground && coversAnyMonitor(windowRect)) {
			BufferedImage robot = captureWithRobot(windowRect);
			if (robot != null && !isBlack(robot)) {
				return robot;
			}
		}

		// Windowed mode: GDI is fast and captures occluded/background windows without raising them.
		BufferedImage image = captureWithGDI(hWnd);
		if (image == null || image.getWidth() == 0 || image.getHeight() == 0 || isBlack(image)) {
			// PrintWindow came back black/invalid — fall back to the on-screen framebuffer at the window's rect.
			BufferedImage robot = captureWithRobot(windowRect);
			if (robot != null && !isBlack(robot)) {
				return robot;
			}
		}
		return image;
	}

	/** Whether {@code windowRect} covers (to a small tolerance) the full bounds of any connected monitor. */
	private static boolean coversAnyMonitor(RECT windowRect) {
		int w = windowRect.right - windowRect.left;
		int h = windowRect.bottom - windowRect.top;
		try {
			GraphicsDevice[] devices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
			for (GraphicsDevice device : devices) {
				Rectangle b = device.getDefaultConfiguration().getBounds();
				if (Math.abs(w - b.width) <= 2 && Math.abs(h - b.height) <= 2) {
					return true;
				}
			}
		} catch (HeadlessException ignored) {
			// No displays (headless) — treat as non-fullscreen.
		}
		return false;
	}

	private static BufferedImage captureWithGDI(HWND hWnd) {
		HDC hdcWindow = User32.INSTANCE.GetDC(hWnd);
		HDC hdcMemDC = GDI32.INSTANCE.CreateCompatibleDC(hdcWindow);

		RECT bounds = new RECT();
		User32.INSTANCE.GetClientRect(hWnd, bounds);

		int width = bounds.right - bounds.left;
		int height = bounds.bottom - bounds.top;

		if (width <= 0 || height <= 0) {
			User32.INSTANCE.ReleaseDC(hWnd, hdcWindow);
			GDI32.INSTANCE.DeleteDC(hdcMemDC);
			return null;
		}

		HBITMAP hBitmap = GDI32.INSTANCE.CreateCompatibleBitmap(hdcWindow, width, height);
		GDI32.INSTANCE.SelectObject(hdcMemDC, hBitmap.getPointer());

		User32.INSTANCE.PrintWindow(hWnd, hdcMemDC, 2); // 2 = PW_RENDERFULLCONTENT

		WinGDI.BITMAPINFO bmi = new WinGDI.BITMAPINFO();
		bmi.bmiHeader.biWidth = width;
		bmi.bmiHeader.biHeight = -height; // Top-down image
		bmi.bmiHeader.biPlanes = 1;
		bmi.bmiHeader.biBitCount = 32;
		bmi.bmiHeader.biCompression = WinGDI.BI_RGB;

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		GDI32.INSTANCE.GetDIBits(hdcWindow, hBitmap, 0, height, ((DataBufferInt) image.getRaster().getDataBuffer()).getData(), bmi, WinGDI.DIB_RGB_COLORS);

		GDI32.INSTANCE.DeleteObject(hBitmap);
		GDI32.INSTANCE.DeleteDC(hdcMemDC);
		User32.INSTANCE.ReleaseDC(hWnd, hdcWindow);

		return image;
	}

	private static BufferedImage captureWithRobot(RECT bounds) {
		int width = bounds.right - bounds.left;
		int height = bounds.bottom - bounds.top;
		if (width <= 0 || height <= 0) {
			return null;
		}
		try {
			return new Robot().createScreenCapture(new Rectangle(bounds.left, bounds.top, width, height));
		} catch (AWTException e) {
			e.printStackTrace();
			return null;
		}
	}

	private static boolean isBlack(BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		// Check a few pixels to see if they are black. A small sample is enough.
		for (int i = 0; i < 10; i++) {
			int x = (int) (Math.random() * width);
			int y = (int) (Math.random() * height);
			if ((image.getRGB(x, y) & 0x00FFFFFF) != 0) {
				return false; // Found a non-black pixel
			}
		}
		return true;
	}
}
