package com.botmaker.shared.emulator.checks;

import com.botmaker.shared.emulator.PlatformId;
import com.botmaker.shared.tools.Downloads;

import java.util.List;
import java.util.Optional;

/**
 * The version of each Windows emulator BotMaker was tested on, and, where the vendor serves that version's offline
 * installer at a fixed address, the installer pinned by its sha256 (the user's call, 2026-11-11). BotMaker never
 * hosts an installer: the licences forbid it.
 *
 * <p>A product is named by its <i>Apps &amp; features</i> key, which changes with its edition and major version
 * ({@code BlueStacks_nxt} and {@code BlueStacks_msi5}, {@code LDPlayer14}): a version is compared only within one.
 *
 * <p>The versions are the ones phase 1's live test passed on, on 2026-11-10/11. A pin moves only after that test
 * passes on the new version. Which installers are pinned and why the others are not:
 * <ul>
 *   <li>LDPlayer serves each version at {@code setup.ldrescdn.com/download/package/LDPlayer_<v>.exe}, a whole
 *       installer of its own making that reads {@code -silence} (checked 2026-11-11).</li>
 *   <li>BlueStacks' download is a 1 MB online stub ({@code BlueStacksMicroInstaller}) of the newest version only.</li>
 *   <li>MEmu's offline installer is served for its newest version only (9.5.8.0), not the tested 9.5.5.1.</li>
 *   <li>MuMu's and GameLoop's sites give no fixed installer address.</li>
 * </ul>
 */
public final class TestedVersions {

    /**
     * A vendor's offline installer.
     *
     * @param silent the arguments that install it with no window
     */
    public record Installer(Downloads.Remote remote, List<String> silent) {

        public Installer {
            silent = List.copyOf(silent);
        }

        /** The file name the download is kept under. */
        public String fileName() {
            String path = remote.url().replaceFirst("[?#].*$", "");
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    /**
     * One tested product.
     *
     * @param key          its <i>Apps &amp; features</i> key
     * @param product      what the user knows it as
     * @param version      the tested {@code DisplayVersion}
     * @param downloadPage the vendor's page, for a version to install by hand
     * @param installer    the pinned installer of {@code version}, or {@code null}
     */
    public record Tested(PlatformId platform, String key, String product, String version, String downloadPage,
                         Installer installer) {}

    static final Installer LDPLAYER_14 = new Installer(new Downloads.Remote(
            "https://setup.ldrescdn.com/download/package/LDPlayer_14.0.29.0.exe", Downloads.Digest.SHA_256,
            "1e37c56f71a35fdc39f10ff7c8833209b1418e51ddd07bd8ab32dfe34df41619", 974_908_384L), List.of("-silence"));

    public static final List<Tested> ALL = List.of(
            new Tested(PlatformId.BLUESTACKS, "BlueStacks_nxt", "BlueStacks 5", "5.22.265.1012",
                    "https://www.bluestacks.com/download.html", null),
            new Tested(PlatformId.BLUESTACKS, "BlueStacks_msi5", "MSI App Player", "5.22.75.6322",
                    "https://www.msi.com/Landing/appplayer", null),
            new Tested(PlatformId.LDPLAYER, "LDPlayer14", "LDPlayer 14", "14.0.29.0",
                    "https://www.ldplayer.net/other/version-history-and-release-notes.html", LDPLAYER_14),
            new Tested(PlatformId.MUMU, "MuMuPlayerGlobal", "MuMu Player", "6.8.0.0",
                    "https://www.mumuplayer.com/", null),
            new Tested(PlatformId.MEMU, "MEmu", "MEmu", "9.5.5.1", "https://www.memuplay.com/", null),
            new Tested(PlatformId.GAMELOOP, "GameLoop", "GameLoop", "7.0.19.05", "https://www.gameloop.com/", null));

    private TestedVersions() {}

    /** The tested version of the product whose <i>Apps &amp; features</i> key is {@code key}. */
    public static Optional<Tested> forKey(String key) {
        return ALL.stream().filter(t -> t.key().equalsIgnoreCase(key)).findFirst();
    }

    /** The products BotMaker can install itself, in {@link #ALL}'s order. */
    public static List<Tested> installable() {
        return ALL.stream().filter(t -> t.installer() != null).toList();
    }

    /**
     * {@code a} against {@code b}, part by part: numbers as numbers ({@code 7.0.19.05} after {@code 7.0.9}), any
     * other part as text, a missing part as zero.
     */
    static int compare(String a, String b) {
        String[] left = a.trim().split("[.\\-]");
        String[] right = b.trim().split("[.\\-]");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            String l = i < left.length ? left[i] : "0";
            String r = i < right.length ? right[i] : "0";
            int order = l.matches("\\d{1,18}") && r.matches("\\d{1,18}")
                    ? Long.compare(Long.parseLong(l), Long.parseLong(r)) : l.compareTo(r);
            if (order != 0) return order;
        }
        return 0;
    }

    /** The major version, or -1 when the first part is not a number. */
    static int major(String version) {
        String first = version.trim().split("[.\\-]")[0];
        return first.matches("\\d{1,9}") ? Integer.parseInt(first) : -1;
    }
}
