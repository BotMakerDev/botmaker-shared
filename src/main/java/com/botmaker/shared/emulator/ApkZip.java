package com.botmaker.shared.emulator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Inflater;

/**
 * An APK's entries, read <b>without downloading the APK</b>: a ZIP is designed to be read backwards —
 * end-of-central-directory, then the central directory, then one entry — so a few bounded byte ranges get one
 * file out however large the archive is. {@link ApkIcon} takes the launcher icon this way and {@link ApkLabel}
 * the manifest and {@code resources.arsc}; {@link Reader} is the only thing that knows the ranges come over ADB.
 *
 * <p>Never throws: Zip64, a truncated read or a corrupt entry is an empty answer.
 */
final class ApkZip {

    /** Random access to an archive that lives somewhere else — on a device, over ADB. */
    interface Reader {
        /** The archive's total length in bytes, or a non-positive value when it can't be determined. */
        long size();

        /** Up to {@code length} bytes at {@code offset}; a short (or empty) array is a failure, never an error. */
        byte[] read(long offset, int length);
    }

    /** One central-directory record, reduced to what reading its bytes back needs. */
    record Entry(String name, int method, int compressedSize, int uncompressedSize, long localOffset) {}

    private static final int EOCD_SIG = 0x06054b50;
    private static final int CENTRAL_SIG = 0x02014b50;
    private static final int LOCAL_SIG = 0x04034b50;

    /** The largest an end-of-central-directory record can be: 22 fixed bytes plus a 64 KB comment. */
    private static final int EOCD_MAX = 65_557;

    /** A sanity ceiling on the central directory read — a real APK's is well under this even with 50k entries. */
    private static final int CENTRAL_MAX = 16 << 20;

    private ApkZip() {}

    /** Every entry in the archive's central directory, or an empty list when it can't be read. */
    static List<Entry> entries(Reader reader) {
        try {
            long size = reader.size();
            if (size <= 0) return List.of();
            int tailLength = (int) Math.min(size, EOCD_MAX);
            byte[] tail = reader.read(size - tailLength, tailLength);
            int eocd = lastSignature(tail, EOCD_SIG);
            if (eocd < 0 || eocd + 20 > tail.length) return List.of();
            int centralSize = int32(tail, eocd + 12);
            long centralOffset = uint32(tail, eocd + 16);
            // 0xFFFFFFFF in either field means the real value is in a Zip64 record. Bail rather than mis-read.
            if (centralSize <= 0 || centralSize > CENTRAL_MAX || centralOffset <= 0 || centralOffset >= size) {
                return List.of();
            }
            return parseCentral(reader.read(centralOffset, centralSize));
        } catch (Exception e) {
            return List.of();
        }
    }

    static List<Entry> parseCentral(byte[] central) {
        List<Entry> entries = new ArrayList<>();
        int p = 0;
        while (p + 46 <= central.length && int32(central, p) == CENTRAL_SIG) {
            int method = uint16(central, p + 10);
            int compressed = int32(central, p + 20);
            int uncompressed = int32(central, p + 24);
            int nameLength = uint16(central, p + 28);
            int extraLength = uint16(central, p + 30);
            int commentLength = uint16(central, p + 32);
            long localOffset = uint32(central, p + 42);
            if (p + 46 + nameLength > central.length) break;
            String name = new String(central, p + 46, nameLength, StandardCharsets.UTF_8);
            entries.add(new Entry(name, method, compressed, uncompressed, localOffset));
            p += 46 + nameLength + extraLength + commentLength;
        }
        return entries;
    }

    /** The entry called {@code name}, or {@code null}. */
    static Entry find(List<Entry> entries, String name) {
        for (Entry entry : entries) {
            if (entry.name().equals(name)) return entry;
        }
        return null;
    }

    /**
     * {@code entry}'s uncompressed bytes, or {@code null} when it is larger than {@code maxBytes}, stored in a way
     * other than stored/deflated, or can't be read whole. Its local header is read first, since only that carries
     * the real data offset.
     */
    static byte[] bytes(Reader reader, Entry entry, int maxBytes) {
        try {
            if (entry == null || entry.compressedSize() < 0 || entry.uncompressedSize() <= 0
                    || entry.compressedSize() > maxBytes || entry.uncompressedSize() > maxBytes) {
                return null;
            }
            byte[] header = reader.read(entry.localOffset(), 30);
            if (header.length < 30 || int32(header, 0) != LOCAL_SIG) return null;
            // The local header's name/extra lengths are its own and need not match the central directory's.
            long dataAt = entry.localOffset() + 30 + uint16(header, 26) + uint16(header, 28);
            byte[] data = reader.read(dataAt, entry.compressedSize());
            if (data.length < entry.compressedSize()) return null;
            return switch (entry.method()) {
                case 0 -> data;
                case 8 -> inflate(data, entry.uncompressedSize());
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    /** Raw-deflate (ZIP stores no zlib wrapper) into a buffer of the size the directory promised. */
    private static byte[] inflate(byte[] data, int expected) {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(data);
            byte[] out = new byte[expected];
            int written = 0;
            while (written < expected && !inflater.finished()) {
                int n = inflater.inflate(out, written, expected - written);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                written += n;
            }
            return written == expected ? out : null;
        } catch (Exception e) {
            return null;
        } finally {
            inflater.end();
        }
    }

    /** The last occurrence of a 4-byte little-endian signature, or -1. */
    private static int lastSignature(byte[] bytes, int signature) {
        for (int i = bytes.length - 4; i >= 0; i--) {
            if (int32(bytes, i) == signature) return i;
        }
        return -1;
    }

    static int uint16(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8);
    }

    static int int32(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8) | ((b[at + 2] & 0xFF) << 16) | ((b[at + 3] & 0xFF) << 24);
    }

    static long uint32(byte[] b, int at) {
        return int32(b, at) & 0xFFFFFFFFL;
    }
}
