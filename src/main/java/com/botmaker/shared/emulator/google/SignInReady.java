package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.config.InstanceKey;
import com.botmaker.shared.emulator.config.Operation;
import com.botmaker.shared.tools.UserDirs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an instance is given before a Google sign-in (spec § Lowering Google's risk score), on the running instance:
 * <ol>
 *   <li>its identity checked: the model, Android ID and Google services ID, each kept only as a salted hash and told
 *       only as "the same" or "changed", never read out; one changed since its last sign-in refuses the sign-in;</li>
 *   <li>Google Play services and the Play Store present, and their versions said (Play updates both itself once an
 *       account is signed in: before that it has none to update with);</li>
 *   <li>its clock set by the network and checked against this PC's, its time zone this PC's, and its region this PC's
 *       country, its language kept English, which the sign-in reads its screens in;</li>
 *   <li>three minutes gone since it booted.</li>
 * </ol>
 * The identities are kept in {@link UserDirs#config()}, by instance.
 */
public final class SignInReady {

    static final Duration AFTER_BOOT = Duration.ofMinutes(3);
    /** How far the instance's clock may be from this PC's once set. */
    static final Duration CLOCK = Duration.ofMinutes(2);
    private static final Duration POLL = Duration.ofSeconds(1);
    /** How many {@link #POLL}s the network's time is waited for. */
    private static final int SYNC_POLLS = 15;
    static final String GMS = "com.google.android.gms";
    static final String STORE = "com.android.vending";

    /** The instance's shell; tests give their own. */
    interface Shell {
        /** The command's output, empty when it printed nothing or failed. */
        String run(String command);
    }

    /** The identity's parts, each read or not. */
    enum Part {
        MODEL("model", "getprop ro.product.model"),
        ANDROID_ID("Android ID", "settings get secure android_id"),
        GSF_ID("Google services ID", "content query --uri content://com.google.android.gsf.gservices --projection value"
                + " --where \"name='android_id'\"");

        final String label;
        final String command;

        Part(String label, String command) {
            this.label = label;
            this.command = command;
        }
    }

    private static final String SALT = ".salt";
    private static final String COMMENT = "Each instance's identity at its last Google sign-in, as salted hashes only";
    private static final Pattern VALUE = Pattern.compile("value=(\\S+)");
    private static final Pattern VERSION = Pattern.compile("versionName=(\\S+)");

    private final Shell shell;
    private final Pace pace;
    private final Path identities;
    private final ZoneId zone;
    private final Locale locale;

    SignInReady(Shell shell, Pace pace, Path identities, ZoneId zone, Locale locale) {
        this.shell = shell;
        this.pace = pace;
        this.identities = identities;
        this.zone = zone;
        this.locale = locale;
    }

    /** Readies the running {@code instance} for a sign-in. Null when it is ready, else why not, in the user's words. */
    public static String ready(EmulatorInstance instance, Operation.Context context) throws InterruptedException {
        try (AdbDevice device = AdbDevice.connect(instance)) {
            return live(device).ready(instance.name(), InstanceKey.of(instance), context);
        } catch (RuntimeException e) {
            return instance.name() + " could not be readied for a Google sign-in: " + e.getMessage();
        }
    }

    /** Keeps the running {@code instance}'s identity as it is now, once a sign-in on it is done. */
    public static void signedIn(EmulatorInstance instance) {
        try (AdbDevice device = AdbDevice.connect(instance)) {
            live(device).keep(InstanceKey.of(instance));
        } catch (RuntimeException | IOException unkept) {
            // the next sign-in finds no identity kept and keeps one then
        }
    }

    private static SignInReady live(AdbDevice device) {
        Shell shell = command -> {
            try {
                String out = device.shell(command);
                return out == null ? "" : out;
            } catch (VirtualMachineError | LinkageError fault) {
                throw fault;
            } catch (RuntimeException | Error failed) {
                // dadb surfaces some failures as Errors; either way Android did not say
                return "";
            }
        };
        return new SignInReady(shell, Pace.REAL, file(), ZoneId.systemDefault(), Locale.getDefault());
    }

    private static Path file() {
        return UserDirs.config().resolve("google-sign-in-identities.properties");
    }

    String ready(String name, InstanceKey key, Operation.Context context) throws InterruptedException {
        context.step("Checking " + name + " is the same device as at its last sign-in…");
        List<Part> changed;
        try {
            changed = changed(key);
        } catch (IOException unread) {
            return "BotMaker could not read the identities it keeps, so it signs nothing in on " + name + ": "
                    + unread.getMessage();
        }
        if (!changed.isEmpty()) {
            return name + "'s " + String.join(" and ", changed.stream().map(p -> p.label).toList()) + " changed since"
                    + " its last Google sign-in, so BotMaker signs no account in on it: to Google it would be a new"
                    + " device holding the account.";
        }
        if (context.cancelled()) return "Cancelled.";

        String gms = version(GMS);
        if (gms == null) return name + " has no Google Play services, so no Google account can be signed in on it.";
        String store = version(STORE);
        context.step(name + ": Google Play services " + gms + (store == null ? ", no Play Store" : ", Play Store "
                + store) + "; Play updates them itself once the account is signed in.");

        String clock = clock(name);
        if (clock != null) return clock;
        timeZone(name, context);
        region(name, context);

        return booted(name, context);
    }

    /** The parts read now and when the identity was kept that differ; none when no identity is kept. */
    List<Part> changed(InstanceKey key) throws IOException {
        Properties all = LockedFile.read(identities);
        Map<Part, String> kept = kept(all, key);
        String salt = all.getProperty(SALT);
        if (kept.isEmpty() || salt == null) return List.of();
        Map<Part, String> now = hashes(values(), salt);
        return kept.entrySet().stream()
                .filter(entry -> now.containsKey(entry.getKey()) && !now.get(entry.getKey()).equals(entry.getValue()))
                .map(Map.Entry::getKey).toList();
    }

    /** Keeps the instance's identity as read now; a part unread now keeps its last hash. */
    void keep(InstanceKey key) throws IOException {
        Map<Part, String> values = values();
        if (values.isEmpty()) return;
        LockedFile.with(identities, () -> {
            Properties all = LockedFile.read(identities);
            String salt = all.getProperty(SALT);
            if (salt == null) {
                byte[] bytes = new byte[16];
                new SecureRandom().nextBytes(bytes);
                salt = HexFormat.of().formatHex(bytes);
                all.setProperty(SALT, salt);
            }
            Map<Part, String> hashes = new LinkedHashMap<>(kept(all, key));
            hashes.putAll(hashes(values, salt));
            all.setProperty(key.text(), String.join(";", hashes.entrySet().stream()
                    .map(entry -> entry.getKey().name() + "=" + entry.getValue()).toList()));
            LockedFile.write(identities, all, COMMENT);
            return null;
        });
    }

    /**
     * Forgets the identity kept for {@code key}: the instance is deleted, or made again under its name, so its next
     * sign-in is a first one. Null when done, else why not.
     */
    public static String forget(InstanceKey key) {
        return moved(file(), key, null);
    }

    /** Moves the identity kept for {@code from} to its new name {@code to}. Null when done, else why not. */
    public static String renamed(InstanceKey from, InstanceKey to) {
        return moved(file(), from, to);
    }

    /** {@code from}'s identity in {@code file} moved to {@code to}, or forgotten when {@code to} is null. */
    static String moved(Path file, InstanceKey from, InstanceKey to) {
        try {
            LockedFile.with(file, () -> {
                Properties all = LockedFile.read(file);
                String line = (String) all.remove(from.text());
                // an identity left under the new name is another instance's, gone
                boolean dropped = to != null && all.remove(to.text()) != null;
                if (to != null && line != null) all.setProperty(to.text(), line);
                if (line != null || dropped) LockedFile.write(file, all, COMMENT);
                return null;
            });
            return null;
        } catch (IOException e) {
            return "BotMaker could not change the identities it keeps for Google sign-ins: " + e.getMessage();
        }
    }

    private static Map<Part, String> kept(Properties all, InstanceKey key) {
        String line = all.getProperty(key.text());
        Map<Part, String> kept = new LinkedHashMap<>();
        if (line == null) return kept;
        for (String pair : line.split(";")) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2) continue;
            try {
                kept.put(Part.valueOf(parts[0].strip()), parts[1].strip());
            } catch (IllegalArgumentException unknown) {
                // a part this version doesn't read
            }
        }
        return kept;
    }

    /** Each part that answers now, by its value: held in memory only, hashed before anything is kept. */
    private Map<Part, String> values() {
        Map<Part, String> values = new LinkedHashMap<>();
        for (Part part : Part.values()) {
            String value = value(part, shell.run(part.command));
            if (value != null) values.put(part, value);
        }
        return values;
    }

    /**
     * Each value as a hash salted by the file's own random salt: the value itself is never kept, said or logged, and a
     * renamed instance's hashes stay its own.
     */
    private static Map<Part, String> hashes(Map<Part, String> values, String salt) {
        Map<Part, String> hashes = new LinkedHashMap<>();
        values.forEach((part, value) -> hashes.put(part, hash(salt + "\n" + part.name() + "\n" + value)));
        return hashes;
    }

    /** The part's value in the command's output, or null when it did not say. */
    static String value(Part part, String out) {
        String text = out == null ? "" : out.strip();
        if (part == Part.GSF_ID) {
            Matcher found = VALUE.matcher(text);
            text = found.find() ? found.group(1) : "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        boolean failed = lower.startsWith("error") || lower.contains("exception") || lower.contains("not found")
                || lower.contains("permission denied") || text.contains("\n");
        return text.isEmpty() || lower.equals("null") || failed ? null : text;
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException never) {
            throw new IllegalStateException(never);
        }
    }

    /** The installed package's version, or null when Android lists none. */
    private String version(String packageName) {
        Matcher found = VERSION.matcher(shell.run("dumpsys package " + packageName + " | grep versionName"));
        return found.find() ? found.group(1) : null;
    }

    /** The clock set by the network, else by this PC's; null when within {@link #CLOCK}, else why not. */
    private String clock(String name) throws InterruptedException {
        shell.run("settings put global auto_time 1");
        Long off = offSeconds();
        // the network's time takes a moment to land
        for (int i = 0; i < SYNC_POLLS && off != null && Math.abs(off) > CLOCK.toSeconds(); i++) {
            pace.sleep(POLL);
            off = offSeconds();
        }
        if (off != null && Math.abs(off) > CLOCK.toSeconds()) {
            shell.run("date @" + pace.nowMillis() / 1000);
            off = offSeconds();
        }
        if (off == null) return name + "'s clock did not answer, so BotMaker signs nothing in on it.";
        if (Math.abs(off) > CLOCK.toSeconds()) {
            return name + "'s clock is " + Math.abs(off) / 60 + " min " + (off > 0 ? "ahead of" : "behind") + " this"
                    + " PC's and could not be set, so BotMaker signs nothing in on it: Google refuses a device whose"
                    + " clock is wrong.";
        }
        return null;
    }

    private Long offSeconds() {
        try {
            return Long.parseLong(shell.run("date +%s").strip()) - pace.nowMillis() / 1000;
        } catch (NumberFormatException unread) {
            return null;
        }
    }

    private void timeZone(String name, Operation.Context context) {
        String wanted = zone.getId();
        if (wanted.equals(shell.run("getprop persist.sys.timezone").strip())) return;
        shell.run("settings put global auto_time_zone 0");
        shell.run("setprop persist.sys.timezone " + wanted);
        shell.run("am broadcast -a android.intent.action.TIMEZONE_CHANGED --es time-zone " + wanted);
        context.step(wanted.equals(shell.run("getprop persist.sys.timezone").strip())
                ? name + "'s time zone is this PC's, " + wanted + "."
                : name + "'s time zone could not be set to this PC's, " + wanted + "; it is signed in all the same.");
    }

    /** English, which the sign-in reads, in this PC's country; set from the instance's next start. */
    private void region(String name, Operation.Context context) {
        String country = locale.getCountry();
        if (country.isEmpty()) return;
        String wanted = "en-" + country;
        if (wanted.equalsIgnoreCase(shell.run("getprop persist.sys.locale").strip())) return;
        shell.run("setprop persist.sys.locale " + wanted);
        context.step(wanted.equalsIgnoreCase(shell.run("getprop persist.sys.locale").strip())
                ? name + "'s region is this PC's country, " + country + ", from its next start."
                : name + "'s region could not be set to this PC's country, " + country + ".");
    }

    /** Waits until {@link #AFTER_BOOT} has gone since the instance booted; null then, else why not. */
    private String booted(String name, Operation.Context context) throws InterruptedException {
        long said = -1;
        while (true) {
            if (context.cancelled()) return "Cancelled.";
            Double uptime = uptime();
            if (uptime == null || uptime >= AFTER_BOOT.toSeconds()) return null;
            long left = (long) Math.ceil(AFTER_BOOT.toSeconds() - uptime);
            long minutes = (left + 59) / 60;
            if (minutes != said) {
                said = minutes;
                context.step("Letting " + name + " settle for " + minutes + " min after its boot before it signs in…");
            }
            pace.sleep(POLL);
        }
    }

    private Double uptime() {
        String out = shell.run("cat /proc/uptime").strip();
        try {
            return out.isEmpty() ? null : Double.parseDouble(out.split("\\s+")[0]);
        } catch (NumberFormatException unread) {
            return null;
        }
    }
}
