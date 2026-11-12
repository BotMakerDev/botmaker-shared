package com.botmaker.shared.emulator.manage;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.PlatformId;
import com.botmaker.shared.emulator.config.Operation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * On this PC's emulators, with throwaway instances only: {@code -Dbotmaker.live.manage=<platform>:make} creates
 * {@code BM test}, renames it {@code BM test 2} and clones it as {@code BM clone} (started held, its accounts read,
 * then stopped); {@code <platform>:delete} deletes those two, run only after the user said yes.
 */
@EnabledIfSystemProperty(named = "botmaker.live.manage", matches = "(ldplayer|mumu|memu):(make|delete)")
class InstanceManagerLiveTest {

    private static final String CREATED = "BM test";
    private static final String RENAMED = "BM test 2";
    private static final String CLONE = "BM clone";

    @Test
    void manage() throws Exception {
        String[] spec = System.getProperty("botmaker.live.manage").split(":");
        PlatformId product = PlatformId.fromId(spec[0]);
        InstanceManager.Console console = assertInstanceOf(InstanceManager.Console.class,
                InstanceManager.forProduct(product));
        if (spec[1].equals("make")) {
            AtomicReference<EmulatorInstance> made = new AtomicReference<>();
            timed("create", console.create(CREATED, made::set));
            assertEquals(CREATED, made.get().name());
            AtomicReference<EmulatorInstance> renamed = new AtomicReference<>();
            timed("rename", console.rename(made.get(), RENAMED, renamed::set));
            assertEquals(RENAMED, renamed.get().name());
            AtomicReference<EmulatorInstance> clone = new AtomicReference<>();
            timed("clone", console.clone(renamed.get(), CLONE, InstanceManager.AccountRemoval.NOT_YET,
                    InstanceManager.Prepare.NOTHING, clone::set));
            assertEquals(CLONE, clone.get().name());
        } else {
            for (String name : List.of(CLONE, RENAMED)) {
                EmulatorInstance instance = ManageHost.live().instances(product).stream()
                        .filter(i -> name.equals(i.name())).findFirst().orElse(null);
                assertNotNull(instance, name + " is not there");
                timed("delete " + name, console.delete(instance));
            }
        }
    }

    private static void timed(String what, Operation.Body body) throws Exception {
        long start = System.nanoTime();
        String failed = body.run(new Operation.Context() {
            @Override
            public void step(String words) {
                System.out.println("  [" + what + "] " + words);
            }

            @Override
            public void progress(double fraction) {
            }

            @Override
            public void needsYou(String why) {
                System.out.println("  [" + what + "] needs you: " + why);
            }

            @Override
            public void resumed() {
            }

            @Override
            public boolean cancelled() {
                return false;
            }
        });
        System.out.printf("%s: %s in %d s%n", what, failed == null ? "ok" : "FAILED " + failed,
                (System.nanoTime() - start) / 1_000_000_000L);
        assertNull(failed, what);
    }
}
