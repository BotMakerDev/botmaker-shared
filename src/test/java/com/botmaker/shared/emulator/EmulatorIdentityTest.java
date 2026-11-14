package com.botmaker.shared.emulator;

import com.botmaker.shared.emulator.config.InstanceKey;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who answers on an instance's address: its product, then its ID file against the ID this PC keeps. */
class EmulatorIdentityTest {

    private static final EmulatorInstance PIE64 = new EmulatorInstance(PlatformId.BLUESTACKS, "Pie64", "127.0.0.1",
            5555);
    private static final EmulatorInstance PIE64_1 = new EmulatorInstance(PlatformId.BLUESTACKS, "Pie64_1",
            "127.0.0.1", 5555);
    private static final EmulatorInstance LD = new EmulatorInstance(PlatformId.LDPLAYER, "LDPlayer", "127.0.0.1",
            5555);
    private static final List<String> BLUESTACKS_APPS = List.of("com.uncube.launcher3", "com.android.vending");

    /** An instance's Android: its apps, its ID file, and how many times the apps were listed. */
    static class Device implements EmulatorIdentity.Answering {
        final List<String> apps;
        String file = "";
        int listed;
        boolean runs = true;

        @Override
        public boolean runs() {
            return runs;
        }

        Device(List<String> apps) {
            this.apps = apps;
        }

        @Override
        public List<String> packages() {
            listed++;
            return apps;
        }

        @Override
        public String read() {
            return file;
        }

        @Override
        public void write(String text) {
            file = text;
        }
    }

    static final class Ids implements InstanceIds {
        final Map<InstanceKey, String> kept = new HashMap<>();

        @Override
        public Optional<String> expected(InstanceKey key) {
            return Optional.ofNullable(kept.get(key));
        }

        @Override
        public Optional<InstanceKey> owner(String id) {
            return kept.entrySet().stream().filter(e -> e.getValue().equals(id)).map(Map.Entry::getKey).findFirst();
        }

        @Override
        public Optional<String> remember(InstanceKey key, String id) {
            kept.put(key, id);
            return Optional.empty();
        }
    }

    @Test
    void firstSightWritesTheFileAndKeepsItsId() {
        Device device = new Device(BLUESTACKS_APPS);
        Ids ids = new Ids();
        assertEquals(Optional.empty(), EmulatorIdentity.check(PIE64, device, ids));
        String id = ids.kept.get(InstanceKey.of(PIE64));
        assertEquals(Optional.of(new EmulatorIdentity.IdFile(id, PlatformId.BLUESTACKS, "Pie64")),
                EmulatorIdentity.IdFile.parse(device.file));
    }

    @Test
    void aMatchPassesWithoutListingTheApps() {
        Device device = new Device(BLUESTACKS_APPS);
        Ids ids = new Ids();
        EmulatorIdentity.check(PIE64, device, ids);
        device.listed = 0;
        assertEquals(Optional.empty(), EmulatorIdentity.check(PIE64, device, ids));
        assertEquals(0, device.listed);
    }

    @Test
    void anotherInstanceOfTheProductIsToldByItsFile() {
        Device device = new Device(BLUESTACKS_APPS);
        Ids ids = new Ids();
        EmulatorIdentity.check(PIE64_1, device, ids);
        EmulatorIdentity.check(PIE64, new Device(BLUESTACKS_APPS), ids);
        Optional<String> refused = EmulatorIdentity.check(PIE64, device, ids);
        assertTrue(refused.orElseThrow().contains("answers as BlueStacks' Pie64_1, not BlueStacks' Pie64"),
                refused.get());
    }

    @Test
    void anotherProductIsToldByItsApps() {
        Optional<String> refused = EmulatorIdentity.check(LD, new Device(BLUESTACKS_APPS), new Ids());
        assertTrue(refused.orElseThrow().contains("answers as BlueStacks, not LDPlayer"), refused.get());
    }

    @Test
    void aMissingFileOnAnInstanceGivenAnIdRefuses() {
        Ids ids = new Ids();
        EmulatorIdentity.check(PIE64, new Device(BLUESTACKS_APPS), ids);
        Optional<String> refused = EmulatorIdentity.check(PIE64, new Device(BLUESTACKS_APPS), ids);
        assertTrue(refused.orElseThrow().contains("answers with no BotMaker ID"), refused.get());
    }

    @Test
    void aFileWrittenBeforeTheIdWasKeptIsAdopted() {
        Device device = new Device(BLUESTACKS_APPS);
        device.file = new EmulatorIdentity.IdFile("abc", PlatformId.BLUESTACKS, "Old name").text();
        Ids ids = new Ids();
        assertEquals(Optional.empty(), EmulatorIdentity.check(PIE64, device, ids));
        assertEquals("abc", ids.kept.get(InstanceKey.of(PIE64)), "an ID nobody here gave, on the instance running");
        assertEquals("Pie64", EmulatorIdentity.IdFile.parse(device.file).orElseThrow().name());
    }

    @Test
    void aRunKeepsNoIdAndChecksTheNameItsFileSays() {
        Device none = new Device(BLUESTACKS_APPS);
        assertEquals(Optional.empty(), EmulatorIdentity.check(PIE64, none, InstanceIds.NONE));
        assertEquals("", none.file);
        Device other = new Device(BLUESTACKS_APPS);
        other.file = new EmulatorIdentity.IdFile("abc", PlatformId.BLUESTACKS, "Pie64_1").text();
        assertTrue(EmulatorIdentity.check(PIE64, other, InstanceIds.NONE).isPresent());
        Device own = new Device(BLUESTACKS_APPS);
        own.file = new EmulatorIdentity.IdFile("abc", PlatformId.BLUESTACKS, "Pie64").text();
        assertEquals(Optional.empty(), EmulatorIdentity.check(PIE64, own, InstanceIds.NONE));
        assertEquals(0, own.listed, "its own file: the apps are not listed");
    }

    /** Review: a run after a rename in Studio would read the old name; Studio's next connect writes the new one. */
    @Test
    void aRenamedInstancesFileTakesItsNewName() {
        Device device = new Device(BLUESTACKS_APPS);
        Ids ids = new Ids();
        EmulatorIdentity.check(PIE64, device, ids);
        String id = ids.kept.remove(InstanceKey.of(PIE64));
        EmulatorInstance renamed = new EmulatorInstance(PlatformId.BLUESTACKS, "Farm", "127.0.0.1", 5555);
        ids.kept.put(InstanceKey.of(renamed), id);
        assertEquals(Optional.empty(), EmulatorIdentity.check(renamed, device, ids));
        assertEquals(Optional.empty(), EmulatorIdentity.check(renamed, device, InstanceIds.NONE));
    }

    /** Review: a first sight on an address another instance holds would give that one this instance's ID. */
    @Test
    void noIdIsGivenNorTakenWhereTheProductSaysAnotherRuns() {
        Device device = new Device(BLUESTACKS_APPS);
        device.runs = false;
        Ids ids = new Ids();
        assertEquals(Optional.empty(), EmulatorIdentity.check(PIE64, device, ids));
        assertEquals("", device.file);
        assertTrue(ids.kept.isEmpty());
        device.file = new EmulatorIdentity.IdFile("abc", PlatformId.BLUESTACKS, "Pie64_1").text();
        assertTrue(EmulatorIdentity.check(PIE64, device, ids).isPresent(), "another PC's file, another name");
    }

    @Test
    void anIdFileThatDidNotWriteIsNotKept() {
        Device device = new Device(BLUESTACKS_APPS) {
            @Override
            public void write(String text) {
            }
        };
        Ids ids = new Ids();
        assertEquals(Optional.empty(), EmulatorIdentity.check(PIE64, device, ids));
        assertTrue(ids.kept.isEmpty());
    }

    @Test
    void anInstanceNotToldByItsAppsIsNotChecked() {
        EmulatorInstance phone = new EmulatorInstance(PlatformId.PHYSICAL, "Pixel", new AdbEndpoint.Server("abc"));
        Device device = new Device(List.of());
        assertEquals(Optional.empty(), EmulatorIdentity.check(phone, device, new Ids()));
        assertEquals("", device.file);
    }

    @Test
    void anotherNamesTheInstanceAFileSaysUnlessItIsThisOne() {
        Ids ids = new Ids();
        String pie64_1 = new EmulatorIdentity.IdFile("b", PlatformId.BLUESTACKS, "Pie64_1").text();
        assertEquals(Optional.of("BlueStacks' Pie64_1"), EmulatorIdentity.another(PIE64, pie64_1, ids));
        String ld = new EmulatorIdentity.IdFile("c", PlatformId.LDPLAYER, "Pie64").text();
        assertEquals(Optional.of("LDPlayer's Pie64"), EmulatorIdentity.another(PIE64, ld, ids), "another product");
        String own = new EmulatorIdentity.IdFile("a", PlatformId.BLUESTACKS, "Pie64").text();
        assertEquals(Optional.empty(), EmulatorIdentity.another(PIE64, own, ids));
        assertEquals(Optional.empty(), EmulatorIdentity.another(PIE64, "", ids), "no file says nothing");
    }

    @Test
    void aFileHoldingThisInstancesIdUnderAnOlderNameIsARename() {
        Ids ids = new Ids();
        ids.kept.put(InstanceKey.of(PIE64), "a");
        String older = new EmulatorIdentity.IdFile("a", PlatformId.BLUESTACKS, "Old name").text();
        assertEquals(Optional.empty(), EmulatorIdentity.another(PIE64, older, ids));
        assertEquals(Optional.empty(), EmulatorIdentity.another(PIE64, older, InstanceIds.NONE),
                "no IDs kept: a rename can't be told from a sibling, so only a product is");
        String ld = new EmulatorIdentity.IdFile("c", PlatformId.LDPLAYER, "LDPlayer").text();
        assertEquals(Optional.of("LDPlayer's LDPlayer"), EmulatorIdentity.another(PIE64, ld, InstanceIds.NONE));
    }

    @Test
    void aMarkWaitsWhileASiblingIsUpOnTheSameAddress() {
        EmulatorInstance sibling = PIE64_1.withState(EmulatorState.RUNNING);
        assertTrue(EmulatorIdentity.sharing(PIE64, List.of(PIE64, sibling)).isPresent());
        assertEquals(Optional.empty(), EmulatorIdentity.sharing(PIE64,
                List.of(PIE64, PIE64_1.withState(EmulatorState.STOPPED))));
        assertEquals(Optional.empty(), EmulatorIdentity.sharing(PIE64, List.of(LD.withState(EmulatorState.RUNNING))),
                "another product is told by its apps");
    }

    @Test
    void aNameIsQuotedForTheShell() {
        assertEquals("'it'\\''s'", EmulatorIdentity.quoted("it's"));
    }
}
