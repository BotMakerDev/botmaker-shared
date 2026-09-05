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

## Contract stability

shared is consumed by two modules (SDK + Studio) via the `NativeController` interface and the `capture.*`
value types (`GenericWindow`, `WindowInfo`). **No published bot/project consumes them yet, so this API is
currently freely breakable** — change signatures when it makes the contract cleaner. The only cost is the
ordered cross-module release: land the shared change, release it, then bump both consumers (see the umbrella
`../CLAUDE.md` and `../release.sh`). Reinstate stability discipline once real bots ship.

## Planning

For large changes, write the plan to a dedicated plan file before starting, so work can be resumed if a
session is interrupted. **Always update `ROADMAP.md` when you add a feature or refactor** — append a dated
entry under the newest-first history.

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

## Architecture

`capture.NativeController` (interface) is the single cross-platform abstraction: window enumeration
(`getAllWindows` / `getChildWindows` / `getForegroundWindow`), per-window capture (`captureWindow`, returns
`null` when it can't produce a usable frame — e.g. native Wayland, invalid geometry — so callers apply their
own full-desktop fallback), window management (`focus`/`move`/`resize`), and input synthesis
(`keyDown`/`keyUp`/`typeText`/`mouseMove`/`mouseButton`/`scroll`, plus `postLeftClick*`).

Full-desktop capture is **not** on that interface but does live in this module, as the static
`capture.ScreenCapture` facade over a sealed `CaptureBackend` (`RobotCapture` on Windows/X11/XWayland,
`SpectacleCapture` on KDE Wayland, chosen by `CaptureBackend.select()`); `getVirtualScreenBounds()` is the one
AWT all-monitor union. It used to live in the SDK under the rule "full-desktop capture belongs to each
consumer", which was wrong on its own terms: the platform knowledge is identical to per-window capture's, and
Studio's picker wants the same grab. Adding a GNOME/sway portal+PipeWire path means one new `CaptureBackend`
wired into `select()`, with no caller changes.

On X11 KDE, `LinuxController.captureWindow` sets `_NET_WM_BYPASS_COMPOSITOR=2` on the target so KWin doesn't
unredirect a fullscreen game (which would black out its — and every other window's — off-screen pixmap), then
prefers the XComposite pixmap, and falls back to a root-window crop if the frame reads all-black. See the
2026-07-10 `ROADMAP.md` entry (and the deferred portal/PipeWire path) for the full rationale and the manual
KWin `WindowsBlockCompositing=false` / borderless-windowed workaround for true exclusive-fullscreen games.

`capture.NativeControllerFactory.get()` picks the implementation by OS (JNA `Platform`): `WindowsController`
or `LinuxController` (macOS throws `UnsupportedOperationException`). `setForTesting(...)` injects a fake for
tests. Key codes crossing the interface are **per-OS native codes** (X keysym on Linux, virtual-key code on
Windows); consumers resolve them from their own platform-neutral key enums.

Package map:
- `capture/` — the cross-platform surface: `NativeController`, `NativeControllerFactory`, `GenericWindow`,
  plus full-desktop capture (`ScreenCapture`, `CaptureBackend`, `RobotCapture`, `SpectacleCapture`).
- `capture/windows/` — JNA Windows backend: `User32`/`GDI32` bindings, `WindowsController`, `WindowFinder`,
  `WindowInfo`, `WindowCapture`, `Clicker`.
- `capture/linux/` — JNA Linux/X11 backend: `X11`/`XTest` bindings, `X11Utils`, `LinuxController`.
- `opencv/` — matching engines: `OpencvManager` (template matching), `ColorMatcher` (CIELAB ΔE clusters),
  `ResolutionScaler`, the raw results `RawMatch`/`RawColorMatch`, and `OpenCvNative` — **the** process-wide
  OpenCV loader (see below).
- `config/` — `ProjectProperties`: the `botmaker-project.properties` key names + raw reader, shared because
  Studio writes exactly the keys the SDK reads. Beside it, **`ProjectFile` (2026-08-30) reads the same file
  from a *directory*** rather than off the classpath, for everything that *holds* a project instead of being
  one — the editor, a launcher, a plugin serving the open project. Those had a copy each, and what they
  disagreed about was never the happy path: it was whether a missing file, a blank value or an unparseable
  monitor index is an exception, a zero, or the caller's own default. **Reads only** — a write stamps a
  schema version from the editor's migration ledger, so the write path stays with whoever owns that ledger.
  `CaptureSourceKind` is here too: the four forms a `capture.source` spec can take, and the one owner of the
  tokens that separate them.
- `launch/` — the launch stack: `LaunchKind`/`LaunchSpec` (the `launch.target` grammar), `GameLauncher`,
  `UriLauncher`, `EmulatorAppLauncher`, `RunningProbe` and the `Launcher` facade.
- `emulator/` — Android-emulator capability (see below): `AdbDevice` (dadb transport), `Platforms` +
  `EmulatorPlatform`/`BlueStacksPlatform`/`LdPlayerPlatform`/`MemuPlatform`/`MuMuPlatform`/`GameloopPlatform`
  (all discover for real), `EmulatorLauncher` (host launch/stop), `WindowsRegistry`, `EmulatorInstance`.
  A **physical phone** is a discovery path here, not a stack of its own — `DevicePlatform` over `AdbTools`
  (the host adb server, for USB and TLS wireless; also `pair`/`connect` for Android 11+ wireless debugging,
  which needs the binary because the exchange is TLS-wrapped and dadb implements no STLS) and `SavedDevices`
  (the user's own `host:port` list, in the config dir). `AdbTools.binary()` prefers `PATH`, then
  `$ANDROID_HOME`, then BotMaker's own downloaded copy **last** — an adb server is a singleton on port 5037,
  so a fetched binary must never displace the one a machine's SDK already agrees on. `SavedDevices` lives in shared rather than in Studio deliberately: a phone the editor saves has
  to resolve for a **generated bot** too, and a list in Studio's preferences would not.
- `device/` — the **fast path** over the same devices (see below): `ScrcpyDevice` + `ScrcpyChannel`,
  `ScrcpyControl`, `ScrcpyFrames`, `ScrcpyServer`.
- `tools/` — the host tools BotMaker installs **for itself**: `ManagedTools` (the pinned `adb` and
  `scrcpy-server` — URL, digest and byte count together), `Downloads` (fetch, verify, then move — a file that
  does not match its pin lands nowhere, because both of these are then *executed*), `Unzip` (zip-slip guarded,
  and it restores the executable bit a `ZipEntry` does not carry) and `UserDirs`. **`UserDirs` is the one
  answer to "where does BotMaker keep things", and its two halves are not interchangeable:** `config()` is
  what the user told us and nothing can rebuild (`SavedDevices`), `cache()` is what we can always fetch again
  (everything in `tools/`). A downloaded tool in config makes a cache-cleaner unable to reclaim 16 MB; a saved
  address in cache makes one delete the user's phones.

## Matching (`com.botmaker.shared.opencv`)

`OpencvManager` (template matching on `org.opencv.core.Mat`) and `ColorMatcher` (CIELAB ΔE clusters behind the
SDK's `Pixel`) live here because the SDK matches at runtime and Studio's Magic Wand matches at edit time.

**Loading the native goes through `OpenCvNative.ensureLoaded()` and nowhere else.** There were three copies of
that loader — the SDK's, Studio's (whose javadoc admitted it mirrored the SDK's) and one inside `OcrNative` —
each with its own `loaded` flag, so nothing stopped the same process from extracting the native repeatedly.
Call it from a `static {}` block on any class that links an `org.opencv` type. `OcrNative` now lives in the
SDK (`com.botmaker.sdk.internal.ocr`) and still delegates here, by fully-qualified name — which is the reason
the OCR move left `opencv/` behind: it has two consumers and the OCR stack had one.

**shared returns raw records; the consumer maps them to its own value types.** `RawMatch`/`RawColorMatch`
carry plain ints and a score and are named "raw" for exactly this reason; the SDK's `vision` layer maps them
onto its public `MatchResult`/`ColorMatch`. The same rule sets the signatures: the authored-resolution
parameter is a `java.awt.Dimension`, not the SDK's `Size` — the SDK converts once, in
`ImageTemplate.authoredSize()`.

## Android emulator (`com.botmaker.shared.emulator`)

**Six classes arrived here from Studio on 2026-08-30** and they are the editor-time half of this capability:
`EmulatorProbe` (liveness, `screencap`, installed apps), `EmulatorAppCache` (what was last seen on an
instance, on disk), `EmulatorInstanceScanner` (discovery across platforms), and the three capture surfaces
`EmulatorSurface`/`AdbEmulatorSurface`/`ScrcpyEmulatorSurface`. Their only dependency outside shared was a
cache directory, which came with them as `config/CacheDirs` — Studio's `BotMakerDirs` now delegates to it, so
there is still exactly one cache root and not two.

They moved because the Remote Pilot is becoming a plugin's feature and a plugin may not name a Studio type.
The rule that put them here rather than in the SDK is this module's own: **a capability the host and every
plugin it loads may consume**. A plugin wanting to screen-grab an emulator now can, and gets the same probe
Studio's own picker uses rather than a second one that drifts.

The rest of the package is the discovery + ADB transport, hosted in shared so **both** consumers reach it: the SDK's
`api.emulator.Emulator` wraps it as a `CaptureSource` at runtime, and a Studio capture picker can screen-grab an
emulator at edit time. `AdbDevice` is one dadb connection (`dev.mobile:dadb` — pure-JVM ADB, no `adb.exe`; `screencap()` plus
`tap`/`swipe`/`key`/`text`/`startApp`/`shell`). Capture has **two** paths and picks between them by
`AdbEndpoint.local()`: raw `exec:screencap` (no device-side encode, decoded by `RawFramebuffer`) on loopback,
`exec:screencap -p` everywhere else — raw skips the PNG encode but moves ~10 MB, which is a win on loopback
and a loss over a cable or a radio. Both are lossless, so it is a latency choice only. `shell()` runs through
one `sh` held open across calls (`AdbShellSession`, marker-framed) rather than forking one per command. Note the
Kotlin package is `dadb.*`, not the `dev.mobile` groupId, and dadb self-manages the RSA key (`~/.android/adbkey`).
Discovery (`Platforms.discoverAll()`) reads each product's local config/registry → `EmulatorInstance`s (name +
ADB port): `BlueStacksPlatform` (`bluestacks.conf`), `LdPlayerPlatform` (`leidian<i>.config`, port 5555+2·i,
name via regex — no Jackson), `MemuPlatform` (VirtualBox `.memu` NAT forwarding rule → host port of guest 5555)
and `MuMuPlatform` (`vms\MuMuPlayer-12.0-<i>`, port 16384+32·i) all discover for real; `GameloopPlatform`
detects the install and returns its single primary instance on the fixed port 5555. Beyond discovery, each
`EmulatorInstance` also carries the host `launchCommand`/`stopCommand` its platform resolved (LDPlayer
`ldconsole`, MuMu `MuMuManager`, MEmu `memuc`, BlueStacks `HD-Player --instance`, Gameloop engine exe), which
`EmulatorLauncher` spawns to start/stop an instance the ADB transport can't reach until it's up. `AdbDevice`
also does app queries (`installedApps`/`isInstalled`/`currentApp`). Windows-first, best-effort, never throws.
dadb pulls kotlin-stdlib, which now rides into every consumer (Studio included) — the accepted cost of
shipping no adb binary.

## The device fast path (`com.botmaker.shared.device`)

`emulator/` is the **floor**: a `screencap` per frame, and `input tap` — which is a shell script that execs
`app_process`, i.e. a JVM start on the device, per tap. No transport work reaches that; only a control socket
does. `device/` is that path. `ScrcpyDevice` is the facade and deliberately mirrors the `AdbDevice` verbs it
replaces (`grab`/`tap`/`swipe`/`key`), so a consumer holds one or the other behind its own interface and
falling back is a change of field. `ScrcpyChannel` pushes `scrcpy-server`, runs it under `app_process` and
opens the video + control sockets; `ScrcpyFrames` decodes through a piped `ffmpeg` keeping **one** picture
(the newest — a queued frame is by definition a stale one); `ScrcpyControl` is the pure message encoder.

Three things here are load-bearing and easy to undo by accident:

- **No `max_size`, ever.** `docs/display-pipeline.md` §3: framebuffer, stream and reference resolution are
  *one number*. A scaler between a bot's templates and the pixels it taps fails quietly — matching keeps
  succeeding while every tap lands wrong. `ScrcpyChannelTest` asserts the argument's absence.
- **The server is located or downloaded, never vendored** (`ScrcpyServer`, ≥ 2.1). A real scrcpy install is
  searched first and wins; `ensure()` — called only from `ScrcpyDevice.Builder.open()`, the capture path —
  falls back to fetching `tools/ManagedTools.SCRCPY_SERVER`, which is the **one automatic download** in the
  stack (a headless bot has no dialog to click). `available()` stays a pure probe and never downloads.
  **Version detection has one trap:** it is read from the installed *client* binary, so for our own managed
  file it is taken from the pin instead — a v4.1 file announced as some PATH client's version is a server that
  exits and a socket that never accepts. Missing `ffmpeg` ⇒ the `emulator/` floor, which needs neither.
- **Nothing here has spoken to a real server yet.** The tests pin the layout *we transcribed*, not the
  layout a device reads. A gesture landing wrong on hardware is a `ScrcpyControl` transcription bug first.

## GitHub (`com.botmaker.shared.github`)

Four classes, moved here from `botmaker-studio`'s `sharing/` package on 2026-09-05 and otherwise unchanged:
`GitHubClient` (async REST over the JDK `HttpClient` + Jackson — no third-party GitHub SDK, none is
official), `GitHubAuth` (**OAuth device flow**, hand-rolled because no library implements it, with the token
persisted best-effort `0600` under `CacheDirs`), `GitHubConfig` (the owner/repo names of the gallery, the
plugin registry, Studio and the CLI, plus the raw-CDN URLs) and `SemVer`.

**Why it is here rather than copied.** Studio stopped being the only operator of those repositories:
`botmaker-dashboard` reads the same registry, the same gallery and the same pull requests. A device flow with
token storage and a polling loop is exactly the code that must not exist twice, and the owner/repo names are
one edit rather than two when a repository moves. It is also what this module is for — *the capabilities the
host and every plugin it loads may consume* — and none of the four names a JavaFX type, which is what made
the move imports-only.

**The counter-example is `botmaker-cli`, and it went the other way on purpose.** The CLI copies ~40 lines of
*file shapes* (`RegistryEntry`, `GalleryEntry`) rather than depending on shared, because nothing maintains a
record's field list after the first commit and the alternative there was depending on an **application**.
This is neither: live protocol code, and shared is a library both consumers already resolve.

**Two things stayed in Studio.** `GoogleAuth`/`GoogleConfig` — nothing else wants them, and the twin
device-flow implementation is not a duplicate of anything a second consumer needs. And every *reader* built
on this layer (`BotPublisher`, `GitHubGallery`, `PluginRegistry`, `BotInstaller`, `UpdateService`,
`CliUpdateService`): those know what a bot, a gallery entry and a plugin index are, which is the editor's
vocabulary rather than the platform's. `GitHubConfig`'s javadoc names them as prose, never as `{@link}` —
a link pointing out of this module is how the dependency would come back in the reader's mind if not in the
pom.

**`credentials.json` is shared with `GoogleAuth`, which still lives in Studio**, so every write merges the
whole map rather than overwriting it. Signing into one provider must not wipe the other's token — that is
why `store` reads before it writes, and it is now a rule spanning two repositories.

## groupId note

This module's Maven `groupId` is `com.github.LiQiyeDev` (not `com.botmaker.shared`) on purpose — it matches
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
