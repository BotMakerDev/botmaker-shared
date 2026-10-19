package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link PhoneState#of}: the server's word for the phone's serial, else whether its saved address answers. */
class PhoneStateTest {

    private static EmulatorInstance onCable(String serial) {
        return new EmulatorInstance(PlatformId.PHYSICAL, serial, new AdbEndpoint.Server(serial));
    }

    @Test
    void aPhoneWaitingOnItsPromptIsUnauthorized() {
        List<AdbTools.ServerDevice> devices = List.of(new AdbTools.ServerDevice("R58M1", "unauthorized", "", true));
        assertEquals(PhoneState.UNAUTHORIZED, PhoneState.of(onCable("R58M1"), devices, false));
    }

    @Test
    void theServersWordWinsOverTheAddress() {
        List<AdbTools.ServerDevice> devices = List.of(
                new AdbTools.ServerDevice("R58M1", "device", "Pixel_7", true),
                new AdbTools.ServerDevice("R58M2", "offline", "", true));
        assertEquals(PhoneState.ONLINE, PhoneState.of(onCable("R58M1"), devices, false));
        assertEquals(PhoneState.OFFLINE, PhoneState.of(onCable("R58M2"), devices, true));
    }

    /** "no permissions" is a udev rule, not a cable: it isn't told to replug. */
    @Test
    void anotherServerWordIsUnknown() {
        List<AdbTools.ServerDevice> devices = List.of(new AdbTools.ServerDevice("R58M1", "no permissions", "", true));
        assertEquals(PhoneState.UNKNOWN, PhoneState.of(onCable("R58M1"), devices, false));
    }

    @Test
    void aSavedAddressTheServerLacksIsOnlineOnlyWhenItAnswers() {
        EmulatorInstance saved = DevicePlatform.configured("192.168.1.5:5555").getFirst();
        assertEquals(PhoneState.ONLINE, PhoneState.of(saved, List.of(), true));
        assertEquals(PhoneState.NOT_CONNECTED, PhoneState.of(saved, List.of(), false));
    }

    @Test
    void anUnknownIdParsesToUnknown() {
        assertEquals(PhoneState.UNKNOWN, PhoneState.fromId("asleep"));
        assertEquals(PhoneState.UNAUTHORIZED, PhoneState.fromId("unauthorized"));
    }
}
