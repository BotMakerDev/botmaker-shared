package com.botmaker.shared.emulator.checks;

import com.botmaker.shared.emulator.BlueStacksPlatform;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.PlatformId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What a Windows emulator's own file says about one instance: whether its ADB is on, its memory and its screen.
 * Each product keeps them in a file of its own (LDPlayer's {@code leidian<i>.config}, BlueStacks'
 * {@code bluestacks.conf}, MuMu's {@code vm_config.json}, MEmu's {@code <vm>.memu}), found from the instance's
 * launch command, and each parser is pure over that file's text. Formats as measured 2026-11-11 on LDPlayer 14,
 * BlueStacks 5.22, MuMu 12 (Android 15) and MEmu 9.
 *
 * @param file   the file read, or {@code null} when the product keeps none BotMaker knows (GameLoop)
 * @param adbOn  the file's ADB switch, or {@code null} when the product has none (ADB always on)
 * @param ramMb  the memory the instance asks for, or -1 when the file does not say
 * @param width  the screen's width, or -1 when the file does not say
 * @param height the screen's height, or -1
 * @param dpi    the screen's density, or -1
 */
public record WindowsSettings(Path file, Boolean adbOn, int ramMb, int width, int height, int dpi) {

    static final WindowsSettings NONE = new WindowsSettings(null, null, -1, -1, -1, -1);

    /** LDPlayer's default when its file names no size: a new instance is 1600×900 at 240 dpi. */
    static final int LDPLAYER_DPI = 240;

    public boolean hasSize() {
        return width > 0 && height > 0;
    }

    /** The file of {@code instance}'s product, read; {@link #NONE} when there is none or it can't be read. */
    public static WindowsSettings of(EmulatorInstance instance) {
        Optional<Path> file = fileOf(instance);
        if (file.isEmpty()) return NONE;
        String text = WaydroidChecks.read(file.get());
        if (text == null) return NONE;
        return parse(instance, file.get(), text);
    }

    /** {@code text}, the file of {@code instance}'s product, parsed; {@link #NONE} for a product with no such file. */
    public static WindowsSettings parse(EmulatorInstance instance, Path file, String text) {
        return switch (instance.platformId()) {
            case LDPLAYER -> ldPlayer(file, text);
            case BLUESTACKS -> blueStacks(file, text, token(instance));
            case MUMU -> muMu(file, text);
            case MEMU -> memu(file, text);
            default -> NONE;
        };
    }

    /** The file that holds {@code instance}'s settings, found from its launch command. */
    public static Optional<Path> fileOf(EmulatorInstance instance) {
        List<String> launch = instance.launchCommand();
        if (launch.isEmpty()) return Optional.empty();
        Path program = Path.of(launch.getFirst());
        Path install = program.getParent();
        if (install == null) return Optional.empty();
        return switch (instance.platformId()) {
            case LDPLAYER -> argAfter(launch, "--index")
                    .map(index -> install.resolve("vms").resolve("config").resolve("leidian" + index + ".config"));
            case BLUESTACKS -> BlueStacksPlatform.confFor(program);
            case MUMU -> argAfter(launch, "-v").flatMap(index -> muMuConfig(install.getParent(), index));
            case MEMU -> argAfter(launch, "-n")
                    .map(vm -> install.resolve("MemuHyperv VMs").resolve(vm).resolve(vm + ".memu"));
            default -> Optional.empty();
        };
    }

    /** BlueStacks' instance token ({@code Pie64}), its launch command's {@code --instance}. */
    public static String token(EmulatorInstance instance) {
        return argAfter(instance.launchCommand(), "--instance").orElse(null);
    }

    /** MuMu keeps instance {@code i} under {@code vms\MuMuPlayer(Global)-<android>-<i>\configs}. */
    private static Optional<Path> muMuConfig(Path install, String index) {
        if (install == null) return Optional.empty();
        try (Stream<Path> dirs = Files.list(install.resolve("vms"))) {
            return dirs.filter(dir -> dir.getFileName().toString().endsWith("-" + index))
                    .flatMap(dir -> Stream.of("configs", "config").map(f -> dir.resolve(f).resolve("vm_config.json")))
                    .filter(Files::isReadable).findFirst();
        } catch (IOException | SecurityException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> argAfter(List<String> command, String flag) {
        int at = command.indexOf(flag);
        return at < 0 || at + 1 >= command.size() ? Optional.empty() : Optional.of(command.get(at + 1));
    }

    // --- the parsers: pure over the file's text ---

    private static final Pattern LD_ADB = Pattern.compile("\"basicSettings\\.adbDebug\"\\s*:\\s*(\\d+)");
    private static final Pattern LD_SIZE = Pattern.compile(
            "\"advancedSettings\\.resolution\"\\s*:\\s*\\{\\s*\"width\"\\s*:\\s*(\\d+)\\s*,\\s*\"height\"\\s*:\\s*(\\d+)");
    private static final Pattern LD_DPI = Pattern.compile("\"advancedSettings\\.resolutionDpi\"\\s*:\\s*(\\d+)");
    private static final Pattern LD_RAM = Pattern.compile("\"advancedSettings\\.memorySize\"\\s*:\\s*(\\d+)");

    /** LDPlayer writes {@code adbDebug} only once ADB was turned on: a new instance's file has none, and ADB is off. */
    static WindowsSettings ldPlayer(Path file, String json) {
        Matcher adb = LD_ADB.matcher(json);
        Matcher size = LD_SIZE.matcher(json);
        boolean sized = size.find();
        return new WindowsSettings(file, adb.find() && !adb.group(1).equals("0"), number(LD_RAM, json),
                sized ? Integer.parseInt(size.group(1)) : -1, sized ? Integer.parseInt(size.group(2)) : -1,
                number(LD_DPI, json));
    }

    /** ADB is one switch for every instance of the edition ({@code bst.enable_adb_access}); the rest is per token. */
    static WindowsSettings blueStacks(Path file, String conf, String token) {
        String adb = AvdChecks.value(conf, "bst.enable_adb_access");
        String prefix = "bst.instance." + token + ".";
        return new WindowsSettings(file, adb == null ? Boolean.FALSE : unquote(adb).equals("1"),
                token == null ? -1 : AvdChecks.megabytes(unquoted(conf, prefix + "ram")),
                token == null ? -1 : integer(unquoted(conf, prefix + "fb_width")),
                token == null ? -1 : integer(unquoted(conf, prefix + "fb_height")),
                token == null ? -1 : integer(unquoted(conf, prefix + "dpi")));
    }

    private static int integer(String value) {
        try {
            return value == null ? -1 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static final Pattern MUMU_MEMORY = Pattern.compile("\"memory\"\\s*:\\s*\"?(\\d+)\"?");

    /** MuMu's {@code vm.memory} is in GB; its screen is not in this file, and its ADB is always on. */
    static WindowsSettings muMu(Path file, String json) {
        int gb = number(MUMU_MEMORY, json);
        return new WindowsSettings(file, null, gb < 0 ? -1 : gb * 1024, -1, -1, -1);
    }

    private static final Pattern MEMU_RAM = Pattern.compile("<Memory\\s+RAMSize=\"(\\d+)\"");

    /** MEmu's {@code .memu} is VirtualBox XML: memory on {@code <Memory>}, the screen in guest properties. */
    static WindowsSettings memu(Path file, String xml) {
        return new WindowsSettings(file, null, number(MEMU_RAM, xml), guestProperty(xml, "resolution_width"),
                guestProperty(xml, "resolution_height"), guestProperty(xml, "vbox_dpi"));
    }

    private static int guestProperty(String xml, String name) {
        return number(Pattern.compile("<GuestProperty\\s+name=\"" + Pattern.quote(name) + "\"\\s+value=\"(\\d+)\""), xml);
    }

    private static int number(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        try {
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String unquoted(String conf, String key) {
        String value = AvdChecks.value(conf, key);
        return value == null ? null : unquote(value);
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    // --- the writers: pure, each changing one setting and leaving every other line as it was ---

    /** LDPlayer's file with ADB on: the key replaced, or added as the object's first. */
    static String ldPlayerWithAdb(String json) {
        return ldPlayerWithAdb(json, true);
    }

    /**
     * LDPlayer's file with {@code "key": raw} ({@code raw} is JSON as written: {@code 60}, {@code true}): the value
     * replaced, or the key added as the object's first.
     */
    public static String ldPlayerWith(String json, String key, String raw) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\"[^\"]*\"|[^,}\\s]+)").matcher(json);
        if (m.find()) return json.substring(0, m.start(1)) + raw + json.substring(m.end(1));
        int brace = json.indexOf('{');
        if (brace < 0) return json;
        String rest = json.substring(brace + 1);
        boolean empty = rest.isBlank() || rest.trim().startsWith("}");
        return json.substring(0, brace + 1) + "\n    \"" + key + "\": " + raw + (empty ? "" : ",") + rest;
    }

    /** LDPlayer's file with ADB {@code on} or off; a missing key is off already, so off adds none. */
    public static String ldPlayerWithAdb(String json, boolean on) {
        Matcher adb = LD_ADB.matcher(json);
        if (adb.find()) return json.substring(0, adb.start(1)) + (on ? "1" : "0") + json.substring(adb.end(1));
        return on ? ldPlayerWith(json, "basicSettings.adbDebug", "1") : json;
    }

    /** BlueStacks' conf with {@code key="value"}: the line replaced, or added at the end. */
    public static String blueStacksWith(String conf, String key, String value) {
        Pattern line = Pattern.compile("(?m)^" + Pattern.quote(key) + "=.*$");
        String wanted = key + "=\"" + value + "\"";
        Matcher m = line.matcher(conf);
        if (m.find()) return conf.substring(0, m.start()) + wanted + conf.substring(m.end());
        String newline = conf.contains("\r\n") ? "\r\n" : "\n";
        return conf + (conf.isEmpty() || conf.endsWith("\n") ? "" : newline) + wanted + newline;
    }

    /** Whether {@code platform} keeps its ADB switch in a file BotMaker writes. */
    static boolean adbInFile(PlatformId platform) {
        return platform == PlatformId.LDPLAYER || platform == PlatformId.BLUESTACKS;
    }
}
