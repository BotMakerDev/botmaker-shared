package com.botmaker.shared.vm;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Optional;

/**
 * Copies a file out of an ISO 9660 disc image, by its path on the disc: what QEMU needs to boot a Linux installer's
 * kernel itself ({@link Qemu.Kernel}). It reads the primary volume's directories, whose names match ignoring case,
 * a {@code ;1} version and a trailing dot ({@code VMLINUZ.;1} is {@code vmlinuz}).
 */
final class IsoFiles {

    private static final int SECTOR = IsoImage.SECTOR;
    private static final int PVD = 16;
    private static final int ROOT_RECORD = 156;
    private static final int DIRECTORY = 0x02;

    private IsoFiles() {}

    /**
     * Copies {@code path} ({@code casper/vmlinuz}) from {@code iso} to {@code target}, through a file beside it.
     *
     * @throws IOException when the image isn't ISO 9660, or has no such file
     */
    static void copy(Path iso, String path, Path target) throws IOException {
        try (FileChannel disc = FileChannel.open(iso, StandardOpenOption.READ)) {
            ByteBuffer pvd = read(disc, (long) PVD * SECTOR, SECTOR);
            if (pvd.get(0) != 1 || !new String(bytes(pvd, 1, 5), StandardCharsets.US_ASCII).equals("CD001")) {
                throw new IOException(iso.getFileName() + " isn't a disc image.");
            }
            Entry at = entry(pvd, ROOT_RECORD);
            for (String part : path.split("/")) {
                if (!at.directory()) throw new IOException(iso.getFileName() + " has no " + path + ".");
                if (at.extent() * SECTOR + at.length() > disc.size()) throw new IOException("The disc image ends early.");
                at = find(disc, at, part).orElseThrow(() -> new IOException(iso.getFileName() + " has no " + path + "."));
            }
            if (at.directory()) throw new IOException(path + " is a folder on " + iso.getFileName() + ".");
            if (at.extent() * SECTOR + at.length() > disc.size()) throw new IOException("The disc image ends early.");
            Path partial = target.resolveSibling(target.getFileName() + ".part");
            try (FileChannel out = FileChannel.open(partial, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                long done = 0;
                while (done < at.length()) {
                    done += disc.transferTo((long) at.extent() * SECTOR + done, at.length() - done, out);
                }
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record Entry(long extent, long length, boolean directory, String name) {}

    private static Optional<Entry> find(FileChannel disc, Entry folder, String name) throws IOException {
        ByteBuffer records = read(disc, folder.extent() * SECTOR, (int) folder.length());
        int at = 0;
        while (at < records.limit()) {
            int length = records.get(at) & 0xFF;
            if (length == 0) {
                at = (at / SECTOR + 1) * SECTOR; // records never cross a sector: the rest of this one is padding
                continue;
            }
            Entry entry = entry(records, at);
            if (entry.name().equals(normal(name))) return Optional.of(entry);
            at += length;
        }
        return Optional.empty();
    }

    private static Entry entry(ByteBuffer b, int at) {
        int nameLength = b.get(at + 32) & 0xFF;
        String raw = new String(bytes(b, at + 33, nameLength), StandardCharsets.ISO_8859_1);
        return new Entry(b.getInt(at + 2) & 0xFFFFFFFFL, b.getInt(at + 10) & 0xFFFFFFFFL,
                (b.get(at + 25) & DIRECTORY) != 0, normal(raw));
    }

    /** {@code name} as directories are compared: no version, no trailing dot, lower case. */
    static String normal(String name) {
        int version = name.indexOf(';');
        String bare = version >= 0 ? name.substring(0, version) : name;
        if (bare.endsWith(".")) bare = bare.substring(0, bare.length() - 1);
        return bare.toLowerCase(Locale.ROOT);
    }

    private static ByteBuffer read(FileChannel disc, long position, int length) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while (b.hasRemaining()) {
            if (disc.read(b, position + b.position()) < 0) throw new IOException("The disc image ends early.");
        }
        return b.flip();
    }

    private static byte[] bytes(ByteBuffer b, int at, int length) {
        byte[] out = new byte[length];
        b.get(at, out);
        return out;
    }
}
