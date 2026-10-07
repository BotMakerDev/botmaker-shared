package com.botmaker.shared.vnc;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bot's view of a VM screen, against {@link FakeVncServer}: what it captures, how a connection fails in
 * words, and the exact VNC messages its clicks, wheel and keys become.
 */
class VncControllerTest {

    private static final String V38 = "RFB 003.008\n";

    private static VncController connect(FakeVncServer server, String password) throws IOException {
        return VncController.connect("127.0.0.1", server.port(), password, Keysyms.NativeKeys.VIRTUAL_KEY,
                "Guest", 3_000);
    }

    @Test
    void theScreenIsOneWindowWhosePixelsAreWhatTheServerSent() throws Exception {
        try (FakeVncServer server = new FakeVncServer(V38, FakeVncServer.SECURITY_NONE, null, 4, 3);
             VncController vnc = connect(server, null)) {
            long seen = vnc.frames();
            server.raw(1, 1, 2, 1, new int[]{0xFF0000, 0x00FF00});
            seen = vnc.awaitFrame(seen, 3_000);
            server.raw(0, 2, 1, 1, new int[]{0x0000FF});
            vnc.awaitFrame(seen, 3_000);

            BufferedImage frame = vnc.captureScreen();
            assertEquals(new Dimension(4, 3), vnc.screenSize());
            assertEquals(0xFF0000, frame.getRGB(1, 1) & 0xFFFFFF);
            assertEquals(0x00FF00, frame.getRGB(2, 1) & 0xFFFFFF);
            assertEquals(0x0000FF, frame.getRGB(0, 2) & 0xFFFFFF);
            assertEquals(0x000000, frame.getRGB(3, 0) & 0xFFFFFF, "the first full frame, black");
            assertEquals(List.of(new Rectangle(0, 0, 4, 3)),
                    vnc.getAllWindows().stream().map(w -> w.getRect()).toList());
            assertTrue(vnc.supportsBackgroundInput());
            assertNull(server.failure);
        }
    }

    @Test
    void aCopiedAreaAndAResizeReachTheCapture() throws Exception {
        try (FakeVncServer server = new FakeVncServer(V38, FakeVncServer.SECURITY_NONE, null, 4, 2);
             VncController vnc = connect(server, null)) {
            long seen = vnc.frames();
            server.raw(0, 0, 1, 1, new int[]{0x123456});
            seen = vnc.awaitFrame(seen, 3_000);
            server.copyRect(3, 1, 1, 1, 0, 0);
            seen = vnc.awaitFrame(seen, 3_000);
            assertEquals(0x123456, vnc.captureScreen().getRGB(3, 1) & 0xFFFFFF);

            server.resize(6, 5);
            vnc.awaitFrame(seen, 3_000);
            assertEquals(new Dimension(6, 5), vnc.screenSize());
        }
    }

    @Test
    void aPasswordIsSentAsVncAuthenticationAndAWrongOrMissingOneIsASentence() throws Exception {
        try (FakeVncServer server = new FakeVncServer(V38, FakeVncServer.SECURITY_VNC, "s3cret!x", 2, 2);
             VncController vnc = connect(server, "s3cret!x")) {
            assertTrue(vnc.alive());
            assertNull(server.failure);
        }
        try (FakeVncServer server = new FakeVncServer(V38, FakeVncServer.SECURITY_VNC, "s3cret!x", 2, 2)) {
            IOException refused = assertThrows(IOException.class, () -> connect(server, "wrong"));
            assertTrue(refused.getMessage().contains("password"), refused.getMessage());
        }
        try (FakeVncServer server = new FakeVncServer(V38, FakeVncServer.SECURITY_VNC, "s3cret!x", 2, 2)) {
            IOException missing = assertThrows(IOException.class, () -> connect(server, null));
            assertTrue(missing.getMessage().contains("password"), missing.getMessage());
        }
    }

    @Test
    void anOlderServerIsSpokenTo() throws Exception {
        try (FakeVncServer server = new FakeVncServer("RFB 003.003\n", FakeVncServer.SECURITY_NONE, null, 2, 2);
             VncController vnc = connect(server, null)) {
            assertTrue(vnc.alive());
        }
    }

    @Test
    void aServerThatNeverPaintsIsASentenceNotAnEmptyController() throws Exception {
        try (FakeVncServer server = new FakeVncServer(V38, FakeVncServer.SECURITY_NONE, null, 2, 2)) {
            server.sendsFirstFrame = false;
            IOException silent = assertThrows(IOException.class, () -> VncController.connect("127.0.0.1",
                    server.port(), null, Keysyms.NativeKeys.VIRTUAL_KEY, "Guest", 300));
            assertTrue(silent.getMessage().contains("no picture"), silent.getMessage());
        }
    }

    @Test
    void aClickIsAMoveAPressAndARelease() throws Exception {
        try (FakeVncServer server = new FakeVncServer(V38, FakeVncServer.SECURITY_NONE, null, 100, 100);
             VncController vnc = connect(server, null)) {
            vnc.click(10, 20, 1);
            List<byte[]> pointer = server.inputs(3);
            assertArrayEquals(pointerEvent(0, 10, 20), pointer.get(0));
            assertArrayEquals(pointerEvent(1, 10, 20), pointer.get(1));
            assertArrayEquals(pointerEvent(0, 10, 20), pointer.get(2));
            assertEquals(new Point(10, 20), vnc.cursorPosition());

            vnc.mouseButton(3, true);
            assertArrayEquals(pointerEvent(4, 10, 20), server.inputs(1).get(0));
            vnc.mouseButton(3, false);
            assertArrayEquals(pointerEvent(0, 10, 20), server.inputs(1).get(0));

            vnc.mouseMove(500, -3);
            assertArrayEquals(pointerEvent(0, 99, 0), server.inputs(1).get(0), "kept on the screen");
        }
    }

    @Test
    void theWheelIsButtonsFourAndFivePressedAndReleased() throws Exception {
        try (FakeVncServer server = new FakeVncServer(V38, FakeVncServer.SECURITY_NONE, null, 100, 100);
             VncController vnc = connect(server, null)) {
            vnc.mouseMove(5, 6);
            server.inputs(1);
            vnc.scroll(-2);
            List<byte[]> wheel = server.inputs(4);
            assertArrayEquals(pointerEvent(16, 5, 6), wheel.get(0));
            assertArrayEquals(pointerEvent(0, 5, 6), wheel.get(1));
            assertArrayEquals(pointerEvent(16, 5, 6), wheel.get(2));
            vnc.scroll(1);
            assertArrayEquals(pointerEvent(8, 5, 6), server.inputs(2).get(0));
        }
    }

    @Test
    void windowsKeyCodesAndTextBecomeKeysymsWithShiftWhereTheKeyNeedsIt() throws Exception {
        try (FakeVncServer server = new FakeVncServer(V38, FakeVncServer.SECURITY_NONE, null, 10, 10);
             VncController vnc = connect(server, null)) {
            vnc.keyDown('A');
            vnc.keyUp('A');
            List<byte[]> keys = server.inputs(2);
            assertArrayEquals(keyEvent(true, 'a'), keys.get(0));
            assertArrayEquals(keyEvent(false, 'a'), keys.get(1));

            vnc.typeText("é!\n");
            List<byte[]> typed = server.inputs(8);
            assertArrayEquals(keyEvent(true, 0xE9), typed.get(0));
            assertArrayEquals(keyEvent(false, 0xE9), typed.get(1));
            assertArrayEquals(keyEvent(true, 0xFFE1), typed.get(2), "! is typed with Shift held");
            assertArrayEquals(keyEvent(true, '!'), typed.get(3));
            assertArrayEquals(keyEvent(false, '!'), typed.get(4));
            assertArrayEquals(keyEvent(false, 0xFFE1), typed.get(5));
            assertArrayEquals(keyEvent(true, 0xFF0D), typed.get(6));
        }
    }

    @Test
    void aServerThatHangsUpLeavesNoWindowAndDropsInput() throws Exception {
        try (FakeVncServer server = new FakeVncServer(V38, FakeVncServer.SECURITY_NONE, null, 2, 2);
             VncController vnc = connect(server, null)) {
            long seen = vnc.frames();
            server.raw(0, 0, 1, 1, new int[]{0xABCDEF});
            vnc.awaitFrame(seen, 3_000);
            server.hangUp();
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (vnc.alive() && System.nanoTime() < deadline) Thread.sleep(20);

            assertFalse(vnc.alive());
            assertTrue(vnc.failure() != null);
            assertEquals(List.of(), vnc.getAllWindows());
            assertNull(vnc.getForegroundWindow());
            vnc.click(1, 1, 1);
            assertEquals(0xABCDEF, vnc.captureScreen().getRGB(0, 0) & 0xFFFFFF, "the last frame stays readable");
        }
    }

    @Test
    void nothingListeningIsASentence() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        int closed = port;
        IOException e = assertThrows(IOException.class, () -> VncController.connect("127.0.0.1", closed, null,
                Keysyms.NativeKeys.VIRTUAL_KEY, "Guest", 1_000));
        assertTrue(e.getMessage().startsWith("Nothing answers VNC"), e.getMessage());
    }

    private static byte[] pointerEvent(int buttons, int x, int y) {
        return ByteBuffer.allocate(6).put((byte) 5).put((byte) buttons).putShort((short) x).putShort((short) y)
                .array();
    }

    private static byte[] keyEvent(boolean down, int keysym) {
        return ByteBuffer.allocate(8).put((byte) 4).put((byte) (down ? 1 : 0)).putShort((short) 0).putInt(keysym)
                .array();
    }
}
