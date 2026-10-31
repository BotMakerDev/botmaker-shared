package com.botmaker.shared.emulator.tree;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.Rectangle;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link UiTree} on a synthetic two-pane settings screen ({@code tree/settings-two-pane.xml}, no device content). */
class UiTreeTest {

    static String fixture(String name) {
        try (InputStream in = UiTreeTest.class.getResourceAsStream("/tree/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final UiTree SETTINGS = UiTree.parse(fixture("settings-two-pane.xml"));

    private static UiNode first(UiTree tree, String text) {
        return tree.all().stream().filter(n -> n.text().equals(text)).findFirst().orElseThrow();
    }

    @Test
    void parseReadsBoundsTraitsAndChildren() {
        assertEquals(2, SETTINGS.roots().size(), "one root per window");
        UiNode app = SETTINGS.roots().get(1);
        assertEquals(2, app.children().size());

        UiNode title = app.children().getFirst().children().getFirst().children().getFirst();
        assertEquals("Network & internet", title.text());
        assertEquals(new Rectangle(108, 138, 192, 32), title.bounds());
        assertEquals("android.widget.TextView", title.className());
        assertEquals("com.example.settings", title.packageName());
        assertEquals(Set.of(Trait.ENABLED), title.traits());

        UiNode wifi = SETTINGS.all().stream().filter(n -> n.label().equals("Wi‑Fi")).findFirst().orElseThrow();
        assertEquals(Set.of(Trait.CHECKABLE, Trait.CHECKED, Trait.CLICKABLE, Trait.LONG_CLICKABLE, Trait.ENABLED,
                Trait.FOCUSED), wifi.traits());
        assertTrue(wifi.has(Trait.CHECKED));

        UiNode search = SETTINGS.all().stream().filter(n -> n.shortId().equals("search")).findFirst().orElseThrow();
        assertTrue(search.has(Trait.PASSWORD));
        assertFalse(search.has(Trait.ENABLED));

        assertEquals(2, SETTINGS.all().stream().filter(n -> n.has(Trait.SCROLLABLE)).count());
        assertEquals(14, SETTINGS.all().stream().filter(n -> n.id().equals("android:id/title")).count());
    }

    @Test
    void shortIdDropsPackage() {
        UiNode title = first(SETTINGS, "Battery");
        assertEquals("android:id/title", title.id());
        assertEquals("title", title.shortId());
        UiNode pane = SETTINGS.all().stream().filter(n -> n.id().endsWith("list_pane")).findFirst().orElseThrow();
        assertEquals("list_pane", pane.shortId());
        assertEquals("", SETTINGS.roots().get(1).shortId(), "no id is an empty one");
    }

    @Test
    void appKeepsOnlyFocusedPackage() {
        UiTree app = SETTINGS.app("com.example.settings");
        assertEquals(1, app.roots().size());
        assertTrue(app.all().stream().noneMatch(n -> n.packageName().equals("com.android.systemui")));
        assertEquals(2, app.all().stream().filter(n -> n.text().equals("Network & internet")).count(),
                "both panes keep theirs; only the status bar's is gone");
        assertTrue(SETTINGS.app("com.other").roots().isEmpty());
    }

    @Test
    void atReturnsSmallestFirst() {
        List<UiNode> under = SETTINGS.app("com.example.settings").at(new Point(150, 150));
        assertEquals(List.of("Network & internet", "", "", ""), under.stream().map(UiNode::text).toList());
        assertEquals(new Rectangle(108, 138, 192, 32), under.getFirst().bounds());
        assertEquals(new Rectangle(0, 100, 700, 70), under.get(1).bounds(), "then the row holding it");
        assertTrue(SETTINGS.at(new Point(5000, 5000)).isEmpty());
    }

    @Test
    void parentOfWalksUpByIdentity() {
        UiNode title = first(SETTINGS, "Battery");
        UiNode pane = SETTINGS.parentOf(title);
        assertEquals("list_pane", pane.shortId());
        assertSame(SETTINGS.roots().get(1), SETTINGS.parentOf(pane));
        assertNull(SETTINGS.parentOf(SETTINGS.roots().getFirst()), "a root has no parent");
    }

    @Test
    void parseSurvivesEntitiesAndEmptyAttributes() {
        UiTree tree = UiTree.parse("""
                <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
                <hierarchy rotation="0"><node text="Tom &amp; Jerry &#233;" resource-id="" content-desc="" \
                class="android.view.View" package="p" bounds="[0,0][10,10]"><node text="" bounds="nonsense" />\
                </node></hierarchy>UI hierchary dumped to: /dev/tty""");
        UiNode root = tree.roots().getFirst();
        assertEquals("Tom & Jerry é", root.text());
        UiNode child = root.children().getFirst();
        assertEquals("", child.text());
        assertEquals("", child.id());
        assertEquals("", child.label());
        assertEquals("", child.className());
        assertEquals("", child.packageName());
        assertEquals(new Rectangle(), child.bounds(), "unreadable bounds are empty, never a throw");
        assertTrue(child.traits().isEmpty());
    }

    @Test
    void parseRefusesWhatIsNoHierarchy() {
        assertThrows(IllegalArgumentException.class, () -> UiTree.parse("<html/>"));
        assertThrows(IllegalArgumentException.class, () -> UiTree.parse("not xml"));
    }

    @Test
    void boundsCannotBeChangedFromOutside() {
        UiNode title = first(SETTINGS, "Battery");
        title.bounds().translate(50, 50);
        assertEquals(new Rectangle(108, 306, 192, 32), title.bounds());
    }
}
