package com.botmaker.shared.vm;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * One game VM as BotMaker made it, kept as {@value #FILE} in its folder: which hypervisor runs it, how far its
 * setup got, its size, and its loopback ports. Its passwords are beside it, in {@link VmCredentials}.
 *
 * @param qmpPort   QEMU's control port; {@code 0} for VMware
 * @param agentPort QEMU's guest-agent port; {@code 0} for VMware
 * @param eventsPort QEMU's second QMP port, for {@link QmpEvents}; {@code 0} for VMware, and for a VM recorded
 *                   before it had one until its next start
 */
public record VmRecord(Path folder, String name, Hypervisor hypervisor, Stage stage, VmSize size, Path windowsIso,
                       String language, int vncPort, int qmpPort, int agentPort, int eventsPort) {

    static final String FILE = "vm.properties";
    static final String ANSWER_ISO = "answer.iso";

    /** How far setup got. */
    public enum Stage {
        /** Its disk, answer disc and configuration exist; Windows isn't installed. */
        PREPARED("prepared", "Ready to install Windows"),
        /** It has been started on its Windows disc and Setup may be running. */
        INSTALLING("installing", "Installing Windows"),
        /** Windows is installed and signed in, and the guest tools answer. */
        READY("ready", "Ready"),
        UNKNOWN("unknown", "Unknown");

        private final String id;
        private final String displayName;

        Stage(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        public static Stage fromId(String id) {
            for (Stage s : values()) {
                if (s.id.equals(id)) return s;
            }
            return UNKNOWN;
        }
    }

    public VmRecord withStage(Stage next) {
        return new VmRecord(folder, name, hypervisor, next, size, windowsIso, language, vncPort, qmpPort, agentPort,
                eventsPort);
    }

    public VmRecord withPorts(int vnc, int qmp, int agent, int events) {
        return new VmRecord(folder, name, hypervisor, stage, size, windowsIso, language, vnc, qmp, agent, events);
    }

    public Path disk() {
        return folder.resolve(hypervisor == Hypervisor.VMWARE ? "disk.vmdk" : "disk.qcow2");
    }

    public Path vmx() {
        return folder.resolve(name + ".vmx");
    }

    public Path answerIso() {
        return folder.resolve(ANSWER_ISO);
    }

    public Qemu.Ports qemuPorts() {
        return new Qemu.Ports(qmpPort, agentPort, eventsPort);
    }

    /**
     * What the hypervisor runs: while Windows installs, with its disc, the answer disc and {@code extraDiscs};
     * once it is {@link Stage#READY}, with none, so no boot waits on a disc's "press any key".
     */
    public VmSpec spec(List<Path> extraDiscs) {
        boolean installing = stage != Stage.READY;
        List<Path> discs = new ArrayList<>();
        if (installing) {
            discs.add(answerIso());
            discs.addAll(extraDiscs);
        }
        return new VmSpec(name, folder, size, installing ? windowsIso : null, discs, vncPort);
    }

    public void save() throws IOException {
        Properties p = new Properties();
        p.setProperty("name", name);
        p.setProperty("hypervisor", hypervisor.id());
        p.setProperty("stage", stage.id());
        p.setProperty("cpus", Integer.toString(size.cpus()));
        p.setProperty("memoryMb", Integer.toString(size.memoryMb()));
        p.setProperty("diskGb", Integer.toString(size.diskGb()));
        p.setProperty("windowsIso", windowsIso.toString());
        p.setProperty("language", language);
        p.setProperty("vncPort", Integer.toString(vncPort));
        p.setProperty("qmpPort", Integer.toString(qmpPort));
        p.setProperty("agentPort", Integer.toString(agentPort));
        p.setProperty("eventsPort", Integer.toString(eventsPort));
        Path tmp = folder.resolve(FILE + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            p.store(w, "A BotMaker game VM");
        }
        Files.move(tmp, folder.resolve(FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The record in {@code folder}; throws when there is none or it is damaged. */
    public static VmRecord load(Path folder) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(folder.resolve(FILE), StandardCharsets.UTF_8)) {
            p.load(r);
        }
        try {
            VmSize size = new VmSize(Integer.parseInt(p.getProperty("cpus")),
                    Integer.parseInt(p.getProperty("memoryMb")), Integer.parseInt(p.getProperty("diskGb")));
            return new VmRecord(folder, VmSpec.requireName(p.getProperty("name")),
                    Hypervisor.fromId(p.getProperty("hypervisor")), Stage.fromId(p.getProperty("stage")), size,
                    Path.of(p.getProperty("windowsIso")), p.getProperty("language", "en-US"),
                    Integer.parseInt(p.getProperty("vncPort")), Integer.parseInt(p.getProperty("qmpPort", "0")),
                    Integer.parseInt(p.getProperty("agentPort", "0")),
                    Integer.parseInt(p.getProperty("eventsPort", "0")));
        } catch (RuntimeException e) {
            throw new IOException("The VM record in " + folder + " is damaged.", e);
        }
    }
}
