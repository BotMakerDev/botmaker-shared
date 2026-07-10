# ROADMAP

A running history of features and refactors for `botmaker-shared`, for future Claude Code sessions.
**Append here whenever you add a feature or refactor** (required — see `CLAUDE.md` › Planning).

Format: newest first. Each dated entry has a **Done** list and, when relevant, **Deferred / next**
(intentionally left for later, with enough context to pick up cold).

---

## 2026-07-11 — Windows enum filter, fullscreen fallback, Linux "same content" regression fix

**Done**
- **Windows enumeration filter.** `WindowFinder.getAllWindows` returned 100–200+ handles (any window with a
  title). It now applies the alt-tab heuristic (`isRealAppWindow`): visible, unowned (or `WS_EX_APPWINDOW`),
  not `WS_EX_TOOLWINDOW`, not DWM-**cloaked** (new `Dwmapi` binding, `DWMWA_CLOAKED`), non-zero size, non-empty
  title — collapsing the list to the ~handful of real app windows. New `User32` bindings: `IsWindowVisible`,
  `GetWindowLongA`, `GetWindow` (+ `GWL_EXSTYLE`/`WS_EX_*`/`GW_OWNER` constants).
- **Windows fullscreen capture (`WindowCapture`).** Multi-monitor aware: a foreground window that fills *any*
  monitor (not just the primary, matched exactly before) is treated as borderless-fullscreen and captured via
  `Robot` (GDI `PrintWindow` returns black for D3D/OpenGL surfaces). GDI still leads for windowed/background
  windows, with a Robot-at-window-rect fallback on a black/invalid frame.
- **Linux "all windows show the same content" regression fix (`LinuxController.captureWindow`).** The
  root-window crop reads whatever is *visually* at the window's rect, so running it for background/occluded
  windows returned the window in front — making every capture identical. Root-crop is now gated on the
  **foreground** window (`isForeground` via `_NET_ACTIVE_WINDOW`); background windows fall back to the
  on-window `XGetImage` (their own un-occluded pixels), never another window's content.

**Deferred / next**
- True *exclusive*-fullscreen games on Windows still can't be captured by GDI/Robot (needs DXGI Desktop
  Duplication). Workaround stays: run the game borderless-windowed. Same borderless note as the Linux path.

`## 2026-07-10 — Fullscreen-game capture: keep KWin compositing + root-crop fallback
`
**Done**
- Fixed the **black capture** of a fullscreen game (repro: Firestone, a Unity/Proton title on X11 KDE) that
  *also* blacked out every other window's capture the moment it launched. Root cause: KWin **unredirects**
  (globally suspends compositing for) a fullscreen window, destroying the off-screen backing pixmaps that
  `captureViaComposite` reads — so the game and every other window read black.
- `LinuxController.captureWindow` now:
  1. sets **`_NET_WM_BYPASS_COMPOSITOR = 2`** on the target (`X11Utils.setKeepComposited`, new
     `X11.XChangeProperty` binding) — EWMH "never unredirect this window", so KWin keeps compositing it and
     the pixmap stays readable (works because a Proton/OpenGL window renders to a normal X11 drawable);
  2. detects an **all-black** frame (`isAllBlack`, sparse-grid sample) and falls back to a **root-window
     crop** (`XGetImage` on the root at the window's absolute rect — `getWindowGeometry` already returns
     absolute coords) which reads the on-screen framebuffer; then the previous on-window `XGetImage` as a
     last resort. Root-crop runs only *after* the occlusion-safe composite path, so overlapping-window
     capture is unaffected.
- Manual belt-and-suspenders if a game still blacks out (true exclusive-fullscreen / direct scanout): disable
  KWin ▸ *Allow applications to block compositing*
  (`kwriteconfig6 --file kwinrc --group Compositing --key WindowsBlockCompositing false` then reconfigure)
  and/or run the game **borderless-windowed** rather than exclusive fullscreen.

**Deferred / next**
- A compositor-agnostic **xdg-desktop-portal ScreenCast / PipeWire** capture backend would read KWin's
  scanout regardless of unredirect (robust for true direct-scanout games). The `kde.portal` backend is
  installed; deferred until the property fix proves insufficient for some title.

## 2026-07-10 — Occlusion-safe window capture (XComposite off-screen pixmap)

**Done**
- `LinuxController.captureWindow` no longer returns a **black rectangle** where another window overlaps the
  target. It now prefers the window's off-screen backing pixmap via XComposite (`XCompositeNameWindowPixmap`
  → `XGetImage` on the pixmap → `XFreePixmap`), which contains the whole window regardless of what's in
  front. Falls back to the previous on-window `XGetImage` when no compositor is running or the extension is
  unavailable (there, occluded pixels still read black, as before). Gated on a real compositor via the
  `_NET_WM_CM_S<screen>` selection owner (new helpers `captureViaComposite` / `compositorActive`).
- New JNA bindings: `XComposite` (libXcomposite, loaded defensively — `null` when absent) and
  `X11.XGetSelectionOwner` / `X11.XFreePixmap`.

## 2026-07-08 — Minimized-window restore, X error silencer, telemetry resilience

**Done**
- `NativeController` gained two additive (defaulted) methods: `getAllWindows(boolean includeMinimized)` (Linux
  includes unmapped/minimized client windows when requested) and `restoreWindow(GenericWindow)`. Linux maps +
  raises + focuses the window (`XMapWindow` binding added — de-iconifies per ICCCM 4.1.4); Windows uses
  `ShowWindow(SW_RESTORE)`. Lets a consumer un-minimize a target so its pixels become capturable.
- `LinuxController.captureWindow` now re-checks `isWindowViewable` right before `XGetImage`, so a window
  minimized between enumeration and capture returns `null` cleanly instead of provoking an Xlib `BadMatch`.
- `X11ErrorSilencer.install()` + `X11.XSetErrorHandler` binding: installs a no-op Xlib error handler to swallow
  benign non-fatal protocol errors. Must be installed **before** the JavaFX GTK backend (Studio's
  `BotMakerStudio.main`) or GDK warns "XSetErrorHandler() called with a GDK error trap pushed".
- **Telemetry resilience.** `TelemetryFrame.read` now distinguishes a recoverable payload-decode failure
  (`FrameFormatException` — e.g. an old-SDK wire-version skew; framing stays aligned) from a fatal stream error.
  `TelemetryServer` skips a bad frame (reporting the reason once via a new optional `onError` sink) and
  re-accepts across client reconnects instead of dying on the first hiccup. `TelemetryClient` retries a dropped
  socket a bounded number of times (5 × 250ms) rather than permanently disabling on the first `IOException`.

## 2026-07-08 — Window capture via XGetImage (portal/prompt-free on Wayland)

**Done**
- `LinuxController.captureWindow` no longer uses AWT `Robot`. On Wayland every `Robot` grab tunnels through
  xdg-desktop-portal → a screen-share prompt per grab and then a `SecurityException`. It now reads the
  window's pixmap directly with `X11.XGetImage(display, window, 0,0,w,h, AllPlanes, ZPixmap)` — no portal, no
  prompt — for X11/XWayland windows (the only ones enumerable anyway). This also fixes the SDK's
  `Window.capture()` (bots' window vision) on Wayland, not just Studio's preview.
- Re-added the `XGetImage` binding + a minimal `XImage` Structure to `X11.java` (the file's note said these
  were "removed since we use Robot"). Frees the image via its own `f.destroy_image` function pointer
  (`Function.getFunction(...)` — what the `XDestroyImage` macro expands to). ZPixmap decoded to
  `TYPE_INT_ARGB` via the image's red/green/blue masks.
- Contract unchanged: `captureWindow` still returns `null` on any failure so callers keep their full-desktop
  fallback.

## 2026-07-08 — Telemetry wire v2: per-event source line

**Done**
- `TelemetryEvent.Match/Click/Region` gained an `int line()` (1-based bot source line that triggered the
  event, `-1` when unknown), with a line-less convenience constructor so existing call sites are unchanged.
- `TelemetryFrame` bumped `PROTOCOL_VERSION` to 2 and writes/reads the trailing `line` for each type. The
  version guard means a v1 emitter (an old released SDK) and a v2 reader (the new Studio) reject each other's
  frames rather than misreading — local dev must run the local-SNAPSHOT SDK. Lets the Studio highlight the
  running block live during a plain run (see `../botmaker-sdk` `IpcObserver.botLine()` and the Studio ROADMAP).

## 2026-07-07 — Telemetry IPC channel (`com.botmaker.shared.ipc`)

**Done**
- New dependency-free (JNA-only, no JSON) loopback telemetry protocol shared by the SDK (emitter) and
  Studio (consumer), for the Studio's live window-preview overlays. Geometry-only — no image bytes cross
  the socket; the Studio captures the frame itself.
- `TelemetryEvent` — sealed record hierarchy (`Match`/`Click`/`Region`) with a `Target` (window title +
  bounds, or a screen) and `Rect`s. `TelemetryFrame` — length-prefixed binary framing (int32 length +
  1-byte protocol version + 1-byte type tag + field-by-field encode/decode via `DataInput/OutputStream`).
- `TelemetryServer` (Studio side): binds an ephemeral 127.0.0.1 port before launch, accepts one connection
  per run, validates a handshake token, decodes frames to a callback. `TelemetryClient` (bot side):
  best-effort/non-blocking — bounded queue + single writer thread, drops on overflow, disables on IOException.
  `TelemetryClient.fromEnvironment()` returns null when `BM_IPC_PORT` is unset (zero overhead outside Studio).
- Constants in `IpcEnv` (`BM_IPC_PORT`/`BM_IPC_TOKEN`). First tests in this module: `TelemetryFrameTest`
  (round-trip) + `TelemetryChannelTest` (loopback, token reject, overflow non-block). Added JUnit Jupiter
  5.10.2 + Surefire 3.2.5 to the pom.

**Deferred / next**
- Consumed by SDK `api/observe` bridge + Studio preview panel (separate modules). Migrating `BM-INPUT` off
  stdout onto this channel is possible later but out of scope.

## 2026-07-06 — Cursor-preserving background input (pluggable Linux backends)

**Done**
- **Replaced the single XTest click path with a pluggable `LinuxInputBackend`** (`capture/linux/input/`),
  selected by the `botmaker.linux.input` system property / `BOTMAKER_LINUX_INPUT` env var
  (`auto` → xsendevent | `xsendevent` | `uinput` | `xtest`). `auto` never picks a cursor-moving backend.
  `LinuxController` now delegates all input synthesis (`postLeftClick*`, `mouseMove`, `mouseButton`,
  `keyDown/keyUp`, `typeText`, `scroll`) to the chosen backend and destroys it in `close()`.
- **`XSendEventBackend` (new default) — cursor-preserving background clicking.** Delivers synthetic
  `ButtonPress`/`ButtonRelease`/`MotionNotify`/`Key*` straight to the target window via new `X11.XSendEvent`
  + an `XButtonEvent`/`XEvent` JNA struct (padded past `sizeof(XEvent)`); drills to the leaf child under the
  point with `XTranslateCoordinates`. The real cursor never moves and the target need not be focused/on top.
  Reaches X11/XWayland windows — exactly the set this module enumerates/captures. **Verified on KDE Wayland:**
  clicks land on a Swing target while the real cursor stays put.
- **`UinputBackend` — reliable-everywhere opt-in, pure Java** (JNA to `/dev/uinput`, no `ydotool`/`dotool`).
  Absolute virtual pointer + keyboard; the key fix for KWin/GNOME was `UI_SET_PROPBIT INPUT_PROP_POINTER`
  (without it the ABS axes are ignored) plus a tight key-capability set. **Verified:** clicks land precisely
  (cursor warps to the exact target) incl. apps XSendEvent can't reach. Moves the shared cursor (documented);
  falls back to xsendevent if `/dev/uinput` can't be opened. Includes an X-keysym → evdev `KEY_*` table.
- **`XTestBackend`** — the legacy warp-and-click, extracted verbatim, now opt-in only.
- **Additive `NativeController.supportsBackgroundInput()`** (default `false`) reports whether the active
  backend leaves the cursor untouched — non-breaking for the SDK/Studio.

**Deferred / next**
- **Reliable *and* cursor-safe on native Wayland** — impossible in-process (one seat, no per-window
  injection). Needs either the RemoteDesktop portal + libei (still moves the cursor, one-time permission
  dialog) or running the target inside a bot-owned nested/headless compositor (Xephyr/gamescope) — a
  consumer-level (SDK/Studio) architecture change, not a shared change.
- **uinput multi-monitor** — the absolute device maps across a single output; clicks on secondary monitors
  may need per-output coordinate handling.

## 2026-07-06 — Extracted into a standalone shared module

**Done**
- **Extracted the cross-platform native window plumbing** (`capture.*` — `NativeController`,
  `NativeControllerFactory`, `GenericWindow`, and the `windows/` + `linux/` JNA backends) out of the SDK
  and Studio into this dedicated module so both consume one copy instead of duplicating it.
- **Coordinate:** published as `com.github.LiQiyeDev:botmaker-shared` (JitPack); the umbrella reactor
  builds it first. The SDK and Studio depend on it via a `${botmaker.shared.version}` property
  (`0.0.0-SNAPSHOT` in the reactor, a real tag once released).
- **Depends only on JNA** — no JavaFX, no OpenCV. Full-desktop capture backends stay in the consumers.

**Deferred / next**
- **macOS backend** — `NativeControllerFactory.get()` currently throws `UnsupportedOperationException`
  on macOS; a Cocoa/CoreGraphics `NativeController` would light up the Mac path for both consumers.
- **Wayland-native per-window capture** — `captureWindow` returns `null` under native Wayland today;
  a portal/PipeWire backend here would remove the consumers' full-desktop fallback for that case.
