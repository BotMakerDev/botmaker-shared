// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import com.botmaker.shared.emulator.tree.Trait;
import com.botmaker.shared.emulator.tree.UiNode;
import com.botmaker.shared.emulator.tree.UiTree;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.Rectangle;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The page's tree from a recorded {@code Accessibility.getFullAXTree} answer and the boxes measured for it. */
class WebTreeTest {

    private static JsonNode recorded() throws Exception {
        try (InputStream in = WebTreeTest.class.getResourceAsStream("/web/ax-home.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    private static WebTree tree() throws Exception {
        return WebTree.from(recorded(), Map.of(
                5, new WebTree.Extra(new Rectangle(10, 20, 100, 24), Map.of("href", "/case-studies", "id", "nav-cases")),
                12, new WebTree.Extra(new Rectangle(600, 400, 160, 48), Map.of("data-testid", "cta-start")),
                7, new WebTree.Extra(null, Map.of())));
    }

    @Test
    void ignoredNodesAndInlineTextBoxesAreLeftOutAndTheirChildrenKept() throws Exception {
        WebTree tree = tree();
        WebNode root = tree.root().orElseThrow();
        assertEquals(Role.ROOT_WEB_AREA, root.role());
        assertEquals(-1, root.parent());
        // The ignored node 2 is gone: banner, main and footer are the page's own children.
        assertEquals(List.of(Role.BANNER, Role.MAIN, Role.CONTENT_INFO),
                tree.children(root).stream().map(WebNode::role).toList());
        assertTrue(tree.nodes().stream().noneMatch(n -> n.name().equals("Case studies") && n.role() == Role.UNKNOWN));
        WebNode text = tree.nodes().stream().filter(n -> n.role() == Role.STATIC_TEXT).findFirst().orElseThrow();
        assertTrue(tree.children(text).isEmpty());
        for (WebNode node : tree.nodes()) {
            assertEquals(node, tree.nodes().get(node.index()));
            node.children().forEach(child -> assertEquals(node.index(), tree.nodes().get(child).parent()));
        }
    }

    @Test
    void rolesNamesValuesAndStatesAreRead() throws Exception {
        WebTree tree = tree();
        WebNode start = tree.find(Role.BUTTON, "get started ").getFirst();
        assertEquals(Set.of(WebState.DISABLED), start.states());
        WebNode email = tree.find(Role.TEXTBOX, "Email").getFirst();
        assertEquals("nobody", email.value());
        assertEquals(Set.of(WebState.REQUIRED, WebState.INVALID), email.states());
        assertEquals(Set.of(WebState.CHECKED), tree.find(Role.CHECKBOX, "Remember me").getFirst().states());
        assertEquals(Set.of(WebState.SELECTED), tree.find(Role.TAB, "Pricing").getFirst().states());
        assertTrue(tree.find(Role.BUTTON, "Talk to us").getFirst().states().isEmpty());
        assertEquals(1, tree.find(Role.IMAGE, "Logo").size(), "img is the older name of image");
        assertEquals(1, tree.nodes().stream().filter(n -> n.role() == Role.UNKNOWN).count(), "blockquote");
    }

    @Test
    void extrasGiveBoxesAndLocatorAttributes() throws Exception {
        WebTree tree = tree();
        WebNode cases = tree.find(Role.LINK, "Case studies").getFirst();
        assertEquals("http://fixture.test/case-studies", cases.href(), "the AX url wins over the raw attribute");
        assertEquals("nav-cases", cases.domId());
        assertEquals(new Rectangle(10, 20, 100, 24), cases.box());
        WebNode start = tree.find(Role.BUTTON, "Get started").getFirst();
        assertEquals("cta-start", start.testId());
        assertEquals(12, start.backendId());
        assertTrue(start.laidOut());
        assertFalse(tree.find(Role.BUTTON, "Talk to us").getFirst().laidOut());
    }

    @Test
    void asAUiTreeEachNodeKeepsItsRoleNameIdsBoxAndTraits() throws Exception {
        WebTree web = tree();
        UiTree tree = web.asUiTree();
        assertEquals(web.nodes().size(), tree.all().size());
        UiNode cases = tree.all().stream().filter(n -> n.label().equals("Case studies")
                && n.className().equals("web:link")).findFirst().orElseThrow();
        assertEquals("nav-cases", cases.id());
        assertEquals("Case studies", cases.text());
        assertEquals(new Rectangle(10, 20, 100, 24), cases.bounds());
        assertTrue(cases.has(Trait.CLICKABLE) && cases.has(Trait.ENABLED));
        assertEquals(Optional.of(Role.LINK), WebTree.roleOf(cases.className()));
        UiNode start = tree.all().stream().filter(n -> n.id().equals("cta-start")).findFirst().orElseThrow();
        assertFalse(start.has(Trait.CLICKABLE), "disabled");
        assertFalse(start.has(Trait.ENABLED));
        UiNode email = tree.all().stream().filter(n -> n.label().equals("Email")).findFirst().orElseThrow();
        assertEquals("nobody", email.text(), "a field's text is its value");
        assertTrue(tree.all().stream().filter(n -> n.label().equals("Remember me")).findFirst().orElseThrow()
                .has(Trait.CHECKED));
        assertEquals(Optional.empty(), WebTree.roleOf("android.widget.Button"));
        assertEquals(Optional.of(web.find(Role.LINK, "Case studies").getFirst()), web.pressedAt(new Point(50, 30)));
        assertEquals(Optional.empty(), web.pressedAt(new Point(5, 5)));
    }

    @Test
    void interactiveNodesAreThePagesInputsInTreeOrder() throws Exception {
        assertEquals(List.of("Case studies", "Talk to us", "Get started", "Email", "Remember me", "Pricing"),
                tree().interactive().stream().map(WebNode::name).toList());
    }

    @Test
    void theOutlineIndentsByDepthAndLeavesOutText() throws Exception {
        String outline = tree().outline();
        assertTrue(outline.startsWith("RootWebArea \"Fixture home\"\n  banner\n    navigation \"Main\"\n"), outline);
        assertTrue(outline.contains("      link \"Case studies\" -> http://fixture.test/case-studies #nav-cases\n"),
                outline);
        assertTrue(outline.contains("    button \"Get started\" [disabled] testid=cta-start\n"), outline);
        assertTrue(outline.contains("    textbox \"Email\" = \"nobody\" [invalid] [required]\n"), outline);
        assertFalse(outline.contains("StaticText"), outline);
    }

    @Test
    void anEmptyAnswerIsAnEmptyTree() throws Exception {
        assertTrue(WebTree.from(new ObjectMapper().readTree("{\"nodes\":[]}"), Map.of()).nodes().isEmpty());
    }

    @Test
    void aQuadsBoundingBoxAndFlatAttributesAreRead() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertEquals(new Rectangle(10, 20, 30, 40),
                WebPage.rectangle(json.readTree("[10,20, 40,20, 40,60, 10,60]")));
        assertEquals(Map.of("id", "x", "class", "a b"),
                WebPage.attributes(json.readTree("[\"id\",\"x\",\"class\",\"a b\"]")));
    }

    @Test
    void rolesAndStatesParseTotally() {
        assertEquals(Role.UNKNOWN, Role.fromId(null));
        assertEquals(Role.UNKNOWN, Role.fromId("somethingNew"));
        assertEquals(Role.MENU_ITEM, Role.fromId("menuitem"));
        assertEquals(WebState.UNKNOWN, WebState.fromId("focusable"));
        assertEquals(ConsoleMessage.Level.WARNING, ConsoleMessage.Level.fromId("warn"));
        assertEquals(ConsoleMessage.Level.UNKNOWN, ConsoleMessage.Level.fromId("loud"));
    }
}
