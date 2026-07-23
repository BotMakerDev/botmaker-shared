package com.botmaker.shared.launch;

import com.botmaker.shared.Diag;

import java.awt.Desktop;
import java.net.URI;
import java.util.List;

/**
 * Opens a URI (e.g. a {@code steam://} protocol URL) through the operating system's registered handler.
 *
 * <p>{@link Desktop#browse(URI)} is tried first but is a silent no-op under many Linux desktop
 * environments (and unsupported headless), so this falls back to the platform's URL opener
 * ({@code xdg-open} / {@code open} / {@code rundll32}). Returns whether a handler was successfully invoked;
 * never throws.
 *
 * <p>This used to exist twice — once in the SDK ({@code internal.launch.UriLauncher}, for {@code steam://} and
 * friends) and once in Studio ({@code util.BrowserLauncher}, for {@code https://}) — each javadoc naming the
 * other as the copy it could not depend on. shared is the module both can depend on, so it owns it now and the
 * two callers are thin wrappers.
 */
public final class UriLauncher {

    private UriLauncher() {}

    /** Opens {@code uri} with the OS handler. Returns {@code true} if a launcher was invoked. */
    public static boolean open(String uri) {
        if (uri == null || uri.isBlank()) return false;
        // Custom protocol schemes (steam://, discord://, …) must go to the OS protocol handler. On Windows
        // Desktop.browse hands them to the default *browser*, which shows a blank page instead of launching
        // Steam — so only use Desktop.browse for real web/file URLs and route everything else natively.
        if (isWebOrFileScheme(uri) && tryDesktop(uri)) return true;
        return tryNativeOpener(uri);
    }

    private static boolean isWebOrFileScheme(String uri) {
        String lower = uri.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("file:");
    }

    private static boolean tryDesktop(String uri) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(uri));
                return true;
            }
        } catch (Exception e) {
            Diag.error("Desktop.browse failed for " + uri + ": " + e.getMessage());
        }
        return false;
    }

    private static boolean tryNativeOpener(String uri) {
        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> command;
        if (os.contains("win")) {
            // rundll32 url.dll,FileProtocolHandler routes the URI through ShellExecute, which honours
            // registered protocol handlers (steam://, com.epicgames.launcher://, …). Unlike `explorer.exe
            // <uri>`, it correctly handles a scheme that carries a query string: `explorer.exe` treats
            // `com.epicgames.launcher://apps/X?action=launch&silent=true` as a filesystem target, fails to
            // resolve it, and silently opens a default Explorer window (the user's Documents) instead of
            // launching the game — the Epic "opens Documents and nothing happens" bug. rundll32 takes the
            // full URI as a single argument (no shell, so the `&` is not split) and hands it to the handler.
            // Steam has no query string so it worked either way; Epic only works via this path.
            command = List.of("rundll32", "url.dll,FileProtocolHandler", uri);
        } else if (os.contains("mac")) {
            command = List.of("open", uri);
        } else {
            command = List.of("xdg-open", uri);
        }
        try {
            new ProcessBuilder(command).inheritIO().start();
            return true;
        } catch (Exception e) {
            Diag.error("Native opener failed for " + uri + ": " + e.getMessage());
            return false;
        }
    }
}
