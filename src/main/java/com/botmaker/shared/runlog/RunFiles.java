package com.botmaker.shared.runlog;

import com.botmaker.runlog.RunFacts;
import com.botmaker.runlog.RunLog;
import com.botmaker.shared.Diag;
import com.botmaker.shared.ipc.TelemetryEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A bot's run log ({@code run.jsonl}, spec {@code docs/superpowers/specs/2026-11-03-run-reports-design.md} §2): started
 * in the run's folder, its first line the run's facts, then every line {@link Diag} keeps, through {@link
 * Diag#setFileSink}.
 *
 * <p>The facts are what any host knows from the bot's folder and classpath: its pom's coordinate, its tag and commit
 * (a run copy's {@code bot.json}, else its git), the versions of BotMaker's jars and of every plugin on the classpath,
 * the OS. What the run is in a plugin's own words ({@link RunFacts#details}) and which line of the bot a line belongs
 * to are the caller's: this module knows neither.
 */
public final class RunFiles {

    private static final String GROUP = "com.github.BotMakerDev";
    /** The host's own jars, whose versions every run's facts carry. */
    private static final List<String> HOST_JARS = List.of("botmaker-shared", "botmaker-session", "botmaker-runlog");
    /** What every plugin jar carries (the plugin parent copies it): how its jar is found on the classpath. */
    private static final String PLUGIN_MARK = "META-INF/botmaker/changelog.md";
    private static final Pattern PARENT = Pattern.compile("(?s)<parent>.*?</parent>");
    private static final Pattern POM_PROPERTIES = Pattern.compile("META-INF/maven/[^/]+/[^/]+/pom\\.properties");
    private static final long GIT_SECONDS = 3;

    private static RunLog current;
    private static boolean hooked;

    private RunFiles() {}

    /**
     * Starts the run's log in {@code folder} with {@code details}, the plugin's own facts of the run, and keeps every
     * line {@code Diag} keeps there, each passed through {@code attribute} as it is made. A second start in the same
     * folder (a warm JVM's next try) keeps the log it has; a log that cannot be made is said and the run goes on
     * without one.
     */
    public static synchronized void start(Path folder, Map<String, String> details,
                                          UnaryOperator<TelemetryEvent.Log> attribute) {
        if (folder == null) return;
        if (current != null && current.folder().equals(folder)) return;
        stop();
        RunLog log;
        try {
            log = RunLog.in(folder, facts(Path.of(System.getProperty("user.dir", ".")), details));
        } catch (IOException | RuntimeException e) {
            Diag.warn("RunLog", "This run's log will not be kept: " + e.getMessage());
            return;
        }
        current = log;
        Diag.setFileSink(attribute, line -> log.write(LogLines.of(line)));
        if (!hooked) {
            // One hook for the JVM, closing whichever log is open at exit: a warm JVM's many runs add no more.
            Runtime.getRuntime().addShutdownHook(new Thread(RunFiles::stop, "run log"));
            hooked = true;
        }
    }

    /** Stops writing the run's log, closing it. */
    public static synchronized void stop() {
        if (current == null) return;
        Diag.setFileSink(null, null);
        current.close();
        current = null;
    }

    /** The run's facts: what {@code botFolder} and the classpath say, and the caller's {@code details}. */
    static RunFacts facts(Path botFolder, Map<String, String> details) {
        Map<String, String> source = source(botFolder);
        return new RunFacts(coordinate(botFolder), source.get("tag"), source.get("commit"),
                Boolean.parseBoolean(source.get("dirty")), versions(),
                System.getProperty("os.name", "") + " " + System.getProperty("os.version", ""),
                System.currentTimeMillis(), details);
    }

    /** The bot pom's {@code groupId:artifactId}, its parent's left out; null when it has none. */
    static String coordinate(Path botFolder) {
        try {
            String pom = PARENT.matcher(Files.readString(botFolder.resolve("pom.xml"), StandardCharsets.UTF_8))
                    .replaceFirst("");
            String group = element(pom, "groupId");
            String artifact = element(pom, "artifactId");
            return group == null || artifact == null ? null : group + ":" + artifact;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String element(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">\\s*([^<\\s]+)\\s*</" + name + ">").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Where the bot's code came from: a run copy's {@code bot.json} names its tag and commit; a working copy's git
     * says its exact tag, if any, its commit and whether it differs. Keys {@code tag}, {@code commit}, {@code dirty};
     * an unknown one is absent.
     */
    static Map<String, String> source(Path botFolder) {
        Map<String, String> out = new LinkedHashMap<>();
        Path json = botFolder.resolve("bot.json");
        if (Files.isRegularFile(json)) {
            try {
                JsonNode node = new ObjectMapper().readTree(json.toFile());
                if (node.hasNonNull("tag")) out.put("tag", node.get("tag").asText());
                if (node.hasNonNull("commit")) out.put("commit", node.get("commit").asText());
            } catch (IOException | RuntimeException ignored) {
                // a bot.json that does not read says nothing
            }
            return out;
        }
        if (!Files.exists(botFolder.resolve(".git"))) return out;
        String commit = git(botFolder, "rev-parse", "HEAD");
        if (commit != null) out.put("commit", commit);
        String tag = git(botFolder, "describe", "--tags", "--exact-match");
        if (tag != null) out.put("tag", tag);
        String status = git(botFolder, "status", "--porcelain");
        if (status != null) out.put("dirty", String.valueOf(!status.isBlank()));
        return out;
    }

    /** {@code git <args>}'s output in {@code folder}, stripped; null when git fails, is missing or takes too long. */
    private static String git(Path folder, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", folder.toString()));
        command.addAll(List.of(args));
        Path out = null;
        try {
            // Output to a file, so the wait's timeout holds: a git stuck with its output open never blocks the run.
            out = Files.createTempFile("botmaker-git", ".out");
            Process p = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(out.toFile()).start();
            if (!p.waitFor(GIT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? Files.readString(out, StandardCharsets.UTF_8).strip() : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (out != null) {
                try {
                    Files.deleteIfExists(out);
                } catch (IOException ignored) {
                    // a temp file left behind
                }
            }
        }
    }

    /**
     * Coordinate to version of the host's jars and of every plugin jar on the classpath, read from each jar's
     * {@code pom.properties}. A jar whose version cannot be read is left out.
     */
    static Map<String, String> versions() {
        ClassLoader loader = RunFiles.class.getClassLoader();
        Map<String, String> out = new LinkedHashMap<>();
        for (String artifact : HOST_JARS) {
            try (InputStream in = loader.getResourceAsStream("META-INF/maven/" + GROUP + "/" + artifact + "/pom.properties")) {
                if (in != null) put(out, in);
            } catch (IOException ignored) {
                // left out
            }
        }
        try {
            Enumeration<URL> plugins = loader.getResources(PLUGIN_MARK);
            while (plugins.hasMoreElements()) {
                if (plugins.nextElement().openConnection() instanceof JarURLConnection jar) pluginVersion(jar, out);
            }
        } catch (IOException ignored) {
            // the plugins' versions are left out
        }
        return out;
    }

    private static void pluginVersion(JarURLConnection connection, Map<String, String> out) {
        connection.setUseCaches(false);
        try (JarFile jar = connection.getJarFile()) {
            for (JarEntry entry : jar.stream().toList()) {
                if (!POM_PROPERTIES.matcher(entry.getName()).matches()) continue;
                try (InputStream in = jar.getInputStream(entry)) {
                    put(out, in);
                }
            }
        } catch (IOException ignored) {
            // this plugin's version is left out
        }
    }

    private static void put(Map<String, String> out, InputStream in) throws IOException {
        Properties p = new Properties();
        p.load(in);
        String group = p.getProperty("groupId");
        String artifact = p.getProperty("artifactId");
        String version = p.getProperty("version");
        if (group != null && artifact != null && version != null) out.put(group + ":" + artifact, version);
    }
}
