package com.botmaker.shared.vm;

import com.botmaker.shared.tools.UserDirs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** BotMaker's game VMs on this computer: one folder each under {@link #root()}. Never throws. */
public final class VmInventory {

    private VmInventory() {}

    /** Where game VMs live: per user and local to this computer, as their disks are tens of gigabytes. */
    public static Path root() {
        return UserDirs.config().resolve("vm");
    }

    /** The folder a VM named {@code name} has, or would have. */
    public static Path folder(String name) {
        return root().resolve(name);
    }

    /** The VM named {@code name}, or empty when there is none or its record doesn't read. */
    public static Optional<VmRecord> find(String name) {
        Path folder = folder(name);
        if (!Files.isRegularFile(folder.resolve(VmRecord.FILE))) return Optional.empty();
        try {
            return Optional.of(VmRecord.load(folder));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Every VM whose record reads, by name. */
    public static List<VmRecord> list() {
        List<VmRecord> records = new ArrayList<>();
        if (!Files.isDirectory(root())) return records;
        try (Stream<Path> folders = Files.list(root())) {
            for (Path folder : folders.sorted().toList()) {
                if (!Files.isRegularFile(folder.resolve(VmRecord.FILE))) continue;
                try {
                    records.add(VmRecord.load(folder));
                } catch (IOException e) {
                    // a damaged record: not listed
                }
            }
        } catch (IOException e) {
            // an unreadable root lists nothing
        }
        return records;
    }

    /** Whether {@code vm} runs now. */
    public static boolean running(VmRecord vm) {
        return switch (vm.hypervisor()) {
            case QEMU -> QmpClient.listening(vm.qmpPort());
            case VMWARE -> VmwareWorkstation.find().map(ws -> {
                try {
                    return ws.running().stream().anyMatch(p -> p.toString().equalsIgnoreCase(vm.vmx().toString()));
                } catch (IOException e) {
                    return false;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }).orElse(false);
            case UNKNOWN -> false;
        };
    }

    /** The loopback ports the listed VMs already use. */
    static List<Integer> portsInUse() {
        List<Integer> ports = new ArrayList<>();
        for (VmRecord vm : list()) {
            ports.add(vm.vncPort());
            ports.add(vm.qmpPort());
            ports.add(vm.agentPort());
        }
        return ports;
    }
}
