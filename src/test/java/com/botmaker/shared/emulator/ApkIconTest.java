package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The APK icon reader against real ZIPs built in memory. An APK <em>is</em> a ZIP, so
 * {@link ZipOutputStream} produces the exact structure the reader walks — end-of-central-directory, central
 * directory, local headers — and the interesting behaviour (which entry it picks, and that it only ever reads
 * the ranges it needs) is asserted here rather than against a device nobody has in CI.
 */
class ApkIconTest {

    /** A {@link ApkIcon.Reader} over a byte array that records how much of it was actually read. */
    private static final class ArrayReader implements ApkIcon.Reader {
        private final byte[] bytes;
        private long bytesRead;

        ArrayReader(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public long size() {
            return bytes.length;
        }

        @Override
        public byte[] read(long offset, int length) {
            int from = (int) Math.min(offset, bytes.length);
            int to = (int) Math.min(offset + length, bytes.length);
            bytesRead += to - from;
            return Arrays.copyOfRange(bytes, from, to);
        }
    }

    private static byte[] png(int size, Color color) throws Exception {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, size, size);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] zip(String[] names, byte[][] contents) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < names.length; i++) {
                zip.putNextEntry(new ZipEntry(names[i]));
                zip.write(contents[i]);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void readsTheLauncherIconAndPrefersTheLargestDensity() throws Exception {
        byte[] apk = zip(
                new String[]{
                        "AndroidManifest.xml",
                        "res/mipmap-mdpi/ic_launcher.png",
                        "res/mipmap-xxxhdpi/ic_launcher.png",
                        "res/drawable/some_button.png",
                        "classes.dex"},
                new byte[][]{
                        "not really xml".getBytes(),
                        png(16, Color.RED),
                        png(64, Color.GREEN),
                        png(128, Color.BLUE),
                        new byte[4096]});

        BufferedImage icon = ApkIcon.read(new ArrayReader(apk));

        assertNotNull(icon);
        // 64, not 128: some_button.png is bigger but isn't an icon, and mdpi is an icon but is smaller.
        assertEquals(64, icon.getWidth());
    }

    @Test
    void fallsBackToAnAdaptiveIconsForegroundLayer() throws Exception {
        byte[] apk = zip(
                new String[]{
                        "res/drawable/ic_launcher_background.png",
                        "res/mipmap-xxhdpi/ic_launcher_foreground.png"},
                new byte[][]{png(108, Color.WHITE), png(72, Color.MAGENTA)});

        BufferedImage icon = ApkIcon.read(new ArrayReader(apk));

        // The background layer is a flat colour and never the logo, so it must lose to the foreground even
        // though it is the larger image.
        assertNotNull(icon);
        assertEquals(72, icon.getWidth());
    }

    @Test
    void readsOnlyASmallFractionOfALargeArchive() throws Exception {
        byte[] bulk = new byte[4_000_000];
        // Incompressible, so the entry stays large on disk — a compressible one would defeat the point.
        new java.util.Random(7).nextBytes(bulk);
        byte[] apk = zip(
                new String[]{"lib/arm64-v8a/libgame.so", "res/mipmap-xxxhdpi/ic_launcher.png"},
                new byte[][]{bulk, png(96, Color.ORANGE)});

        ArrayReader reader = new ArrayReader(apk);
        assertNotNull(ApkIcon.read(reader));

        // The whole point of walking the ZIP structure instead of pulling the file: a game APK is enormous
        // and the icon is a rounding error of it.
        assertEquals(true, reader.bytesRead < apk.length / 10,
                "read " + reader.bytesRead + " of " + apk.length + " bytes");
    }

    @Test
    void returnsNullRatherThanThrowingOnRubbish() {
        assertNull(ApkIcon.read(new ArrayReader(new byte[0])));
        assertNull(ApkIcon.read(new ArrayReader("this is not a zip file at all".getBytes())));
        assertNull(ApkIcon.read(new ArrayReader(new byte[70_000])));
    }

    @Test
    void returnsNullWhenTheArchiveHasNoIcon() throws Exception {
        byte[] apk = zip(new String[]{"classes.dex", "res/raw/song.ogg"},
                new byte[][]{new byte[128], new byte[128]});
        assertNull(ApkIcon.read(new ArrayReader(apk)));
    }

    @Test
    void picksTheBaseApkOutOfASplitInstall() {
        assertEquals("/data/app/~~a==/com.foo-1/base.apk",
                AdbDevice.parseApkPath("package:/data/app/~~a==/com.foo-1/split_config.arm64_v8a.apk\n"
                        + "package:/data/app/~~a==/com.foo-1/base.apk"));
        assertEquals("/data/app/com.foo-2/base.apk",
                AdbDevice.parseApkPath("package:/data/app/com.foo-2/base.apk"));
        assertNull(AdbDevice.parseApkPath(""));
        assertNull(AdbDevice.parseApkPath("Failure [not installed]"));
    }
}
