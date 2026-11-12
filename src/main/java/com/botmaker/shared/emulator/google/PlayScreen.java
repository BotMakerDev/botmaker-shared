package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.tree.UiNode;
import com.botmaker.shared.emulator.tree.UiTree;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What the Play app shows on a game's page, read from its own windows only, by the words on its buttons in English and
 * French (the emulators here run either). The only button BotMaker ever presses is one that says exactly
 * <em>Install</em>; a page that shows a price, <em>Buy</em> or <em>Pre-register</em> anywhere is {@link #PRICED} or
 * {@link #PRE_REGISTER} and nothing on it is pressed, whatever else it shows (2026-11-12: never spend money).
 */
public enum PlayScreen {
    LOADING("loading", "Loading"),
    /** The game's page, free, with Install. */
    INSTALL("install", "Install"),
    /** Play is downloading or installing it: Cancel, or a percentage. */
    INSTALLING("installing", "Installing"),
    /** Open, Play or Uninstall: it is on the instance. */
    INSTALLED("installed", "Installed"),
    /** A price or Buy: never pressed. */
    PRICED("priced", "A paid app"),
    /** Pre-register: the game isn't out; never pressed. */
    PRE_REGISTER("pre_register", "Pre-registration"),
    /** Play asks to sign in. */
    SIGNED_OUT("signed_out", "Play asks to sign in"),
    UNKNOWN("unknown", "A screen BotMaker doesn't know");

    static final String PLAY = "com.android.vending";

    private static final String[] INSTALL_WORDS = {"Install", "Installer"};
    private static final String[] BUY_WORDS = {"Buy", "Acheter"};
    private static final String[] PRE_REGISTER_WORDS = {"Pre-register", "Préinscription", "Pré-inscription",
            "S'inscrire à l'avance"};
    private static final String[] CANCEL_WORDS = {"Cancel", "Annuler"};
    private static final String[] INSTALLED_WORDS = {"Open", "Play", "Uninstall", "Ouvrir", "Jouer", "Désinstaller"};
    private static final String[] SIGN_IN_WORDS = {"Sign in", "Se connecter"};
    /** A price on its own: {@code $4.99}, {@code 1,99 €}, {@code US$0.99}, {@code 4,99 zł}. */
    private static final Pattern PRICE = Pattern.compile(
            "(?:[a-z]{0,2}[$€£¥₩₹]|r\\$)\\s?\\d[\\d.,\\s]*|\\d[\\d.,\\s]*\\s?(?:[$€£¥₩₹]|zł|kr|₽)");
    private static final Pattern PERCENT = Pattern.compile("\\d{1,3}\\s?%");

    private final String id;
    private final String displayName;

    PlayScreen(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public static PlayScreen fromId(String id) {
        for (PlayScreen screen : values()) {
            if (screen.id.equals(id)) return screen;
        }
        return UNKNOWN;
    }

    /** The screen {@code tree} shows, the Play app's windows only. */
    public static PlayScreen of(UiTree tree) {
        UiTree play = tree == null ? null : tree.app(PLAY);
        if (play == null || play.all().isEmpty() || Screens.blank(play)) return LOADING;
        // money first: nothing on a page that asks for some is pressed
        if (priced(play)) return PRICED;
        if (Screens.saying(play, PRE_REGISTER_WORDS).isPresent()) return PRE_REGISTER;
        // Install before the download's signs: a "30%" card or a dialog's Cancel beside it is not a download
        if (installButton(play).isPresent()) return INSTALL;
        if (Screens.saying(play, CANCEL_WORDS).isPresent() || play.all().stream()
                .anyMatch(n -> PERCENT.matcher(Screens.plain(n.text())).matches())) {
            return INSTALLING;
        }
        if (Screens.saying(play, INSTALLED_WORDS).isPresent()) return INSTALLED;
        if (Screens.saying(play, SIGN_IN_WORDS).isPresent()) return SIGNED_OUT;
        return UNKNOWN;
    }

    /** The button that says exactly Install, on {@link #INSTALL} only: the one thing BotMaker presses. */
    Optional<UiNode> next(UiTree tree) {
        return this == INSTALL ? installButton(tree.app(PLAY)) : Optional.empty();
    }

    private static Optional<UiNode> installButton(UiTree play) {
        return Screens.saying(play, INSTALL_WORDS);
    }

    static boolean priced(UiTree play) {
        if (Screens.saying(play, BUY_WORDS).isPresent()) return true;
        return play.all().stream().anyMatch(n -> PRICE.matcher(Screens.plain(n.text())).matches()
                || PRICE.matcher(Screens.plain(n.label())).matches());
    }
}
