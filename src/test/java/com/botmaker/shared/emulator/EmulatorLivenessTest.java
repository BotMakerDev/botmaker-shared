package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who is up when several emulators ask for one port: {@link EmulatorLiveness#of}, without a socket. */
class EmulatorLivenessTest {

    private static final EmulatorInstance BLUESTACKS =
            new EmulatorInstance(PlatformId.BLUESTACKS, "Pie64", "127.0.0.1", 5555);
    private static final EmulatorInstance LDPLAYER =
            new EmulatorInstance(PlatformId.LDPLAYER, "LDPlayer", "127.0.0.1", 5555);
    private static final EmulatorInstance WAYDROID =
            new EmulatorInstance(PlatformId.WAYDROID, "Waydroid", "192.168.240.112", 5555);

    @Test
    void aStoppedInstanceWhosePortAnswersNamesTheEmulatorHoldingIt() {
        EmulatorInstance ld = LDPLAYER.withState(EmulatorState.STOPPED);
        EmulatorInstance bs = BLUESTACKS.withState(EmulatorState.RUNNING);
        List<EmulatorInstance> all = List.of(bs, ld);

        EmulatorLiveness ldNow = EmulatorLiveness.of(ld, all, true);
        assertEquals(EmulatorState.STOPPED, ldNow.state());
        assertEquals("port 5555 is in use by BlueStacks: Pie64", ldNow.clash());

        EmulatorLiveness bsNow = EmulatorLiveness.of(bs, all, true);
        assertEquals(EmulatorState.RUNNING, bsNow.state());
        assertNull(bsNow.clash());
    }

    @Test
    void aStoppedInstanceWhosePortAnswersWithNoProductClaimingItBlamesAnotherProgram() {
        EmulatorInstance ld = LDPLAYER.withState(EmulatorState.STOPPED);
        assertEquals("port 5555 is in use by another program",
                EmulatorLiveness.of(ld, List.of(ld), true).clash());
        assertNull(EmulatorLiveness.of(ld, List.of(ld), false).clash(), "a closed port is just stopped");
    }

    @Test
    void anInstanceWhoseProductSaysNothingIsUpWhenItsPortAnswersUnlessAnotherProductHoldsThePort() {
        assertEquals(EmulatorState.RUNNING, EmulatorLiveness.of(WAYDROID, List.of(WAYDROID), true).state());
        assertEquals(EmulatorState.STOPPED, EmulatorLiveness.of(WAYDROID, List.of(WAYDROID), false).state());

        EmulatorInstance ld = LDPLAYER.withState(EmulatorState.RUNNING);
        EmulatorLiveness bs = EmulatorLiveness.of(BLUESTACKS, List.of(BLUESTACKS, ld), true);
        assertEquals(EmulatorState.STOPPED, bs.state());
        assertEquals("port 5555 is in use by LDPlayer: LDPlayer", bs.clash());
    }

    @Test
    void anInstanceWhoseProductSaysAndroidIsUpWithAClosedPortHasItsAdbOffAndSaysWhereToTurnItOn() {
        EmulatorInstance ld = LDPLAYER.withState(EmulatorState.RUNNING);
        EmulatorLiveness closed = EmulatorLiveness.of(ld, List.of(ld), false);
        assertFalse(closed.running());
        assertTrue(closed.adbClosed());
        assertEquals("ADB off", closed.label());
        assertEquals("Android is up but nothing answers on port 5555 — turn on ADB in LDPlayer: "
                + "Settings › Other settings › ADB debugging › Open local connection", closed.problem(ld));

        EmulatorLiveness open = EmulatorLiveness.of(ld, List.of(ld), true);
        assertEquals(EmulatorState.RUNNING, open.state());
        assertNull(open.problem(ld));
        EmulatorInstance booting = LDPLAYER.withState(EmulatorState.STARTING);
        EmulatorLiveness stillBooting = EmulatorLiveness.of(booting, List.of(booting), false);
        assertEquals(EmulatorState.STARTING, stillBooting.state());
        assertFalse(stillBooting.adbClosed(), "a process up with Android maybe not is a boot, not ADB off");
        assertEquals(EmulatorState.RUNNING, EmulatorLiveness.of(booting, List.of(booting), true).state(),
                "a process up whose port answers (MEmu, BlueStacks) is up");
    }

    @Test
    void twoProductsBothUpOnOneAddressAreBothRefusedAndEachNamesTheOther() {
        EmulatorInstance ld = LDPLAYER.withState(EmulatorState.RUNNING);
        EmulatorInstance bs = BLUESTACKS.withState(EmulatorState.STARTING);
        List<EmulatorInstance> all = List.of(bs, ld);

        EmulatorLiveness ldNow = EmulatorLiveness.of(ld, all, true);
        assertFalse(ldNow.running());
        assertEquals("port clash", ldNow.label());
        assertEquals("port 5555 is also claimed by BlueStacks: Pie64 — stop one of them", ldNow.problem(ld));
        assertFalse(EmulatorLiveness.of(bs, all, true).running());
    }

    @Test
    void aClosedPortIsNotRunningWithoutAskingAnyProduct() {
        assertFalse(EmulatorLiveness.running(LDPLAYER, instance -> false));
        assertFalse(EmulatorLiveness.running(null, instance -> true));
    }

    @Test
    void anInstanceIsLookedUpAgainByItsAddressAndInstallSoItsStateIsTodays() {
        EmulatorInstance ld14 = LDPLAYER.withCommands(List.of("C:\\LDPlayer\\LDPlayer14\\ldconsole.exe"), List.of());
        EmulatorInstance ld9 = LDPLAYER.withCommands(List.of("C:\\LDPlayer\\LDPlayer9\\ldconsole.exe"), List.of());
        EmulatorInstance ld14Now = ld14.withState(EmulatorState.RUNNING);
        EmulatorInstance ld9Now = ld9.withState(EmulatorState.STOPPED);

        assertSame(ld14Now, EmulatorLiveness.current(ld14, List.of(ld9Now, ld14Now)));
        assertSame(ld9Now, EmulatorLiveness.current(ld9, List.of(ld9Now, ld14Now)));
        assertSame(WAYDROID, EmulatorLiveness.current(WAYDROID, List.of(ld9Now)), "gone: the instance itself");
    }

    @Test
    void aStateIdReadsBackAndAnythingElseIsUnknown() {
        for (EmulatorState state : EmulatorState.values()) assertEquals(state, EmulatorState.fromId(state.id()));
        assertEquals(EmulatorState.UNKNOWN, EmulatorState.fromId("paused"));
        assertEquals(EmulatorState.UNKNOWN, EmulatorState.fromId(null));
    }
}
