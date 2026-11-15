package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.config.AccountRecord.Verification;
import com.botmaker.shared.emulator.tree.Trait;
import com.botmaker.shared.emulator.tree.UiNode;
import com.botmaker.shared.emulator.tree.UiTree;

import java.util.List;
import java.util.Optional;

/**
 * A screen of Android's add-a-Google-account flow, read from the accessibility tree. Google's pages are a WebView whose
 * HTML ids reach the tree ({@code identifierId}, {@code passwordNext}), so the email and password screens are read by id
 * in any language; the screens after them by their English words, and one BotMaker cannot read is {@link #UNKNOWN},
 * which the user answers. Captured 2026-11-12 on MuMu 12 (Android 15): the email screen, and its answer to an address
 * Google has no account for. Captured 2026-11-15 on LDPlayer 14 (Android 14): the email screen with no HTML id at
 * all, read by its one field and its English words, and moved on by a native NEXT. The second-step screens
 * (2026-11-14) are built from Google's ids and words, never captured:
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
    /**
     * "Use your passkey to confirm it's really you" (seen live 2026-11-14): no emulator has the user's passkey, so
     * BotMaker answers it with "Try another way".
     */
    PASSKEY("passkey", "Passkey", true),
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
        return this == CHALLENGES || this == PHONE_TAP || this == CODE || this == BACKUP_CODE || this == PASSKEY
                || this == TWO_STEP;
    }

    /** Whether it is the screen of {@code method}: Check your phone for the phone prompt, a code's field for a code. */
    public boolean answers(Verification method) {
        return switch (this) {
            case PHONE_TAP -> method == Verification.PHONE;
            case CODE -> method == Verification.CODE || method == Verification.AUTHENTICATOR;
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
        if (Screens.byId(tree, "identifierId").isPresent()) return email(tree);
        if (passwordField(tree).isPresent()) {
            if (Screens.shows(tree, "wrong password")) return WRONG_PASSWORD;
            // Google asking the password again to confirm it's the user is theirs: BotMaker types it once only
            return Screens.shows(tree, "verify it's you", "verify that it's you", "confirm it's you") ? TWO_STEP
                    : PASSWORD;
        }
        if (Screens.byId(tree, CODE_IDS).isPresent() || Screens.byId(tree, BACKUP_ID).isPresent()
                || Screens.shows(tree, "2-step verification", "verify it's you", "verify that it's you",
                "choose how you want to sign in", "check your phone") || passkey(tree) || ways(tree) >= 2) {
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
        // the email page by its words, last: on a page with no HTML id they are all there is to read
        return emailField(tree).isPresent() ? email(tree) : UNKNOWN;
    }

    private static SignInScreen email(UiTree tree) {
        return Screens.shows(tree, "couldn't find", "enter a valid email") ? NO_SUCH_ACCOUNT : EMAIL;
    }

    /**
     * Which second step: Google's field ids first, as a code's screen may name the backup codes in a hint; then the
     * list, as its rows name every method's words; then the words.
     */
    private static SignInScreen secondStep(UiTree tree) {
        if (Screens.byId(tree, BACKUP_ID).isPresent()) return BACKUP_CODE;
        if (Screens.byId(tree, CODE_IDS).isPresent()) return CODE;
        // the phone's prompt by its heading first: its page may also offer two ways, and is not the list
        if (Screens.shows(tree, "check your phone", "google sent a notification")) return PHONE_TAP;
        // the list, by its heading or by two ways or more on it, whatever Google heads it with
        if (Screens.shows(tree, "choose how you want to sign in") || ways(tree) >= 2) return CHALLENGES;
        if (Screens.shows(tree, "backup code") && anyField(tree).isPresent()) return BACKUP_CODE;
        if (Screens.shows(tree, "enter the code", "verification code") && anyField(tree).isPresent()) return CODE;
        if (passkey(tree)) return PASSKEY;
        if (Screens.shows(tree, "tap yes")) return PHONE_TAP;
        return TWO_STEP;
    }

    /** Google's passkey prompt, by its heading or by what it says the device will ask. */
    private static boolean passkey(UiTree tree) {
        return Screens.shows(tree, "use your passkey to", "will ask for your fingerprint, face, or screen lock");
    }

    /** The words of each way the list offers, one group a way. */
    private static final String[][] WAYS = {
            {"tap yes on your phone", "get a notification"},
            {"google authenticator", "get a verification code", "get a code"},
            {"backup code"},
            {"use your passkey"},
            {"security key"},
            {"enter your password"},
            {"use another phone or computer"},
    };

    /** How many of the list's ways {@code tree} shows. */
    private static int ways(UiTree tree) {
        int ways = 0;
        for (String[] words : WAYS) if (Screens.shows(tree, words)) ways++;
        return ways;
    }

    /**
     * Where the email is typed: Google's id for it, else, where the WebView gives the tree no HTML id (LDPlayer 14,
     * Android 14, 2026-11-15), the one text field of a page offering "Forgot email?" or "Create account".
     */
    static Optional<UiNode> emailField(UiTree tree) {
        return Screens.byId(tree, "identifierId").or(() -> Screens.shows(tree, "forgot email", "create account")
                ? onlyField(tree) : Optional.empty());
    }

    /** The screen's text field when it has exactly one that is not a password's. */
    private static Optional<UiNode> onlyField(UiTree tree) {
        List<UiNode> fields = tree.all().stream()
                .filter(n -> n.className().endsWith("EditText") && !n.has(Trait.PASSWORD)).toList();
        return fields.size() == 1 ? Optional.of(fields.getFirst()) : Optional.empty();
    }

    /** Where the password is typed: the field Android marks a password, else Google's own id for it. */
    static Optional<UiNode> passwordField(UiTree tree) {
        Optional<UiNode> marked = tree.all().stream().filter(n -> n.has(Trait.PASSWORD)).findFirst();
        return marked.isPresent() ? marked : Screens.byId(tree, "Passwd");
    }

    /**
     * Whether a {@link #CODE} screen asks the authenticator app's code (its field {@code totpPin}, or its words), not a
     * texted one: only there does a code made from the account's key answer.
     */
    static boolean authenticatorCode(UiTree tree) {
        return Screens.byId(tree, "totpPin").isPresent() || Screens.shows(tree, "authenticator app",
                "google authenticator");
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
        Optional<UiNode> words = switch (method) {
            case PHONE -> Screens.holding(tree, "tap yes on your phone", "get a notification", "tap yes");
            case CODE -> Screens.holding(tree, "google authenticator", "verification code", "get a code");
            // "Get a verification code from the Google Authenticator app": not a texted code
            case AUTHENTICATOR -> Screens.holding(tree, "google authenticator", "authenticator app");
            case BACKUP_CODE -> Screens.holding(tree, "backup code");
            default -> Optional.empty();
        };
        return words.map(node -> clickable(tree, node));
    }

    /**
     * What a tap on {@code node} should hit: the clickable row around its words, a few levels up at most and no taller
     * than a row, else the words themselves. A tap on a row's words alone didn't move Google's list on (2026-11-14).
     */
    private static UiNode clickable(UiTree tree, UiNode node) {
        UiNode at = node;
        for (int up = 0; up < 4 && at != null; up++) {
            if (at.has(Trait.CLICKABLE)) {
                return at.bounds().height <= node.bounds().height * 4 ? at : node;
            }
            at = tree.parentOf(at);
        }
        return node;
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
