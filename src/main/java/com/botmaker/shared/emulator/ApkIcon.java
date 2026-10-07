package com.botmaker.shared.emulator;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Locale;

import javax.imageio.ImageIO;

/**
 * Pulls one app's launcher icon out of its APK <b>without downloading the APK</b> ({@link ApkZip}).
 *
 * <p><b>Why this exists.</b> A package list is a list of reverse-DNS strings, and
 * {@code com.supercell.clashofclans} is only obvious if you already know it. The icon is what identifies a
 * game at a glance, and Android has no shell command that will hand one over: {@code pm} lists packages,
 * {@code dumpsys} describes them, and neither can render a resource. The icon only exists inside the APK.
 *
 * <p><b>It never throws and it is allowed to fail.</b> Every return is "the icon, or {@code null}": Zip64,
 * an APK with only an adaptive-icon XML and no raster fallback, a device whose {@code dd} does not behave, a
 * split APK whose base carries no {@code res/} — all of them are a missing thumbnail, which is exactly what
 * the caller showed before this existed.
 */
final class ApkIcon {

    /** Icons above this are not icons; refusing them keeps one absurd entry from pulling megabytes. */
    private static final int ICON_MAX_BYTES = 4 << 20;

    private ApkIcon() {}

    /** The best launcher icon in the archive, or {@code null} when there isn't one we can decode. */
    static BufferedImage read(ApkZip.Reader reader) {
        return read(reader, ApkZip.entries(reader));
    }

    /** As {@link #read(ApkZip.Reader)}, over a directory already read. */
    static BufferedImage read(ApkZip.Reader reader, java.util.List<ApkZip.Entry> entries) {
        try {
            ApkZip.Entry best = null;
            int bestRank = 0;
            for (ApkZip.Entry entry : entries) {
                int rank = rank(entry.name());
                // Ties break on size: an APK ships the same icon at every density, and the largest is worth showing.
                if (rank > 0 && entry.compressedSize() > 0 && entry.compressedSize() <= ICON_MAX_BYTES
                        && entry.uncompressedSize() <= ICON_MAX_BYTES
                        && (rank > bestRank || (rank == bestRank && entry.uncompressedSize() > best.uncompressedSize()))) {
                    best = entry;
                    bestRank = rank;
                }
            }
            byte[] png = best == null ? null : ApkZip.bytes(reader, best, ICON_MAX_BYTES);
            return png == null ? null : ImageIO.read(new ByteArrayInputStream(png));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * How much this entry looks like <em>the</em> launcher icon; 0 means "not an icon".
     *
     * <p>Matching on the file name rather than resolving the manifest's {@code android:icon} is deliberate.
     * An icon resource is narrowed by density and is often an adaptive-icon XML with no raster behind it; the
     * convention ({@code res/mipmap-<density>/ic_launcher.png}) is near-universal because every Android project
     * ships it, and being wrong here costs a slightly-off thumbnail, not a wrong launch. (The label, which has
     * no such convention, is resolved properly: {@link ApkLabel}.)
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
}
