package com.botmaker.shared.game;

import com.botmaker.shared.platform.Os;
import com.botmaker.shared.tools.UserDirs;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HICON;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * A Windows program's own icon as a PNG file, for a launcher that keeps no cover art on disk (Epic): the game
 * picker shows the game's icon rather than its initials.
 *
 * <p>Read from the program's icon resource ({@code PrivateExtractIcons}), not asked of the shell: the shell's
 * large image of a program whose icon has no large size is a blank page, and its small one is 16 px. Windows
 * scales the nearest size the resource has to {@link #SIZE}.
 *
 * <p>Windows only; {@code null} elsewhere and on any failure. Kept under {@link UserDirs#cache()}{@code
 * /game-icons}, named by the caller's key, the launch program's modification time and the size, so a game update
 * writes a new file (and removes the old one) and an unchanged game is read back without opening a program.
 */
final class ExeIcons {

    /** The largest size an icon resource holds. */
    private static final int SIZE = 256;

    private ExeIcons() {}

    /** The one {@code user32} call JNA's own binding lacks. */
    private interface Extract extends StdCallLibrary {
        Extract INSTANCE = Native.load("user32", Extract.class, W32APIOptions.DEFAULT_OPTIONS);

        int PrivateExtractIcons(String file, int index, int cx, int cy, HICON[] icons, int[] ids, int count,
                                int flags);
    }

    /** Whether this machine can read a program's icon at all; off Windows nothing is worth looking for. */
    static boolean supported() {
        return Os.current() == Os.WINDOWS;
    }

    /**
     * The icon for the game started by {@code launch}, as a PNG under the cache, or {@code null}. The cached file
     * is named after {@code launch} (its modification time), so a hit reads nothing else; on a miss,
     * {@code source} picks the program whose icon it is, which may be another one beside it. Blocking; never
     * throws.
     */
    static Path of(Path launch, String key, UnaryOperator<Path> source) {
        if (launch == null || key == null || !supported()) return null;
        try {
            if (!Files.isRegularFile(launch)) return null;
            String prefix = key.replaceAll("[^A-Za-z0-9._-]", "_") + "-";
            String suffix = "-" + SIZE + ".png";
            Path dir = UserDirs.cache().resolve("game-icons");
            Path png = dir.resolve(prefix + Files.getLastModifiedTime(launch).toMillis() + suffix);
            if (Files.isRegularFile(png)) return png;
            Path program = source.apply(launch);
            BufferedImage image = program == null ? null : extract(program);
            if (image == null) return null;
            Files.createDirectories(dir);
            // A file of its own per writer: two scans at once must not write into the same one.
            Path part = Files.createTempFile(dir, prefix, ".part");
            try {
                if (!ImageIO.write(image, "png", part.toFile())) return null;
                Files.move(part, png, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(part);
            }
            pruneOlder(dir, prefix, suffix, png);
            return png;
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /** Deletes the icons an earlier version of the same game left, so a weekly patch doesn't pile them up. */
    private static void pruneOlder(Path dir, String prefix, String suffix, Path keep) {
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> !p.equals(keep)).filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith(prefix) && name.endsWith(suffix)
                        && name.substring(prefix.length(), name.length() - suffix.length()).matches("\\d+");
            }).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // in use or gone: the next update tries again
                }
            });
        } catch (Exception ignored) {
            // nothing pruned is nothing lost
        }
    }

    /** The program's first icon at {@link #SIZE}, or {@code null} when it has none. */
    private static BufferedImage extract(Path exe) {
        HICON[] icons = new HICON[1];
        int[] ids = new int[1];
        int got = Extract.INSTANCE.PrivateExtractIcons(exe.toString(), 0, SIZE, SIZE, icons, ids, 1, 0);
        if (got < 1 || icons[0] == null) return null;
        WinGDI.ICONINFO info = new WinGDI.ICONINFO();
        try {
            if (!User32.INSTANCE.GetIconInfo(icons[0], info)) return null;
            int[] color = info.hbmColor == null ? null : pixels(info.hbmColor);
            int[] mask = info.hbmMask == null ? null : pixels(info.hbmMask);
            if (color == null) return null;
            boolean anyAlpha = false;
            for (int argb : color) anyAlpha |= (argb >>> 24) != 0;
            if (!anyAlpha) {
                // An icon without an alpha channel says what is transparent in its mask: white is see-through.
                for (int i = 0; i < color.length; i++) {
                    boolean clear = mask != null && (mask[i] & 0xFFFFFF) != 0;
                    color[i] = clear ? 0 : 0xFF000000 | color[i];
                }
            }
            BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, SIZE, SIZE, color, 0, SIZE);
            return image;
        } finally {
            if (info.hbmColor != null) GDI32.INSTANCE.DeleteObject(info.hbmColor);
            if (info.hbmMask != null) GDI32.INSTANCE.DeleteObject(info.hbmMask);
            User32.INSTANCE.DestroyIcon(icons[0]);
        }
    }

    /** {@code bitmap} as {@link #SIZE}² top-down 32-bit pixels, or {@code null}. */
    private static int[] pixels(HBITMAP bitmap) {
        HDC screen = User32.INSTANCE.GetDC(null);
        try {
            WinGDI.BITMAPINFO bmi = new WinGDI.BITMAPINFO();
            bmi.bmiHeader.biWidth = SIZE;
            bmi.bmiHeader.biHeight = -SIZE;
            bmi.bmiHeader.biPlanes = 1;
            bmi.bmiHeader.biBitCount = 32;
            bmi.bmiHeader.biCompression = WinGDI.BI_RGB;
            Memory buffer = new Memory((long) SIZE * SIZE * 4);
            int lines = GDI32.INSTANCE.GetDIBits(screen, bitmap, 0, SIZE, buffer, bmi, WinGDI.DIB_RGB_COLORS);
            return lines == SIZE ? buffer.getIntArray(0, SIZE * SIZE) : null;
        } finally {
            User32.INSTANCE.ReleaseDC(null, screen);
        }
    }
}
