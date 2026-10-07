package com.botmaker.shared.emulator;

import java.nio.charset.StandardCharsets;

import static com.botmaker.shared.emulator.ApkZip.int32;
import static com.botmaker.shared.emulator.ApkZip.uint16;

/**
 * An app's name — "Clash of Clans", not {@code com.supercell.clashofclans} — read out of its APK <b>without
 * downloading the APK</b> ({@link ApkZip}).
 *
 * <p>Android has no shell command that prints it: the name is {@code <application android:label>} in the
 * compiled manifest, which is binary XML, and nearly always a reference into {@code resources.arsc}, the
 * compiled resource table. So this reads both and follows the reference: the manifest's string pool and the
 * {@code application} element give the label's resource id, then the table's package, type and entry give the
 * string, in the default language when there is one ("en" next, then any). Only the formats' few chunks on that
 * path are understood; anything else is skipped by its declared size.
 *
 * <p>Never throws: an unreadable archive, a label in the framework's resources, or a format this does not know
 * is {@code null}, and the caller shows the package as it did before.
 */
final class ApkLabel {

    /** A manifest larger than this is not one; the cap keeps a bad entry from pulling megabytes. */
    private static final int MANIFEST_MAX = 4 << 20;
    /** {@code resources.arsc} is read whole; a game's is a few megabytes, and over this it is not worth it. */
    private static final int TABLE_MAX = 32 << 20;

    private static final int RES_STRING_POOL = 0x0001;
    private static final int RES_TABLE = 0x0002;
    private static final int RES_XML = 0x0003;
    private static final int RES_XML_START_ELEMENT = 0x0102;
    private static final int RES_XML_RESOURCE_MAP = 0x0180;
    private static final int RES_TABLE_PACKAGE = 0x0200;
    private static final int RES_TABLE_TYPE = 0x0201;

    private static final int TYPE_REFERENCE = 0x01;
    private static final int TYPE_STRING = 0x03;
    /** {@code android:label}'s attribute id, which names it in the manifest whatever its string says. */
    private static final int ATTR_LABEL = 0x01010001;
    private static final int NO_ENTRY = 0xFFFFFFFF;

    private ApkLabel() {}

    /** The app's label, or {@code null}. */
    static String read(ApkZip.Reader reader) {
        String label = read(reader, ApkZip.entries(reader));
        return label == null || label.isEmpty() ? null : label;
    }

    /**
     * As {@link #read(ApkZip.Reader)}, over a directory already read, telling the two failures apart: {@code ""}
     * when the files were read and name nothing (no label, one in the framework's resources, a format this does
     * not know), {@code null} when a file couldn't be read whole — a short read from a busy device, worth asking
     * again.
     */
    static String read(ApkZip.Reader reader, java.util.List<ApkZip.Entry> entries) {
        try {
            ApkZip.Entry manifestEntry = ApkZip.find(entries, "AndroidManifest.xml");
            if (manifestEntry == null) return "";
            byte[] manifest = ApkZip.bytes(reader, manifestEntry, MANIFEST_MAX);
            if (manifest == null) return null;
            Label label = manifestLabel(manifest);
            if (label == null) return "";
            if (label.text() != null) return label.text();
            ApkZip.Entry tableEntry = ApkZip.find(entries, "resources.arsc");
            if (tableEntry == null) return "";
            byte[] table = ApkZip.bytes(reader, tableEntry, TABLE_MAX);
            if (table == null) return tableEntry.uncompressedSize() > TABLE_MAX ? "" : null;
            String text = resolve(table, label.resource());
            return text == null ? "" : text;
        } catch (Exception e) {
            return null;
        }
    }

    /** What {@code android:label} holds: the text itself, or the resource id of a string. */
    record Label(String text, int resource) {}

    /** {@code <application android:label>} in a compiled manifest, or {@code null}. Pure, for the tests. */
    static Label manifestLabel(byte[] xml) {
        if (xml == null || xml.length < 8 || uint16(xml, 0) != RES_XML) return null;
        StringPool strings = null;
        int[] ids = new int[0];
        int p = uint16(xml, 2);
        while (p + 8 <= xml.length) {
            int type = uint16(xml, p);
            int size = int32(xml, p + 4);
            if (size < 8 || p + size > xml.length) return null;
            if (type == RES_STRING_POOL) {
                strings = StringPool.at(xml, p);
            } else if (type == RES_XML_RESOURCE_MAP) {
                ids = new int[(size - uint16(xml, p + 2)) / 4];
                for (int i = 0; i < ids.length; i++) ids[i] = int32(xml, p + uint16(xml, p + 2) + 4 * i);
            } else if (type == RES_XML_START_ELEMENT && strings != null) {
                int ext = p + uint16(xml, p + 2);
                if ("application".equals(strings.get(int32(xml, ext + 4)))) {
                    return attribute(xml, ext, strings, ids);
                }
            }
            p += size;
        }
        return null;
    }

    private static Label attribute(byte[] xml, int ext, StringPool strings, int[] ids) {
        int start = uint16(xml, ext + 8);
        int width = uint16(xml, ext + 10);
        int count = uint16(xml, ext + 12);
        for (int i = 0; i < count; i++) {
            int a = ext + start + i * width;
            int name = int32(xml, a + 4);
            boolean label = (name >= 0 && name < ids.length && ids[name] == ATTR_LABEL)
                    || ("label".equals(strings.get(name)) && (name >= ids.length || ids[name] == 0));
            if (!label) continue;
            int rawValue = int32(xml, a + 8);
            int dataType = xml[a + 15] & 0xFF;
            int data = int32(xml, a + 16);
            if (dataType == TYPE_REFERENCE) return new Label(null, data);
            String text = strings.get(dataType == TYPE_STRING ? data : rawValue);
            return text == null || text.isBlank() ? null : new Label(text, 0);
        }
        return null;
    }

    /** The string resource {@code id} names in a compiled resource table, or {@code null}. Pure, for the tests. */
    static String resolve(byte[] table, int id) {
        if (table == null || table.length < 12 || uint16(table, 0) != RES_TABLE) return null;
        StringPool values = null;
        for (int depth = 0; depth < 4; depth++) {
            int p = uint16(table, 2);
            Value best = null;
            while (p + 8 <= table.length) {
                int type = uint16(table, p);
                int size = int32(table, p + 4);
                if (size < 8 || p + size > table.length) return null;
                if (type == RES_STRING_POOL && values == null) {
                    values = StringPool.at(table, p);
                } else if (type == RES_TABLE_PACKAGE && int32(table, p + 8) == (id >>> 24)) {
                    best = inPackage(table, p, size, id);
                }
                p += size;
            }
            if (best == null || values == null) return null;
            if (best.type() == TYPE_STRING) return values.get(best.data());
            if (best.type() != TYPE_REFERENCE) return null;
            id = best.data();
        }
        return null;
    }

    /** One entry's value, and how well its configuration suits a name shown to a user (higher is better). */
    private record Value(int type, int data, int score) {}

    private static Value inPackage(byte[] table, int pkg, int pkgSize, int id) {
        int typeId = (id >> 16) & 0xFF;
        int index = id & 0xFFFF;
        Value best = null;
        int q = pkg + uint16(table, pkg + 2);
        while (q + 8 <= pkg + pkgSize) {
            int size = int32(table, q + 4);
            if (size < 8 || q + size > pkg + pkgSize) return best;
            if (uint16(table, q) == RES_TABLE_TYPE && (table[q + 8] & 0xFF) == typeId) {
                Value value = entry(table, q, index);
                if (value != null && (best == null || value.score() > best.score())) best = value;
            }
            q += size;
        }
        return best;
    }

    /**
     * Entry {@code index} of one {@code ResTable_type} chunk, in any of its three offset layouts (32-bit, 16-bit,
     * sparse) and two entry layouts (full, compact), or {@code null} when this configuration doesn't have it.
     */
    private static Value entry(byte[] table, int q, int index) {
        int headerSize = uint16(table, q + 2);
        int flags = table[q + 9] & 0xFF;
        int count = int32(table, q + 12);
        int entriesStart = int32(table, q + 16);
        int offsets = q + headerSize;
        int offset = NO_ENTRY;
        if ((flags & 0x01) != 0) {
            for (int i = 0; i < count; i++) {
                if (uint16(table, offsets + 4 * i) == index) {
                    offset = uint16(table, offsets + 4 * i + 2) * 4;
                    break;
                }
            }
        } else if (index < count) {
            if ((flags & 0x02) != 0) {
                int half = uint16(table, offsets + 2 * index);
                offset = half == 0xFFFF ? NO_ENTRY : half * 4;
            } else {
                offset = int32(table, offsets + 4 * index);
            }
        }
        if (offset == NO_ENTRY) return null;
        int e = q + entriesStart + offset;
        if (e + 8 > table.length) return null;
        int entryFlags = uint16(table, e + 2);
        int score = score(table, q + 20);
        if ((entryFlags & 0x08) != 0) {
            return new Value((entryFlags >> 8) & 0xFF, int32(table, e + 4), score);
        }
        if ((entryFlags & 0x01) != 0) return null;
        int v = e + uint16(table, e);
        if (v + 8 > table.length) return null;
        return new Value(table[v + 3] & 0xFF, int32(table, v + 4), score);
    }

    /** No language (the default strings) first, then English, then any other. */
    private static int score(byte[] table, int config) {
        if (config + 12 > table.length || int32(table, config) < 12) return 1;
        int language = uint16(table, config + 8);
        if (language == 0) return 3;
        return table[config + 8] == 'e' && table[config + 9] == 'n' ? 2 : 1;
    }

    /** A {@code ResStringPool}, its strings decoded only when asked for. */
    private record StringPool(byte[] bytes, int at, int count, boolean utf8, int stringsStart) {

        static StringPool at(byte[] bytes, int p) {
            int count = int32(bytes, p + 8);
            boolean utf8 = (int32(bytes, p + 16) & 0x100) != 0;
            return new StringPool(bytes, p, count, utf8, int32(bytes, p + 20));
        }

        String get(int index) {
            if (index < 0 || index >= count) return null;
            try {
                int s = at + stringsStart + int32(bytes, at + uint16(bytes, at + 2) + 4 * index);
                if (utf8) {
                    s += (bytes[s] & 0x80) != 0 ? 2 : 1; // its length in UTF-16 units, which UTF-8 doesn't need
                    int length = bytes[s] & 0xFF;
                    if ((length & 0x80) != 0) {
                        length = ((length & 0x7F) << 8) | (bytes[s + 1] & 0xFF);
                        s += 2;
                    } else {
                        s += 1;
                    }
                    return new String(bytes, s, length, StandardCharsets.UTF_8);
                }
                int length = uint16(bytes, s);
                if ((length & 0x8000) != 0) {
                    length = ((length & 0x7FFF) << 16) | uint16(bytes, s + 2);
                    s += 4;
                } else {
                    s += 2;
                }
                return new String(bytes, s, 2 * length, StandardCharsets.UTF_16LE);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
