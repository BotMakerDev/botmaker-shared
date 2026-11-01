package com.botmaker.shared.emulator;

import com.botmaker.shared.emulator.tree.UiNode;
import com.botmaker.shared.emulator.tree.UiTree;

import java.awt.Point;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A dialog Android itself shows over an app, never part of the app: a bot answers it without its author mapping it
 * (the user's call, 2026-11-01, after Sword x Staff stopped answering on Waydroid while it loaded). Told by the
 * focused window's title in {@code dumpsys window}, which each of them sets, and answered by its button's id: the
 * "isn't responding" and "keeps stopping" dialogs cannot be cancelled, so Back leaves them up (Android 13, Waydroid).
 */
public enum SystemDialog {
    /** "… isn't responding": its Wait. */
    NOT_RESPONDING("isn't responding", "aerr_wait", Pattern.compile(
            "mCurrentFocus=Window\\{\\S+ \\S+ Application Not Responding: ([\\w.]+)")),
    /** "… keeps stopping": the app is gone; its Close app. */
    CRASHED("stopped", "aerr_close", Pattern.compile("mCurrentFocus=Window\\{\\S+ \\S+ Application Error: ([\\w.]+)")),
    /** "Allow … to …?": a runtime permission the app asks; its Don't allow. */
    PERMISSION("asks a permission", "permission_deny_button", Pattern.compile(
            "mCurrentFocus=Window\\{\\S+ \\S+ [\\w.]*permissioncontroller/\\S*GrantPermissionsActivity"));

    private final String said;
    private final String button;
    private final Pattern focus;

    SystemDialog(String said, String button, Pattern focus) {
        this.said = said;
        this.button = button;
        this.focus = focus;
    }

    /** How a trace line says it: {@code the game isn't responding}. */
    public String said() {
        return said;
    }

    /**
     * Where to tap its answer in {@code tree}, read whole (Android's own windows kept): the centre of the button its
     * id names, whatever package serves it ({@code android}, a vendor's permission controller). Empty when the tree
     * holds none.
     */
    public Optional<Point> answerIn(UiTree tree) {
        if (tree == null) return Optional.empty();
        return tree.all().stream().filter(node -> button.equals(node.shortId())).map(UiNode::bounds)
                .filter(bounds -> !bounds.isEmpty()).findFirst()
                .map(bounds -> new Point((int) bounds.getCenterX(), (int) bounds.getCenterY()));
    }

    /**
     * A dialog in focus, and whether it is about the app in focus behind it: another app's "isn't responding" (System
     * UI's, a store's) is answered too, but says nothing about the game.
     */
    public record Shown(SystemDialog dialog, boolean theApps) {}

    private static final Pattern FOCUSED_APP = Pattern.compile("mFocusedApp=\\S*\\{\\S+ \\S+ ([\\w.]+)/");

    /**
     * The system dialog in focus on any display of {@code dumpsys window displays}' focus lines, or empty. A
     * permission is always the app's: only the app in focus asks one.
     */
    public static Optional<Shown> over(String dumpsys) {
        if (dumpsys == null) return Optional.empty();
        for (SystemDialog dialog : values()) {
            Matcher m = dialog.focus.matcher(dumpsys);
            if (!m.find()) continue;
            if (m.groupCount() == 0) return Optional.of(new Shown(dialog, true));
            Matcher app = FOCUSED_APP.matcher(dumpsys);
            return Optional.of(new Shown(dialog, app.find() && app.group(1).equals(m.group(1))));
        }
        return Optional.empty();
    }
}
