package com.botmaker.shared.emulator;

import com.botmaker.shared.platform.Os;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;
import java.util.stream.Stream;

/**
 * Discovers <b>Android Studio's emulators</b>, the virtual devices (AVDs) in {@link AndroidSdk#avdHome()}, each one an
 * instance whether it runs or not, started by the SDK's {@code emulator} and stopped through the adb server.
 *
 * <p><b>Each AVD is named by its id</b>, the {@code <name>.ini} file name {@code emulator -avd} takes, so a bot's
 * reference stays put whatever port it comes up on. <b>Its address is the adb server's serial</b>,
 * {@code emulator-<console port>}: the server already holds the emulator's key and its connection.
 *
 * <p><b>Where each one runs</b> is read without starting a process, as a picker scans every couple of seconds:
 * <ul>
 *   <li>the emulator processes' own arguments ({@code -avd X -port P}),</li>
 *   <li>else the name an {@code emulator-*} device says it is ({@code ro.boot.qemu.avd_name}), read once per serial
 *       while the serial stays listed.</li>
 * </ul>
 * A stopped AVD is given a planned port, the lowest free even one from {@value #FIRST_PORT}, and its start command
 * passes it, so the row is the same instance before and after it starts (the picker polls the instance it started).
 *
 * <p>Linux, macOS and Windows alike; empty where no SDK holds an emulator.
 */
public final class AvdPlatform implements EmulatorPlatform {

    public static final PlatformId PLATFORM_ID = PlatformId.AVD;

    /** The emulator's console ports: even, its adbd on the next one; the adb server looks up to 5585. */
    static final int FIRST_PORT = 5554;
    static final int LAST_PORT = 5584;

    private static final String SERIAL_PREFIX = "emulator-";
    /** Where an emulator image says which AVD it is: API 30 and later, then older images. */
    private static final List<String> NAME_PROPS = List.of("ro.boot.qemu.avd_name", "ro.kernel.qemu.avd_name");
    private static final Path KVM = Path.of("/dev/kvm");
    private static final int PROBE_MS = 100;

    /**
     * The AVD each {@code emulator-*} connection said it runs, by serial and transport id ({@link #namesOf}), "" for
     * one that names none; dropped once unlisted. The id tells a new emulator on a serial from the one before it.
     */
    private static final Map<String, String> NAMES = new ConcurrentHashMap<>();

    @Override
    public PlatformId id() {
        return PLATFORM_ID;
    }

    @Override
    public boolean isInstalled() {
        return AndroidSdk.emulator().isPresent();
    }

    @Override
    public List<EmulatorInstance> discover() {
        Optional<Path> emulator = AndroidSdk.emulator();
        if (emulator.isEmpty()) return List.of();
        List<String> avds = avds(AndroidSdk.avdHome());
        if (avds.isEmpty()) return List.of();
        List<AdbTools.ServerDevice> devices = AdbTools.devices();
        Map<String, Integer> byArgs = runningByArgs(emulatorCommandLines());
        Path adb = AdbTools.binary().map(File::toPath).orElse(null);
        return instances(avds, byArgs, devices, namesOf(devices, byArgs), AvdPlatform::free, emulator.get(), adb);
    }

    @Override
    public String statusNote() {
        return "no virtual device yet: create one in Android Studio › Device Manager";
    }

    /**
     * Whether this machine's emulator will refuse to boot for want of KVM: on Linux, {@code /dev/kvm} missing or
     * not readable and writable by this user. False on other OSes, which accelerate otherwise.
     */
    public static boolean kvmMissing() {
        return Os.current().isLinux() && !(Files.isReadable(KVM) && Files.isWritable(KVM));
    }

    /** The AVD ids in {@code avdHome}: one per {@code *.ini} file, by its name, sorted. Empty when it is missing. */
    static List<String> avds(Path avdHome) {
        if (avdHome == null || !Files.isDirectory(avdHome)) return List.of();
        try (Stream<Path> entries = Files.list(avdHome)) {
            return entries.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".ini") && n.length() > ".ini".length())
                    .map(n -> n.substring(0, n.length() - ".ini".length())).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * The AVDs running emulator processes name, each with the console port its {@code -port} (or {@code -ports})
     * gave, {@code -1} for none. The emulator's launcher and its qemu child say the same; a port wins over none.
     */
    static Map<String, Integer> runningByArgs(List<List<String>> commandLines) {
        Map<String, Integer> running = new HashMap<>();
        for (List<String> line : commandLines) {
            String avd = null;
            int port = -1;
            for (int i = 0; i < line.size(); i++) {
                String token = line.get(i);
                String next = i + 1 < line.size() ? line.get(i + 1) : null;
                if (token.equals("-avd") && next != null) avd = next;
                else if (token.startsWith("@") && token.length() > 1 && avd == null) avd = token.substring(1);
                else if ((token.equals("-port") || token.equals("-ports")) && next != null) port = port(next);
            }
            if (avd != null) running.merge(avd, port, Math::max);
        }
        return running;
    }

    /**
     * {@code count} console ports for stopped AVDs, the lowest even ones from {@value #FIRST_PORT} to
     * {@value #LAST_PORT} that are not {@code taken} and where both it and its adbd's port are {@code free}.
     * Fewer when the range runs out.
     */
    static List<Integer> plannedPorts(int count, Set<Integer> taken, IntPredicate free) {
        List<Integer> ports = new ArrayList<>();
        for (int port = FIRST_PORT; port <= LAST_PORT && ports.size() < count; port += 2) {
            if (!taken.contains(port) && free.test(port) && free.test(port + 1)) ports.add(port);
        }
        return ports;
    }

    /**
     * Every AVD as an instance, in {@code avds}' order: a running one where it runs, a stopped one at a planned
     * port. A process names it ({@code byArgs}), else a serial does ({@code nameBySerial}). A process with no port
     * (Android Studio passes none) takes a serial that has named no AVD yet, as one still booting cannot say.
     *
     * <ul>
     *   <li>RUNNING: its serial is online.</li>
     *   <li>STARTING: its process runs, or its serial is listed but not online yet.</li>
     *   <li>STOPPED: neither.</li>
     * </ul>
     */
    static List<EmulatorInstance> instances(List<String> avds, Map<String, Integer> byArgs,
                                            List<AdbTools.ServerDevice> devices, Map<String, String> nameBySerial,
                                            IntPredicate free, Path emulator, Path adb) {
        Map<Integer, AdbTools.ServerDevice> bySerialPort = new TreeMap<>();
        for (AdbTools.ServerDevice device : devices) {
            int port = serialPort(device.serial());
            if (port > 0) bySerialPort.put(port, device);
        }
        Map<String, Integer> known = new HashMap<>();
        byArgs.forEach((avd, port) -> {
            if (port > 0) known.put(avd, port);
        });
        nameBySerial.forEach((serial, avd) -> {
            if (!avd.isEmpty()) known.putIfAbsent(avd, serialPort(serial));
        });
        List<Integer> unnamed = new ArrayList<>();
        bySerialPort.forEach((port, device) -> {
            if (!known.containsValue(port) && !nameBySerial.containsKey(device.serial())) unnamed.add(port);
        });
        for (String avd : avds) {
            if (byArgs.containsKey(avd) && !known.containsKey(avd) && !unnamed.isEmpty()) {
                known.put(avd, unnamed.removeFirst());
            }
        }
        Set<Integer> taken = new HashSet<>(bySerialPort.keySet());
        taken.addAll(known.values());
        long unplaced = avds.stream().filter(avd -> !known.containsKey(avd)).count();
        List<Integer> planned = plannedPorts((int) unplaced, taken, free);

        List<EmulatorInstance> instances = new ArrayList<>();
        int next = 0;
        for (String avd : avds) {
            Integer port = known.get(avd);
            if (port == null) {
                // The range ran out: no port to start it on, so it is not offered.
                if (next >= planned.size()) continue;
                port = planned.get(next++);
            }
            AdbTools.ServerDevice device = bySerialPort.get(port);
            EmulatorState state = device != null && device.online() ? EmulatorState.RUNNING
                    : device != null || byArgs.containsKey(avd) ? EmulatorState.STARTING : EmulatorState.STOPPED;
            instances.add(instance(avd, port, state, emulator, adb));
        }
        return List.copyOf(instances);
    }

    /** One AVD on {@code port}: started there by {@code emulator}, stopped by {@code adb}; no command for a null one. */
    static EmulatorInstance instance(String avd, int port, EmulatorState state, Path emulator, Path adb) {
        String serial = SERIAL_PREFIX + port;
        List<String> launch = emulator == null ? List.of()
                : List.of(emulator.toString(), "-avd", avd, "-port", Integer.toString(port));
        List<String> stop = adb == null ? List.of() : List.of(adb.toString(), "-s", serial, "emu", "kill");
        return new EmulatorInstance(PLATFORM_ID, avd, new AdbEndpoint.Server(serial), launch, stop, state);
    }

    /**
     * The AVD each online {@code emulator-*} serial says it runs, read from the device once per serial and kept
     * while the serial is listed. Only the serials whose AVD the processes did not already place are asked.
     */
    private static Map<String, String> namesOf(List<AdbTools.ServerDevice> devices, Map<String, Integer> byArgs) {
        Set<String> listed = new HashSet<>();
        Set<Integer> placed = new HashSet<>(byArgs.values());
        Map<String, String> names = new HashMap<>();
        for (AdbTools.ServerDevice device : devices) {
            int port = serialPort(device.serial());
            if (port < 0) continue;
            String key = device.serial() + "#" + device.transportId();
            listed.add(key);
            String name = NAMES.get(key);
            if (name == null && device.online() && !placed.contains(port)) {
                // Read outside the map: a device that hangs must not hold up another scan.
                name = readName(device.serial());
                if (name != null) NAMES.put(key, name);
            }
            if (name != null) names.put(device.serial(), name);
        }
        NAMES.keySet().retainAll(listed);
        return names;
    }

    /** The AVD {@code serial} says it is, "" when it names none, or null (not kept) when it could not be asked. */
    private static String readName(String serial) {
        try (AdbDevice device = AdbDevice.connect(new AdbEndpoint.Server(serial))) {
            for (String prop : NAME_PROPS) {
                String name = device.getProp(prop);
                if (!name.isBlank()) return name;
            }
            return "";
        } catch (Throwable t) {
            // dadb surfaces some failures as Errors; asked again on the next scan
            return null;
        }
    }

    /** Every running emulator process's command and arguments; empty where the OS hides them. */
    private static List<List<String>> emulatorCommandLines() {
        List<List<String>> lines = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(process -> {
            ProcessHandle.Info info = process.info();
            Optional<String> command = info.command();
            Optional<String[]> arguments = info.arguments();
            if (command.isEmpty() || arguments.isEmpty()) return;
            String file = Path.of(command.get()).getFileName().toString();
            if (!file.startsWith("qemu-system") && !file.startsWith("emulator")) return;
            List<String> line = new ArrayList<>();
            line.add(command.get());
            line.addAll(List.of(arguments.get()));
            lines.add(line);
        });
        return lines;
    }

    /**
     * Whether nothing listens on {@code port} of loopback: a connection is refused. Never a bind: a scan holding an
     * emulator's port even for a moment could fail its start, and the adb server probes these ports.
     */
    private static boolean free(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), PROBE_MS);
            return false;
        } catch (ConnectException refused) {
            return true;
        } catch (IOException e) {
            // A timeout or anything else: not known to be free.
            return false;
        }
    }

    /** {@code 5554} for {@code emulator-5554}; -1 for any other serial. */
    static int serialPort(String serial) {
        return serial.startsWith(SERIAL_PREFIX) ? port(serial.substring(SERIAL_PREFIX.length())) : -1;
    }

    /** A {@code -port} value, the first of a {@code -ports} pair; -1 when it is not a number. */
    private static int port(String value) {
        int comma = value.indexOf(',');
        try {
            return Integer.parseInt(comma < 0 ? value : value.substring(0, comma));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
