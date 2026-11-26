// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.capture.mac;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Mac's pick over a window list shaped as {@code CGWindowListCopyWindowInfo} answers it, front to back: the menu
 * bar, the Dock, an invisible and a 1×1 window of Studio, then the game over Steam. No CoreGraphics.
 */
class MacPointerTest {

    private static List<MacWindow> recorded() throws IOException {
        try (InputStream in = MacPointerTest.class.getResourceAsStream("/mac/window-list.json")) {
            List<MacWindow> windows = new ArrayList<>();
            for (JsonNode w : new ObjectMapper().readTree(in)) {
                JsonNode b = w.get("kCGWindowBounds");
                windows.add(new MacWindow(w.get("kCGWindowNumber").asLong(), w.get("kCGWindowOwnerPID").asInt(),
                        w.get("kCGWindowName").asText(), w.get("kCGWindowOwnerName").asText(),
                        new Rectangle2D.Double(b.get("X").asDouble(), b.get("Y").asDouble(),
                                b.get("Width").asDouble(), b.get("Height").asDouble()),
                        w.get("kCGWindowLayer").asInt(), w.get("kCGWindowAlpha").asDouble()));
            }
            return windows;
        }
    }

    private static Optional<Long> idAt(double x, double y) throws IOException {
        return MacPointer.pointedAt(recorded(), x, y).map(MacWindow::id);
    }

    @Test
    void theFrontMostOrdinaryWindowUnderThePointIsPicked() throws IOException {
        assertEquals(Optional.of(140L), idAt(600, 500), "Studio's invisible and 1×1 windows above it are skipped");
        assertEquals(Optional.of(141L), idAt(100, 100), "Steam where the game does not cover it");
    }

    @Test
    void theMenuBarAndTheDockAreNoPickEvenOverAWindow() throws IOException {
        assertEquals(Optional.empty(), idAt(1400, 10), "the menu bar");
        assertEquals(Optional.empty(), idAt(600, 860), "the Dock, over the game");
        assertEquals(Optional.of(140L), idAt(600, 840), "just above the Dock, the game");
        assertEquals(Optional.empty(), idAt(1500, 840), "the bare desktop");
    }
}
