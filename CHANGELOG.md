# Changelog

What each released version of `botmaker-shared` changes, in a few bullets. `ROADMAP.md` stays the detailed
engineering log; this is the short answer, and it is what `release.sh` publishes as the GitHub Release body.

**`release.sh` refuses to cut a version with no section here** (`check_changelog`, decide pass, before
anything is tagged). If the top section still says `## [Unreleased]`, rename it to the version being cut and
date it.

No bot ever names shared directly — it is a transitive dependency of the SDK — so this file is written for
whoever is debugging a capture, a launch or an OCR result, not for a bot author.

Sections are `## [x.y.z] — YYYY-MM-DD`, newest first.

## [Unreleased]

### Added

- **`com.botmaker.shared.config.Settings` — how a bot reads its own parameters, for every plugin rather than
  for one.** Three classes, no new dependency (`jackson-databind` was already here):

  ```java
  Duration   wait = Settings.load("wait", Duration.class);
  int      health = Settings.load("minHealth", int.class);
  List<Rect> zones = Settings.loadAll("zones", Rect.class);
  boolean       on = Settings.enabled("Mining");
  ```

  `ProjectValues` is the untyped store over `activities.json` — a name in, stored text out, nothing that
  knows what a value means. `ValueGrammar` is `Class<T> → parse/store/fallback`, found by `ServiceLoader`.
  `Settings` resolves one against the other.

  **Why here and not in the SDK or the contract.** Of the five stages in a value's life, four were already
  plugin-general — declaring, editing, writing and reading it in an *editor* are all contract types. The
  fifth, reading it in a **running bot**, was `com.botmaker.sdk.api.config.Wire` and was plugin #1's alone.
  It could not move to the contract, because a bot's classpath does not have the contract on it — the SDK
  declares it `provided` deliberately. `botmaker-shared` is the only published module on **both** a bot's and
  a plugin's classpath, with no JavaFX and no contract types. The placement is forced, not chosen.

  **`activities.json` still has one owner and it is still the SDK.** What moved is the untyped key lookup,
  which has no schema in it. The model records, the flow's meaning and every rule about what an activity is
  stay in `com.botmaker.sdk.authoring`; `ProjectData` keeps the flow half and delegates the rest. Same line
  the GitHub layer moved on — *shared owns the request, not what the JSON means*.

  **Shared ships no grammar at all, and that is deliberate.** A vocabulary belongs to whoever introduced it,
  so the SDK will supply one wrapping `WireText` — the parsers that already exist and already serve the
  editor through `SdkValueTypes`. Putting "the obvious JDK ones" here would create a second `Duration` parser
  beside the SDK's, which is the drift the design exists to prevent.

  Every read is total: an undeclared name, text that will not parse, a name declared as another type and a
  missing file all answer the type's own fallback, so *enforce a default for every type* is a property of the
  grammar rather than a rule to remember per call site. **One thing throws** — a type no grammar on the
  classpath claims, which is a packaging mistake rather than a bad file, and has no value to fall back to.
  Two grammars claiming one type is refused by name rather than resolved by jar order.

  `ProjectValues.typeId` reads **both shapes the file has**: the object the editor writes
  (`"type": {"type": "WHOLE_NUMBER", "shape": "ONE", …}`, because a declared type carries its shape) and the
  bare string a hand-written or older file holds. It landed reading only the second, which is the one no real
  project contains.

- **`com.botmaker.shared.github` — the GitHub layer.** `GitHubClient` (async REST over the JDK `HttpClient`),
  `GitHubAuth` (the OAuth device flow, with the token stored `0600` under the cache dir), `GitHubConfig` (the
  gallery / plugin-registry / Studio / CLI repository names and the raw-CDN URLs) and `SemVer`. Moved
  verbatim from `botmaker-studio`, which is no longer the only operator of those repositories — the coming
  `botmaker-dashboard` reads the same registry and the same pull requests, and a device flow with token
  storage is not code that may exist twice.

## [0.0.20] — 2026-09-02

- **Compiled for Java 25 (LTS).** Every consumer — the SDK, session, Studio and any plugin depending on this
  module directly — needs a 25 runtime. Nothing about the native plumbing changed.

## [0.0.19] — 2026-09-02

- **Installed-game discovery moved in from Studio** (`com.botmaker.shared.game`): the Steam, Epic, Heroic and
  Faugus library scanners, `GameLibraries`, `GameLibraryProvider` and `InstalledGame`. Enumerating what is
  installed on the machine is host-platform work, like enumerating windows and emulators, and it had to leave
  Studio because the SDK's game-launch editors need it and a plugin cannot see Studio's classes.
- Adds `jackson-databind` (2.17.0), because the Epic and Faugus launchers keep their catalogues as JSON. The
  SDK and Studio already declared the same version themselves; `botmaker-session` is the one consumer for
  which it is genuinely new.

## [0.0.18] — 2026-08-22

- **OCR stops depending on the host.** The Linux Tesseract natives are bundled instead of borrowed, so a
  machine with no `libtesseract` installed reads text correctly rather than failing at the first OCR call.

## [0.0.17] — 2026-08-19

- Re-tagged so JitPack rebuilt it for its consumers. No source change.

## [0.0.16] — 2026-08-19

- **A phone is an address, not a host and a port**, and a saved phone is a shared thing rather than a Studio
  preference.
- **The fast path**: continuous scrcpy video plus a control socket, and a capture floor that no longer pays
  for an encode and a fork per frame.
- **The managed tools became findable** — BotMaker fetches its own `adb` and `scrcpy-server` and a bot
  self-serves rather than requiring a hand-installed toolchain.
- Waydroid gained a child command so it can run on a private display, and gamescope is never launched unsized
  (so its framebuffer is not scaled).
- Template matching stopped scoring 0.89 on things that are not on screen; the launcher deny-list learned
  Electron and AppImage; the Windows side buttons stopped clicking left; a swipe is a telemetry event.

## [0.0.15] — 2026-08-04

- CI only: one `ci.yml` per repo, compile-only.

## [0.0.14] — 2026-08-04

- **A timed-out spawn kills the whole process tree** rather than leaking the shell's child.

## [0.0.13] — 2026-08-02

- **The bot's runtime tuning became eight project keys**, so it is configuration rather than generated source.
- **No spawn can hang on a full pipe**, and a throwing telemetry listener no longer kills the channel.
- The session stack moved out to `botmaker-session`; a closed session stops existing; a click inside a session
  keeps the pointer on its target; `ColorMatcher` gained a count gate beside the area filter.

## [0.0.12] — 2026-07-19

- **OCR core in shared** (OpenCV + Tess4J), shared by the SDK and Studio.
- **Emulator discovery** across platforms — Android emulator, MEmu, MuMu — plus `EmulatorLauncher` and the
  dadb transport.

## [0.0.11] — 2026-07-14

- Studio overlays are promoted above fullscreen windows on X11, and remapped so the window manager re-reads
  `_NET_WM_WINDOW_TYPE`.

## Earlier

v0.0.10 and below predate this file. `ROADMAP.md` has the dated log.
