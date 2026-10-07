package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The APK label reader against a compiled manifest and resource table built here byte by byte, in the layouts
 * {@code aapt2} writes: a UTF-16 string pool in the manifest, a UTF-8 one in the table, 32-bit, 16-bit and sparse
 * entry offsets, and the compact entry form.
 */
class ApkLabelTest {

    private static final int LABEL_ID = 0x7f0b0001;

    @Test
    void anApplicationLabelReferencingAStringIsReadInTheDefaultLanguageOverAnyOther() throws Exception {
        byte[] manifest = manifest(reference(LABEL_ID));
        byte[] table = table(List.of("Nom en français", "Clash of Clans"),
                type(0x0b, "fr", 0, entries(-1, 0)),
                type(0x0b, "", 0, entries(-1, 1)));

        assertEquals(new ApkLabel.Label(null, LABEL_ID), ApkLabel.manifestLabel(manifest));
        assertEquals("Clash of Clans", ApkLabel.resolve(table, LABEL_ID));

        byte[] apk = ApkIconTest.zip(new String[]{"AndroidManifest.xml", "resources.arsc", "classes.dex"},
                new byte[][]{manifest, table, new byte[64]});
        assertEquals("Clash of Clans", ApkLabel.read(new ApkIconTest.ArrayReader(apk)));
    }

    @Test
    void englishIsPreferredWhenThereIsNoDefaultAndAReferenceToAReferenceIsFollowed() {
        byte[] table = table(List.of("Nom", "Name"),
                type(0x0b, "fr", 0, entries(-1, 0)),
                type(0x0b, "en", 0, entries(-1, 1)),
                type(0x0c, "", 0, entries(ref(LABEL_ID))));
        assertEquals("Name", ApkLabel.resolve(table, LABEL_ID));
        assertEquals("Name", ApkLabel.resolve(table, 0x7f0c0000), "a label that points at another string");
    }

    @Test
    void sixteenBitSparseAndCompactEntriesAreReadToo() {
        assertEquals("Farm", ApkLabel.resolve(table(List.of("Farm"), type(0x0b, "", 0x02, entries(-1, 0))), LABEL_ID));
        assertEquals("Farm", ApkLabel.resolve(table(List.of("Farm"), type(0x0b, "", 0x01, entries(-1, 0))), LABEL_ID));
        assertEquals("Farm", ApkLabel.resolve(table(List.of("Farm"), compactType(0x0b, 1, 0)), LABEL_ID));
    }

    @Test
    void aLiteralLabelNeedsNoTableAndAnythingElseIsNull() {
        assertEquals(new ApkLabel.Label("Solitaire", 0), ApkLabel.manifestLabel(manifest(literal())));
        assertNull(ApkLabel.manifestLabel("not xml".getBytes()));
        assertNull(ApkLabel.manifestLabel(null));
        assertNull(ApkLabel.resolve(table(List.of("x"), type(0x0b, "", 0, entries(0))), 0x01040000),
                "a label in the framework's resources is not in the app's table");
        assertNull(ApkLabel.resolve(table(List.of("x"), type(0x0b, "", 0, entries(0))), 0x7f0b0005),
                "an entry the table doesn't have");
        assertNull(ApkLabel.read(new ApkIconTest.ArrayReader(new byte[100])));
    }

    @Test
    void anApkThatNamesNothingSaysSoAndOneWhoseFileCantBeReadIsAskedAgain() throws Exception {
        byte[] apk = ApkIconTest.zip(new String[]{"AndroidManifest.xml", "resources.arsc"},
                new byte[][]{manifest(reference(0x01040000)), table(List.of("x"), type(0x0b, "", 0, entries(0)))});
        ApkIconTest.ArrayReader reader = new ApkIconTest.ArrayReader(apk);
        assertEquals("", ApkLabel.read(reader, ApkZip.entries(reader)),
                "a label in the framework's resources: read, and nothing to show");

        ApkZip.Reader shortReads = new ApkZip.Reader() {
            @Override
            public long size() {
                return reader.size();
            }

            @Override
            public byte[] read(long offset, int length) {
                // The directory reads whole; the manifest's bytes come back short, as from a busy device.
                byte[] bytes = reader.read(offset, length);
                return length < 1000 && length > 30 ? java.util.Arrays.copyOf(bytes, length / 2) : bytes;
            }
        };
        assertNull(ApkLabel.read(shortReads, ApkZip.entries(reader)));
    }

    @Test
    void theManifestNamesThePackageAndAnApkFileOnThisComputerSaysWhichAppItIs() throws Exception {
        byte[] manifest = manifestWithPackage(literal());
        assertEquals("com.example.solitaire", ApkLabel.manifestPackage(manifest));
        assertNull(ApkLabel.manifestPackage(manifest(literal())), "no package attribute");
        assertEquals(new ApkLabel.Label("Solitaire", 0), ApkLabel.manifestLabel(manifest),
                "the manifest's own attributes don't hide the application's");

        byte[] apk = ApkIconTest.zip(new String[]{"AndroidManifest.xml"}, new byte[][]{manifest});
        assertEquals(new ApkLabel.ApkFacts("com.example.solitaire", "Solitaire"),
                ApkLabel.facts(new ApkIconTest.ArrayReader(apk)));
        assertNull(ApkLabel.facts(new ApkIconTest.ArrayReader(new byte[64])));
    }

    // --- builders -----------------------------------------------------------------------------------------

    /** A value: {@code -1} is "no entry", {@code n >= 0} a string index, {@link #ref} a reference. */
    private static long[] entries(long... values) {
        return values;
    }

    private static long ref(int id) {
        return (1L << 40) | (id & 0xFFFFFFFFL);
    }

    private static byte[] manifest(byte[] applicationAttribute) {
        return manifest(new byte[0][], applicationAttribute);
    }

    /** {@code <manifest package="com.example.solitaire">}: the package attribute, a plain string. */
    static byte[] manifestWithPackage(byte[] applicationAttribute) {
        return manifest(new byte[][]{attribute(5, 6, 0x03, 6)}, applicationAttribute);
    }

    private static byte[] manifest(byte[][] manifestAttributes, byte[] applicationAttribute) {
        List<String> strings = List.of("manifest", "application", "label", "Solitaire", "android", "package",
                "com.example.solitaire");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        write(body, pool(strings, false));
        ByteBuffer map = le(8 + 12);
        map.putShort((short) 0x0180).putShort((short) 8).putInt(20).putInt(0).putInt(0).putInt(0x01010001);
        write(body, map.array());
        write(body, element(0, manifestAttributes));
        write(body, element(1, new byte[][]{applicationAttribute}));
        return chunk(0x0003, 8, body.toByteArray());
    }

    static byte[] literalLabel() {
        return literal();
    }

    private static byte[] reference(int id) {
        return attribute(2, -1, 0x01, id);
    }

    private static byte[] literal() {
        return attribute(2, 3, 0x03, 3);
    }

    private static byte[] attribute(int name, int raw, int dataType, int data) {
        ByteBuffer a = le(20);
        a.putInt(4).putInt(name).putInt(raw).putShort((short) 8).put((byte) 0).put((byte) dataType).putInt(data);
        return a.array();
    }

    private static byte[] element(int name, byte[][] attributes) {
        ByteBuffer e = le(36 + 20 * attributes.length);
        e.putShort((short) 0x0102).putShort((short) 16).putInt(36 + 20 * attributes.length).putInt(1).putInt(-1);
        e.putInt(-1).putInt(name).putShort((short) 20).putShort((short) 20).putShort((short) attributes.length);
        e.putShort((short) 0).putShort((short) 0).putShort((short) 0);
        for (byte[] attribute : attributes) e.put(attribute);
        return e.array();
    }

    private static byte[] table(List<String> strings, byte[]... types) {
        ByteArrayOutputStream packageBody = new ByteArrayOutputStream();
        write(packageBody, pool(List.of(), true));
        write(packageBody, pool(List.of(), true));
        for (byte[] type : types) write(packageBody, type);
        ByteBuffer packageHeader = le(288 - 8);
        packageHeader.putInt(0x7f);
        packageHeader.position(256 + 4);
        byte[] pkg = chunk(0x0200, 288, concat(packageHeader.array(), packageBody.toByteArray()));

        ByteBuffer count = le(4).putInt(1);
        return chunk(0x0002, 12, concat(count.array(), pool(strings, true), pkg));
    }

    /** A {@code ResTable_type} for {@code language} ("" for default) with the given layout {@code flags}. */
    private static byte[] type(int id, String language, int flags, long[] values) {
        boolean sparse = (flags & 0x01) != 0;
        boolean sixteen = (flags & 0x02) != 0;
        ByteArrayOutputStream offsets = new ByteArrayOutputStream();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int present = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == -1) {
                if (!sparse) write(offsets, sixteen ? le(2).putShort((short) 0xFFFF).array() : le(4).putInt(-1).array());
                continue;
            }
            int offset = data.size();
            if (sparse) write(offsets, le(4).putShort((short) i).putShort((short) (offset / 4)).array());
            else write(offsets, sixteen ? le(2).putShort((short) (offset / 4)).array() : le(4).putInt(offset).array());
            boolean reference = (values[i] >> 40) == 1;
            ByteBuffer entry = le(16);
            entry.putShort((short) 8).putShort((short) 0).putInt(i);
            entry.putShort((short) 8).put((byte) 0).put((byte) (reference ? 0x01 : 0x03)).putInt((int) values[i]);
            write(data, entry.array());
            present++;
        }
        int count = sparse ? present : values.length;
        return typeChunk(id, language, flags, count, offsets.toByteArray(), data.toByteArray());
    }

    private static byte[] compactType(int id, int entryCount, int stringIndex) {
        ByteArrayOutputStream offsets = new ByteArrayOutputStream();
        for (int i = 0; i < entryCount; i++) write(offsets, le(4).putInt(-1).array());
        write(offsets, le(4).putInt(0).array());
        ByteBuffer entry = le(8).putShort((short) 0).putShort((short) ((0x03 << 8) | 0x08)).putInt(stringIndex);
        return typeChunk(id, "", 0, entryCount + 1, offsets.toByteArray(), entry.array());
    }

    private static byte[] typeChunk(int id, String language, int flags, int count, byte[] offsets, byte[] data) {
        int configSize = 64;
        int headerSize = 20 + configSize;
        int entriesStart = headerSize + offsets.length;
        ByteBuffer header = le(headerSize - 8);
        header.put((byte) id).put((byte) flags).putShort((short) 0).putInt(count).putInt(entriesStart);
        header.putInt(configSize).putInt(0);
        if (!language.isEmpty()) header.put((byte) language.charAt(0)).put((byte) language.charAt(1));
        return chunk(0x0201, headerSize, concat(header.array(), offsets, data));
    }

    private static byte[] pool(List<String> strings, boolean utf8) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        ByteBuffer offsets = le(4 * strings.size());
        for (String s : strings) {
            offsets.putInt(data.size());
            if (utf8) {
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                data.write(s.length());
                data.write(bytes.length);
                write(data, bytes);
                data.write(0);
            } else {
                write(data, le(2).putShort((short) s.length()).array());
                write(data, s.getBytes(StandardCharsets.UTF_16LE));
                write(data, new byte[2]);
            }
        }
        while (data.size() % 4 != 0) data.write(0);
        ByteBuffer header = le(28 - 8);
        header.putInt(strings.size()).putInt(0).putInt(utf8 ? 0x100 : 0).putInt(28 + 4 * strings.size()).putInt(0);
        return chunk(0x0001, 28, concat(header.array(), offsets.array(), data.toByteArray()));
    }

    private static byte[] chunk(int type, int headerSize, byte[] rest) {
        ByteBuffer header = le(8).putShort((short) type).putShort((short) headerSize).putInt(8 + rest.length);
        return concat(header.array(), rest);
    }

    private static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) write(out, part);
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }
}
