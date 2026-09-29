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

- **`GitHubClient.getOrFail`**: a GET that fails its future with a `GitHubError` on anything but 200. The error
  carries the status, GitHub's own message and `X-RateLimit-Remaining`, and says plainly when the rate limit is
  used up. `get` is unchanged and still answers `null`.
- **Conditional GETs.** `GitHubClient.get` and `getOrFail` remember each 200's `ETag` in memory and send
  `If-None-Match` next time. An unchanged resource comes back as a 304, which does not spend the rate limit,
  and is read as the body it stands for.
- **`GitHubAuth.pollForToken(code, cancelled)`** stops the device-flow poll when asked. Cancelling the future did
  not stop the loop, so a closed sign-in dialog kept polling and could still store a token.
- **Debug lines cross the telemetry wire.** `TelemetryEvent.Log` (frame tag 5) carries a line's level, source,
  text, repeat count, time, desktop rectangle, the class and method that wrote it, and the bot's class and line
  it was written for. An older reader skips the frame and keeps reading.
- **`Diag.Origin(source, className, method)`** and `Diag.log(Origin, …)`/`Diag.error(Origin, …)`: a caller that
  knows where a line was written passes it. Otherwise the writer is the first caller outside `Diag`, looked up
  only while a trace sink is set.
- **Frames can be relayed without being decoded.** `TelemetryFrame.readFrame` reads one whole frame as bytes,
  `decode` reads those bytes back, and `isLog`/`log` pick out a debug line without decoding anything else.
  `TelemetryServer.relaying(token, onFrame, onError)` hands each frame on as bytes, for a host that passes
  frames to plugins without reading them.
- **`Diag.setSink`**: each diagnostic printed while debugging is on also goes to the sink as a `Log`, with its
  source taken from the leading `[Name]`. The printed output is unchanged.
- **`Diag.log(source, message, count, where)` and `Diag.error(source, message[, t])`** print and trace a line
  under a source the caller names. A message's own leading `[Name]` still wins.
- **`-Dbotmaker.debug=true|false`** (`Diag.RUN_PROPERTY`) sets whether diagnostics start on; `Diag.runOverride()`
  reads it.

### Changed

- **Desktop capture under Wayland uses grim (Sway, Hyprland) or gnome-screenshot (GNOME) when Spectacle is not
  installed**, instead of falling back to Robot, which returns black or asks the portal on every grab.
  `SpectacleCapture` is replaced by `ToolCapture`, one constant per program.

### Removed

- **`config.ProjectProperties`, `config.ProjectFile` and `config.CaptureSourceKind`.** A bot has no
  `botmaker-project.properties` any more: its tuning is the SDK's `@Managed("settings")` value in its own
  Java, and what it launches on this machine arrives as the `botmaker.launch.target` system property.

## [0.0.28] — 2026-09-27

### Changed

- **uinput presses every key the SDK's `Key` names.** Punctuation, Home/End/Page Up/Page Down/Insert, Caps
  and Num Lock and the numpad were dropped silently under real input on Linux; each now has its evdev code.
  Keypad Enter is `KEY_KPENTER` rather than the main Enter.

### Fixed

- **uinput follows the keyboard layout.** It emitted the US board's positions, so on AZERTY `a` typed `q`
  (and `z`/`w`, `m` and punctuation landed on the wrong keys) while XTest and Windows typed what was asked.
  The virtual device now reads the X keyboard mapping once when it is created and sends each key where the
  layout puts it, falling back to the US position for a key the layout lacks. A layout switched mid-run is not
  followed; as with XTest, only the key is pressed — a character on a shifted level (AZERTY's digits) gets no
  Shift added.

### Added

- **`ColorMatcher.matchMask(image, target, tolerance)`** — the per-pixel ΔE mask `findClusters` labels,
  row-major, so an editor (the SDK's Precision overlay) draws exactly what a search sees rather than a second
  threshold of its own.

- **`ProjectFile.set(resourcesDir, key, value)`** — one key written into
  `botmaker-project.properties`, load-modify-store, `false` rather than a throw when it cannot be written.
  This class was deliberately read-only, deferring every write to the schema ledger that owned the file
  being written beside; that ledger (`Authoring`, `capture.json`) is deleted, so what the rule protected no
  longer exists and the one caller left — the SDK's emulator picker naming the instance it just chose — had
  nowhere to write. A blank or null value removes the key rather than storing an empty one, so *unset* and
  *set to nothing* stay the same state they have always been for every reader here.

### Removed

- **The project-wide capture resolution, both halves of it.** `ProjectProperties.KEY_CAPTURE_WIDTH`,
  `KEY_CAPTURE_HEIGHT` and `defaultResolution()`, `ProjectFile.captureSize`, and `ResolutionScaler`'s
  fallback onto them. **Nothing in any module ever wrote either key**, so every reader took its own default
  on every call and always had: `primaryScale` returned `1.0`, and a background session started at
  `BackgroundLauncher`'s own size. The authoring half of the same fact — `capture.json`'s `reference` — was
  written by nothing either, so both halves went together rather than one of them acquiring a writer to
  justify the other. A template's own `captureWidth`/`captureHeight`, recorded beside the picture by the
  capture that made it, **is** written and is untouched: it is the `authored` argument `OpencvManager`
  already passes, and the only resolution scaling that has ever happened.

No source changes since v0.0.26; re-released for updated upstream pins.

No source changes since v0.0.25; re-released for updated upstream pins.

### Changed

- **Every repository is `BotMakerDev`'s now.** `GitHubConfig`'s gallery, registry, Studio, CLI and issue
  owners name the organization the repositories moved to on 2026-09-18. `STUDIO_REPO` is spelled
  `botmaker-studio`, the repository's real name. The raw index URLs still fall back to the old owner,
  now `PREVIOUS_OWNER`, which is `NEXT_OWNER`'s replacement.

### Added

- **`GitHubConfig.MAINTAINER`**, the maintainer's GitHub login, for the two places that ask "is this the
  maintainer?". They compared a login with the repository owner, and since an owner can now be an
  organization, no login would ever match.

## [0.0.27] — 2026-09-23

### Added

- **`ProjectFile.set(resourcesDir, key, value)`** — one key written into
  `botmaker-project.properties`, load-modify-store, `false` rather than a throw when it cannot be written.
  This class was deliberately read-only, deferring every write to the schema ledger that owned the file
  being written beside; that ledger (`Authoring`, `capture.json`) is deleted, so what the rule protected no
  longer exists and the one caller left — the SDK's emulator picker naming the instance it just chose — had
  nowhere to write. A blank or null value removes the key rather than storing an empty one, so *unset* and
  *set to nothing* stay the same state they have always been for every reader here.

### Removed

- **The project-wide capture resolution, both halves of it.** `ProjectProperties.KEY_CAPTURE_WIDTH`,
  `KEY_CAPTURE_HEIGHT` and `defaultResolution()`, `ProjectFile.captureSize`, and `ResolutionScaler`'s
  fallback onto them. **Nothing in any module ever wrote either key**, so every reader took its own default
  on every call and always had: `primaryScale` returned `1.0`, and a background session started at
  `BackgroundLauncher`'s own size. The authoring half of the same fact — `capture.json`'s `reference` — was
  written by nothing either, so both halves went together rather than one of them acquiring a writer to
  justify the other. A template's own `captureWidth`/`captureHeight`, recorded beside the picture by the
  capture that made it, **is** written and is untouched: it is the `authored` argument `OpencvManager`
  already passes, and the only resolution scaling that has ever happened.

No source changes since v0.0.26; re-released for updated upstream pins.

No source changes since v0.0.25; re-released for updated upstream pins.

### Changed

- **Every repository is `BotMakerDev`'s now.** `GitHubConfig`'s gallery, registry, Studio, CLI and issue
  owners name the organization the repositories moved to on 2026-09-18. `STUDIO_REPO` is spelled
  `botmaker-studio`, the repository's real name. The raw index URLs still fall back to the old owner,
  now `PREVIOUS_OWNER`, which is `NEXT_OWNER`'s replacement.

### Added

- **`GitHubConfig.MAINTAINER`**, the maintainer's GitHub login, for the two places that ask "is this the
  maintainer?". They compared a login with the repository owner, and since an owner can now be an
  organization, no login would ever match.

## [0.0.26] — 2026-09-21

No source changes since v0.0.25; re-released for updated upstream pins.

### Changed

- **Every repository is `BotMakerDev`'s now.** `GitHubConfig`'s gallery, registry, Studio, CLI and issue
  owners name the organization the repositories moved to on 2026-09-18. `STUDIO_REPO` is spelled
  `botmaker-studio`, the repository's real name. The raw index URLs still fall back to the old owner,
  now `PREVIOUS_OWNER`, which is `NEXT_OWNER`'s replacement.

### Added

- **`GitHubConfig.MAINTAINER`**, the maintainer's GitHub login, for the two places that ask "is this the
  maintainer?". They compared a login with the repository owner, and since an owner can now be an
  organization, no login would ever match.

## [0.0.25] — 2026-09-19

### Changed

- **Every repository is `BotMakerDev`'s now.** `GitHubConfig`'s gallery, registry, Studio, CLI and issue
  owners name the organization the repositories moved to on 2026-09-18. `STUDIO_REPO` is spelled
  `botmaker-studio`, the repository's real name. The raw index URLs still fall back to the old owner,
  now `PREVIOUS_OWNER`, which is `NEXT_OWNER`'s replacement.

### Added

- **`GitHubConfig.MAINTAINER`**, the maintainer's GitHub login, for the two places that ask "is this the
  maintainer?". They compared a login with the repository owner, and since an owner can now be an
  organization, no login would ever match.

## [0.0.24] — 2026-09-18

### Changed

- **The gallery and plugin-registry indexes are read from `BotMakerDev` first, then `LiQiyeDev`.**
  `GitHubConfig.catalogRawUrls()`, `indexRawUrls()` and `registryIndexRawUrls()` return both in that order,
  and `GitHubClient.getFirstString` takes the first that answers. This is the first half of moving the
  repositories into the organization; nothing has moved yet, so today the first URL answers 404 and the
  second serves. The single-URL methods are gone.

## [0.0.23] — 2026-09-18

No source changes since v0.0.22; re-released for updated upstream pins.

### Added

- **The gallery's tiered catalog.** `GitHubConfig.CATALOG_PATH` and `catalogRawUrl()` name `catalog.json`,
  which lists every bot with its tier (Vetted or Community). `VETTED_DIR` and `vettedPath(owner, repo)` name
  the maintainer-only records saying which release of a bot is vetted. `indexRawUrl()` is unchanged: that
  file now holds Vetted bots only, for the Studios already installed.

## [0.0.22] — 2026-09-17

### Added

- **The gallery's tiered catalog.** `GitHubConfig.CATALOG_PATH` and `catalogRawUrl()` name `catalog.json`,
  which lists every bot with its tier (Vetted or Community). `VETTED_DIR` and `vettedPath(owner, repo)` name
  the maintainer-only records saying which release of a bot is vetted. `indexRawUrl()` is unchanged: that
  file now holds Vetted bots only, for the Studios already installed.

## [0.0.21] — 2026-09-16

### Added

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
