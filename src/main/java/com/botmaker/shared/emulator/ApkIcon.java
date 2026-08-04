package com.botmaker.shared.emulator;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.Inflater;

import javax.imageio.ImageIO;

/**
 * Pulls one app's launcher icon out of its APK <b>without downloading the APK</b>.
 *
 * <p><b>Why this exists.</b> A package list is a list of reverse-DNS strings, and
 * {@code com.supercell.clashofclans} is only obvious if you already know it. The icon is what identifies a
 * game at a glance, and Android has no shell command that will hand one over: {@code pm} lists packages,
 * {@code dumpsys} describes them, and neither can render a resource. The icon only exists inside the APK.
 *
 * <p><b>Why a ZIP reader rather than a file pull.</b> A game APK is routinely hundreds of megabytes and the
 * icon is a few kilobytes of it. An APK is a ZIP, and a ZIP is designed to be read backwards — end-of-central
 * -directory, then the central directory, then one entry — so four bounded byte ranges get the icon out
 * regardless of how large the archive is. That is the whole shape of this class; {@link Reader} is the only
 * thing that has to know those ranges come over ADB.
 *
 * <p><b>It never throws and it is allowed to fail.</b> Every return is "the icon, or {@code null}": Zip64,
 * an APK with only an adaptive-icon XML and no raster fallback, a device whose {@code dd} does not behave, a
 * split APK whose base carries no {@code res/} — all of them are a missing thumbnail, which is exactly what
 * the caller showed before this existed.
 */
final class ApkIcon {

    /** Random access to an archive that lives somewhere else — on a device, over ADB. */
    interface Reader {
        /** The archive's total length in bytes, or a non-positive value when it can't be determined. */
        long size();

        /** Up to {@code length} bytes at {@code offset}; a short (or empty) array is a failure, never an error. */
        byte[] read(long offset, int length);
    }

    private static final int EOCD_SIG = 0x06054b50;
    private static final int CENTRAL_SIG = 0x02014b50;
    private static final int LOCAL_SIG = 0x04034b50;

    /** The largest an end-of-central-directory record can be: 22 fixed bytes plus a 64 KB comment. */
    private static final int EOCD_MAX = 65_557;

    /** A sanity ceiling on the central directory read — a real APK's is well under this even with 50k entries. */
    private static final int CENTRAL_MAX = 16 << 20;

    /** Icons above this are not icons; refusing them keeps one absurd entry from pulling megabytes. */
    private static final int ICON_MAX_BYTES = 4 << 20;

    private ApkIcon() {}

    /** The best launcher icon in the archive, or {@code null} when there isn't one we can decode. */
    static BufferedImage read(Reader reader) {
        try {
            long size = reader.size();
            if (size <= 0) {
                return null;
            }
            int tailLength = (int) Math.min(size, EOCD_MAX);
            byte[] tail = reader.read(size - tailLength, tailLength);
            int eocd = lastSignature(tail, EOCD_SIG);
            if (eocd < 0 || eocd + 20 > tail.length) {
                return null;
            }
            int centralSize = int32(tail, eocd + 12);
            long centralOffset = uint32(tail, eocd + 16);
            // 0xFFFFFFFF in either field means the real value is in a Zip64 record. Bail rather than
            // mis-read: an APK that large is not one whose icon is worth chasing.
            if (centralSize <= 0 || centralSize > CENTRAL_MAX || centralOffset <= 0 || centralOffset >= size) {
                return null;
            }
            byte[] central = reader.read(centralOffset, centralSize);
            Entry icon = bestIcon(central);
            return icon == null ? null : decode(reader, icon);
        } catch (Exception e) {
            return null;
        }
    }

    /** One central-directory record, reduced to what reading its bytes back needs. */
    private record Entry(String name, int method, int compressedSize, int uncompressedSize, long localOffset,
                         int rank) {}

    /**
     * Walks the central directory and keeps the highest-ranked icon, breaking ties on uncompressed size —
     * an APK ships the same icon at every screen density and the largest is the one worth showing.
     */
    private static Entry bestIcon(byte[] central) {
        Entry best = null;
        int p = 0;
        while (p + 46 <= central.length && int32(central, p) == CENTRAL_SIG) {
            int method = uint16(central, p + 10);
            int compressed = int32(central, p + 20);
            int uncompressed = int32(central, p + 24);
            int nameLength = uint16(central, p + 28);
            int extraLength = uint16(central, p + 30);
            int commentLength = uint16(central, p + 32);
            long localOffset = uint32(central, p + 42);
            if (p + 46 + nameLength > central.length) {
                break;
            }
            String name = new String(central, p + 46, nameLength, StandardCharsets.UTF_8);
            int rank = rank(name);
            if (rank > 0 && compressed > 0 && compressed <= ICON_MAX_BYTES && uncompressed <= ICON_MAX_BYTES
                    && (best == null || rank > best.rank()
                        || (rank == best.rank() && uncompressed > best.uncompressedSize()))) {
                best = new Entry(name, method, compressed, uncompressed, localOffset, rank);
            }
            p += 46 + nameLength + extraLength + commentLength;
        }
        return best;
    }

    /**
     * How much this entry looks like <em>the</em> launcher icon; 0 means "not an icon".
     *
     * <p>Matching on the file name rather than resolving the manifest's {@code android:icon} is deliberate.
     * The manifest is binary XML pointing at a resource id that has to be looked up in {@code resources.arsc}
     * and then narrowed by density — two more formats to parse, for a picker thumbnail. The convention
     * ({@code res/mipmap-<density>/ic_launcher.png}) is near-universal because every Android project ships
     * it, and being wrong here costs a slightly-off thumbnail, not a wrong launch.
     */
    private static int rank(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("res/") || !lower.endsWith(".png")) {
            return 0;
        }
        String base = lower.substring(lower.lastIndexOf('/') + 1);
        // A foreground layer ranks below a complete icon but above a generic "icon.png": on an adaptive-icon
        // app it is the logo, and it is often the only raster left in the archive.
        if (base.startsWith("ic_launcher") && !base.contains("background")) {
            return base.contains("foreground") ? 3 : 4;
        }
        if (base.contains("app_icon") || base.contains("launcher")) {
            return 2;
        }
        return base.equals("icon.png") ? 1 : 0;
    }

    /** Reads {@code entry}'s bytes (its local header first, since only that carries the real data offset). */
    private static BufferedImage decode(Reader reader, Entry entry) throws Exception {
        byte[] header = reader.read(entry.localOffset(), 30);
        if (header.length < 30 || int32(header, 0) != LOCAL_SIG) {
            return null;
        }
        // The local header's name/extra lengths are its own and need not match the central directory's.
        long dataAt = entry.localOffset() + 30 + uint16(header, 26) + uint16(header, 28);
        byte[] data = reader.read(dataAt, entry.compressedSize());
        if (data.length < entry.compressedSize()) {
            return null;
        }
        byte[] png = entry.method() == 0 ? data : inflate(data, entry.uncompressedSize());
        return png == null ? null : ImageIO.read(new ByteArrayInputStream(png));
    }

    /** Raw-deflate (ZIP stores no zlib wrapper) into a buffer of the size the directory promised. */
    private static byte[] inflate(byte[] data, int expected) {
        if (expected <= 0 || expected > ICON_MAX_BYTES) {
            return null;
        }
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(data);
            byte[] out = new byte[expected];
            int written = inflater.inflate(out);
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
            if (int32(bytes, i) == signature) {
                return i;
            }
        }
        return -1;
    }

    private static int uint16(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8);
    }

    private static int int32(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8) | ((b[at + 2] & 0xFF) << 16) | ((b[at + 3] & 0xFF) << 24);
    }

    private static long uint32(byte[] b, int at) {
        return int32(b, at) & 0xFFFFFFFFL;
    }
}
