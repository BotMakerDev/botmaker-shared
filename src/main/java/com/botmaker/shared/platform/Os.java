package com.botmaker.shared.platform;

import com.sun.jna.Platform;

import java.util.List;

/**
 * The host operating system, as a closed set — the one place this module asks "which OS is this?".
 *
 * <p><b>Why this exists.</b> shared had <em>two</em> OS-detection idioms at once: JNA's {@link Platform}
 * (in {@code NativeControllerFactory} and {@code InputListenerFactory}, which have to agree with the native
 * bindings they pick) and four private, hand-rolled copies of
 * {@code System.getProperty("os.name","").toLowerCase().contains(…)} in {@code WindowsRegistry},
 * {@code GameLauncher}, {@code WaydroidCli} and {@code UriLauncher}. Two idioms can disagree — {@code os.name}
 * is a display string, JNA reads the platform it actually loaded a library for — and four copies of a
 * substring test are four chances to write {@code "windows"} where {@code "win"} was meant. All six now ask
 * here, and this is the module's <b>only</b> importer of {@link Platform}: the sniff that decides which native
 * bindings load is by construction the same one the launch and discovery paths read.
 *
 * <p>{@link #UNKNOWN} is the {@code PlatformId} pattern's total-parse rule applied to a probe: an OS we don't
 * model is answered, not thrown at. It behaves as a generic freedesktop host, which is what the code it
 * replaced did (anything that was neither {@code win} nor {@code mac} took the {@code xdg-open} branch).
 */
public enum Os {

    /** Windows, in every edition JNA recognises. */
    WINDOWS("Windows") {
        /**
         * rundll32 url.dll,FileProtocolHandler routes the URI through ShellExecute, which honours
         * registered protocol handlers (steam://, com.epicgames.launcher://, …). Unlike `explorer.exe
         * &lt;uri&gt;`, it correctly handles a scheme that carries a query string: `explorer.exe` treats
         * `com.epicgames.launcher://apps/X?action=launch&amp;silent=true` as a filesystem target, fails to
         * resolve it, and silently opens a default Explorer window (the user's Documents) instead of
         * launching the game — the Epic "opens Documents and nothing happens" bug. rundll32 takes the
         * full URI as a single argument (no shell, so the `&amp;` is not split) and hands it to the handler.
         * Steam has no query string so it worked either way; Epic only works via this path.
         */
        @Override
        public List<String> openCommand(String uri) {
            return List.of("rundll32", "url.dll,FileProtocolHandler", uri);
        }
    },

    /** Linux — the only platform with a native input listener, and the only one Waydroid runs on. */
    LINUX("Linux"),

    /** macOS. Recognised and named, though {@code NativeControllerFactory} still refuses to run there. */
    MAC("macOS") {
        @Override
        public List<String> openCommand(String uri) {
            return List.of("open", uri);
        }
    },

    /** Anything else (Solaris, the BSDs, …) — treated as a generic freedesktop host. */
    UNKNOWN("this platform");

    private static final Os CURRENT = detect();

    private final String displayName;

    Os(String displayName) {
        this.displayName = displayName;
    }

    /**
     * The host OS, detected once per process. Memoized because it cannot change under a running JVM and the
     * launch and discovery paths ask repeatedly.
     */
    public static Os current() {
        return CURRENT;
    }

    private static Os detect() {
        if (Platform.isWindows()) {
            return WINDOWS;
        }
        if (Platform.isLinux()) {
            return LINUX;
        }
        if (Platform.isMac()) {
            return MAC;
        }
        return UNKNOWN;
    }

    /** The OS's name as a human writes it, for a message that has to say which platform refused. */
    public String displayName() {
        return displayName;
    }

    public boolean isWindows() {
        return this == WINDOWS;
    }

    public boolean isLinux() {
        return this == LINUX;
    }

    public boolean isMac() {
        return this == MAC;
    }

    /**
     * The argv that hands {@code uri} to this platform's registered protocol handler. The default is the
     * freedesktop {@code xdg-open}, which is why {@link #LINUX} and {@link #UNKNOWN} declare nothing: the
     * table {@code UriLauncher} used to hold as an if/else chain <em>is</em> this switch, so it lives on the
     * constants and a new platform can't be added without answering it.
     */
    public List<String> openCommand(String uri) {
        return List.of("xdg-open", uri);
    }
}
