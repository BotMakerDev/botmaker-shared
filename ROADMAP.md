# ROADMAP

A running history of features and refactors for `botmaker-shared`, for future Claude Code sessions.
**Append here whenever you add a feature or refactor** (required — see `CLAUDE.md` › Planning).

Format: newest first. Each dated entry has a **Done** list and, when relevant, **Deferred / next**
(intentionally left for later, with enough context to pick up cold).

---

## 2026-07-18 — Android emulator capability (`com.botmaker.shared.emulator`)

**Done** (Phase 3 refactor — hoisted from the SDK so Studio can reuse it without depending on the SDK)
- **New `emulator/` package: discovery + ADB transport, shared by both consumers.** The SDK's
  `api.emulator.Emulator` wraps it as a `CaptureSource` at runtime; a Studio capture picker can screen-grab an
  emulator at edit time. This removes a discovery-logic duplicate that had been copied into Studio.
- **`AdbDevice`** — one dadb connection (`dev.mobile:dadb:1.2.9`, pure-JVM ADB, no `adb.exe`/server). `screencap()`
  (binary-safe `exec:screencap -p` → ImageIO), `tap`/`swipe`/`key`/`text`/`startApp`/`getProp`/`shell`,
  `isConnected`. dadb owns the RSA key (`~/.android/adbkey`). Kotlin package is `dadb.*`, not the groupId.
- **Discovery** — `EmulatorPlatform` + `EmulatorInstance`; `Platforms.discoverAll()` aggregates. `BlueStacksPlatform`
  (parses `bluestacks.conf`) and `LdPlayerPlatform` (`leidian<i>.config`; ADB port 5555+2·i; playerName read via
  **regex**, so shared needs no Jackson) discover for real; MEmu/MuMu/Gameloop scaffolded. `WindowsRegistry`
  (`reg query`) locates install dirs. Windows-first, best-effort, never throws. Parsers pure + unit-tested
  (`BlueStacksPlatformTest`/`LdPlayerPlatformTest`, moved here from the SDK).
- **Dependency:** `dev.mobile:dadb` added (compile) → pulls kotlin-stdlib, which now rides into **every** consumer
  including Studio's app-image. Accepted so Studio can later preview an emulator screen in the capture picker.

**Deferred / next**
- Native-window capture backend for BlueStacks (it renders a real window) behind the same transport — faster than
  per-frame `screencap` PNGs.
- MEmu/MuMu/Gameloop discovery parsers (scaffolds return empty today).
- Studio emulator-screen capture picker (the reason the transport lives here) — not built yet.

---

## 2026-07-18 — OCR core (`com.botmaker.shared.ocr`)

**Done**
- **New `ocr/` package — on-screen text recognition, shared by both consumers.** Put in shared (not the SDK)
  so Studio can reuse it later without depending on the SDK — the first capability shared hosts above the
  window layer. The SDK exposes it through a new `api.vision.Text` facade; Studio wiring is deferred.
  - `OcrEngine` — core. `text(img[, opts])` → whole-image string; `recognize(img, opts)` → per-word/line
    `TextResult`s with source-local boxes + confidence (boxes mapped back down through the upscale factor).
    Tess4J's `Tesseract` is **not** thread-safe, so it's held in a `ThreadLocal` (bots are multi-threaded,
    same reason the SDK's `VisionContext` is thread-local).
  - `OcrPreprocessor` — OpenCV pass (grayscale → upscale (cubic) → binarize Otsu/adaptive → optional invert)
    that makes game fonts viable. Re-implements a minimal `BufferedImage↔Mat` bridge so shared does **not**
    depend on the SDK's `OpencvManager`.
  - `OcrOptions` (immutable record + `with*` copy methods) — languages, PSM, OEM, upscale, binarize mode,
    invert, char whitelist, WORD/LINE granularity. `OcrNative` — idempotent loader mirroring the SDK's
    `OpenCvNative` (OpenCV native + extracting bundled `tessdata` to a temp dir; Tesseract needs a real
    filesystem datapath, not a classpath resource). `TextResult` — immutable record (AWT `Rectangle`, since
    shared has no SDK geometry types).
- **Deps added to `pom.xml`:** `org.openpnp:opencv:4.9.0-0` (exact version the SDK already uses — one native
  on any consumer classpath) and `net.sourceforge.tess4j:tess4j:5.19.0` (bundles Windows DLLs). Both compile
  scope so Studio picks them up transitively without an SDK dependency. **This makes the old "shared depends
  only on JNA, no OpenCV" note false** — CLAUDE.md updated.
- **Bundled traineddata** — `tessdata_fast` eng/chi_sim/jpn/kor under `src/main/resources/tessdata/` (~10 MB
  total; `_fast` keeps the jar lean vs. the ~80–100 MB standard models). Adding a language is data-only (drop
  the file + add the code to `OcrNative.BUNDLED_LANGUAGES`).
- **Tests** — `OcrEngineTest` renders known text via Java2D (no screen dependency) and asserts recognition,
  plausible boxes, and char-whitelist behaviour. Exercises the full OpenCV-native + Tesseract path.

**Deferred / next**
- **Studio wiring** — Studio can call `OcrEngine` directly for editor features (text-region picker, live
  text read on the capture overlay). Not started.
- **Linux** — Tess4J bundles only Windows DLLs; Linux needs system `libtesseract`/`liblept`. A missing native
  surfaces as an `UnsatisfiedLinkError` (not swallowed). Document the prerequisite / consider bundling.
- **CJK jar size** — if the bundled CJK models bite, move them to lazy on-first-use extraction/download while
  keeping `eng` bundled; `OcrNative` is already structured so language set is data-only.

---

## 2026-07-14 — Overlay-above-fullscreen: remap so the WM re-reads the window type

**Done**
- **`X11Utils.promoteAboveFullscreen` now unmaps→sets `_NET_WM_WINDOW_TYPE=NOTIFICATION`→remaps** instead of a
  bare `XChangeProperty`. Most WMs read the window type **only at map time**, so setting it on an already-mapped
  overlay was silently ignored — that's why overlays still slipped behind a fullscreen app (e.g. Firestone).
  The remap forces a re-read and the WM reclassifies the overlay as a notification (stacked over fullscreen).
  Guarded by a new `isWindowType` check so repeat calls (Studio's re-raise timer) skip the remap — no flicker.
- New **`X11.XUnmapWindow`** binding. Still best-effort (swallows errors; a true exclusive-fullscreen
  Wine/Proton game that bypasses the WM remains uncoverable — documented limitation).

---

## 2026-07-14 — Promote Studio overlays above fullscreen windows (X11)

**Done**
- **`NativeController.promoteOverlayAboveFullscreen(String windowTitle)`** (default no-op; implemented by
  `LinuxController`, no-op on Windows). Studio's transparent always-on-top overlays (Overlay Editor, capture
  toolbar/surfaces) were only `_NET_WM_STATE_ABOVE`, which the WM still ranks below a fullscreen
  (`_NET_WM_STATE_FULLSCREEN`) game — so overlays vanished behind fullscreen apps. `LinuxController` resolves
  the overlay's X11 window by title (`X11Utils.getClientList` + `getWindowTitle`) and promotes it.
- **`X11Utils.promoteAboveFullscreen(display, window)`** (mirrors `setKeepComposited`): sets
  `_NET_WM_WINDOW_TYPE = _NET_WM_WINDOW_TYPE_NOTIFICATION` (notification surfaces draw over fullscreen on
  mutter/KWin) + sends a `_NET_WM_STATE` ADD `_NET_WM_STATE_ABOVE` root client message, then `XRaiseWindow`.
  New `X11.XA_ATOM` / `_NET_WM_STATE_ADD` constants. Best-effort (swallows errors; degrades on Wayland).

---

## 2026-07-12 — Reliable key recording + EWMH window activation

**Done**
- **X11InputListener: keysym lookups on a dedicated connection.** `XKeycodeToKeysym` was being called on the
  `dataDisplay` while it was blocked inside `XRecordEnableContext`; the first call's lazy keyboard-mapping
  fetch is a server round-trip on that busy connection, which returned `NoSymbol(0)` and silently dropped
  **every** recorded keystroke (mouse events still worked — they never resolve a keysym). Added a third,
  dedicated `keysymDisplay` connection, used only on the record/callback thread, for all keycode→keysym
  lookups. This is the fix for "macro recorder registers clicks/scroll but not keyboard" on X11.
- **`LinuxController.focusWindow`/`restoreWindow` now also EWMH-activate.** Added an `_NET_ACTIVE_WINDOW`
  client-message path (`activateWindow`) sent to the root with `SubstructureRedirect|SubstructureNotify`, so
  reparenting/EWMH window managers that ignore a bare `XRaiseWindow`/`XSetInputFocus` still bring the target
  to the foreground. New `X11.XClientMessageEvent` struct + `ClientMessage`/substructure-mask constants + a
  second `XSendEvent` binding. Best-effort (no-op when the WM doesn't advertise the atom).

---

## 2026-07-11 — Global input listener (X11 XRecord) for the Studio macro recorder

**Done**
- **New `input/` package: passive global input observation.** `InputListener` (interface) +
  `InputEvent` (sealed record family: `ButtonPress`/`ButtonRelease`/`Motion`/`KeyPress`/`KeyRelease`, absolute
  screen coords + wall-clock timestamp; key events carry the shift-resolved keysym) + `InputListenerFactory`
  (OS-gated; `isSupported()` = Linux). Mirrors the `ipc.TelemetryEvent` "one wire vocabulary both modules
  share" pattern. This is the **observe** counterpart to the existing input **synthesis** on `NativeController`
  — nothing here injects input, and XRecord cannot swallow events (passive only), so the app keeps receiving
  its input normally.
- **`capture/linux/XRecord.java`** — JNA bindings for the XRecord extension (same `libXtst` as `XTest`).
  To avoid mapping the intricate `XRecordRange`/`XRecordInterceptData` structs, it uses `XRecordAllocRange`
  and reads/writes the two `device_events` bytes + the intercept-data fields at documented byte offsets.
  Added `X11.XKeycodeToKeysym` (the inverse of the existing `XKeysymToKeycode`) to decode recorded keys.
- **`input/linux/X11InputListener.java`** — opens two X connections (control + a data connection for the
  blocking `XRecordEnableContext` loop on a named daemon thread), decodes device events to `InputEvent`s,
  tracks Shift for correct key casing, and `close()`s by `XRecordDisableContext` on the control connection —
  the same daemon-thread + `volatile boolean` + unblock-on-close lifecycle shape as `ipc.TelemetryServer`.

**Deferred / next**
- **Windows listener** (`SetWindowsHookEx WH_MOUSE_LL/WH_KEYBOARD_LL`) to make the recorder cross-platform.
- Modifier **combos** (Ctrl/Alt+key): currently standalone modifier keys are surfaced but the Studio
  translator drops them; a real combo → `Keyboard.combo(...)` path is future work.

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
