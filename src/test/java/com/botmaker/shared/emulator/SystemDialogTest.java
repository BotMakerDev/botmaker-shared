package com.botmaker.shared.emulator;

import com.botmaker.shared.emulator.SystemDialog.Shown;
import com.botmaker.shared.emulator.tree.UiTree;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SystemDialogTest {

    private static final String GAME = "  mFocusedApp=ActivityRecord{b5c6c54 u0 com.zjcs.android.us/"
            + "com.leiting.unity.AppActivity} t116}\n";

    @Test
    void androidsOwnDialogsAreToldByTheFocusedWindow() {
        // As Waydroid printed it, one line per display, the app's activity on the first (2026-11-01).
        assertEquals(Optional.of(new Shown(SystemDialog.NOT_RESPONDING, true)), SystemDialog.over("""
                  mCurrentFocus=Window{d902fad u0 com.zjcs.android.us/com.leiting.unity.AppActivity}
                  mFocusedApp=ActivityRecord{b5c6c54 u0 com.zjcs.android.us/com.leiting.unity.AppActivity} t116}
                  mCurrentFocus=Window{f151d9 u0 Application Not Responding: com.zjcs.android.us}
                """ + GAME));
        assertEquals(Optional.of(new Shown(SystemDialog.CRASHED, true)), SystemDialog.over(
                "  mCurrentFocus=Window{1a2b u0 Application Error: com.zjcs.android.us}\n" + GAME));
        assertEquals(Optional.of(new Shown(SystemDialog.PERMISSION, true)), SystemDialog.over("  mCurrentFocus="
                + "Window{3c u0 com.android.permissioncontroller/com.android.permissioncontroller.permission.ui"
                + ".GrantPermissionsActivity}\n"));
        assertEquals(Optional.of(new Shown(SystemDialog.PERMISSION, true)), SystemDialog.over("  mCurrentFocus="
                + "Window{3c u0 com.google.android.permissioncontroller/com.android.permissioncontroller.permission"
                + ".ui.GrantPermissionsActivity}\n"));
    }

    /** The dialogs cannot be cancelled: each is answered by its button, found by id whatever package serves it. */
    @Test
    void eachIsAnsweredByItsButton() {
        UiTree tree = UiTree.parse("<hierarchy rotation=\"0\">"
                + node("android:id/aerr_close", "Close app", "[333,320][640,352]")
                + node("android:id/aerr_wait", "Wait", "[333,368][640,400]")
                + node("com.google.android.permissioncontroller:id/permission_deny_button", "Don't allow",
                "[330,390][950,450]") + "</hierarchy>");
        assertEquals(Optional.of(new Point(486, 384)), SystemDialog.NOT_RESPONDING.answerIn(tree));
        assertEquals(Optional.of(new Point(486, 336)), SystemDialog.CRASHED.answerIn(tree));
        assertEquals(Optional.of(new Point(640, 420)), SystemDialog.PERMISSION.answerIn(tree));
        assertEquals(Optional.empty(), SystemDialog.NOT_RESPONDING.answerIn(UiTree.parse("<hierarchy/>")));
    }

    private static String node(String id, String text, String bounds) {
        return "<node index=\"0\" text=\"" + text.replace("'", "&apos;") + "\" resource-id=\"" + id + "\" "
                + "class=\"android.widget.Button\" package=\"android\" content-desc=\"\" checkable=\"false\" "
                + "checked=\"false\" clickable=\"true\" enabled=\"true\" focusable=\"true\" focused=\"false\" "
                + "scrollable=\"false\" long-clickable=\"false\" password=\"false\" selected=\"false\" bounds=\""
                + bounds + "\" />";
    }

    /** Each display's focus now, never the last ANR's snapshot that opens a whole dumpsys window. */
    @Test
    void theFocusIsReadOffTheDisplaysOnly() {
        assertEquals("dumpsys window displays | grep -E 'mCurrentFocus|mFocusedApp'", AdbDevice.FOCUS);
    }

    @Test
    void anotherAppsDialogIsNotTheGames() {
        assertEquals(Optional.of(new Shown(SystemDialog.NOT_RESPONDING, false)), SystemDialog.over(
                "  mCurrentFocus=Window{f1 u0 Application Not Responding: com.android.systemui}\n" + GAME));
    }

    @Test
    void theAppOrNothingInFocusIsNoDialog() {
        assertEquals(Optional.empty(), SystemDialog.over(
                "  mCurrentFocus=Window{d902fad u0 com.zjcs.android.us/com.leiting.unity.AppActivity}\n" + GAME));
        assertEquals(Optional.empty(), SystemDialog.over("  mCurrentFocus=null\n"));
        assertEquals(Optional.empty(), SystemDialog.over(null));
    }
}
