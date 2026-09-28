package com.botmaker.shared.capture;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the platform-agnostic parts of the capture facade — backend selection and the argument vectors.
 * (Actually grabbing pixels needs a display/compositor and is exercised manually, not in unit tests.)
 */
class CaptureBackendTest {

    @Test
    void selectReturnsAPermittedBackend() {
        CaptureBackend backend = CaptureBackend.select();
        assertNotNull(backend);
        assertTrue(backend instanceof RobotCapture || backend instanceof ToolCapture,
                "select() must return one of the sealed permitted backends");
    }

    @Test
    void noScreenshotProgramWithoutWayland() {
        // available() requires WAYLAND_DISPLAY; in a typical headless/X11 test env it is absent,
        // so selection falls back to Robot. This documents the env-gated contract without asserting
        // on the ambient environment when Wayland *is* present.
        if (System.getenv("WAYLAND_DISPLAY") == null) {
            assertTrue(ToolCapture.available().isEmpty());
            assertTrue(CaptureBackend.select() instanceof RobotCapture);
        } else {
            assertNotNull(CaptureBackend.select());
        }
    }

    @Test
    void eachProgramWritesTheWholeDesktopToTheLastArgument() {
        Path out = Path.of("/tmp/shot.png");
        assertArrayEquals(new String[]{"spectacle", "-b", "-n", "-f", "-o", "/tmp/shot.png"},
                ToolCapture.SPECTACLE.argv(out));
        assertArrayEquals(new String[]{"grim", "/tmp/shot.png"}, ToolCapture.GRIM.argv(out));
        assertArrayEquals(new String[]{"gnome-screenshot", "-f", "/tmp/shot.png"},
                ToolCapture.GNOME_SCREENSHOT.argv(out));
    }
}
