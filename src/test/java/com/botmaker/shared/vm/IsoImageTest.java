package com.botmaker.shared.vm;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The answer disc, read back the way a drive would: from its volume descriptors to each tree's files. */
class IsoImageTest {

    @Test
    void bothTreesListTheFilesAndPointAtTheirBytes() {
        byte[] answer = "<unattend/>".getBytes(StandardCharsets.UTF_8);
        byte[] big = new byte[5000];
        for (int i = 0; i < big.length; i++) big[i] = (byte) i;
        byte[] iso = IsoImage.bytes("botmaker", Map.of("autounattend.xml", answer, "big-file.bin", big));

        assertEquals(0, iso.length % IsoImage.SECTOR);
        ByteBuffer b = ByteBuffer.wrap(iso).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, iso[16 * 2048]);
        assertEquals("CD001", new String(iso, 16 * 2048 + 1, 5, StandardCharsets.US_ASCII));
        assertEquals(iso.length / 2048, b.getInt(16 * 2048 + 80), "the volume space size");
        assertEquals("BOTMAKER", new String(iso, 16 * 2048 + 40, 8, StandardCharsets.US_ASCII));
        assertEquals(2, iso[17 * 2048]);
        assertArrayEquals(new byte[] {0x25, 0x2F, 0x45}, slice(iso, 17 * 2048 + 88, 3), "Joliet's escape");
        assertEquals((byte) 255, iso[18 * 2048], "the terminator");

        Map<String, byte[]> primary = files(iso, 16, StandardCharsets.US_ASCII);
        assertEquals(java.util.List.of("AUTOUNAT.XML;1", "BIG_FILE.BIN;1"), java.util.List.copyOf(primary.keySet()));
        Map<String, byte[]> joliet = files(iso, 17, StandardCharsets.UTF_16BE);
        assertEquals(java.util.List.of("autounattend.xml", "big-file.bin"), java.util.List.copyOf(joliet.keySet()));
        assertArrayEquals(answer, joliet.get("autounattend.xml"));
        assertArrayEquals(big, joliet.get("big-file.bin"));
        assertArrayEquals(big, primary.get("BIG_FILE.BIN;1"));
    }

    @Test
    void thePathTablesNameTheRoot() {
        byte[] iso = IsoImage.bytes("x", Map.of("a.txt", new byte[] {1}));
        ByteBuffer b = ByteBuffer.wrap(iso);
        for (int descriptor : new int[] {16, 17}) {
            int at = descriptor * 2048;
            int root = b.order(ByteOrder.LITTLE_ENDIAN).getInt(at + 156 + 2);
            int little = b.order(ByteOrder.LITTLE_ENDIAN).getInt(at + 140);
            int big = b.order(ByteOrder.BIG_ENDIAN).getInt(at + 148);
            assertEquals(10, b.order(ByteOrder.LITTLE_ENDIAN).getInt(at + 132));
            assertEquals(root, b.order(ByteOrder.LITTLE_ENDIAN).getInt(little * 2048 + 2));
            assertEquals(root, b.order(ByteOrder.BIG_ENDIAN).getInt(big * 2048 + 2));
            assertEquals(1, b.order(ByteOrder.LITTLE_ENDIAN).getShort(little * 2048 + 6), "its parent is itself");
        }
    }

    @Test
    void thePlainTreesNamesAreUniqueEightDotThree() {
        java.util.Set<String> taken = new java.util.HashSet<>();
        assertEquals("AUTOUNAT.XML;1", IsoImage.primaryName("autounattend.xml", taken));
        assertEquals("AUTOUN~1.XML;1", IsoImage.primaryName("AUTOUNATTEND.xml", taken));
        assertEquals("A_B.TAR;1", IsoImage.primaryName("a.b.tar", taken));
        assertEquals("README.;1", IsoImage.primaryName("readme", taken));
    }

    @Test
    void namesADiscCantHoldAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> IsoImage.bytes("x", Map.of("a b.txt", new byte[0])));
        Map<String, byte[]> many = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) many.put("a-rather-long-file-name-number-" + i + ".txt", new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> IsoImage.bytes("x", many));
    }

    /** The files in the root directory a volume descriptor names, in directory order. */
    private static Map<String, byte[]> files(byte[] iso, int descriptor, java.nio.charset.Charset names) {
        ByteBuffer b = ByteBuffer.wrap(iso).order(ByteOrder.LITTLE_ENDIAN);
        int root = b.getInt(descriptor * 2048 + 156 + 2);
        int rootLength = b.getInt(descriptor * 2048 + 156 + 10);
        Map<String, byte[]> files = new LinkedHashMap<>();
        int at = root * 2048;
        int end = at + rootLength;
        while (at < end && iso[at] != 0) {
            int size = iso[at] & 0xFF;
            int extent = b.getInt(at + 2);
            int length = b.getInt(at + 10);
            assertEquals(extent, ByteBuffer.wrap(iso).order(ByteOrder.BIG_ENDIAN).getInt(at + 6), "both-endian");
            boolean directory = (iso[at + 25] & 2) != 0;
            int idLength = iso[at + 32] & 0xFF;
            if (!directory) files.put(new String(iso, at + 33, idLength, names), slice(iso, extent * 2048, length));
            at += size;
        }
        return files;
    }

    private static byte[] slice(byte[] bytes, int from, int length) {
        byte[] out = new byte[length];
        System.arraycopy(bytes, from, out, 0, length);
        return out;
    }
}
