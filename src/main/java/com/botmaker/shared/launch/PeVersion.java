package com.botmaker.shared.launch;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * The version a Windows program states of itself, read from its file without Wine: the {@code VS_VERSIONINFO}
 * resource's {@code FileVersion}, else its {@code ProductVersion}, else the fixed {@code dwFileVersion} numbers.
 * Reads the headers and the resource section only, never the whole file. Empty for anything that is not a PE, has
 * no version resource, or is cut short.
 */
final class PeVersion {

    private static final int RT_VERSION = 16;
    private static final int FIXED_SIGNATURE = 0xFEEF04BD;
    /** The high bit of a resource entry's offset: it points at another directory, not at data. */
    private static final long SUBDIRECTORY = 0x80000000L;
    /** A resource section larger than this is not read: a version resource is a few hundred bytes. */
    private static final int MAX_SECTION = 64 << 20;

    private PeVersion() {}

    static Optional<String> of(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return Optional.ofNullable(read(channel));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String read(FileChannel file) throws IOException {
        ByteBuffer dos = at(file, 0, 64);
        if (dos == null || dos.getShort(0) != 0x5A4D) return null;
        long pe = Integer.toUnsignedLong(dos.getInt(0x3C));
        ByteBuffer coff = at(file, pe, 24);
        if (coff == null || coff.getInt(0) != 0x00004550) return null;
        int sections = Short.toUnsignedInt(coff.getShort(6));
        int optionalSize = Short.toUnsignedInt(coff.getShort(20));
        long optionalAt = pe + 24;
        ByteBuffer optional = at(file, optionalAt, optionalSize);
        if (optional == null || optionalSize < 2) return null;
        int dirs = switch (Short.toUnsignedInt(optional.getShort(0))) {
            case 0x10B -> 96;
            case 0x20B -> 112;
            default -> -1;
        };
        if (dirs < 0 || optionalSize < dirs + 3 * 8 || optional.getInt(dirs - 4) < 3) return null;
        long resourceRva = Integer.toUnsignedLong(optional.getInt(dirs + 16));
        if (resourceRva == 0) return null;
        ByteBuffer table = at(file, optionalAt + optionalSize, sections * 40);
        if (table == null) return null;
        for (int i = 0; i < sections; i++) {
            int s = i * 40;
            long va = Integer.toUnsignedLong(table.getInt(s + 12));
            long size = Math.max(Integer.toUnsignedLong(table.getInt(s + 8)), Integer.toUnsignedLong(table.getInt(s + 16)));
            if (resourceRva < va || resourceRva >= va + size) continue;
            long raw = Integer.toUnsignedLong(table.getInt(s + 16));
            if (raw > MAX_SECTION) return null;
            ByteBuffer section = at(file, Integer.toUnsignedLong(table.getInt(s + 20)), (int) raw);
            return section == null ? null : fromResources(section, (int) (resourceRva - va), va);
        }
        return null;
    }

    /** The version in the resource tree that starts at {@code root} of {@code section}, mapped at {@code va}. */
    private static String fromResources(ByteBuffer section, int root, long va) {
        long types = entry(section, root, RT_VERSION);
        if (types < 0 || (types & SUBDIRECTORY) == 0) return null;
        long names = entry(section, root + (int) (types & ~SUBDIRECTORY), -1);
        if (names < 0 || (names & SUBDIRECTORY) == 0) return null;
        long language = entry(section, root + (int) (names & ~SUBDIRECTORY), -1);
        if (language < 0 || (language & SUBDIRECTORY) != 0) return null;
        int data = root + (int) language;
        if (data + 8 > section.limit()) return null;
        long rva = Integer.toUnsignedLong(section.getInt(data));
        int size = section.getInt(data + 4);
        long offset = rva - va;
        if (offset < 0 || size <= 0 || offset + size > section.limit()) return null;
        ByteBuffer blob = section.slice((int) offset, size).order(ByteOrder.LITTLE_ENDIAN);
        String version = text(blob, "FileVersion");
        if (version == null) version = text(blob, "ProductVersion");
        return version != null ? version : fixed(blob);
    }

    /**
     * The offset field of the entry of the directory at {@code dir} whose id is {@code id}, or of its first entry for
     * {@code -1}, unsigned ({@link #SUBDIRECTORY} set for a directory); {@code -1} when there is none.
     */
    private static long entry(ByteBuffer section, int dir, int id) {
        if (dir < 0 || dir + 16 > section.limit()) return -1;
        int count = Short.toUnsignedInt(section.getShort(dir + 12)) + Short.toUnsignedInt(section.getShort(dir + 14));
        for (int i = 0; i < count; i++) {
            int e = dir + 16 + i * 8;
            if (e + 8 > section.limit()) return -1;
            if (id < 0 || section.getInt(e) == id) return Integer.toUnsignedLong(section.getInt(e + 4));
        }
        return -1;
    }

    /** The value of the {@code String} entry named {@code key}, or {@code null} when it is absent or blank. */
    private static String text(ByteBuffer blob, String key) {
        byte[] name = (key + "\0").getBytes(StandardCharsets.UTF_16LE);
        int at = indexOf(blob, name);
        if (at < 6) return null;
        int chars = Short.toUnsignedInt(blob.getShort(at - 6 + 2));
        int value = (at + name.length + 3) & ~3;
        if (chars == 0 || value + chars * 2 > blob.limit()) return null;
        byte[] bytes = new byte[chars * 2];
        blob.get(value, bytes);
        String text = new String(bytes, StandardCharsets.UTF_16LE);
        int end = text.indexOf('\0');
        text = (end < 0 ? text : text.substring(0, end)).strip();
        return text.isEmpty() ? null : text;
    }

    /** {@code VS_FIXEDFILEINFO}'s file version as {@code a.b.c.d}, or {@code null}. */
    private static String fixed(ByteBuffer blob) {
        for (int i = 0; i + 16 <= blob.limit(); i += 4) {
            if (blob.getInt(i) != FIXED_SIGNATURE) continue;
            int ms = blob.getInt(i + 8), ls = blob.getInt(i + 12);
            return (ms >>> 16) + "." + (ms & 0xFFFF) + "." + (ls >>> 16) + "." + (ls & 0xFFFF);
        }
        return null;
    }

    private static int indexOf(ByteBuffer blob, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= blob.limit(); i += 2) {
            for (int j = 0; j < needle.length; j++) {
                if (blob.get(i + j) != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** {@code length} bytes at {@code position}, little-endian, or {@code null} when the file ends first. */
    private static ByteBuffer at(FileChannel file, long position, int length) throws IOException {
        if (length < 0 || position < 0 || position + length > file.size()) return null;
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            if (file.read(buffer, position + buffer.position()) < 0) return null;
        }
        return buffer.flip();
    }
}
