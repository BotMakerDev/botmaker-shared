package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.tree.Trait;
import com.botmaker.shared.emulator.tree.UiNode;
import com.botmaker.shared.emulator.tree.UiTree;

import java.util.Optional;

/**
 * A screen of Android's add-a-Google-account flow, read from the accessibility tree. Google's pages are a WebView whose
 * HTML ids reach the tree ({@code identifierId}, {@code passwordNext}), so the email and password screens are read by id
 * in any language; the screens after them by their English words, and one BotMaker cannot read is {@link #UNKNOWN},
 * which the user answers. Captured 2026-11-12 on MuMu 12 (Android 15): the email screen, and its answer to an address
 * Google has no account for.
 */
public enum SignInScreen {
    /** No words on it yet: the page is loading. */
    LOADING("loading", "Loading", false),
    EMAIL("email", "Email", false),
    /** The email screen saying Google has no such account. */
    NO_SUCH_ACCOUNT("no_such_account", "No such account", false),
    PASSWORD("password", "Password", false),
    WRONG_PASSWORD("wrong_password", "Wrong password", false),
    /** A second step: a code, a phone prompt to approve, "Verify it's you". */
    TWO_STEP("two_step", "2-Step Verification", true),
    CAPTCHA("captcha", "Captcha", true),
    /** Google refused the sign-in itself ("Couldn't sign you in"). */
    REFUSED("refused", "Sign-in refused", false),
    TERMS("terms", "Google's terms", false),
    /** Android's "Google services" page: backup and the like, then Accept. */
    SERVICES("services", "Google services", false),
    /** "Add phone number?" and the like, skipped. */
    PHONE_PROMPT("phone_prompt", "Add a phone number", false),
    UNKNOWN("unknown", "A screen BotMaker doesn't know", true);

    private final String id;
    private final String displayName;
    private final boolean needsYou;

    SignInScreen(String id, String displayName, boolean needsYou) {
        this.id = id;
        this.displayName = displayName;
        this.needsYou = needsYou;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** Whether only the user can get past it; BotMaker waits until the screen moves on. */
    public boolean needsYou() {
        return needsYou;
    }

    public static SignInScreen fromId(String id) {
        for (SignInScreen screen : values()) {
            if (screen.id.equals(id)) return screen;
        }
        return UNKNOWN;
    }

    /** The screen {@code tree} shows. */
    public static SignInScreen of(UiTree tree) {
        if (tree == null || Screens.blank(tree)) return LOADING;
        if (Screens.byId(tree, "captchaimg", "ca").isPresent()
                || Screens.shows(tree, "type the text you hear or see", "i'm not a robot")) {
            return CAPTCHA;
        }
        if (Screens.shows(tree, "couldn't sign you in", "this browser or app may not be secure")) return REFUSED;
        if (Screens.byId(tree, "identifierId").isPresent()) {
            return Screens.shows(tree, "couldn't find", "enter a valid email") ? NO_SUCH_ACCOUNT : EMAIL;
        }
        if (passwordField(tree).isPresent()) {
            if (Screens.shows(tree, "wrong password")) return WRONG_PASSWORD;
            // Google asking the password again to confirm it's the user is theirs: BotMaker types it once only
            return Screens.shows(tree, "verify it's you", "verify that it's you", "confirm it's you") ? TWO_STEP
                    : PASSWORD;
        }
        if (Screens.byId(tree, "totpPin", "idvPin", "idvPreregisteredPhonePin").isPresent()
                || Screens.shows(tree, "2-step verification", "verify it's you", "verify that it's you")) {
            return TWO_STEP;
        }
        if (Screens.byId(tree, "signinconsentNext").isPresent() || Screens.saying(tree, "I agree").isPresent()) {
            return TERMS;
        }
        if (Screens.shows(tree, "add phone number", "add a phone number", "add recovery")
                && Screens.saying(tree, "Skip", "Not now").isPresent()) {
            return PHONE_PROMPT;
        }
        if (Screens.shows(tree, "google services") && Screens.saying(tree, "Accept", "More").isPresent()) {
            return SERVICES;
        }
        return UNKNOWN;
    }

    /** Where the email is typed. */
    static Optional<UiNode> emailField(UiTree tree) {
        return Screens.byId(tree, "identifierId");
    }

    /** Where the password is typed: the field Android marks a password, else Google's own id for it. */
    static Optional<UiNode> passwordField(UiTree tree) {
        Optional<UiNode> marked = tree.all().stream().filter(n -> n.has(Trait.PASSWORD)).findFirst();
        return marked.isPresent() ? marked : Screens.byId(tree, "Passwd");
    }

    /** What moves {@code this} screen on, empty when nothing but Enter does. */
    Optional<UiNode> next(UiTree tree) {
        return switch (this) {
            case EMAIL -> Screens.byId(tree, "identifierNext").or(() -> Screens.saying(tree, "Next"));
            case PASSWORD -> Screens.byId(tree, "passwordNext").or(() -> Screens.saying(tree, "Next"));
            case TERMS -> Screens.byId(tree, "signinconsentNext").or(() -> Screens.saying(tree, "I agree"));
            // "More" scrolls the page down until it turns into "Accept"
            case SERVICES -> Screens.saying(tree, "More").or(() -> Screens.saying(tree, "Accept"));
            case PHONE_PROMPT -> Screens.saying(tree, "Skip", "Not now");
            default -> Optional.empty();
        };
    }
}
