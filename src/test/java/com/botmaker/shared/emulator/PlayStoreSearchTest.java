package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Google Play's search page, cut down to the two shapes a result takes there (checked against the page Play served
 * for "clash of clans" on 2026-10-07): the top result, an empty link named by its {@code aria-label} with the icon
 * after it, and the list cards, whose link holds a screenshot, the icon and the name.
 */
class PlayStoreSearchTest {

    private static final String PAGE = """
            <div class="ipRz4"><a href="/store/apps/details?id=com.supercell.clashofclans" aria-label="Clash of Clans" \
            class="Qfxief"></a><div class="Y4A2mc"><div class="DxDVjd"><img src="https://play-lh.googleusercontent.com/\
            sFmW=s64-rw" class="T75of bzqKMd" alt="Icon image" /><div><div class="vWM94c">Clash of Clans</div>\
            <div class="LbQbAe">Supercell</div></div></div></div></div>
            <a class="Si6A0c Gy4nib" href="/store/apps/details?id=com.supercell.hayday" jslog="1"><div class="Vc0mnc">\
            <span class="zQIDqd"><img src="https://i.ytimg.com/vi/ieGB/hqdefault.jpg" alt="Screenshot image" /></span>\
            <div class="aCy7Gf"><button aria-label="Play Hay Day"><span class="notranslate"><svg viewBox="0 0 56 56">\
            <path d="M28"/></svg></span></button></div></div><div class="j2FCNc"><img src="https://play-lh.\
            googleusercontent.com/f_AN=s64-rw" srcset="https://play-lh.googleusercontent.com/f_AN=s128-rw 2x" \
            alt="Thumbnail image" /><div class="cXFu1"><div class="ubGTjb"><span class="DdYX5">Hay Day</span></div>\
            <div class="ubGTjb"><span class="wMUdtb">Supercell</span></div></div></div></a>
            <a class="Si6A0c" href="/store/apps/details?id=com.example.tom_and_jerry"><div><img \
            src="https://play-lh.googleusercontent.com/abc=w416-h235-rw" alt="Screenshot image" /></div><div>\
            <span class="DdYX5">Tom &amp; Jerry</span></div></a>
            <a href="/store/apps/details?id=com.supercell.clashofclans">Clash of Clans again</a>
            """;

    @Test
    void eachLinkedAppIsOneResultNamedAndWithItsSquareIcon() {
        assertEquals(List.of(
                new PlayStoreSearch.StoreApp("com.supercell.clashofclans", "Clash of Clans",
                        "https://play-lh.googleusercontent.com/sFmW=s128-rw"),
                new PlayStoreSearch.StoreApp("com.supercell.hayday", "Hay Day",
                        "https://play-lh.googleusercontent.com/f_AN=s128-rw"),
                new PlayStoreSearch.StoreApp("com.example.tom_and_jerry", "Tom & Jerry", null)),
                PlayStoreSearch.parse(PAGE), "a screenshot is not an icon, and an app linked twice is listed once");
        assertEquals(List.of(), PlayStoreSearch.parse("<html>no results</html>"));
    }

    @Test
    void aPastedPlayAddressOrATypedPackageNamesItsApp() {
        assertEquals(Optional.of("com.supercell.clashofclans"),
                PlayStoreSearch.packageIn("https://play.google.com/store/apps/details?id=com.supercell.clashofclans&hl=fr"));
        assertEquals(Optional.of("com.supercell.clashofclans"), PlayStoreSearch.packageIn(" com.supercell.clashofclans "));
        assertEquals(Optional.empty(), PlayStoreSearch.packageIn("clash of clans"));
        assertEquals(Optional.empty(), PlayStoreSearch.packageIn("clash"));
        assertEquals(Optional.empty(), PlayStoreSearch.packageIn("https://play.google.com/store/apps/details?id=x;rm"));
    }

    @Test
    void aTypedPackageIsFirstOnlyWhenPlayListsItOrAnAddressNamedIt() {
        var hayDay = new PlayStoreSearch.StoreApp("com.supercell.hayday", "Hay Day", null);
        var royale = new PlayStoreSearch.StoreApp("com.supercell.clashroyale", "Clash Royale", null);
        assertEquals(List.of(hayDay, royale),
                PlayStoreSearch.typedFirst(List.of(royale, hayDay), "com.supercell.hayday", false));
        assertEquals(List.of(royale), PlayStoreSearch.typedFirst(List.of(royale), "Dr.Stone", false),
                "a name with a dot is not offered as a package nobody lists");
        assertEquals(List.of(new PlayStoreSearch.StoreApp("com.YoStarEN.Arknights", "com.YoStarEN.Arknights", null),
                royale), PlayStoreSearch.typedFirst(List.of(royale), "com.YoStarEN.Arknights", true));
    }

    @Test
    void theStoreIsOpenedOnTheAppsPageInGooglePlay() {
        assertEquals("am start -a android.intent.action.VIEW -d 'market://details?id=com.supercell.hayday' "
                + "-p com.android.vending", EmulatorInstall.storeIntent("com.supercell.hayday"));
    }
}
