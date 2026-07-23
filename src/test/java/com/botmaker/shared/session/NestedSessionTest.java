package com.botmaker.shared.session;

import com.botmaker.shared.launch.LaunchSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the parts of {@link NestedSession} that don't need a live X server: the launch-kind → argv
 * mapping (which kinds can be handed a private {@code DISPLAY}) and the immutable {@link NestedSession.Options}
 * builder. The end-to-end supervisor behaviour (Xephyr allocation, XTest isolation, tree reaping) is verified
 * by the manual live run recorded in the ROADMAP, not here — it needs Xephyr, which CI does not provide.
 */
class NestedSessionTest {

	@Test
	void exeAndCliLaunchAsOwnChildrenWithSplitArgs() {
		assertEquals(List.of("/opt/game/run.sh"),
			NestedSession.commandFor(LaunchSpec.parse("exe:/opt/game/run.sh")));
		assertEquals(List.of("xterm", "-e", "sleep", "300"),
			NestedSession.commandFor(LaunchSpec.parse("cli:xterm -e sleep 300")));
	}

	@Test
	void storeLauncherKindsHaveNoNestedCommand() {
		// steam:/heroic:/epic: hand off to a daemon on :0; they can't be given a private DISPLAY (deferred).
		assertTrue(NestedSession.commandFor(LaunchSpec.parse("steam:570")).isEmpty());
		assertTrue(NestedSession.commandFor(LaunchSpec.parse("heroic:AbC123")).isEmpty());
		assertTrue(NestedSession.commandFor(LaunchSpec.parse("epic:Fortnite")).isEmpty());
	}

	@Test
	void optionsAreImmutableAndCarryTheirConfig() {
		NestedSession.Options base = NestedSession.Options.xephyr(1280, 720);
		assertEquals(1280, base.width());
		assertEquals(720, base.height());
		assertTrue(base.windowManagerCommand().isEmpty());
		assertTrue(base.extraEnv().isEmpty());

		NestedSession.Options withWm = base.withWindowManager("openbox", "--sm-disable");
		assertEquals(List.of("openbox", "--sm-disable"), withWm.windowManagerCommand());
		// The builder returns a new value; the base is untouched.
		assertTrue(base.windowManagerCommand().isEmpty());

		NestedSession.Options withEnv = base.withExtraEnv(Map.of("WINEPREFIX", "/tmp/pfx"));
		assertEquals("/tmp/pfx", withEnv.extraEnv().get("WINEPREFIX"));
		// Defensive copy: mutating the returned view is refused.
		assertThrows(UnsupportedOperationException.class,
			() -> withEnv.extraEnv().put("X", "y"));
	}

	@Test
	void backendPicksTheDisplayServer() {
		NestedSession.Options xephyr = NestedSession.Options.xephyr(1280, 720);
		assertEquals(NestedSession.Backend.XEPHYR, xephyr.backend());

		NestedSession.Options gs = NestedSession.Options.gamescope(1920, 1080);
		assertEquals(NestedSession.Backend.GAMESCOPE, gs.backend());
		// Default gamescope argv carries the requested size and is standalone (no "--" child).
		assertEquals(List.of("gamescope", "-W", "1920", "-H", "1080"), gs.displayServerCommand());
		assertTrue(gs.displayServerCommand().stream().noneMatch("--"::equals));

		// An explicit override wins over the default argv.
		NestedSession.Options custom = gs.withGamescopeCommand("gamescope", "--backend", "sdl", "-W", "800", "-H", "600");
		assertEquals(List.of("gamescope", "--backend", "sdl", "-W", "800", "-H", "600"),
			custom.displayServerCommand());
	}

	@Test
	void gamescopeDisplayNumberParsedFromStderrBanner() {
		// The forms gamescope prints across versions (some prefix wlserver:, casing varies).
		assertEquals(":1", GamescopeDisplay.parseDisplayNumber(
			"wlserver: [xwayland/server.c:1146] Starting Xwayland on :1"));
		assertEquals(":2", GamescopeDisplay.parseDisplayNumber(
			"gamescope: starting\nStarting Xwayland on :2, DISPLAY=:2\nmore log"));
		// Not announced yet / unrelated output → no number.
		assertNull(GamescopeDisplay.parseDisplayNumber(""));
		assertNull(GamescopeDisplay.parseDisplayNumber(null));
		assertNull(GamescopeDisplay.parseDisplayNumber("gamescope: creating nested compositor\n"));
	}
}
