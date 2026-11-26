# Architecture

`capture.NativeController` (interface) is the single cross-platform abstraction: window enumeration
(`getAllWindows` / `getChildWindows` / `getForegroundWindow`), per-window capture (`captureWindow`, returns
`null` when it can't produce a usable frame — e.g. native Wayland, invalid geometry — so callers apply their
own full-desktop fallback), window management (`focus`/`move`/`resize`), and input synthesis
(`keyDown`/`keyUp`/`typeText`/`mouseMove`/`mouseButton`/`scroll`, plus `postLeftClick*`).

Full-desktop capture is **not** on that interface but does live in this module, as the static
`capture.ScreenCapture` facade over a sealed `CaptureBackend` (`RobotCapture` on Windows/X11/XWayland, and
under Wayland the first installed `ToolCapture` program — Spectacle, grim, gnome-screenshot — chosen by
`CaptureBackend.select()`; the SDK's plugin kept its own grim/gnome-screenshot list until 2026-09-28, and a
GNOME user got a working grab from one path and a black one from the other); `getVirtualScreenBounds()` is the one
AWT all-monitor union. A `ToolCapture` grab never falls back to the Robot (2026-11-26): on Wayland the Robot reads
through the screen-cast portal, which asks on every grab. Grabs go one at a time, since Spectacle hands a concurrent
one to the running instance and writes nothing, and one that wrote nothing is tried once more, then `null`. A region
is `captureRegion`, cropped from that grab; a caller holding its own Robot asks `robotReadsScreen()` first. It used to live in the SDK under the rule "full-desktop capture belongs to each
consumer", which was wrong on its own terms: the platform knowledge is identical to per-window capture's, and
Studio's picker wants the same grab. Adding a GNOME/sway portal+PipeWire path means one new `CaptureBackend`
wired into `select()`, with no caller changes.

On X11 KDE, `LinuxController.captureWindow` sets `_NET_WM_BYPASS_COMPOSITOR=2` on the target so KWin doesn't
unredirect a fullscreen game (which would black out its — and every other window's — off-screen pixmap), then
prefers the XComposite pixmap, and falls back to a root-window crop if the frame reads all-black. See the
2026-07-10 `ROADMAP.md` entry (and the deferred portal/PipeWire path) for the full rationale and the manual
KWin `WindowsBlockCompositing=false` / borderless-windowed workaround for true exclusive-fullscreen games.

`capture.NativeControllerFactory.get()` picks the implementation by OS (JNA `Platform`): `WindowsController`,
`LinuxController` or `MacController` (any other OS throws `UnsupportedOperationException`).
`setForTesting(...)` injects a fake for tests. Key codes crossing the interface are **per-OS native codes**
(X keysym on Linux, virtual-key code on Windows); consumers resolve them from their own platform-neutral key
enums. macOS takes the X keysym too and translates it itself (`MacKeyCodes`), so the SDK's `Key` needs no
third column.

Package map:
- `capture/` — the cross-platform surface: `NativeController`, `NativeControllerFactory`, `GenericWindow`,
  plus full-desktop capture (`ScreenCapture`, `CaptureBackend`, `RobotCapture`, `ToolCapture`), and
  `IgnoredClickWatch`, which both background paths (Windows, macOS) run their first clicks through.
  `WindowPointer` lets the user point at a window (`current()` picks the backend by desktop: `linux.KWinPointer`,
  `linux.X11Pointer`, `windows.WindowsPointer`, `mac.MacPointer`) and resolves the click to one of
  `getAllWindows()`; Windows and Mac decide each hooked event with `PointClick`. Only KWin and X11 were tried
  live; Windows' and Mac's pointers have unit tests of their pure parts only, and their live check is owed.
- `capture/windows/` — JNA Windows backend: `User32`/`GDI32`/`Dwmapi` bindings, `WindowsController`,
  `WindowFinder`, `WindowInfo`. Input: `PostedInput` (background messages, encoded by `WindowMessages`),
  `SendInputs` (take-over). Capture: `WindowCapture` (the ladder), `WindowFrames`, and
  the opt-in `WgcCapture` over `Com` (vtable calls). `WindowsDpi` keeps every coordinate physical. A
  window's rect is its client area. See `../../docs/display-pipeline.md` §8.
- `capture/linux/` — JNA Linux/X11 backend: `X11`/`XTest` bindings, `X11Utils`, `LinuxController`.
- `capture/mac/` — JNA macOS backend, C APIs only (no Objective-C): `CoreGraphics` and `Accessibility`
  bindings, `MacController`. Windows: `MacWindows` (the CG window list), `MacAx` (raise, move, resize).
  Input: `MacInput` — background is `CGEventPostToPid` into the app's queue, take-over is `CGEventPost` at the
  HID tap. Capture: `MacCapture` (`CGWindowListCreateImage`, deprecated since macOS 14 but still answering).
  `MacDisplays` keeps every coordinate in pixels (Retina is 2×). `MacPermission` says once, by name, when
  Screen Recording, Accessibility or Input Monitoring is missing. A window's rect is its frame, title bar
  included.
- `input/` — the global input listener (`InputListener`, `InputEvent`, `InputListenerFactory`), passive, for
  the Studio recorder and stop key. `input/linux/X11InputListener` over XRecord; `input/mac/MacInputListener`
  over a listen-only CoreGraphics event tap (the `capture.mac.CoreGraphics` binding) on its own run-loop
  thread (`RunLoops`). Both report pixels, X
  button numbers and X keysyms (a Mac key reads back through `MacKeyCodes.toKeysym`); `KeyNames` names a
  keysym where no libX11 can.
- `opencv/` — matching engines: `OpencvManager` (template matching), `ColorMatcher` (CIELAB ΔE clusters),
  `ResolutionScaler`, the raw results `RawMatch`/`RawColorMatch`, and `OpenCvNative` — **the** process-wide
  OpenCV loader (see below).
- `config/` — `CacheDirs` alone since 2026-09-27. `ProjectProperties`, `ProjectFile` and `CaptureSourceKind`
  (the `botmaker-project.properties` keys, its readers, and the `capture.source` grammar) are **deleted**: a
  bot's tuning is the SDK's `@SdkValue(SdkValue.Id.SETTINGS)` value in its own Java, what it launches on this machine is
  the `botmaker.launch.target` system property Studio passes to the run, and the capture source has been
  `Sdk.captureSource()` since 2026-09-22. Nothing reads an old project's file; nothing deletes it either.
- `launch/` — the launch stack: `LaunchKind`/`LaunchSpec` (the `launch.target` grammar), `GameLauncher`,
  `UriLauncher`, `EmulatorAppLauncher`, `RunningProbe` and the `Launcher` facade.
- `emulator/` — Android-emulator capability (see below): `AdbDevice` (dadb transport), `Platforms` +
  `EmulatorPlatform`/`BlueStacksPlatform`/`LdPlayerPlatform`/`MemuPlatform`/`MuMuPlatform`
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
- `web/` — a website as a run reads it (2026-11-18, the umbrella's spec `2026-11-18-web-discovery-design.md`), on
  Playwright for Java since 2026-11-21 (spec `2026-11-21-playwright-web-design.md`). `ChromiumInstall` puts
  Playwright's own Chromium under `UserDirs.cache()/playwright`; `Chromium` starts it and opens each `WebPage` as a
  tab in a context of its own, at 1440×900, recorded into a folder when asked (`newPage(Path)`, `WebPage.video()`).
  `BrowserThread` makes every Playwright call, which the library needs on one thread. `WebPage` loads, presses,
  types, shoots the viewport (`frame`) and the whole page (`whole`), and reads the accessibility tree (`WebTree`,
  `WebNode`, `Role`); `PageCdp`, package-private, is the tab's own CDP session for what Playwright has no call for
  (the tree by backend node, the guard's redirect pause, a new user agent on an open tab). The guard (`Guard`,
  `Scope`, `Robots`, `Presses`, `FormProbe`, `FormRule`) keeps a run read-only and on its site; `Pace` and `Budget`
  slow and count its loads; `Consent` answers a cookie banner.
