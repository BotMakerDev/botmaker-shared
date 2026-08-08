package com.botmaker.shared.emulator;

import com.botmaker.shared.Diag;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The Android framebuffer size Waydroid boots with, and the only remedy in the Waydroid stack that BotMaker
 * can apply itself (it needs no root).
 *
 * <p><b>Why this is not optional.</b> gamescope sizes the window it hosts Waydroid in with {@code -w/-h}. If
 * Android's own framebuffer is a different size, the compositor scales or letterboxes it — and every
 * coordinate a bot matched against a template is then off by that transform, silently. A template authored at
 * 1080×1920 keeps matching (matching is scale-tolerant) while the tap lands somewhere else, which is the worst
 * shape a bug can have. So the size is set on both sides from one place, here.
 *
 * <p><b>Why the ordering is what it is.</b> {@code waydroid prop set} talks to the running container, so the
 * session has to be up to change the value — and the value is only read at session start, so it has to go
 * down and up again for the change to take. That restart is user-visible (the Android UI disappears and
 * reboots), which is exactly why {@link #apply} is a no-op when the values already match: on the normal launch
 * path, where nothing changed, nothing restarts.
 *
 * <p><b>Why reading it needs three sources.</b> {@code waydroid prop get} talks to the container too, and the
 * one moment the size is actually needed — building the launch argv — is the one moment there is no container
 * to ask: with the session down the command prints {@code "WayDroid session is stopped"} and exits 0, which
 * parses as "unset". The persisted value is not reachable either; {@code persist.*} properties live in the
 * container's own {@code data/property/} store, which is root-owned. So {@link #read(boolean)} asks the live
 * container when there is one and <b>remembers what it answered</b> ({@link #REMEMBERED_FILE}) for the next
 * cold start, and also honours a size an administrator pinned in the {@code [properties]} section of
 * {@value #SYSTEM_CONFIG_PATH}.
 *
 * @param width  the Android framebuffer width in pixels
 * @param height the Android framebuffer height in pixels
 */
public record WaydroidResolution(int width, int height) {

    /** The persistent Android properties Waydroid reads its display size from at session start. */
    public static final String WIDTH_PROP = "persist.waydroid.width";
    public static final String HEIGHT_PROP = "persist.waydroid.height";

    /**
     * The size gamescope is given when nothing can say what Android will boot at.
     *
     * <p>Not a guess at the container's size — a decision about it. With {@link #WIDTH_PROP} unset (Waydroid's
     * out-of-the-box state) Android sizes its framebuffer to the <em>compositor's</em> output, so sizing
     * gamescope is precisely what makes the two agree; the alternative, leaving gamescope unsized, lets it pick
     * its own default and puts a scaler between the bot's templates and the pixels it clicks. When the
     * properties <em>are</em> set, {@link #read(boolean)} answers with them instead and this is never reached.
     */
    public static final WaydroidResolution DEFAULT = new WaydroidResolution(1920, 1080);

    /** Waydroid's host-side config; its {@code [properties]} section is applied to the container at start. */
    static final String SYSTEM_CONFIG_PATH = "/var/lib/waydroid/waydroid.cfg";

    /** Where the last size a running container reported is kept, so a cold start still knows it. */
    static final Path REMEMBERED_FILE =
            Path.of(System.getProperty("user.home", "."), ".botmaker", "waydroid-resolution.properties");

    public WaydroidResolution {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("A resolution must be positive, got " + width + "x" + height);
        }
    }

    /** {@code 1080x1920}, for logs and the diagnostics panel. */
    @Override
    public String toString() {
        return width + "x" + height;
    }

    /**
     * The configured size, probing the session state itself. Prefer {@link #read(boolean)} where the caller
     * already knows it — both of this package's callers do, and the probe is a second {@code waydroid} spawn.
     */
    public static WaydroidResolution read() {
        return read(WaydroidCli.available() && WaydroidStatus.read().sessionRunning());
    }

    /**
     * The size Android will boot at, or {@code null} when no source can say.
     *
     * <p>In order: the live container (only it knows, and only while it is up — so what it says is remembered
     * for the next cold start), the {@code [properties]} an administrator pinned in
     * {@value #SYSTEM_CONFIG_PATH}, then what a previous session reported. {@code null} still means "unset",
     * which is a real and common state; it is the <em>caller</em> that must not turn it into an unsized
     * gamescope — see {@link #DEFAULT}.
     *
     * @param sessionRunning whether a Waydroid session is up, i.e. whether {@code prop get} has anyone to ask
     */
    public static WaydroidResolution read(boolean sessionRunning) {
        if (!WaydroidCli.available()) {
            return null;
        }
        if (sessionRunning) {
            WaydroidResolution live = parse(WaydroidCli.waydroid("prop", "get", WIDTH_PROP),
                    WaydroidCli.waydroid("prop", "get", HEIGHT_PROP));
            if (live != null) {
                remember(live);
                return live;
            }
            // Running and unset: Android took the compositor's size, so any size we pass is 1:1. Fall through
            // rather than return null, so the launch argv still carries flags.
        }
        WaydroidResolution pinned = fromSystemConfig(readIfPresent(Path.of(SYSTEM_CONFIG_PATH)));
        return pinned != null ? pinned : remembered();
    }

    /**
     * The display size pinned in the {@code [properties]} section of Waydroid's own {@code waydroid.cfg}, or
     * {@code null} when that section doesn't set it (the default — the file ships only ABI and bridge keys).
     * Package-private and pure so the INI walk is testable without Waydroid installed.
     */
    static WaydroidResolution fromSystemConfig(String cfg) {
        if (cfg == null) {
            return null;
        }
        String width = null;
        String height = null;
        boolean inProperties = false;
        for (String raw : cfg.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("[")) {
                inProperties = line.equals("[properties]");
                continue;
            }
            int eq = inProperties ? line.indexOf('=') : -1;
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (WIDTH_PROP.equals(key)) {
                width = value;
            } else if (HEIGHT_PROP.equals(key)) {
                height = value;
            }
        }
        return parse(width, height);
    }

    /** Records {@code resolution} for the next cold start; best-effort, like everything else here. */
    private static void remember(WaydroidResolution resolution) {
        try {
            Files.createDirectories(REMEMBERED_FILE.getParent());
            Files.writeString(REMEMBERED_FILE,
                    WIDTH_PROP + "=" + resolution.width() + "\n" + HEIGHT_PROP + "=" + resolution.height() + "\n",
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            Diag.log("[Emulator] waydroid: couldn't remember the framebuffer size: " + e.getMessage());
        }
    }

    /** What the last running session reported, or {@code null} if we have never seen one. */
    private static WaydroidResolution remembered() {
        String text = readIfPresent(REMEMBERED_FILE);
        if (text == null) {
            return null;
        }
        Properties props = new Properties();
        try {
            props.load(new StringReader(text));
        } catch (Exception e) {
            return null;
        }
        return parse(props.getProperty(WIDTH_PROP), props.getProperty(HEIGHT_PROP));
    }

    /** {@code file}'s text, or {@code null} when it is absent or unreadable. Never throws. */
    private static String readIfPresent(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Builds a resolution from two raw {@code waydroid prop get} outputs. An unset property prints an empty
     * line, so a blank — or anything non-numeric — reads as "not configured" rather than as a parse failure.
     * Package-private and pure so the parse is testable without a container.
     */
    static WaydroidResolution parse(String widthOutput, String heightOutput) {
        Integer w = positiveInt(widthOutput);
        Integer h = positiveInt(heightOutput);
        return w == null || h == null ? null : new WaydroidResolution(w, h);
    }

    private static Integer positiveInt(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Makes the container boot at this size, restarting the session so the new value takes effect — and does
     * <b>nothing</b> when it is already configured that way, which is the case on every launch after the
     * first.
     *
     * <p>Requires a running session to set the properties on, so it starts one if there is none. Returns
     * whether the size is now configured as asked; best-effort and never throws, like everything else in this
     * package.
     */
    public boolean apply() {
        if (!WaydroidCli.available()) {
            return false;
        }
        if (this.equals(read())) {
            return true;   // the common path: no restart, nothing user-visible happens
        }
        if (!WaydroidStatus.read().sessionRunning()) {
            // prop set goes to the container, so there has to be one to talk to.
            WaydroidCli.waydroid("session", "start");
        }
        boolean set = WaydroidCli.waydroid("prop", "set", WIDTH_PROP, Integer.toString(width)) != null
                && WaydroidCli.waydroid("prop", "set", HEIGHT_PROP, Integer.toString(height)) != null;
        if (!set) {
            return false;
        }
        // Read at session start only — without the cycle the properties are stored and ignored.
        WaydroidCli.waydroid("session", "stop");
        WaydroidCli.waydroid("session", "start");
        return true;
    }
}
