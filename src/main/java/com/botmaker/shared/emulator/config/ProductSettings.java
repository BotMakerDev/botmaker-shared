package com.botmaker.shared.emulator.config;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.PlatformId;
import com.botmaker.shared.emulator.WaydroidCli;
import com.botmaker.shared.emulator.checks.WindowsSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.botmaker.shared.emulator.config.ConfigField.ADB;
import static com.botmaker.shared.emulator.config.ConfigField.CPU_CORES;
import static com.botmaker.shared.emulator.config.ConfigField.DO_NOT_DISTURB;
import static com.botmaker.shared.emulator.config.ConfigField.FPS;
import static com.botmaker.shared.emulator.config.ConfigField.LOCALE;
import static com.botmaker.shared.emulator.config.ConfigField.MEMORY;
import static com.botmaker.shared.emulator.config.ConfigField.POPUPS_OFF;
import static com.botmaker.shared.emulator.config.ConfigField.ROOT;
import static com.botmaker.shared.emulator.config.ConfigField.SCREEN;
import static com.botmaker.shared.emulator.config.ConfigField.TIME_ZONE;

/**
 * What one emulator product's own settings say about an instance, and which {@link ConfigField}s BotMaker can apply
 * there (spec {@code 2026-11-12-emulators-window-design.md} § Fields). A field it cannot apply is shown as set by hand
 * on that emulator, never as a change.
 *
 * <p>Read without Android: from the product's file (LDPlayer's {@code leidian<i>.config}, BlueStacks'
 * {@code bluestacks.conf}, MEmu's {@code .memu}) or console ({@code MuMuManager setting --all}), so a stopped instance
 * still answers. Keys as found 2026-11-12 on LDPlayer 14, BlueStacks 5 (nxt and MSI), MuMu 12 (6.8) and MEmu 9; LDPlayer
 * writes {@code cpuCount}, {@code fps} and {@code rootMode} only once changed, so a new instance's file leaves them unread.
 * Locale, time zone and the quiet-run settings are Android's on every product, set over ADB.
 */
public enum ProductSettings {
    LDPLAYER(PlatformId.LDPLAYER, EnumSet.of(SCREEN, CPU_CORES, MEMORY, FPS, ROOT, ADB)),
    /** Written by {@code MuMuManager setting}, keys as its {@code --info} lists them (2026-11-12). */
    MUMU(PlatformId.MUMU, EnumSet.of(SCREEN, CPU_CORES, MEMORY, FPS, ROOT)),
    /** Written by {@code memuc setconfigex}, keys as its {@code getconfigex} reads them (2026-11-12). */
    MEMU(PlatformId.MEMU, EnumSet.of(SCREEN, CPU_CORES, MEMORY, FPS, ROOT)),
    /** BlueStacks and MSI App Player: one product, one {@code bluestacks.conf} per edition. */
    BLUESTACKS(PlatformId.BLUESTACKS, EnumSet.of(SCREEN, CPU_CORES, MEMORY, FPS, ROOT, ADB)),
    GAMELOOP(PlatformId.GAMELOOP, EnumSet.noneOf(ConfigField.class)),
    /** Waydroid's screen is its {@code persist.waydroid.width/height}, the checks' existing Fix. */
    WAYDROID(PlatformId.WAYDROID, EnumSet.of(SCREEN)),
    AVD(PlatformId.AVD, EnumSet.noneOf(ConfigField.class)),
    /** A phone, or a product this build does not know: nothing is applied. */
    NONE(PlatformId.UNKNOWN, EnumSet.noneOf(ConfigField.class));

    /**
     * Set over ADB on every emulator: Android's own settings. A holder, as an enum's constructor reads no static. Play's
     * auto-update is not one: it lives in the Play app's own preferences, which no shell command writes, so it is set
     * by hand.
     */
    private static final class OverAdb {
        static final Set<ConfigField> FIELDS = EnumSet.of(LOCALE, TIME_ZONE, POPUPS_OFF);
    }

    private final PlatformId platform;
    private final Set<ConfigField> applies;

    ProductSettings(PlatformId platform, Set<ConfigField> own) {
        this.platform = platform;
        Set<ConfigField> all = EnumSet.copyOf(own);
        if (!platform.equals(PlatformId.UNKNOWN)) all.addAll(OverAdb.FIELDS);
        this.applies = Collections.unmodifiableSet(all);
    }

    public static ProductSettings of(PlatformId platform) {
        for (ProductSettings product : values()) {
            if (product.platform == platform) return product;
        }
        return NONE;
    }

    public PlatformId platform() {
        return platform;
    }

    /** The fields Apply can make so on this product. */
    public Set<ConfigField> applies() {
        return applies;
    }

    public boolean applies(ConfigField field) {
        return applies.contains(field);
    }

    /** Whether {@code field} is Android's own, set over ADB on every product. */
    static boolean overAdb(ConfigField field) {
        return OverAdb.FIELDS.contains(field);
    }

    /**
     * Whether BotMaker sets the field for each run rather than once: Do Not Disturb is turned on as a run starts and
     * back as it ends ({@code QuietRun}), so what Android has between runs says nothing against it.
     */
    public boolean setEachRun(ConfigField field) {
        return field == DO_NOT_DISTURB && this != NONE;
    }

    /**
     * Whether the product keeps its screen turned against Android's: MuMu's 1600×900 is Android's {@code wm size}
     * 900×1600. A configured screen is Android's (the user's call, 2026-11-12), so this product's reads and writes swap
     * width and height.
     */
    public boolean turnsScreen() {
        return this == MUMU;
    }

    /** ADB is always on in these: no setting turns it off. */
    private boolean adbAlwaysOn() {
        return this == MUMU || this == MEMU || this == GAMELOOP || this == WAYDROID || this == AVD;
    }

    /** What the product's own file or console says now; empty for what it does not say or could not be read. */
    public Map<ConfigField, ConfigValue> read(EmulatorInstance instance) {
        Map<ConfigField, ConfigValue> out = switch (this) {
            case LDPLAYER, BLUESTACKS, MEMU -> WindowsSettings.fileOf(instance).flatMap(ProductSettings::text)
                    .map(text -> parse(instance, text)).orElseGet(Map::of);
            case MUMU -> muMuConsole(instance).map(ProductSettings::muMu).orElseGet(Map::of);
            default -> Map.of();
        };
        if (!adbAlwaysOn()) return out;
        Map<ConfigField, ConfigValue> withAdb = new EnumMap<>(ConfigField.class);
        withAdb.putAll(out);
        withAdb.put(ADB, new ConfigValue.Toggle(true));
        return Collections.unmodifiableMap(withAdb);
    }

    /** The product's file text, parsed: LDPlayer's, BlueStacks' (for the instance's token) or MEmu's. */
    Map<ConfigField, ConfigValue> parse(EmulatorInstance instance, String text) {
        WindowsSettings file = WindowsSettings.parse(instance, null, text);
        Map<ConfigField, ConfigValue> out = new EnumMap<>(ConfigField.class);
        if (file.hasSize() && file.dpi() > 0) out.put(SCREEN, new ConfigValue.Screen(file.width(), file.height(), file.dpi()));
        if (file.ramMb() > 0) out.put(MEMORY, new ConfigValue.Megabytes(file.ramMb()));
        if (file.adbOn() != null) out.put(ADB, new ConfigValue.Toggle(file.adbOn()));
        switch (this) {
            case LDPLAYER -> {
                count(out, CPU_CORES, number(LD_CPU, text));
                count(out, FPS, number(LD_FPS, text));
                toggle(out, ROOT, word(LD_ROOT, text));
            }
            case BLUESTACKS -> {
                String token = WindowsSettings.token(instance);
                if (token == null) break;   // no --instance: its lines cannot be told apart, so they stay unread
                String prefix = "bst.instance." + token + ".";
                count(out, CPU_CORES, number(confLine(prefix + "cpus"), text));
                count(out, FPS, number(confLine(prefix + "max_fps"), text));
                toggle(out, ROOT, word(confLine(prefix + "enable_root_access"), text));
            }
            case MEMU -> {
                count(out, CPU_CORES, number(MEMU_CPU, text));
                count(out, FPS, number(guestProperty("fps"), text));
                toggle(out, ROOT, word(guestProperty("enable_su"), text));
            }
            default -> {
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static final Pattern LD_CPU = Pattern.compile("\"advancedSettings\\.cpuCount\"\\s*:\\s*(\\d+)");
    private static final Pattern LD_FPS = Pattern.compile("\"basicSettings\\.fps\"\\s*:\\s*(\\d+)");
    private static final Pattern LD_ROOT = Pattern.compile("\"basicSettings\\.rootMode\"\\s*:\\s*\"?(\\w+)");
    private static final Pattern MEMU_CPU = Pattern.compile("<CPU\\s+count=\"(\\d+)\"");

    private static Pattern confLine(String key) {
        return Pattern.compile("(?m)^" + Pattern.quote(key) + "=\"?(\\w+)\"?\\s*$");
    }

    private static Pattern guestProperty(String name) {
        return Pattern.compile("<GuestProperty\\s+name=\"" + Pattern.quote(name) + "\"\\s+value=\"(\\w+)\"");
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * MuMu's {@code setting -v <i> --all}: one flat object of strings, the screen in floats ({@code "1600.000000"}),
     * {@code vm_mem} in GB.
     */
    static Map<ConfigField, ConfigValue> muMu(String output) {
        Map<ConfigField, ConfigValue> out = new EnumMap<>(ConfigField.class);
        JsonNode all;
        try {
            all = JSON.readTree(output.substring(Math.max(0, output.indexOf('{'))));
        } catch (Exception e) {
            return Map.of();
        }
        if (all == null || !all.isObject()) return Map.of();
        int width = whole(all.path("resolution_width").asText(""));
        int height = whole(all.path("resolution_height").asText(""));
        int dpi = whole(all.path("resolution_dpi").asText(""));
        if (width > 0 && height > 0 && dpi > 0) out.put(SCREEN, new ConfigValue.Screen(height, width, dpi)); // turned
        count(out, CPU_CORES, whole(all.path("vm_cpu").asText("")));
        int gb = whole(all.path("vm_mem").asText(""));
        if (gb > 0) out.put(MEMORY, new ConfigValue.Megabytes(gb * 1024));
        count(out, FPS, whole(all.path("max_frame_rate").asText("")));
        toggle(out, ROOT, all.path("root_permission").asText(null));
        return Collections.unmodifiableMap(out);
    }

    /** {@code MuMuManager.exe setting -v <i> --all}, from the console and index the instance's launch command names. */
    private static Optional<String> muMuConsole(EmulatorInstance instance) {
        List<String> launch = instance.launchCommand();
        int at = launch.indexOf("-v");
        if (launch.isEmpty() || at < 0 || at + 1 >= launch.size()) return Optional.empty();
        return Optional.ofNullable(WaydroidCli.run(launch.getFirst(), "setting", "-v", launch.get(at + 1), "--all"));
    }

    private static Optional<String> text(Path file) {
        try {
            return Optional.of(Files.readString(file));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static void count(Map<ConfigField, ConfigValue> out, ConfigField field, int value) {
        if (value > 0) out.put(field, field.kind() == ConfigField.Kind.MEGABYTES
                ? new ConfigValue.Megabytes(value) : new ConfigValue.Count(value));
    }

    /** {@code 1}/{@code true} on, {@code 0}/{@code false} off; anything else unread. */
    private static void toggle(Map<ConfigField, ConfigValue> out, ConfigField field, String word) {
        if (word == null) return;
        switch (word.strip().toLowerCase(Locale.ROOT)) {
            case "1", "true" -> out.put(field, new ConfigValue.Toggle(true));
            case "0", "false" -> out.put(field, new ConfigValue.Toggle(false));
            default -> {
            }
        }
    }

    private static int number(Pattern pattern, String text) {
        return whole(word(pattern, text));
    }

    private static String word(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** {@code "1600"} or {@code "1600.000000"} as 1600; -1 for anything else. */
    private static int whole(String text) {
        if (text == null || text.isBlank()) return -1;
        try {
            double value = Double.parseDouble(text.strip());
            return value == Math.rint(value) && value > 0 && value < Integer.MAX_VALUE ? (int) value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
