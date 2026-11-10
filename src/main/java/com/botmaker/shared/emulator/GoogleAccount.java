package com.botmaker.shared.emulator;

import com.botmaker.shared.Diag;
import com.botmaker.shared.Spawn;
import com.botmaker.shared.oauth.Credentials;
import com.botmaker.shared.tools.ManagedTools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The one Google account BotMaker downloads Google Play's files with, for every emulator (2026-11-10, the user's
 * call): signed in once on this computer, never on an instance. {@link #signIn} runs goopdl's own browser sign-in,
 * an isolated Chrome or Edge window where Google asks for the password and the 2-step check itself, and keeps the
 * email and the AAS token it gives in {@link Credentials}, beside GitHub's and Studio's own sign-ins.
 * {@link PlayDownload} uses it only when Aurora's anonymous accounts gave nothing.
 *
 * <p><b>The token is the account's key to Google Play.</b> Only the user signs in, in the browser window, opened from
 * the Game dialog or by an assistant (the SDK's {@code setup google_sign_in}, 2026-11-10); no tool's answer carries the
 * token ({@code CLAUDE.md}: no assistant session reads a secret value), and nothing logs it.
 */
public final class GoogleAccount {

    static final String EMAIL = "google_play_email";
    static final String TOKEN = "google_play_aas";

    /** goopdl's browser capture waits five minutes for the user; this leaves it room to answer. */
    private static final Duration TIMEOUT = Duration.ofMinutes(6);

    private static final String EMAIL_LINE = "BOTMAKER_EMAIL=";
    private static final String TOKEN_LINE = "BOTMAKER_AAS=";
    private static final String ERROR_LINE = "BOTMAKER_ERROR=";

    /**
     * goopdl's {@code aastoken --browser} without its printing, which gives the token alone: the same two calls, the
     * email printed too. goopdl is pinned ({@link ManagedTools#GOOPDL_VERSION}), so its module names hold.
     */
    private static final String SCRIPT = String.join("\n",
            "import sys",
            "try:",
            "    from goopdl.browser_oauth import capture_oauth_credentials",
            "    from goopdl.aastoken import fetch_aas_token",
            "    email, oauth = capture_oauth_credentials()",
            "    token = fetch_aas_token(email, oauth)",
            "except Exception as e:",
            "    print('" + ERROR_LINE + "' + (str(e) or type(e).__name__), flush=True)",
            "    sys.exit(1)",
            "print('" + EMAIL_LINE + "' + email, flush=True)",
            "print('" + TOKEN_LINE + "' + token, flush=True)");

    private GoogleAccount() {}

    /** A signed-in account; its {@code toString} leaves the token out. */
    record Account(String email, String token) {

        @Override
        public String toString() {
            return "Account[" + email + "]";
        }
    }

    /** What a sign-in did: the email signed in, or why not. */
    public record Result(Optional<String> email, String message) {

        public boolean ok() {
            return email.isPresent();
        }
    }

    /** The email signed in on this computer, or empty. */
    public static Optional<String> signedIn() {
        return account().map(Account::email);
    }

    /** The account {@link PlayDownload} downloads with, or empty. */
    static Optional<Account> account() {
        String email = Credentials.read(EMAIL);
        String token = Credentials.read(TOKEN);
        return valid(email, token) ? Optional.of(new Account(email, token)) : Optional.empty();
    }

    /** What a caller says after {@link #signOut}: the sign-in stays listed at Google until removed there. */
    public static final String SIGNED_OUT = "Signed out of Google on this computer. Google still lists the sign-in "
            + "under the account's devices, where you can remove it.";

    /** The thread waiting in {@link #signIn}, or null: one sign-in at a time, whoever started it. */
    private static final AtomicReference<Thread> SIGNING_IN = new AtomicReference<>();

    /** Forgets the account on this computer. Google still lists the sign-in under the account's devices. */
    public static void signOut() {
        Credentials.remove(EMAIL);
        Credentials.remove(TOKEN);
    }

    /** Whether a sign-in waits in the browser, started by the Game dialog or an assistant. */
    public static boolean signingIn() {
        return SIGNING_IN.get() != null;
    }

    /** Stops the sign-in waiting in the browser, if one is; whether one was. Its {@link #signIn} then returns. */
    public static boolean cancelSignIn() {
        Thread waiting = SIGNING_IN.get();
        if (waiting == null) return false;
        waiting.interrupt();
        return true;
    }

    /**
     * Opens goopdl's browser window, waits for the user to sign in there, and keeps the account. Installs goopdl
     * first when there is none. Blocking (minutes, the user's time) and interruptible ({@link #cancelSignIn}); refused
     * while another sign-in waits.
     *
     * @param progress optional narration; never the token
     */
    public static Result signIn(Consumer<String> progress) {
        if (!SIGNING_IN.compareAndSet(null, Thread.currentThread())) {
            return new Result(Optional.empty(), "A Google sign-in is already waiting in the browser.");
        }
        try {
            return signInOnce(progress);
        } finally {
            SIGNING_IN.set(null);
        }
    }

    private static Result signInOnce(Consumer<String> progress) {
        if (ManagedTools.goopdlInstalled().isEmpty()) report(progress, "Getting goopdl, the Google Play downloader…");
        StringBuilder why = new StringBuilder();
        Optional<Path> goopdl = ManagedTools.installGoopdl(why::append);
        if (Thread.currentThread().isInterrupted()) return new Result(Optional.empty(), "Stopped signing in.");
        if (goopdl.isEmpty()) {
            return new Result(Optional.empty(), why.isEmpty() ? "goopdl couldn't be installed." : why.toString());
        }
        Optional<Path> python = ManagedTools.goopdlPython();
        if (python.isEmpty()) return new Result(Optional.empty(), "goopdl's Python is missing from its venv.");
        report(progress, "Sign in to Google in the browser window that opened, then select I agree.");
        // Filled on Spawn's drain thread, read here.
        List<String> lines = new CopyOnWriteArrayList<>();
        Spawn.Completed done;
        Path temp = null;
        try {
            // goopdl's browser profile, signed in, lands in its temp folder; a cancel kills Python before its own
            // cleanup, so the folder is BotMaker's and goes whatever happens.
            temp = Files.createTempDirectory("botmaker-google-");
            List<String> command = command(python.get());
            Diag.log("[Google] " + command.getFirst() + " -I -X utf8 -c <goopdl's browser sign-in>");
            done = Spawn.streamed(TIMEOUT, command, Map.of("TEMP", temp.toString(), "TMP", temp.toString(),
                    "TMPDIR", temp.toString()), lines::add);
        } catch (IOException e) {
            return new Result(Optional.empty(), "goopdl didn't start: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Optional.empty(), "Stopped signing in.");
        } finally {
            if (temp != null) deleteTree(temp);
        }
        if (done == null) return new Result(Optional.empty(), "Nobody signed in within " + TIMEOUT.toMinutes()
                + " minutes.");
        List<String> said = List.copyOf(lines);
        Optional<Account> account = parse(said);
        if (account.isEmpty()) return new Result(Optional.empty(), "Google sign-in failed: " + error(said));
        Credentials.store(TOKEN, account.get().token());
        Credentials.store(EMAIL, account.get().email());
        if (!account.equals(account())) {
            signOut();
            return new Result(Optional.empty(), "Signed in to Google, but BotMaker couldn't keep the account in "
                    + "its credentials file; try again.");
        }
        return new Result(Optional.of(account.get().email()), "Signed in to Google as " + account.get().email() + ".");
    }

    /**
     * Deletes {@code folder}, trying for a few seconds: the browser killed with Python may still hold its profile's
     * files open for a moment on Windows.
     */
    private static void deleteTree(Path folder) {
        // A cancel interrupted the thread; the profile still goes, and the interrupt is kept for the caller.
        boolean interrupted = Thread.interrupted();
        try {
            deleteTreeRetrying(folder);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void deleteTreeRetrying(Path folder) {
        for (int attempt = 0; attempt < 10 && Files.exists(folder); attempt++) {
            try (Stream<Path> walk = Files.walk(folder)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException | UncheckedIOException ignored) {
                // a file vanished or is held: the next round sees what is left
            }
            if (!Files.exists(folder)) return;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (Files.exists(folder)) Diag.log("[Google] couldn't delete the sign-in's browser profile " + folder);
    }

    /** The command that runs {@link #SCRIPT} in goopdl's venv, isolated from the user's Python settings. Pure. */
    static List<String> command(Path python) {
        // -I ignores PYTHONIOENCODING; -X utf8 makes the pipe UTF-8 whatever the console's code page.
        return List.of(python.toString(), "-I", "-X", "utf8", "-c", SCRIPT);
    }

    /** The account in the script's lines, when it printed both and the token is an AAS token. Pure. */
    static Optional<Account> parse(List<String> lines) {
        String email = "";
        String token = "";
        for (String line : lines) {
            if (line.startsWith(EMAIL_LINE)) email = line.substring(EMAIL_LINE.length()).strip();
            if (line.startsWith(TOKEN_LINE)) token = line.substring(TOKEN_LINE.length()).strip();
        }
        return valid(email, token) ? Optional.of(new Account(email, token)) : Optional.empty();
    }

    /** Whether {@code email} and {@code token} make an account goopdl takes. Pure. */
    private static boolean valid(String email, String token) {
        return email.contains("@") && token.startsWith("aas_et/");
    }

    /** Why the script failed, in its words; never a token line. Pure. */
    static String error(List<String> lines) {
        String last = "it gave no account.";
        for (String line : lines) {
            if (line.startsWith(ERROR_LINE)) return line.substring(ERROR_LINE.length()).strip();
            if (!line.startsWith(TOKEN_LINE) && !line.isBlank()) last = line.strip();
        }
        return last;
    }

    private static void report(Consumer<String> progress, String message) {
        if (progress != null) progress.accept(message);
    }
}
