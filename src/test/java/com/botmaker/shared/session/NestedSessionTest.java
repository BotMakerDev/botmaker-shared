package com.botmaker.shared.session;

import com.botmaker.shared.launch.LaunchSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
