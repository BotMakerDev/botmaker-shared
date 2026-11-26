// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.capture.linux;

import com.botmaker.shared.Spawn;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.WindowPointer;

import java.awt.Rectangle;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Points through KWin (KDE, Wayland or X11): {@code org.kde.KWin.queryWindowInfo} shows KWin's own crosshair, takes
 * the click (which never reaches the window) and answers the clicked window's class, caption and geometry. Run by
 * {@code gdbus}, so no D-Bus library. Esc is KWin's {@code org.kde.KWin.Error.UserCancel}.
 */
public final class KWinPointer implements WindowPointer {

    private static final Duration BUS_PROBE = Duration.ofSeconds(3);
    /** The longest a pick waits for KWin's answer. */
    static final Duration KWIN_WAIT = Duration.ofHours(1);
    private static final Pattern ENTRY = Pattern.compile("'([A-Za-z]+)': <");
    private static final Pattern TYPE_WORD = Pattern.compile("[a-z]+[0-9]* (?=\\S)");

    private final Supplier<List<GenericWindow>> capturable;

    public KWinPointer(Supplier<List<GenericWindow>> capturable) {
        this.capturable = capturable;
    }

    /** The clicked window as KWin says it, its geometry in KWin's logical coordinates. */
    public record KWinWindow(String resourceClass, String caption, double x, double y, double width, double height) {
        Rectangle rect() {
            return new Rectangle((int) Math.round(x), (int) Math.round(y), (int) Math.round(width),
                    (int) Math.round(height));
        }
    }

    /** True when {@code org.kde.KWin} owns its name on the session bus. */
    public static boolean kwinOnBus() {
        try {
            Spawn.Completed answer = Spawn.run(BUS_PROBE, "gdbus", "call", "--session",
                    "--dest", "org.freedesktop.DBus", "--object-path", "/org/freedesktop/DBus",
                    "--method", "org.freedesktop.DBus.NameHasOwner", "org.kde.KWin");
            return answer != null && answer.ok() && answer.output().contains("true");
        } catch (IOException e) {
            return false; // no gdbus
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public boolean available() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Here {@code timeout} is not applied: KWin keeps its crosshair until a click or Esc, whether or not its
     * caller is still waiting, so giving up early would leave the user's next click to a query nobody reads. The
     * wait ends with KWin's answer, bounded only by {@link #KWIN_WAIT}.
     */
    @Override
    public Pointed point(Duration timeout) {
        Spawn.Completed answer;
        try {
            answer = Spawn.run(KWIN_WAIT.plusSeconds(5), "gdbus", "call", "--session", "--dest", "org.kde.KWin",
                    "--object-path", "/KWin", "--method", "org.kde.KWin.queryWindowInfo",
                    "--timeout", Long.toString(KWIN_WAIT.toSeconds()));
        } catch (IOException e) {
            return new Pointed.Unreadable("KWin couldn't be asked: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Pointed.Cancelled();
        }
        if (answer == null) return new Pointed.Cancelled(); // timed out, gdbus killed
        if (!answer.ok()) {
            if (answer.output().contains("UserCancel")) return new Pointed.Cancelled();
            return new Pointed.Unreadable("KWin didn't say which window: " + answer.output().strip().lines()
                    .findFirst().orElse("exit " + answer.exitCode()));
        }
        return parse(answer.output())
                .map(window -> WindowPointer.resolve(
                        new Clicked(null, window.resourceClass(), window.caption(), window.rect()),
                        capturable.get(), NATIVE_WAYLAND))
                .orElse(new Pointed.Cancelled());
    }

    /**
     * {@code gdbus}'s text of an {@code a{sv}}, such as {@code ({'caption': <'Hades II'>, 'x': <0.0>},)}; empty for
     * an empty map, KWin's answer when nothing was picked.
     */
    static Optional<KWinWindow> parse(String output) {
        Map<String, String> values = new HashMap<>();
        Matcher entry = ENTRY.matcher(output);
        int from = 0;
        while (from < output.length() && entry.find(from)) {
            int at = entry.end();
            Matcher type = TYPE_WORD.matcher(output).region(at, output.length());
            if (type.lookingAt()) at = type.end();
            StringBuilder value = new StringBuilder();
            if (at < output.length() && (output.charAt(at) == '\'' || output.charAt(at) == '"')) {
                char quote = output.charAt(at++);
                while (at < output.length() && output.charAt(at) != quote) {
                    char c = output.charAt(at++);
                    if (c == '\\' && at < output.length()) c = output.charAt(at++);
                    value.append(c);
                }
                at++;
            } else {
                while (at < output.length() && output.charAt(at) != '>') value.append(output.charAt(at++));
            }
            values.put(entry.group(1), value.toString().strip());
            from = at;
        }
        if (values.isEmpty()) return Optional.empty();
        return Optional.of(new KWinWindow(blankToNull(values.get("resourceClass")),
                blankToNull(values.get("caption")), number(values.get("x")), number(values.get("y")),
                number(values.get("width")), number(values.get("height"))));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static double number(String s) {
        if (s == null) return 0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
