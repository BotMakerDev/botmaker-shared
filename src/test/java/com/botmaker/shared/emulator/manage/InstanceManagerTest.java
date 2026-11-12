package com.botmaker.shared.emulator.manage;

import com.botmaker.shared.Spawn;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorLauncher;
import com.botmaker.shared.emulator.EmulatorReadiness;
import com.botmaker.shared.emulator.HeldInstances;
import com.botmaker.shared.emulator.NewInstance;
import com.botmaker.shared.emulator.PlatformId;
import com.botmaker.shared.emulator.config.Operation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The consoles' argument shapes as their help on this PC gave them (2026-11-12), and the operations over a fake host.
 */
class InstanceManagerTest {

    private static final String LD = "C:\\LDPlayer\\LDPlayer14\\ldconsole.exe";
    private static final String MUMU = "C:\\Program Files\\Netease\\MuMuPlayer\\nx_main\\MuMuManager.exe";
    private static final String MEMUC = "C:\\Program Files\\Microvirt\\MEmu\\memuc.exe";
    /** A removal that removes nothing and says so, counting the accounts. */
    private static final InstanceManager.AccountRemoval REFUSES = (clone, emails, context) -> clone.name() + " holds "
            + emails.size() + " Google account" + (emails.size() == 1 ? "" : "s") + " BotMaker didn't remove.";

    private static EmulatorInstance ld(int index, String name) {
        String i = String.valueOf(index);
        return new EmulatorInstance(PlatformId.LDPLAYER, name, "127.0.0.1", 5555 + 2 * index,
                List.of(LD, "launch", "--index", i), List.of(LD, "quit", "--index", i));
    }

    private static EmulatorInstance mumu(int index, String name) {
        String i = String.valueOf(index);
        return new EmulatorInstance(PlatformId.MUMU, name, "127.0.0.1", 16384 + 32 * index,
                List.of(MUMU, "control", "-v", i, "launch"), List.of(MUMU, "control", "-v", i, "shutdown"));
    }

    private static EmulatorInstance memu(String vm, int port, String name) {
        return new EmulatorInstance(PlatformId.MEMU, name, "127.0.0.1", port,
                List.of(MEMUC, "start", "-n", vm), List.of(MEMUC, "stop", "-n", vm));
    }

    @Test
    void ldconsoleArguments() {
        InstanceConsole c = new InstanceConsole.LdConsole(LD);
        EmulatorInstance one = ld(1, "Farm");
        assertEquals(List.of(LD, "add", "--name", "Farm 2"), c.create("Farm 2"));
        assertEquals(List.of(LD, "copy", "--name", "Farm 2", "--from", "1"), c.copy(one, "Farm 2"));
        assertEquals(List.of(LD, "rename", "--index", "1", "--title", "Main"), c.rename(one, "Main"));
        assertEquals(List.of(LD, "remove", "--index", "1"), c.delete(one));
        assertEquals(List.of(List.of(LD, "modify", "--index", "1", "--imei", "auto", "--imsi", "auto", "--simserial",
                "auto", "--androidid", "auto", "--mac", "auto")), c.newDeviceIds(one, new Random(1)));
        assertTrue(c.namesOnCreate());
    }

    @Test
    void muMuManagerArguments() {
        InstanceConsole c = new InstanceConsole.MuMuManager(MUMU);
        EmulatorInstance two = mumu(2, "Farm");
        assertEquals(List.of(MUMU, "create", "-n", "1"), c.create("x"));
        assertEquals(List.of(MUMU, "clone", "-v", "2"), c.copy(two, "x"));
        assertEquals(List.of(MUMU, "rename", "-v", "2", "-n", "Main"), c.rename(two, "Main"));
        assertEquals(List.of(MUMU, "delete", "-v", "2"), c.delete(two));
        List<List<String>> ids = c.newDeviceIds(two, new Random(1));
        assertEquals(List.of("android_id", "mac_address", "imei"), ids.stream().map(cmd -> cmd.get(5)).toList());
        ids.forEach(cmd -> assertEquals(List.of(MUMU, "simulation", "-v", "2", "-sk"), cmd.subList(0, 5)));
        assertFalse(c.namesOnCreate());
    }

    @Test
    void memucArguments() {
        InstanceConsole c = new InstanceConsole.Memuc(MEMUC);
        EmulatorInstance vm = memu("MEmu_1", 21513, "Farm");
        assertEquals(List.of(MEMUC, "create", "96"), c.create("x"));
        assertEquals(List.of(MEMUC, "clone", "-n", "MEmu_1"), c.copy(vm, "x"));
        assertEquals(List.of(MEMUC, "rename", "-n", "MEmu_1", "Main"), c.rename(vm, "Main"));
        assertEquals(List.of(MEMUC, "remove", "-n", "MEmu_1"), c.delete(vm));
        assertEquals(List.of(List.of(MEMUC, "randomize", "-n", "MEmu_1")), c.newDeviceIds(vm, new Random(1)));
    }

    @Test
    void anInstanceWithoutItsSelectorIsRefusedInWords() {
        EmulatorInstance bare = new EmulatorInstance(PlatformId.LDPLAYER, "Farm", "127.0.0.1", 5555);
        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new InstanceConsole.LdConsole(LD).delete(bare));
        assertTrue(e.getMessage().contains("refresh"), e.getMessage());
    }

    @Test
    void blueStacksOpensItsManagerAndNoConsoleIsGuessed() {
        InstanceManager bs = InstanceManager.of(new NewInstance(PlatformId.BLUESTACKS,
                List.of("C:\\Program Files\\BlueStacks_nxt\\HD-MultiInstanceManager.exe"), true, () -> 1), new Fake());
        assertInstanceOf(InstanceManager.OpensManager.class, bs);
        assertInstanceOf(InstanceManager.Console.class, InstanceManager.of(
                new NewInstance(PlatformId.LDPLAYER, List.of(LD, "add"), false, () -> 1), new Fake()));
        assertNull(InstanceManager.of(new NewInstance(PlatformId.AVD, List.of("avdmanager"), false, () -> 1),
                new Fake()));
    }

    @Test
    void namesAreCheckedBeforeAnyConsoleRuns() {
        List<EmulatorInstance> all = List.of(ld(0, "LDPlayer"), ld(1, "Farm"));
        assertNull(InstanceManager.Console.nameProblem("Farm (2)", all, null));
        assertTrue(InstanceManager.Console.nameProblem(" farm ", all, null).contains("Another"));
        assertNull(InstanceManager.Console.nameProblem("Farm", all, all.get(1)), "its own name");
        assertTrue(InstanceManager.Console.nameProblem("a,b", all, null).contains("letters"));
        assertTrue(InstanceManager.Console.nameProblem("x@y", all, null).contains("letters"));
        assertTrue(InstanceManager.Console.nameProblem("  ", all, null).contains("name"));
    }

    @Test
    void ldPlayerCreatesUnderItsNameAndIsJudgedByTheList() throws Exception {
        Fake host = new Fake(ld(0, "LDPlayer"));
        host.answer = command -> {
            host.instances.add(ld(1, command.get(3)));
            return new Spawn.Completed(1, ""); // ldconsole add exits 1 after creating one
        };
        AtomicReference<EmulatorInstance> made = new AtomicReference<>();
        assertNull(console(PlatformId.LDPLAYER, LD, host).create("Farm", made::set).run(new Ctx()));
        assertEquals("Farm", made.get().name());
        assertEquals(List.of(List.of(LD, "add", "--name", "Farm")), host.commands);
    }

    @Test
    void muMuNamesWhatItCreatedWithARename() throws Exception {
        Fake host = new Fake(mumu(0, "Android Device"));
        host.answer = command -> {
            if (command.get(1).equals("create")) host.instances.add(mumu(1, "Android Device"));
            if (command.get(1).equals("rename")) host.instances.set(1, mumu(1, command.get(5)));
            return new Spawn.Completed(0, "");
        };
        AtomicReference<EmulatorInstance> made = new AtomicReference<>();
        assertNull(console(PlatformId.MUMU, MUMU, host).create("Farm", made::set).run(new Ctx()));
        assertEquals("Farm", made.get().name());
        assertEquals(List.of(MUMU, "rename", "-v", "1", "-n", "Farm"), host.commands.get(1));
    }

    @Test
    void aCreateNothingCameOfSaysWhatTheConsoleSaid() {
        Fake host = new Fake();
        host.answer = command -> new Spawn.Completed(1, "disk full");
        Exception e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> console(PlatformId.LDPLAYER, LD, host).create("Farm", i -> {}).run(new Ctx()));
        assertTrue(e.getMessage().contains("disk full"), e.getMessage());
    }

    /**
     * Review item 5: the clone's first start follows its new IDs, is held from bots, and its accounts go before
     * anything.
     */
    @Test
    void cloneRemovesAccountsBeforeStart() throws Exception {
        EmulatorInstance source = ld(0, "LDPlayer");
        Fake host = new Fake(source);
        host.answer = command -> {
            if (command.get(1).equals("copy")) host.instances.add(ld(1, command.get(3)));
            return new Spawn.Completed(0, "");
        };
        host.accounts.add("someone@example.com");
        AtomicReference<EmulatorInstance> made = new AtomicReference<>();
        List<String> heldDuring = new ArrayList<>();
        host.onStart = clone -> {
            heldDuring.add("start held from others: " + elsewhere(clone));
        };
        InstanceManager.AccountRemoval removal = (clone, emails, context) -> {
            host.log.add("remove " + emails.size());
            host.accounts.clear();
            return null;
        };
        InstanceManager.Prepare prepare = (clone, context) -> {
            host.log.add("prepare");
            return null;
        };
        assertNull(console(PlatformId.LDPLAYER, LD, host).clone(source, "Farm", removal, prepare, made::set)
                .run(new Ctx()));
        assertEquals(List.of("console copy", "console modify", "adb on Farm", "start Farm", "accounts", "remove 1",
                "accounts", "prepare", "stop Farm"), host.log);
        assertEquals(List.of("start held from others: true"), heldDuring);
        assertFalse(HeldInstances.heldElsewhere(made.get()), "released at the end");
        assertEquals(List.of(LD, "copy", "--name", "Farm", "--from", "0"), host.commands.getFirst());
    }

    @Test
    void aCloneWhoseAccountsCantGoIsLeftStoppedAndSaysSo() throws Exception {
        EmulatorInstance source = mumu(0, "Main");
        Fake host = new Fake(source);
        host.answer = command -> {
            if (command.get(1).equals("clone")) host.instances.add(mumu(1, "Main"));
            if (command.get(1).equals("rename")) host.instances.set(1, mumu(1, command.get(5)));
            return new Spawn.Completed(0, "");
        };
        host.accounts.add("someone@example.com");
        String failed = console(PlatformId.MUMU, MUMU, host).clone(source, "Farm",
                REFUSES, (c, x) -> {
                    throw new AssertionError("nothing is applied over a copied account");
                }, c -> {}).run(new Ctx());
        assertTrue(failed.contains("1 Google account") && failed.contains("stopped"), failed);
        assertFalse(failed.contains("@"), "no email in an outcome: " + failed);
        assertEquals("stop Farm", host.log.getLast());
        assertEquals(3, host.commands.stream().filter(c -> c.get(1).equals("simulation")).count());
    }

    /** Live 2026-11-12: an LDPlayer clone whose start failed (ADB off) was left running. */
    @Test
    void aCloneWhoseStartFailedIsStoppedAgain() throws Exception {
        EmulatorInstance source = ld(0, "LDPlayer");
        Fake host = new Fake(source);
        host.answer = command -> {
            if (command.get(1).equals("copy")) host.instances.add(ld(1, command.get(3)));
            return new Spawn.Completed(0, "");
        };
        host.onStart = clone -> {
            throw new IllegalStateException("can't be driven");
        };
        String failed = console(PlatformId.LDPLAYER, LD, host).clone(source, "Farm",
                REFUSES, InstanceManager.Prepare.NOTHING, c -> {}).run(new Ctx());
        assertEquals("can't be driven It is stopped.", failed);
        assertEquals("stop Farm", host.log.getLast());
    }

    /** Review: a stop that failed is said, never dropped behind a "done". */
    @Test
    void aCloneThatWontStopSaysSo() throws Exception {
        EmulatorInstance source = ld(0, "LDPlayer");
        Fake host = new Fake(source);
        host.answer = command -> {
            if (command.get(1).equals("copy")) host.instances.add(ld(1, command.get(3)));
            return new Spawn.Completed(0, "");
        };
        host.stopFails = "Farm did not stop within 30 seconds.";
        String failed = console(PlatformId.LDPLAYER, LD, host).clone(source, "Farm",
                REFUSES, InstanceManager.Prepare.NOTHING, c -> {}).run(new Ctx());
        assertTrue(failed.startsWith("Farm did not stop") && failed.contains("still runs"), failed);
    }

    /** Review: a made instance the console left unnamed is still handed to the caller. */
    @Test
    void anInstanceWhoseNamingFailedIsStillReported() throws Exception {
        Fake host = new Fake(mumu(0, "Main"));
        host.answer = command -> {
            if (command.get(1).equals("create")) host.instances.add(mumu(1, "MuMu 1"));
            return new Spawn.Completed(command.get(1).equals("rename") ? 3 : 0, "");
        };
        AtomicReference<EmulatorInstance> made = new AtomicReference<>();
        Exception e = assertThrows(IOException.class,
                () -> console(PlatformId.MUMU, MUMU, host).create("Farm", made::set).run(new Ctx()));
        assertEquals("MuMu 1", made.get().name());
        assertTrue(e.getMessage().contains("listed as MuMu 1"), e.getMessage());
    }

    /** Review: a held instance isn't renamed or deleted by another thread, and a hold isn't taken twice. */
    @Test
    void aHeldInstanceIsNotRenamedDeletedOrHeldAgain() throws Exception {
        EmulatorInstance farm = ld(4, "Kept farm");
        Fake host = new Fake(farm);
        InstanceManager.Console console = console(PlatformId.LDPLAYER, LD, host);
        try (HeldInstances.Hold ignored = HeldInstances.hold(farm)) {
            assertThrows(IllegalStateException.class, () -> HeldInstances.hold(farm));
            String rename = CompletableFuture.supplyAsync(() -> run(console.rename(farm, "Other", r -> {}))).get();
            String delete = CompletableFuture.supplyAsync(() -> run(console.delete(farm))).get();
            assertTrue(rename.startsWith("BotMaker is preparing"), rename);
            assertTrue(delete.startsWith("BotMaker is preparing"), delete);
        }
        assertTrue(host.commands.isEmpty());
        assertFalse(elsewhere(farm));
    }

    private static String run(Operation.Body body) {
        try {
            return body.run(new Ctx());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aRunningSourceIsNotCopied() throws Exception {
        EmulatorInstance source = ld(0, "LDPlayer");
        Fake host = new Fake(source);
        host.running.add(source.identity());
        String failed = console(PlatformId.LDPLAYER, LD, host).clone(source, "Farm",
                REFUSES, InstanceManager.Prepare.NOTHING, c -> {}).run(new Ctx());
        assertTrue(failed.startsWith("Stop LDPlayer first"), failed);
        assertTrue(host.commands.isEmpty());
    }

    @Test
    void renameFindsTheInstanceAgainByAddress() throws Exception {
        Fake host = new Fake(memu("MEmu_1", 21513, "Old"));
        host.answer = command -> {
            host.instances.set(0, memu("MEmu_1", 21513, command.get(4)));
            return new Spawn.Completed(0, "");
        };
        AtomicReference<EmulatorInstance> renamed = new AtomicReference<>();
        // the window's copy is stale: another name, the same address
        assertNull(console(PlatformId.MEMU, MEMUC, host).rename(memu("MEmu_1", 21513, "Older"), "New", renamed::set)
                .run(new Ctx()));
        assertEquals("New", renamed.get().name());
        assertEquals(List.of(MEMUC, "rename", "-n", "MEmu_1", "New"), host.commands.getFirst());
    }

    @Test
    void deleteStopsFirstAndChecksItIsGone() throws Exception {
        EmulatorInstance farm = ld(1, "Farm");
        Fake host = new Fake(ld(0, "LDPlayer"), farm);
        host.running.add(farm.identity());
        host.answer = command -> {
            host.instances.remove(1);
            return new Spawn.Completed(0, "");
        };
        assertNull(console(PlatformId.LDPLAYER, LD, host).delete(farm).run(new Ctx()));
        assertEquals(List.of("stop Farm", "console remove"), host.log);
    }

    @Test
    void newDeviceIdsWantItStoppedAndNeverEchoTheConsole() throws Exception {
        EmulatorInstance one = mumu(0, "Main");
        Fake host = new Fake(one);
        host.running.add(one.identity());
        assertTrue(console(PlatformId.MUMU, MUMU, host).newDeviceIds(one).run(new Ctx()).startsWith("Stop Main"));
        host.running.clear();
        host.answer = command -> new Spawn.Completed(2, "bad value " + command.get(7));
        String failed = console(PlatformId.MUMU, MUMU, host).newDeviceIds(one).run(new Ctx());
        assertEquals("MuMuManager.exe refused new device IDs for Main (exit 2).", failed);
    }

    @Test
    void aHeldInstanceIsRefusedToOtherThreadsOnly() throws Exception {
        EmulatorInstance farm = ld(3, "Held farm");
        try (HeldInstances.Hold ignored = HeldInstances.hold(farm)) {
            assertFalse(HeldInstances.heldElsewhere(farm));
            assertTrue(elsewhere(farm));
            assertFalse(CompletableFuture.supplyAsync(() -> EmulatorLauncher.launch(farm)).get());
            assertTrue(CompletableFuture.supplyAsync(() -> EmulatorReadiness.bringUp(farm, null)).get().isEmpty());
            assertTrue(CompletableFuture.supplyAsync(() -> EmulatorReadiness.notReady(farm)).get()
                    .startsWith("BotMaker is preparing Held farm"));
        }
        assertFalse(elsewhere(farm));
    }

    private static boolean elsewhere(EmulatorInstance instance) {
        return CompletableFuture.supplyAsync(() -> HeldInstances.heldElsewhere(instance)).join();
    }

    private static InstanceManager.Console console(PlatformId product, String exe, ManageHost host) {
        return new InstanceManager.Console(InstanceConsole.of(product, exe), host, new Random(7));
    }

    /** A product's instances as a list the fake console changes; every call logged. */
    private static final class Fake implements ManageHost {
        final List<EmulatorInstance> instances = new ArrayList<>();
        final List<List<String>> commands = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        final Set<String> running = new LinkedHashSet<>();
        final Set<String> accounts = new LinkedHashSet<>();
        Function<List<String>, Spawn.Completed> answer = command -> new Spawn.Completed(0, "");
        Consumer<EmulatorInstance> onStart = i -> {};
        String stopFails;

        Fake(EmulatorInstance... known) {
            instances.addAll(List.of(known));
        }

        @Override
        public List<EmulatorInstance> instances(PlatformId product) {
            return List.copyOf(instances);
        }

        @Override
        public Spawn.Completed console(List<String> command) {
            commands.add(command);
            log.add("console " + command.get(1));
            return answer.apply(command);
        }

        @Override
        public boolean running(EmulatorInstance instance) {
            return running.contains(instance.identity());
        }

        @Override
        public String stop(EmulatorInstance instance) {
            log.add("stop " + instance.name());
            if (stopFails != null) return stopFails;
            running.remove(instance.identity());
            return null;
        }

        @Override
        public String start(EmulatorInstance instance, Consumer<String> words) {
            log.add("start " + instance.name());
            running.add(instance.identity()); // the process runs even when Android never answers
            onStart.accept(instance);
            return null;
        }

        @Override
        public Set<String> googleAccounts(EmulatorInstance instance) {
            log.add("accounts");
            return Set.copyOf(accounts);
        }

        @Override
        public String adbOn(EmulatorInstance instance) {
            log.add("adb on " + instance.name());
            return null;
        }
    }

    private static final class Ctx implements Operation.Context {
        @Override
        public void step(String words) {
        }

        @Override
        public void progress(double fraction) {
        }

        @Override
        public void needsYou(String why) {
        }

        @Override
        public void resumed() {
        }

        @Override
        public boolean cancelled() {
            return false;
        }
    }
}
