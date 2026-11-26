// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.capture.linux;

import com.botmaker.shared.capture.linux.KWinPointer.KWinWindow;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KWin's {@code queryWindowInfo} answer as {@code gdbus} prints it. The fixture follows gdbus's GVariant text format
 * and KWin's keys; a recording by a click on this PC is owed (plan phase 7).
 */
class KWinPointerTest {

    private static String fixture() throws IOException {
        try (InputStream in = KWinPointerTest.class.getResourceAsStream("/kwin/query-window-info.txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void readsTheClickedWindowFromARecordedAnswer() throws IOException {
        Optional<KWinWindow> window = KWinPointer.parse(fixture());
        assertEquals(Optional.of(new KWinWindow("steam_app_1145350", "Hades II — It's \"the\" game",
                0, 0, 2560, 1440)), window);
    }

    @Test
    void anEmptyAnswerIsNothingPicked() {
        assertTrue(KWinPointer.parse("(@a{sv} {},)\n").isEmpty());
    }

    @Test
    void readsTypedNumbersAndSingleQuotedCaptions() {
        Optional<KWinWindow> window = KWinPointer.parse(
                "({'caption': <'Balatro'>, 'resourceClass': <'balatro.exe'>, 'x': <int32 10>, 'y': <20.5>,"
                        + " 'width': <1920.0>, 'height': <1080.0>},)");
        assertEquals(Optional.of(new KWinWindow("balatro.exe", "Balatro", 10, 20.5, 1920, 1080)), window);
    }
}
