package com.botmaker.shared.capture;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Picks the window a title needle <em>most</em> refers to, rather than the first one that happens to contain
 * it. Both consumers resolve a capture/launch target from a user-typed title substring — Studio's pilot
 * ({@code TargetCapture.resolveWindow}) and the SDK runtime ({@code Window.find}/{@code NamedWindow}) — and a
 * bare "first {@code contains} hit" lets a wiki tab, chat channel or launcher entry named after the game win
 * over the game itself (the live "Firestone" bug). This is the single ranked matcher both call, so they can't
 * drift (the repo's "a shared type owns the probes its consumers would otherwise each rebuild" rule).
 *
 * <p>Matching is pure over the two fields {@link GenericWindow} carries — title and on-screen rect — because
 * that is all it has (no PID or window class). Candidates are ranked best→worst:
 * <ol>
 *   <li>exact title equality (case-insensitive);</li>
 *   <li>title equals the needle once a trailing dynamic suffix ({@code " - …"}/{@code " – …"}/{@code " — …"}
 *       /{@code ": …"}) — a score, level or document name — is stripped;</li>
 *   <li>title starts with the needle;</li>
 *   <li>the needle appears as a whole word (bounded by non-word characters);</li>
 *   <li>the needle appears anywhere as a substring.</li>
 * </ol>
 * Ties break by <b>shortest title</b> (closest to the bare name), then <b>largest on-screen area</b>, then
 * input order (stable). A window with a null/blank title or a null/zero-area rect is not a candidate, and a
 * window whose title does not contain the needle at all never matches.
 */
public final class WindowMatch {

    /** A tier worse than any real match — the sentinel for "does not contain the needle". */
    private static final int NO_MATCH = Integer.MAX_VALUE;

    /**
     * What a window title puts before its dynamic tail — a score, level, document or channel name. The set
     * the class javadoc's tier 2 describes; note the second and third entries are an en dash and an em dash,
     * which is exactly why they are named once here rather than retyped at the use site.
     */
    private static final String[] SUFFIX_SEPARATORS = {" - ", " – ", " — ", ": "};

    private WindowMatch() {
    }

    /** The single best window for {@code needle}, or {@code null} if none contains it. */
    public static GenericWindow best(Iterable<GenericWindow> windows, String needle) {
        List<GenericWindow> ranked = ranked(windows, needle);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    /** Every window whose title contains {@code needle}, best match first (see the class ranking). */
    public static List<GenericWindow> ranked(Iterable<GenericWindow> windows, String needle) {
        List<GenericWindow> out = new ArrayList<>();
        if (windows == null || needle == null || needle.isBlank()) {
            return out;
        }
        String want = needle.trim().toLowerCase();

        List<Scored> scored = new ArrayList<>();
        int order = 0;
        for (GenericWindow w : windows) {
            if (w == null) {
                continue;
            }
            String title = w.getTitle();
            if (title == null || title.isBlank()) {
                continue;
            }
            long area = area(w.getRect());
            if (area <= 0) {
                continue;
            }
            int tier = tier(title.trim().toLowerCase(), want);
            if (tier != NO_MATCH) {
                scored.add(new Scored(w, tier, title.trim().length(), area, order++));
            }
        }
        scored.sort(Comparator.<Scored>comparingInt(s -> s.tier)
                .thenComparingInt(s -> s.titleLen)
                .thenComparing(Comparator.<Scored>comparingLong(s -> s.area).reversed())
                .thenComparingInt(s -> s.order));
        for (Scored s : scored) {
            out.add(s.window);
        }
        return out;
    }

    /** The match tier of an already-lowercased {@code title} against an already-lowercased {@code want}. */
    private static int tier(String title, String want) {
        if (title.equals(want)) {
            return 0;
        }
        if (stripSuffix(title).equals(want)) {
            return 1;
        }
        if (title.startsWith(want)) {
            return 2;
        }
        if (isWholeWord(title, want)) {
            return 3;
        }
        if (title.contains(want)) {
            return 4;
        }
        return NO_MATCH;
    }

    /** {@code title} up to the first trailing-suffix separator ({@code " - "}, en/em dash, {@code ": "}). */
    private static String stripSuffix(String title) {
        int cut = title.length();
        for (String sep : SUFFIX_SEPARATORS) {
            int i = title.indexOf(sep);
            if (i >= 0) {
                cut = Math.min(cut, i);
            }
        }
        return title.substring(0, cut);
    }

    /** True if {@code want} occurs in {@code title} bounded on both sides by a non-word character. */
    private static boolean isWholeWord(String title, String want) {
        int from = 0;
        while (true) {
            int i = title.indexOf(want, from);
            if (i < 0) {
                return false;
            }
            boolean leftOk = i == 0 || !isWord(title.charAt(i - 1));
            int end = i + want.length();
            boolean rightOk = end == title.length() || !isWord(title.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = i + 1;
        }
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static long area(Rectangle r) {
        if (r == null) {
            return 0;
        }
        return (long) Math.max(0, r.width) * Math.max(0, r.height);
    }

    /** A candidate plus its sort keys. */
    private static final class Scored {
        final GenericWindow window;
        final int tier;
        final int titleLen;
        final long area;
        final int order;

        Scored(GenericWindow window, int tier, int titleLen, long area, int order) {
            this.window = window;
            this.tier = tier;
            this.titleLen = titleLen;
            this.area = area;
            this.order = order;
        }
    }
}
