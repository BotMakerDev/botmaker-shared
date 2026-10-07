package com.botmaker.shared.vm;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A disc image of a few files in one folder: ISO 9660 with Joliet, which every hypervisor mounts and Windows
 * reads with its long, lower-case names (an answer file must be named {@code autounattend.xml}, which plain
 * ISO 9660 can't say). Pure.
 *
 * <p>Layout, in 2048-byte sectors: 0–15 empty, 16 the primary volume descriptor, 17 the Joliet one, 18 the
 * terminator, 19–22 the path tables (each tree's little- and big-endian one), 23 and 24 the two root
 * directories, then each file's data. Both trees point at the same data.
 */
public final class IsoImage {

    static final int SECTOR = 2048;
    private static final int PVD = 16;
    private static final int FIRST_PATH_TABLE = 19;
    private static final int PRIMARY_ROOT = 23;
    private static final int JOLIET_ROOT = 24;
    private static final int FIRST_DATA = 25;
    private static final int ROOT_PATH_TABLE_SIZE = 10;

    private IsoImage() {}

    /** Writes {@code files} (name to contents) as a disc labelled {@code label} at {@code target}. */
    public static void write(Path target, String label, Map<String, byte[]> files) throws IOException {
        Files.write(target, bytes(label, files));
    }

    /**
     * The disc's bytes. Names are 1–64 characters of letters, digits, {@code . _ -}, and as many files as one
     * sector of directory holds (a dozen with long names).
     */
    public static byte[] bytes(String label, Map<String, byte[]> files) {
        Map<String, byte[]> sorted = new TreeMap<>(files);
        int directory = 2 * 34;
        for (String name : sorted.keySet()) {
            if (!name.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("Not a disc file name: " + name);
            directory += 34 + 2 * name.length();
        }
        if (directory > SECTOR) throw new IllegalArgumentException("Too many files for one sector of directory.");
        List<Entry> entries = new ArrayList<>();
        java.util.Set<String> taken = new java.util.HashSet<>();
        int next = FIRST_DATA;
        for (var file : sorted.entrySet()) {
            entries.add(new Entry(file.getKey(), primaryName(file.getKey(), taken), file.getValue(), next));
            next += sectors(file.getValue().length);
        }
        int total = next;
        ByteBuffer iso = ByteBuffer.allocate(total * SECTOR).order(ByteOrder.LITTLE_ENDIAN);

        volumeDescriptor(iso, PVD, 1, label, total, FIRST_PATH_TABLE, PRIMARY_ROOT, false);
        volumeDescriptor(iso, PVD + 1, 2, label, total, FIRST_PATH_TABLE + 2, JOLIET_ROOT, true);
        iso.position((PVD + 2) * SECTOR).put((byte) 255).put("CD001".getBytes(StandardCharsets.US_ASCII)).put((byte) 1);

        for (int tree = 0; tree < 2; tree++) {
            int root = tree == 0 ? PRIMARY_ROOT : JOLIET_ROOT;
            pathTable(iso, FIRST_PATH_TABLE + 2 * tree, root, ByteOrder.LITTLE_ENDIAN);
            pathTable(iso, FIRST_PATH_TABLE + 2 * tree + 1, root, ByteOrder.BIG_ENDIAN);
            iso.position(root * SECTOR);
            directoryRecord(iso, root, SECTOR, true, new byte[] {0});
            directoryRecord(iso, root, SECTOR, true, new byte[] {1});
            List<Entry> ordered = new ArrayList<>(entries);
            if (tree == 0) ordered.sort(Comparator.comparing(Entry::primaryName));
            for (Entry e : ordered) {
                byte[] id = tree == 0 ? e.primaryName().getBytes(StandardCharsets.US_ASCII)
                        : e.name().getBytes(StandardCharsets.UTF_16BE);
                directoryRecord(iso, e.sector(), e.data().length, false, id);
            }
        }
        for (Entry e : entries) iso.position(e.sector() * SECTOR).put(e.data());
        return iso.array();
    }

    private record Entry(String name, String primaryName, byte[] data, int sector) {}

    /**
     * The name a reader that doesn't know Joliet shows: ISO 9660 level 1, an upper-case 8.3 name with its
     * version suffix, made unique among {@code taken} with a {@code ~N} tail as Windows does.
     */
    static String primaryName(String name, java.util.Set<String> taken) {
        String upper = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9.]", "_");
        int dot = upper.lastIndexOf('.');
        String base = (dot < 0 ? upper : upper.substring(0, dot)).replace('.', '_');
        String ext = dot < 0 ? "" : upper.substring(dot + 1);
        ext = ext.substring(0, Math.min(3, ext.length()));
        String candidate = base.substring(0, Math.min(8, base.length()));
        for (int n = 1; !taken.add(candidate + "." + ext); n++) {
            String tail = "~" + n;
            candidate = base.substring(0, Math.min(8 - tail.length(), base.length())) + tail;
        }
        return candidate + "." + ext + ";1";
    }

    private static int sectors(int bytes) {
        return Math.max(1, (bytes + SECTOR - 1) / SECTOR);
    }

    private static void volumeDescriptor(ByteBuffer iso, int sector, int type, String label, int total,
                                         int pathTable, int root, boolean joliet) {
        int at = sector * SECTOR;
        iso.position(at).put((byte) type).put("CD001".getBytes(StandardCharsets.US_ASCII)).put((byte) 1);
        text(iso, at + 8, 32, "", joliet);
        text(iso, at + 40, 32, label.toUpperCase(Locale.ROOT), joliet);
        both32(iso, at + 80, total);
        if (joliet) iso.position(at + 88).put(new byte[] {0x25, 0x2F, 0x45}); // UCS-2 level 3
        both16(iso, at + 120, 1);
        both16(iso, at + 124, 1);
        both16(iso, at + 128, SECTOR);
        both32(iso, at + 132, ROOT_PATH_TABLE_SIZE);
        iso.order(ByteOrder.LITTLE_ENDIAN).putInt(at + 140, pathTable);
        iso.order(ByteOrder.BIG_ENDIAN).putInt(at + 148, pathTable + 1);
        iso.order(ByteOrder.LITTLE_ENDIAN);
        iso.position(at + 156);
        directoryRecord(iso, root, SECTOR, true, new byte[] {0});
        for (int field = 190; field < 813; field += field < 702 ? 128 : 37) {
            text(iso, at + field, field < 702 ? 128 : 37, "", joliet);
        }
        for (int date = 813; date < 881; date += 17) {
            iso.position(at + date).put("0000000000000000".getBytes(StandardCharsets.US_ASCII)).put((byte) 0);
        }
        iso.put(at + 881, (byte) 1);
    }

    /** A space-padded text field: ASCII in the primary descriptor, UCS-2 big-endian in Joliet's. */
    private static void text(ByteBuffer iso, int at, int length, String value, boolean joliet) {
        byte[] field = new byte[length];
        if (joliet) {
            for (int i = 0; i + 1 < length; i += 2) {
                char c = i / 2 < value.length() ? value.charAt(i / 2) : ' ';
                field[i] = (byte) (c >> 8);
                field[i + 1] = (byte) c;
            }
        } else {
            for (int i = 0; i < length; i++) field[i] = (byte) (i < value.length() ? value.charAt(i) : ' ');
        }
        iso.position(at).put(field);
    }

    private static void pathTable(ByteBuffer iso, int sector, int root, ByteOrder order) {
        iso.position(sector * SECTOR).put((byte) 1).put((byte) 0);
        iso.order(order).putInt(root).putShort((short) 1).put((byte) 0).put((byte) 0);
        iso.order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void directoryRecord(ByteBuffer iso, int extent, int length, boolean directory, byte[] id) {
        int size = 33 + id.length + (id.length % 2 == 0 ? 1 : 0);
        int at = iso.position();
        iso.put((byte) size).put((byte) 0);
        both32(iso, at + 2, extent);
        both32(iso, at + 10, length);
        iso.position(at + 18).put(new byte[] {(byte) 126, 1, 1, 0, 0, 0, 0}); // 2026-01-01, UTC
        iso.put((byte) (directory ? 2 : 0)).put((byte) 0).put((byte) 0);
        both16(iso, at + 28, 1);
        iso.position(at + 32).put((byte) id.length).put(id);
        iso.position(at + size);
    }

    /** ISO 9660's "both-endian" number: little-endian, then the same big-endian. */
    private static void both32(ByteBuffer iso, int at, int value) {
        iso.order(ByteOrder.LITTLE_ENDIAN).putInt(at, value);
        iso.order(ByteOrder.BIG_ENDIAN).putInt(at + 4, value);
        iso.order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void both16(ByteBuffer iso, int at, int value) {
        iso.order(ByteOrder.LITTLE_ENDIAN).putShort(at, (short) value);
        iso.order(ByteOrder.BIG_ENDIAN).putShort(at + 2, (short) value);
        iso.order(ByteOrder.LITTLE_ENDIAN);
    }
}
