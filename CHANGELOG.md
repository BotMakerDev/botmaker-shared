# Changelog

What each released version of `botmaker-shared` changes, in a few bullets. `ROADMAP.md` stays the detailed
engineering log; this is the short answer, and it is what `release.sh` publishes as the GitHub Release body.

**`release.sh` refuses to cut a version with no section here** (`check_changelog`, decide pass, before
anything is tagged). If the top section still says `## [Unreleased]`, rename it to the version being cut and
date it.

No bot ever names shared directly — it is a transitive dependency of the SDK — so this file is written for
whoever is debugging a capture, a launch or an OCR result, not for a bot author.

Sections are `## [x.y.z] — YYYY-MM-DD`, newest first.

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
