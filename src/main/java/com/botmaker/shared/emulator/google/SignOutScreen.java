package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.tree.UiNode;
import com.botmaker.shared.emulator.tree.UiTree;

import java.util.Optional;

/**
 * A screen of Android Settings' way to remove an account: the accounts list ({@code android.settings.SYNC_SETTINGS}),
 * the account's page with its Remove button, and the dialog that confirms it. Read by Settings' ids where it has them
 * (the dialog's {@code android:id/button1}, the page's {@code com.android.settings:id/button}), and by the account's
 * email on the list. The list was captured 2026-11-12 on MuMu 12 (Android 15); the page and the dialog are AOSP
 * Settings' own layouts, not yet captured on an instance holding an account.
 */
public enum SignOutScreen {
    LOADING("loading", "Loading"),
    /** The accounts list, the account on it. */
    ACCOUNTS("accounts", "Accounts"),
    /** The account's own page. */
    ACCOUNT("account", "The account"),
    /** "Remove account?" */
    CONFIRM("confirm", "Confirm the removal"),
    UNKNOWN("unknown", "A screen BotMaker doesn't know");

    private final String id;
    private final String displayName;

    SignOutScreen(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public static SignOutScreen fromId(String id) {
        for (SignOutScreen screen : values()) {
            if (screen.id.equals(id)) return screen;
        }
        return UNKNOWN;
    }

    /** The screen {@code tree} shows, on the way to removing {@code email}. */
    public static SignOutScreen of(UiTree tree, String email) {
        if (tree == null || Screens.blank(tree)) return LOADING;
        if (Screens.byFullId(tree, "android:id/button1").isPresent() && Screens.shows(tree, "remove")) return CONFIRM;
        if (removeButton(tree).isPresent()) return ACCOUNT;
        if (Screens.saying(tree, email).isPresent()) return ACCOUNTS;
        return UNKNOWN;
    }

    /** What moves {@code this} screen on. */
    Optional<UiNode> next(UiTree tree, String email) {
        return switch (this) {
            case ACCOUNTS -> Screens.saying(tree, email);
            case ACCOUNT -> removeButton(tree);
            case CONFIRM -> Screens.byFullId(tree, "android:id/button1");
            default -> Optional.empty();
        };
    }

    private static Optional<UiNode> removeButton(UiTree tree) {
        Optional<UiNode> button = Screens.byFullId(tree, "com.android.settings:id/button");
        if (button.isPresent() && Screens.plain(button.get().text()).contains("remove")) return button;
        return Screens.saying(tree, "Remove account");
    }
}
