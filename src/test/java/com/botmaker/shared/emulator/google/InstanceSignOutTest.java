package com.botmaker.shared.emulator.google;

import org.junit.jupiter.api.Test;

import static com.botmaker.shared.emulator.google.TestScreens.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceSignOutTest {

    private static final String EMAIL = "user@example.com";

    /** Settings' list → the account → Remove account → the confirm dialog's Remove account → gone. */
    private static TestScreens.Device settings() {
        TestScreens.Device device = new TestScreens.Device(TestScreens.accountsList(EMAIL));
        device.accounts.add(EMAIL);
        device.onTap = point -> switch (SignOutScreen.of(device.screen, EMAIL)) {
            case ACCOUNTS -> at(device.screen, EMAIL).equals(point) ? TestScreens.accountPage() : null;
            case ACCOUNT -> TestScreens.confirm();
            case CONFIRM -> {
                if (!at(TestScreens.confirm(), "Cancel").equals(point)) device.accounts.remove(EMAIL);
                yield TestScreens.home();
            }
            default -> null;
        };
        return device;
    }

    @Test
    void removesTheAccountThroughSettings() throws Exception {
        TestScreens.Device device = settings();
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        assertNull(new InstanceSignOut(device, new TestScreens.Clock()).signOut(EMAIL, ctx));
        assertEquals(InstanceSignOut.ACCOUNTS, device.log.getFirst());
        assertTrue(device.accounts.isEmpty());
        assertEquals(3, device.log.stream().filter(l -> l.startsWith("tap")).count());
        assertTrue(device.typed.isEmpty());
        for (String said : ctx.steps) assertFalse(said.contains("@"), said);
    }

    @Test
    void anAccountNotThereIsDoneAtOnce() throws Exception {
        TestScreens.Device device = settings();
        device.accounts.clear();
        assertNull(new InstanceSignOut(device, new TestScreens.Clock()).signOut(EMAIL, new TestScreens.Ctx()));
        assertTrue(device.log.isEmpty());
    }

    @Test
    void aScreenItDoesNotKnowIsHandedToTheUserThenTimesOut() throws Exception {
        TestScreens.Device device = settings();
        device.screen = TestScreens.tree(TestScreens.SETTINGS, TestScreens.node("", "Something else", 1));
        device.onTap = point -> null;
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        String failed = new InstanceSignOut(device, new TestScreens.Clock()).signOut(EMAIL, ctx);
        assertNotNull(failed);
        assertFalse(failed.contains("@"), failed);
        assertEquals(1, ctx.needs.size());
        assertTrue(device.accounts.contains(EMAIL));
    }

    @Test
    void aCancelGoesHome() throws Exception {
        TestScreens.Device device = settings();
        TestScreens.Ctx ctx = new TestScreens.Ctx();
        ctx.cancel = true;
        String failed = new InstanceSignOut(device, new TestScreens.Clock()).signOut(EMAIL, ctx);
        assertTrue(failed.startsWith("Cancelled"), failed);
        assertEquals("key " + InstanceSignIn.KEY_HOME, device.log.getLast());
    }
}
