// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator;

import com.botmaker.shared.Diag;
import com.botmaker.shared.emulator.config.InstanceKey;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Who answers on an instance's address (spec {@code 2026-11-14-end-to-end-instances-design.md} § Who answers). A port
 * says where to knock, not who opens: two products may take one address (BlueStacks and LDPlayer both 5555), and two
 * instances of one product may too. So each instance carries an ID file, {@link #FILE}, holding its BotMaker ID, its
 * product and its name, and this PC keeps the ID it gave ({@link InstanceIds}).
 *
 * <p>Checked in order: the ID file against the kept ID (a match passes with nothing more read); else the product by its
 * own apps ({@link EmulatorOwnApps#notThis}); then the file. An instance seen for the first time is given an ID. The
 * file holds a random token, never a device ID.
 */
public final class EmulatorIdentity {

    /** The ID file on the instance: under {@code /data/local/tmp}, which survives a reboot and no app reads. */
    public static final String FILE = "/data/local/tmp/botmaker/instance";

    private EmulatorIdentity() {}

    /**
     * This process's keeper of IDs: the SDK's values of this PC once a window or a bot is opened in Studio, else
     * {@link InstanceIds#NONE}. A process-wide one because the checks run below anything that holds the SDK's
     * services (an apply, a sign-in, a launch).
     */
    private static final AtomicReference<InstanceIds> IDS = new AtomicReference<>(InstanceIds.NONE);

    /** Makes {@code ids} this process's keeper; null puts back {@link InstanceIds#NONE}. */
    public static void use(InstanceIds ids) {
        IDS.set(ids == null ? InstanceIds.NONE : ids);
    }

    /** The device at an address, as the check reads it. */
    interface Answering {
        List<String> packages();

        /** The ID file's text, empty when there is none. */
        String read();

        void write(String text);

        /**
         * Whether the instance's own product says it is the one running ({@link EmulatorLiveness#running}): asked
         * before an ID is given or taken, so a first sight never names another instance holding the address.
         */
        boolean runs();
    }

    /** What an ID file says. */
    record IdFile(String id, PlatformId product, String name) {

        String text() {
            return "id=" + id + "\nproduct=" + product.id() + "\nname=" + name + "\n";
        }

        /** The file {@link #text()} wrote, else empty (no file, or one this build cannot read). */
        static Optional<IdFile> parse(String text) {
            if (text == null || text.isBlank()) return Optional.empty();
            String id = null;
            String product = null;
            String name = null;
            for (String line : text.strip().split("\\R")) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String value = line.substring(eq + 1).strip();
                switch (line.substring(0, eq).strip()) {
                    case "id" -> id = value;
                    case "product" -> product = value;
                    case "name" -> name = value;
                    default -> { }
                }
            }
            if (id == null || id.isEmpty() || product == null || name == null) return Optional.empty();
            return Optional.of(new IdFile(id, PlatformId.fromId(product), name));
        }

        String who() {
            return whose(product, name);
        }
    }

    /** {@code BlueStacks' Pie64}, {@code LDPlayer's LDPlayer-1}. */
    static String whose(PlatformId product, String name) {
        String owner = product.displayName();
        return owner + (owner.endsWith("s") ? "' " : "'s ") + name;
    }

    /**
     * Connects to {@code instance} and checks who answers.
     *
     * @throws IllegalStateException with the user's words when another instance answers; the connection is closed
     */
    static AdbDevice connect(EmulatorInstance instance) {
        AdbDevice device = AdbDevice.connect(instance.adb());
        try {
            Optional<String> refused = check(instance, of(device, instance), IDS.get());
            if (refused.isPresent()) throw new IllegalStateException(refused.get());
            return device;
        } catch (RuntimeException | Error e) {
            device.close();
            throw e;
        }
    }

    private static Answering of(AdbDevice device, EmulatorInstance instance) {
        return new Answering() {
            @Override
            public boolean runs() {
                return EmulatorLiveness.running(instance);
            }

            @Override
            public List<String> packages() {
                return device.allPackages();
            }

            @Override
            public String read() {
                return device.shell("cat " + FILE + " 2>/dev/null");
            }

            @Override
            public void write(String text) {
                String dir = FILE.substring(0, FILE.lastIndexOf('/'));
                device.shell("mkdir -p " + dir + " && printf '%s' " + quoted(text) + " > " + FILE);
                ANSWERS.remove(instance.endpoint());
            }
        };
    }

    /** {@code text} in single quotes for Android's shell. */
    static String quoted(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    /**
     * Why the device answering is not {@code instance}, in the user's words, else empty. An instance whose product
     * BotMaker cannot tell by its apps (a phone, Waydroid, Android Studio's emulator) is not checked: its address is its
     * own.
     */
    static Optional<String> check(EmulatorInstance instance, Answering device, InstanceIds ids) {
        if (!EmulatorOwnApps.tells(instance.platformId())) return Optional.empty();
        InstanceKey key = InstanceKey.of(instance);
        // two first connects at once would each give an ID: one check per instance at a time in this process
        synchronized (LOCKS.computeIfAbsent(key.text(), k -> new Object())) {
            return checked(instance, key, device, ids);
        }
    }

    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    private static Optional<String> checked(EmulatorInstance instance, InstanceKey key, Answering device,
                                            InstanceIds ids) {
        Optional<IdFile> file = IdFile.parse(device.read());
        Optional<String> expected = ids.expected(key);
        Optional<InstanceKey> owner = file.flatMap(f -> ids.owner(f.id()));
        boolean named = file.filter(f -> f.product() == instance.platformId()
                && f.name().equals(instance.reference())).isPresent();
        // the file a check wrote on this instance: its product was told then, so nothing more is read
        if (file.isPresent() && expected.filter(file.get().id()::equals).isPresent()) {
            if (!named) renamed(device, file.get(), instance); // renamed since: a run checks the name
            return Optional.empty();
        }
        if (expected.isEmpty() && named && owner.filter(o -> !o.equals(key)).isEmpty()) {
            keep(ids, key, file.get().id());
            return Optional.empty();
        }
        Optional<String> product = EmulatorOwnApps.notThis(instance, device.packages());
        if (product.isPresent()) return product;
        String here = whose(instance.platformId(), instance.reference());
        if (expected.isPresent() || file.isPresent()) {
            if (file.isEmpty()) {
                return Optional.of(instance.adb().label() + " answers with no BotMaker ID, not as " + here
                        + ": the instance was reset, or another one holds its address. Stop the others, then try"
                        + " again.");
            }
            IdFile found = file.get();
            // a file with an ID nobody here gave, under another name, is taken only from the instance its product
            // says runs; anything else is another instance's
            boolean ours = expected.isEmpty() && owner.isEmpty() && found.product() == instance.platformId()
                    && ids.keeps() && device.runs();
            if (!ours) {
                return Optional.of(instance.adb().label() + " answers as " + found.who() + ", not " + here + ". "
                        + stopOrMove(instance));
            }
            renamed(device, found, instance);
            keep(ids, key, found.id());
            return Optional.empty();
        }
        // first sight: an ID only for the instance its product says runs, and only where IDs are kept
        if (!ids.keeps() || !device.runs()) return Optional.empty();
        IdFile given = new IdFile(UUID.randomUUID().toString(), instance.platformId(), instance.reference());
        device.write(given.text());
        if (IdFile.parse(device.read()).filter(given::equals).isPresent()) keep(ids, key, given.id());
        else Diag.log("[Emulator] " + instance.name() + "'s BotMaker ID file could not be written");
        return Optional.empty();
    }

    private static void renamed(Answering device, IdFile file, EmulatorInstance instance) {
        device.write(new IdFile(file.id(), file.product(), instance.reference()).text());
    }

    /** Keeps {@code id} as {@code key}'s; one that is not kept is given again next time, never a refusal. */
    private static void keep(InstanceIds ids, InstanceKey key, String id) {
        if (!ids.keeps()) return;
        try {
            ids.remember(key, id).ifPresent(why -> Diag.log("[Emulator] an instance's BotMaker ID was not kept: "
                    + why));
        } catch (RuntimeException e) {
            Diag.log("[Emulator] an instance's BotMaker ID was not kept: " + e.getMessage());
        }
    }

    /**
     * Gives the running {@code instance} a new BotMaker ID, over the one it had or none: an instance that was reset,
     * or a copy carrying its original's file. Its product is checked first.
     *
     * @return empty when given, else why not
     */
    public static Optional<String> mark(EmulatorInstance instance) {
        InstanceIds ids = IDS.get();
        if (!ids.keeps()) return Optional.of("This process keeps no values of this PC.");
        Optional<String> shared = sharing(instance, Platforms.recent(Duration.ofSeconds(2)));
        if (shared.isPresent()) return shared;
        InstanceKey key = InstanceKey.of(instance);
        try (AdbDevice raw = AdbDevice.connect(instance.adb())) {
            Answering device = of(raw, instance);
            synchronized (LOCKS.computeIfAbsent(key.text(), k -> new Object())) {
                Optional<String> product = EmulatorOwnApps.notThis(instance, device.packages());
                if (product.isPresent()) return product;
                if (!device.runs()) return Optional.of(instance.name() + " is not the instance running there.");
                IdFile given = new IdFile(UUID.randomUUID().toString(), instance.platformId(), instance.reference());
                device.write(given.text());
                if (IdFile.parse(device.read()).filter(given::equals).isEmpty()) {
                    return Optional.of(instance.name() + "'s ID file could not be written.");
                }
                return ids.remember(key, given.id());
            }
        }
    }

    /**
     * Why {@code instance} can't be marked while another instance of its product says it is up on the same address:
     * its apps can't tell the two apart, so the mark could land on the other. Else empty.
     */
    static Optional<String> sharing(EmulatorInstance instance, List<EmulatorInstance> now) {
        return now.stream()
                .filter(other -> other.platformId() == instance.platformId()
                        && !other.reference().equals(instance.reference())
                        && other.endpoint().equals(instance.endpoint())
                        && (other.state() == EmulatorState.RUNNING || other.state() == EmulatorState.STARTING))
                .findFirst()
                .map(other -> other.name() + " is up on the same address as " + instance.name()
                        + ": stop it first, so the mark lands on " + instance.name() + ".");
    }

    /** How long one read of an address's ID file answers {@link #answersAs}: a picker asks over and over. */
    private static final long ANSWER_MILLIS = 3_000;
    private record Answer(long at, String text) {}
    private static final Map<String, Answer> ANSWERS = new ConcurrentHashMap<>();

    /**
     * Who answers on {@code instance}'s address when its ID file names another instance ("BlueStacks' Pie64_1"), else
     * empty: the file names this one, there is none, or nothing could be read. Reads the file only, never the apps, and
     * keeps the answer a few seconds; for {@link EmulatorLiveness}, so a product that says nothing (MSI) no longer
     * passes for the one holding its address.
     */
    static Optional<String> answersAs(EmulatorInstance instance) {
        if (!EmulatorOwnApps.tells(instance.platformId())) return Optional.empty();
        String address = instance.endpoint();
        long now = System.currentTimeMillis();
        Answer kept = ANSWERS.get(address);
        if (kept == null || now - kept.at() > ANSWER_MILLIS) {
            String text = "";
            try (AdbDevice raw = AdbDevice.connect(instance.adb())) {
                String read = raw.shell("cat " + FILE + " 2>/dev/null");
                text = read == null ? "" : read;
            } catch (RuntimeException unread) {
                // nothing said: the product's own word stands
            }
            kept = new Answer(now, text);
            ANSWERS.put(address, kept);
        }
        return another(instance, kept.text(), IDS.get());
    }

    /**
     * The instance {@code fileText} names when it is not {@code instance}, else empty. A file holding the ID this PC gave
     * {@code instance} is its own under an older name: a rename, which its next checked connect writes. Where no IDs are
     * kept, only a file naming another product is another instance.
     */
    static Optional<String> another(EmulatorInstance instance, String fileText, InstanceIds ids) {
        Optional<IdFile> file = IdFile.parse(fileText);
        if (file.isEmpty()) return Optional.empty();
        try {
            if (ids.expected(InstanceKey.of(instance)).filter(file.get().id()::equals).isPresent()) {
                return Optional.empty();
            }
        } catch (RuntimeException unkeyed) {
            // an instance with no name has no kept ID: its file's names decide
        }
        // with no IDs kept, a rename and another instance of the product look alike: only another product is told
        return file.filter(f -> f.product() != instance.platformId()
                        || (ids.keeps() && !f.name().equals(instance.reference())))
                .map(IdFile::who);
    }

    private static String stopOrMove(EmulatorInstance instance) {
        return "Another instance holds the address " + instance.name() + " uses. Stop it, or give " + instance.name()
                + " another ADB port, then try again.";
    }
}
