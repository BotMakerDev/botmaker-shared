# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**BotMaker-shared** is the **host platform layer**: the cross-platform capabilities the Studio host and every
plugin it loads may consume, the SDK included. That framing matters more than "code two modules happen to
share" — the SDK is *a consumer* of shared, not its co-owner, and a future plugin that wants to enumerate a
window must be able to reach one without taking a dependency on `botmaker-sdk`. Its charter is native window
plumbing (enumerate, capture, focus, move, resize, drive input), the **Android-emulator** capability
(`com.botmaker.shared.emulator`), the **launch stack** (`com.botmaker.shared.launch`), **template/colour
matching** (`com.botmaker.shared.opencv`), **full-desktop capture**, the **project properties file**
(`com.botmaker.shared.config`) and — since 2026-09-05 — the **GitHub layer**
(`com.botmaker.shared.github`).

**OCR is no longer here.** `com.botmaker.shared.ocr` moved to `botmaker-sdk` in SDK 1.2.0 — split by the
`api` boundary rule into `com.botmaker.sdk.api.vision` (`OcrOptions`, `OcrLanguage`, `TextResult`) and
`com.botmaker.sdk.internal.ocr` (`OcrEngine`, `OcrNative`, `OcrPreprocessor`), with `tessdata` and the
Tess4J/bytedeco pins. It had exactly one consumer in six repos (the SDK's `api.vision.Text`), and leaving it
here meant an SDK facade putting a shared — freely breakable, unversioned — type in a bot's hands. The
version-pinning note and the native-staging build passes moved with it: read them in `../botmaker-sdk/pom.xml`.

**Private display sessions are no longer here.** `com.botmaker.shared.session` moved to its own module and
repo, [`botmaker-session`](../botmaker-session/CLAUDE.md), in 2026-07; it depends on this module (and is the
only BotMaker dependency it has). The **launch stack stayed** — including `LaunchIsolation`,
`HostLauncherProbe` and `ProcessOrigin`, which read as session code but cannot leave, because `RunningProbe`
uses `ProcessOrigin` and moving it would invert the dependency. Note that `botmaker-session` excludes this
module's OpenCV when it depends on us, so **do not make `capture/` or `launch/` link an `org.opencv` type**
— that would break a standalone session consumer at runtime. (`SharedNoOcvLeakTest` is the guard. Its Tess4J
half went with the OCR move: there is no OCR engine on this module's classpath to leak.)

It depends on
**JNA** (window/input), **OpenCV** (`org.openpnp:opencv`) for template and colour matching, and **dadb**
(`dev.mobile:dadb`, pure-JVM ADB) for the emulator transport. No JavaFX, and since SDK 1.2.0 no Tess4J.

The architecture is in `docs/architecture/`, one file per section (moved there unchanged on 2026-10-05).

## Read before touching

| Touching (`com.botmaker.shared.…`) | Read (`docs/architecture/`) |
|---|---|
| `capture/` (`NativeController`, `ScreenCapture`, `CaptureBackend`), the package map, `config/`, `launch/`, `tools/` (`UserDirs`) | `architecture.md` |
| `opencv/` (`OpenCvNative`, `OpencvManager`, `ColorMatcher`) | `matching.md` |
| `emulator/` (`AdbDevice`, `Platforms`, `EmulatorProbe`) | `emulator.md` |
| `device/` (scrcpy fast path) | `device.md`, `../docs/display-pipeline.md` |
| `github/` (`GitHubClient`, `GitHubAuth`, `GitHubConfig`) | `github.md` |

## Contract stability

shared is consumed by two modules (SDK + Studio) via the `NativeController` interface and the `capture.*`
value types (`GenericWindow`, `WindowInfo`). **No published bot/project consumes them yet, so this API is
currently freely breakable** — change signatures when it makes the contract cleaner. The only cost is the
ordered cross-module release: land the shared change, release it, then bump both consumers (see the umbrella
`../CLAUDE.md` and `../release.sh`). Reinstate stability discipline once real bots ship.

## Rules

- **OpenCV loads through `OpenCvNative.ensureLoaded()` and nowhere else**, from a `static {}` block on any
  class that links an `org.opencv` type (`matching.md`).
- **shared returns raw records** (`RawMatch`, `RawColorMatch`); the consumer maps them to its own types.
- **`UserDirs.config()` is what the user told us, `cache()` what can be fetched again**; never swap them
  (`architecture.md`).
- **scrcpy: no `max_size`, ever; the server is located or downloaded, never vendored** (`device.md`).
- **`credentials.json` is shared with Studio's `GoogleAuth`**: every write merges the whole map (`github.md`).

## Planning

For large changes, write the plan to a dedicated plan file before starting, so work can be resumed if a
session is interrupted. A finished change writes `CHANGELOG.md` under `## [Unreleased]`. `ROADMAP.md`
holds open work only: add an item when work is left for later, remove it when done; never a dated done-entry.

## Commands

```bash
mvn compile        # Build
mvn test           # Run tests (JUnit Jupiter; NativeControllerFactory.setForTesting injects a fake)
mvn install        # Install to ~/.m2 at 0.0.0-SNAPSHOT so consumers pick up local changes with no tag
```

This module is normally built from the umbrella root (`mvn install`), which builds it **first** so the SDK
and Studio resolve it from the reactor. There is no coordinate trick to test local changes: because the
groupId already matches JitPack, a plain `mvn install` (or `mvn -pl botmaker-shared -am install` from the
umbrella) lands it at the default `0.0.0-SNAPSHOT` every consumer resolves. The old `dev-install.sh` was
removed — it was just that `mvn install`. See `../CLAUDE.md` › Local dev.

## groupId note

This module's Maven `groupId` is `com.github.BotMakerDev` (not `com.botmaker.shared`) on purpose — it matches
the coordinate JitPack serves, so one dependency line in the SDK/Studio resolves both locally (reactor) and
from JitPack. See `pom.xml` and `../CLAUDE.md`.

## Code Style

Prefer functional OOP: minimize mutable state, keep the native side effects (JNA calls, window handles) at
the edges, pass dependencies in rather than reaching for singletons. The one intentional singleton is the
lazily-cached `NativeControllerFactory.instance` (overridable for tests).

**Type a closed set rather than passing a bare `String`.** `PlatformId` is the worked example: the product
key used to be a free-form `String platformId` on `EmulatorInstance`, which let a typo invent a product and
let each consumer keep its own id→display-name switch — they had already drifted ("MuMu" vs "MuMu Player").
An enum carrying both the stable wire `id()` and the `displayName()` makes the set closed, exhaustively
switchable, and single-sourced. Keep the wire id stable (it may be persisted) and keep the parse total
(`fromId` → `UNKNOWN`, never throws) so an unrecognised value from a newer config still loads.

**Put a behaviour shared by the platform implementations in one place, not five.** Discovery is repetitive by
nature, so the common parts are factored out and each platform supplies only what genuinely differs:
`WindowsRegistry.firstNonBlank` (read the same setting from several keys), `PlatformScan.directory` (the
"list the install dir, parse each entry, never throw" walk, taking a per-entry lambda), and
`EmulatorInstance.identity()` / `PlatformStatus.statusLine()` for the keys and strings consumers would
otherwise each rebuild. When adding a product, reach for these before writing a private copy.
