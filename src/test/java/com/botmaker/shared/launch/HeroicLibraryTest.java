package com.botmaker.shared.launch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link HeroicLibrary}'s hand-rolled JSON scanning against the shapes Heroic actually writes.
 *
 * <p>The scanner exists because shared has no Jackson, so the usual safety net — a parser someone else
 * maintains — isn't there. These fixtures are trimmed copies of the real files, keeping the parts that would
 * break a naive regex: nested objects, escaped Windows path separators, braces inside a title, and fields in an
 * order the reader must not depend on.
 */
class HeroicLibraryTest {

	@Test
	void readsEpicInstallRecords() {
		String json = """
			{
			  "43d4ef20fcb94eb39a864d13164fe3ca": {
			    "app_name": "43d4ef20fcb94eb39a864d13164fe3ca",
			    "title": "Rocket League",
			    "install_path": "/home/u/Games/Heroic/Rocket League",
			    "executable": "Binaries/Win64/RocketLeague.exe",
			    "install_size": 12345
			  },
			  "Sunbreak": {
			    "title": "Monster Hunter {Rise}",
			    "app_name": "Sunbreak",
			    "install_path": "C:\\\\Games\\\\MHR",
			    "executable": "MonsterHunterRise.exe"
			  }
			}
			""";

		List<HeroicLibrary.Game> games = HeroicLibrary.parseEpicForTesting(json);

		assertEquals(2, games.size());
		HeroicLibrary.Game rl = games.get(0);
		assertEquals("43d4ef20fcb94eb39a864d13164fe3ca", rl.appName());
		assertEquals("Rocket League", rl.title());
		assertEquals("/home/u/Games/Heroic/Rocket League", rl.installPath());
		assertEquals("Binaries/Win64/RocketLeague.exe", rl.executable());

		HeroicLibrary.Game mhr = games.get(1);
		assertEquals("Monster Hunter {Rise}", mhr.title(), "a brace in a title must not unbalance the scan");
		assertEquals("C:\\Games\\MHR", mhr.installPath(), "escaped separators must be unescaped");
	}

	@Test
	void sideloadedEntriesSurviveNestedObjects() {
		String json = """
			{
			  "games": [
			    {
			      "app_name": "side-1",
			      "title": "My Game",
			      "install": {"executable": "/opt/mygame/run.sh", "platform": "linux"}
			    },
			    {"appName": "side-2", "title": "Other"}
			  ]
			}
			""";

		List<HeroicLibrary.Game> games = HeroicLibrary.parseSideloadedForTesting(json);

		assertEquals(2, games.size());
		assertEquals("side-1", games.get(0).appName());
		assertEquals("/opt/mygame/run.sh", games.get(0).executable(),
			"a field nested one level down still belongs to that entry");
		assertEquals("side-2", games.get(1).appName());
		assertEquals("Other", games.get(1).title());
	}

	@Test
	void runningTokensLeadWithTheExecutableAndPath() {
		HeroicLibrary.Game game = new HeroicLibrary.Game(
			"43d4ef20fcb94eb39a864d13164fe3ca", "Rocket League",
			"/home/u/Games/Heroic/Rocket League", "Binaries/Win64/RocketLeague.exe");

		assertEquals(List.of("RocketLeague.exe", "/home/u/Games/Heroic/Rocket League",
				"43d4ef20fcb94eb39a864d13164fe3ca", "Rocket League"),
			game.runningTokens());
	}

	@Test
	void runningTokensDropBlanksAndTooShortStrings() {
		// "Go" is a real title and a catastrophic process-table needle; the app name carries the detection.
		HeroicLibrary.Game game = new HeroicLibrary.Game("go-app-name", "Go", "", "");

		assertEquals(List.of("go-app-name"), game.runningTokens());
	}

	@Test
	void runningTokensDeduplicateIgnoringCase() {
		HeroicLibrary.Game game = new HeroicLibrary.Game("Celeste", "celeste", "", "Celeste");

		assertEquals(List.of("Celeste"), game.runningTokens());
	}

	@Test
	void unknownAppNameFallsBackToTheAppNameItself() {
		HeroicLibrary.invalidate();
		assertEquals(List.of("not-installed-anywhere"), HeroicLibrary.runningTokens("not-installed-anywhere"));
		assertTrue(HeroicLibrary.runningTokens("  ").isEmpty());
		assertTrue(HeroicLibrary.runningTokens(null).isEmpty());
	}
}
