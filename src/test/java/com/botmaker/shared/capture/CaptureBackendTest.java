package com.botmaker.shared.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the platform-agnostic parts of the capture facade — backend selection. (Actually grabbing
 * pixels needs a display/compositor and is exercised manually, not in unit tests.)
 */
class CaptureBackendTest {

    @Test
    void selectReturnsAPermittedBackend() {
        CaptureBackend backend = CaptureBackend.select();
        assertNotNull(backend);
        assertTrue(backend instanceof RobotCapture || backend instanceof SpectacleCapture,
                "select() must return one of the sealed permitted backends");
    }

    @Test
    void spectacleUnavailableWithoutWayland() {
        // isAvailable() requires WAYLAND_DISPLAY; in a typical headless/X11 test env it is absent,
        // so selection falls back to Robot. This documents the env-gated contract without asserting
        // on the ambient environment when Wayland *is* present.
        if (System.getenv("WAYLAND_DISPLAY") == null) {
            assertTrue(CaptureBackend.select() instanceof RobotCapture);
        } else {
            assertNotNull(CaptureBackend.select());
        }
    }
}
