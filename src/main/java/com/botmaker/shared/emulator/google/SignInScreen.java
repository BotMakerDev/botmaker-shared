package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.config.AccountRecord.Verification;
import com.botmaker.shared.emulator.tree.Trait;
import com.botmaker.shared.emulator.tree.UiNode;
import com.botmaker.shared.emulator.tree.UiTree;

import java.util.Optional;

/**
 * A screen of Android's add-a-Google-account flow, read from the accessibility tree. Google's pages are a WebView whose
 * HTML ids reach the tree ({@code identifierId}, {@code passwordNext}), so the email and password screens are read by id
 * in any language; the screens after them by their English words, and one BotMaker cannot read is {@link #UNKNOWN},
 * which the user answers. Captured 2026-11-12 on MuMu 12 (Android 15): the email screen, and its answer to an address
 * Google has no account for. The second-step screens (2026-11-14) are built from Google's ids and words, never captured:
 * they need a real account.
 */
public enum SignInScreen {
    /** No words on it yet: the page is loading. */
    LOADING("loading", "Loading", false),
    EMAIL("email", "Email", false),
    /** The email screen saying Google has no such account. */
    NO_SUCH_ACCOUNT("no_such_account", "No such account", false),
    PASSWORD("password", "Password", false),
    WRONG_PASSWORD("wrong_password", "Wrong password", false),
    /** "Choose how you want to sign in": the list "Try another way" opens. */
    CHALLENGES("challenges", "Ways to sign in", true),
    /** "Check your phone": Google's prompt, approved on the user's phone. */
    PHONE_TAP("phone_tap", "Approve on your phone", true),
    /** A field for a texted or authenticator code. */
    CODE("code", "Verification code", true),
    /** The field for one of the account's backup codes. */
    BACKUP_CODE("backup_code", "Backup code", true),
    /** Any other second step: a security key, "Verify it's you" with the password again. */
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

    private static final String[] CODE_IDS = {"totpPin", "idvPin", "idvPreregisteredPhonePin", "idvAnyPhonePin"};
    private static final String BACKUP_ID = "backupCodePin";

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

    /**
     * Whether it is the user's unless BotMaker follows the account's method: once BotMaker hands such a screen over,
     * it waits until a screen that isn't moves on.
     */
    public boolean needsYou() {
        return needsYou;
    }

    /** Whether it is a second step, past the password. */
    public boolean secondStep() {
        return this == CHALLENGES || this == PHONE_TAP || this == CODE || this == BACKUP_CODE || this == TWO_STEP;
    }

    /** Whether it is the screen of {@code method}: Check your phone for the phone prompt, a code's field for a code. */
    public boolean answers(Verification method) {
        return switch (this) {
            case PHONE_TAP -> method == Verification.PHONE;
            case CODE -> method == Verification.CODE;
            case BACKUP_CODE -> method == Verification.BACKUP_CODE;
            default -> false;
        };
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
        if (Screens.byId(tree, CODE_IDS).isPresent() || Screens.byId(tree, BACKUP_ID).isPresent()
                || Screens.shows(tree, "2-step verification", "verify it's you", "verify that it's you",
                "choose how you want to sign in", "check your phone")) {
            return secondStep(tree);
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

    /**
     * Which second step: Google's field ids first, as a code's screen may name the backup codes in a hint; then the
     * list, as its rows name every method's words; then the words.
     */
    private static SignInScreen secondStep(UiTree tree) {
        if (Screens.byId(tree, BACKUP_ID).isPresent()) return BACKUP_CODE;
        if (Screens.byId(tree, CODE_IDS).isPresent()) return CODE;
        if (Screens.shows(tree, "choose how you want to sign in")) return CHALLENGES;
        if (Screens.shows(tree, "backup code") && anyField(tree).isPresent()) return BACKUP_CODE;
        if (Screens.shows(tree, "enter the code", "verification code") && anyField(tree).isPresent()) return CODE;
        if (Screens.shows(tree, "check your phone", "google sent a notification", "tap yes")) return PHONE_TAP;
        return TWO_STEP;
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

    /** Where a code or a backup code is typed: Google's id for it, else the screen's one text field. */
    static Optional<UiNode> codeField(UiTree tree) {
        return Screens.byId(tree, CODE_IDS).or(() -> Screens.byId(tree, BACKUP_ID)).or(() -> anyField(tree));
    }

    private static Optional<UiNode> anyField(UiTree tree) {
        return tree.all().stream().filter(n -> n.className().endsWith("EditText") && !n.has(Trait.PASSWORD))
                .findFirst();
    }

    /** The second step's "Try another way", which opens the {@link #CHALLENGES} list. */
    static Optional<UiNode> anotherWay(UiTree tree) {
        return Screens.saying(tree, "Try another way", "More ways to verify");
    }

    /** The {@link #CHALLENGES} row of {@code method}, empty when Google doesn't offer it for this account. */
    static Optional<UiNode> row(UiTree tree, Verification method) {
        return switch (method) {
            case PHONE -> Screens.holding(tree, "tap yes on your phone", "get a notification", "tap yes");
            case CODE -> Screens.holding(tree, "google authenticator", "verification code", "get a code");
            case BACKUP_CODE -> Screens.holding(tree, "backup code");
            default -> Optional.empty();
        };
    }

    /** Whether Google says the code typed was wrong. */
    static boolean wrongCode(UiTree tree) {
        return Screens.shows(tree, "wrong code", "wrong number of digits", "code is incorrect", "code didn't work");
    }

    /** What moves {@code this} screen on, empty when nothing but Enter does. */
    Optional<UiNode> next(UiTree tree) {
        return switch (this) {
            case EMAIL -> Screens.byId(tree, "identifierNext").or(() -> Screens.saying(tree, "Next"));
            case PASSWORD -> Screens.byId(tree, "passwordNext").or(() -> Screens.saying(tree, "Next"));
            case CODE, BACKUP_CODE -> Screens.byId(tree, "totpNext", "idvPreregisteredPhoneNext",
                    "idvanyphoneverifyNext", "backupCodeNext").or(() -> Screens.saying(tree, "Next", "Verify"));
            case TERMS -> Screens.byId(tree, "signinconsentNext").or(() -> Screens.saying(tree, "I agree"));
            // "More" scrolls the page down until it turns into "Accept"
            case SERVICES -> Screens.saying(tree, "More").or(() -> Screens.saying(tree, "Accept"));
            case PHONE_PROMPT -> Screens.saying(tree, "Skip", "Not now");
            default -> Optional.empty();
        };
    }
}
