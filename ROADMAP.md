# ROADMAP

A running history of features and refactors for `botmaker-shared`, for future Claude Code sessions.
**Append here whenever you add a feature or refactor** (required — see `CLAUDE.md` › Planning).

Format: newest first. Each dated entry has a **Done** list and, when relevant, **Deferred / next**
(intentionally left for later, with enough context to pick up cold).

---

## 2026-07-23 — Bot-owned-display plan, Phase 0: nested-display GPU probe

Groundwork for **flawless background input** (the game runs in its own nested display `:N`, whose global
pointer is then exclusively the bot's — see `../.claude/plans/review-this-draft-plan-spicy-flask.md`). Phase 0
is the per-machine go/no-go: can a nested **Xephyr** render hardware 3D, or is it software-only (so modern-3D
targets need **gamescope**)?

**Done**

- **`session/GpuProbe`** — new `com.botmaker.shared.session` package. Spins up a throwaway Xephyr, queries
  `glxinfo -B` and `vulkaninfo --summary` against it, classifies GL/Vulkan as HARDWARE / SOFTWARE
  (llvmpipe/lavapipe/swrast) / UNAVAILABLE, checks whether `gamescope` is on `PATH`, and returns a `Result`
  record (`recommendation()` one-liner + raw `detail()` evidence + `summary()` for a diagnostics panel /
  `main()`). Best-effort and never-throws throughout, matching the discovery/probe-path house style — it's
  meant to run in Studio's diagnostics panel where a crash is worse than "can't tell".
- **Display allocation via `-displayfd 1`** (Xephyr picks a free number, writes it to stdout, we read it back)
  — never scans `/tmp/.X11-unix`. This is the same race-free allocation Phase 2's supervisor needs, proven
  here first.
- **No new dependency.** Xephyr is just a nested X server and gamescope embeds Xwayland, so both are driven
  through the existing JNA `libX11`/`libXtst` bindings pointed at `:N`; only process lifecycle
  (`ProcessBuilder`, `-displayfd`) is new. Confirms the plan's "no new Maven module" premise.

**This machine's verdict (recorded):** Xephyr available; OpenGL = **SOFTWARE** (`llvmpipe`); Vulkan reported
HARDWARE (likely lavapipe miscounted — the Vulkan CPU heuristic wants calibration once real hardware/gamescope
is present); gamescope **not installed**. ⇒ Here Xephyr is 2D-only; a modern-3D target would need gamescope.

**Deferred / next (Phase 1):** display-name arg on `LinuxController` (`XOpenDisplay(name)` at ~`:58`) +
`LinuxController.forDisplay(":9")`, bypass the `NativeControllerFactory.get()` singleton so `:0` and `:N`
controllers coexist, and the `DesktopSession`/`Capability`/`HostSession` seam wrapping today's behaviour.

## 2026-07-23 — Heroic detection stops depending on a hash nothing carries

**Done**

- **`launch/HeroicLibrary`** — reads Heroic's own on-disk config (Epic `legendaryConfig/legendary/installed.json`,
  GOG `gog_store/installed.json` + `store_cache/gog_library.json`, `sideload_apps/library.json`, from both the
  native and Flatpak roots) into `Game(appName, title, installPath, executable)`. Hand-rolled brace/regex
  scanning, not Jackson — shared has no JSON dependency (same call as `LdPlayerPlatform`) and this is a handful
  of string fields. Best-effort and total throughout; parses are cached for 10 s so a poll loop doesn't re-read
  three files per tick.
- **`Launcher.isRunning` takes a token *list*.** A `heroic:` target's token is Heroic's app name — an opaque
  hash for Epic games — and modern Heroic launches in-process, so the live process is `wine`/`umu-run`/`proton`
  carrying the game's *executable* and *install path* and the window is named after its *title*. None of those
  is the app name, which is why detection worked only sometimes. All four are now tried, most distinctive
  first, and every command-line token is tried before any window title (the process table is the stronger
  evidence). Unreadable config degrades to the bare app name, i.e. the old behaviour.
- **Tokens shorter than 3 chars are dropped** (`Game.runningTokens`). A title like "Go" would match half the
  process table, and a false "already running" is indistinguishable from a launch that silently did nothing —
  the same failure the `LAUNCHER_EXECUTABLES` deny-list exists to prevent, which is untouched and still applies.
- **Studio's `HeroicLibraryScanner` now delegates here** for the game list (it keeps only the `InstalledGame`
  mapping and the `icons/` artwork probe, and now probes *every* config root rather than the first). Two
  parsers of one config file drift; this is the "shared owns what its consumers would rebuild" rule.
- `HeroicLibraryTest` fixtures keep the parts that break a naive regex: nested objects, escaped Windows
  separators, a brace inside a title, fields in an unhelpful order.

**Deferred / next**

- Heroic's newer backends (`nile` for Amazon, `comet` for GOG) aren't in `RunningProbe`'s "doing library work"
  exclusion the way `legendary` is. Harmless today — they'd only ever cause a *false positive*, and only while
  Heroic is syncing — but worth adding if one shows up in a `[Target] ignoring pid` trace.
- The install-path token is matched as a substring of the whole command line. A game installed *inside* another
  game's directory would cross-match; no real layout does this, but a path-boundary check is the fix if it ever
  bites.

---

## 2026-07-23 — uinput is the real-input path, and targeted keys actually reach the target

**Done**

- **`UinputBackend`'s keymap was covering a third of the key set, silently.** It mapped letters, digits,
  space, `-`/`.`/`,`, Return, Tab, BackSpace, Escape and Shift — and nothing else. `key()` looked the keysym
  up, got `null`, and `return`ed. So `CTRL`, `ALT`, `META`, `DELETE`, the four arrows and `F1`–`F12` — every
  one of them a constant the SDK's `Key` enum publishes — emitted no event and logged no error. Added the
  missing evdev codes (both `_L` and `_R` modifier keysyms, since `typeVia` and raw-keysym callers may send
  either), and an unmapped keysym now logs once per keysym instead of vanishing. `UinputKeymapTest` pins the
  coverage: shared can't import the SDK's `Key`, so the constants are mirrored there and a future gap fails
  the build rather than showing up as "that key does nothing in the game".
- **`useReliableInput()` now escalates uinput → xdotool → XTest**, was xdotool → XTest → uinput. uinput is a
  kernel virtual device, so its events are indistinguishable from a real keyboard/mouse and reach Wine/Proton
  and native Wayland clients; xdotool and XTest both ride XTEST, which a game under XWayland can still ignore.
  The old order meant xdotool essentially always won and uinput was unreachable in practice. Each fall-through
  logs why, with the udev/`input`-group hint on the uinput failure.
- **A targeted key under a cursor-moving backend now raises the target first.** `LinuxInputBackend`'s
  `key(Pointer window, …)` default delegates to the focused-window path, which is the only thing uinput/
  xdotool/XTest can do — they drive one real device and carry no window. The consequence was that
  `Keyboard.press(source, key)` sent the key to whatever held focus (the Studio) while `Mouse.click` worked,
  because clicks go through `clickRestoringCursor` on the real-pointer path. `LinuxController.keyVia` now
  checks `preservesCursor()` and, when false, focuses the window itself before sending the key globally — the
  keyboard analogue of what `clickWindow` already did. The raise is cached per target for
  `KEY_FOCUS_TTL_MS` (1s) so `typeText` doesn't re-raise per character, and re-done after that so focus
  drifting mid-sequence doesn't silently redirect the rest of the string. `focusWindow` gained a
  `focusHandle(Pointer)` sibling so the key path doesn't rebuild a `GenericWindow` it already has the handle
  for.

**Deferred / next**

- Background *and* game-accepted input is not achievable on X11: the only per-window mechanism is
  `XSendEvent`, and rejecting `send_event=True` is precisely what makes a game a game. The real fix is the
  xdg-desktop-portal **RemoteDesktop** (libei) interface, which is per-session rather than per-window but is
  accepted as real input. Still deferred; the same note is in the Wayland capture entry.
- uinput's absolute axes map across a single output, so on a multi-monitor layout clicks may land on the
  primary only. Untested beyond one screen.

---

## 2026-07-23 — shared takes the rest of `sdk/internal`: matching, desktop capture, project properties

**Done**

- **`com.botmaker.shared.opencv`** — `OpencvManager` (template matching), `ColorMatcher` (CIELAB ΔE colour
  clusters), `ResolutionScaler`, `RawMatch`/`RawColorMatch` and `OpenCvNative` moved here from the SDK, with
  their four test classes. The SDK matches at run time and Studio's Magic Wand matches at edit time, so both
  consumers needed the engine; only the SDK had it.
- **One OpenCV loader, not three.** There were three independent `loadLocally()` calls each guarded by its own
  `loaded` flag — the SDK's `OpenCvNative`, Studio's copy under `ui/app/capture` (whose javadoc admitted it
  mirrored the SDK's), and a third inside shared's own `OcrNative`. All three could run in one JVM, so nothing
  stopped the native from being extracted more than once. `shared.opencv.OpenCvNative` is now the only one;
  `OcrNative.ensureOpenCvLoaded()` and Studio's `MagicWand` delegate to it and Studio's copy is deleted.
- **Full-desktop capture** (`ScreenCapture`, sealed `CaptureBackend`, `RobotCapture`, `SpectacleCapture`) moved
  into `shared.capture`, beside per-window capture. shared's own `CLAUDE.md` used to say full-desktop capture
  "deliberately lives in the consumers"; that rule was wrong on its own terms — the platform knowledge is
  identical to per-window capture's, and Studio's picker wants the same grab. The note is corrected.
- **`com.botmaker.shared.config.ProjectProperties`** — the `botmaker-project.properties` file: its name, its
  key names, the caching and the best-effort parsing. This is the literal case the "a shared type owns the
  keys" rule describes: Studio's `ProjectCreator` *writes* the very keys the SDK reads, and both sides had
  them as string literals. `ProjectCreator` now uses the constants; the SDK's `ProjectDefaults` shrinks to the
  part shared genuinely cannot do — mapping raw values onto `CaptureSource`/`Size`.
- **The raw-record boundary is what makes this work.** shared returns `RawMatch`/`RawColorMatch` and takes the
  authored resolution as a `java.awt.Dimension`; the SDK maps to `MatchResult`/`ColorMatch` and converts
  `Size` once, in `ImageTemplate.authoredSize()`. No SDK type crosses into shared.
- **Found while moving: `OpencvManagerTest` had not run in a long time.** It loaded its template behind
  `assumeTrue(Files.exists("src/main/resources/images/accept_button.png"))`, and that file exists in neither
  module — so the assumption aborted the class and all six tests reported as *skipped*, which reads as green.
  It now generates its own deterministic noise patch: nothing about template matching needs a photograph of a
  button, and a test that cannot be skipped by a missing file cannot rot that way again. shared: 81 tests.

**Deferred / next**

- `ImageDisplay` (Swing preview) stayed in the SDK with the `internal` dev harnesses that are its only
  callers; a JFrame is not something the JavaFX Studio would consume.
- `ProjectProperties` has no tests — it needs a classpath-resource fixture to be worth writing.

---

## 2026-07-23 — shared owns the launch stack, and a launcher stops counting as its games

**Done**

- **New `com.botmaker.shared.launch`.** The launch stack lived in the SDK, where Studio (which deliberately
  does not depend on the SDK) could not reach it — so it had been copied instead: the SDK's `UriLauncher` and
  Studio's `BrowserLauncher` each carried a javadoc naming the other, and Studio's `LaunchTargetNames`
  re-derived by hand the spec grammar `LaunchTarget.parse` already had. shared is the common ancestor, so it
  owns it now: `UriLauncher` (OS protocol handler, **keeping** the `rundll32 url.dll,FileProtocolHandler`
  note that records why `explorer.exe` opens Documents for Epic's query string), `GameLauncher` (the
  Steam/Epic/Heroic/Faugus protocol-then-CLI ladders, `exe`/`cli`/`kill`/`isProcessRunning`), `LaunchKind`
  (the `PlatformId` pattern: persisted wire `id()` + `displayName()`, total `fromId` → `UNKNOWN`),
  `LaunchSpec` (total parse of `<kind>:<token>`, plus `describe`/`shortLabel`/`runningToken`/`fileName` and
  the `emu-app` split), `RunningProbe`, `EmulatorAppLauncher` (on shared's own `Platforms`/`AdbDevice`, not
  the SDK's wrappers) and the `Launcher` facade. `Diag` replaces the SDK's `Debug` throughout.
- **Fixed: a launcher that merely *knows about* a game no longer counts as running it.** Reported symptom —
  a `heroic:` target refused to launch because it read as already running, and worked the moment the Heroic
  launcher was quit. `commandLineMentions` matched any process carrying the token, and Heroic's own UI
  carries every `AppName` in its library. `RunningProbe` now consults a deny-list per process first:
  the launcher UIs (`heroic`, `EpicGamesLauncher`, `steam`, `steamwebhelper`, `faugus-launcher`, an Electron
  shell hosting one) are skipped, while the wrappers a game genuinely runs under (`reaper`, `proton`,
  `umu-run`, `wine`, `gogdl`) still count, and `legendary` counts only with the `launch` verb in its argv
  (Heroic spawns it for library work too). Each skip is traced, so the next false positive is readable from
  the console instead of found by quitting apps one at a time.
- **Deny-list sees through wrapper scripts.** Found while testing: the JDK reports a script's
  `ProcessHandle.Info.command()` as the *interpreter*, so a `bash`-wrapped `heroic` — which is the normal
  Linux packaging shape (AUR/AppImage entry points, `flatpak run`) — slipped straight past the exclusion.
  `programNames` now also reads the script name back out of argv when the command is a known interpreter.
- Tests: `LaunchSpecTest` pins the persisted spec grammar (a round-trip contract — change an id and every
  existing project's target stops resolving); `RunningProbeLauncherTest` stands up **real** processes under
  the names in question, since a mocked process table would prove nothing about a deny-list that is entirely
  about what the OS reports.

**Deferred / next**

- The rest of the `sdk/internal` sweep (`capture`, `opencv`, `config/ProjectDefaults`) — planned, not started.

---

## 2026-07-22 — Real input that games accept, with the cursor put back

**Done**

- **`XdotoolBackend`** (`capture/linux/input/`) — real pointer/keyboard input by driving the `xdotool` CLI.
  It is the same XTEST extension `XTestBackend` binds directly, but chains a whole gesture into one
  invocation (`mousemove --sync X Y click B mousemove restore`), so positioning, clicking and restoring the
  pointer happen with one round trip and xdotool's own bookkeeping of the prior position. Measured ~2 ms per
  spawn, cheaper than the settle delay the gesture needs anyway. Falls back to `XTestBackend` when xdotool
  isn't installed, logging the distro install hint once.
- **It never passes `--window`, deliberately.** Verified against the installed xdotool: that variant uses
  `XSendEvent`, whose `send_event` flag is exactly what games reject, it takes coordinates from the *current
  pointer position* rather than an argument, and `mousemove --window` moves the real cursor anyway — so it is
  not even a cursor-safe way to click a specific point. Coordinate-accurate background clicking stays
  `XSendEventBackend`'s job. Measured directly: with Dolphin behind a maximized IntelliJ,
  `mousemove --window <dolphin> 100 100` put the pointer at the right absolute coordinate but
  `getmouselocation` reported IntelliJ as the window under it — real input hits whatever is **topmost**.
  Hence `clickWindow` raises the target first (`windowactivate --sync`).
- **`NativeController.cursorPosition()` + `clickRestoringCursor(x, y, button)`.** The click policy lives in
  shared rather than in the SDK's `Mouse` because Studio's `PilotInputService` needs the identical behaviour
  and Studio does not depend on the SDK. Default implementation is read → move → settle → press → release →
  move back; the xdotool backend overrides it with its atomic form. `cursorPosition` is `XQueryPointer`
  (already bound) on Linux and the already-bound `GetCursorPos` on Windows.
- **Escalation order is now xdotool → XTest → uinput** (was uinput → XTest). XTEST reaches X11 games and
  needs no `/dev/uinput` permission, so requiring device access first was backwards.
- **Windows: `useReliableInput()` stopped being a no-op.** It had inherited the default returning `true` on
  the claim that `PostMessage` is "both reliable and cursor-safe" — the second half holds, the first does
  not, since a raw-input game never reads its message queue. Escalating now routes `postLeftClick*` and the
  targeted `keyDown`/`keyUp`/`typeText(window, …)` overloads through real device input, and
  `supportsBackgroundInput()` reports honestly afterwards.
- **Windows keyboard sent scancode 0** — a separate root cause from the click bug. `keybd_event` was called
  with `bScan = 0`, which DirectInput/RawInput games ignore because they read scancodes rather than virtual
  keys. Now maps the VK through `MapVirtualKeyA` and sets `KEYEVENTF_SCANCODE`.
- Bound `X11.XKeysymToString` so a keysym can be handed to xdotool by name without a parallel table that
  would drift from the SDK's `Key` constants.

**Deferred / next**

- Windows input still uses `keybd_event`/`mouse_event` rather than `SendInput`. `SendInput` is the modern,
  atomic API and would be the fuller fix, but needs the `INPUT` struct plumbing `User32.java` deliberately
  avoids. The scancode fix above addresses the observed failure; revisit if a game still ignores input.
- Only Linux/X11 was verified on real hardware (click lands + cursor restored, through the Java path).
  The Windows paths compile but are untested — no Windows machine in this session.

---

## 2026-07-22 — One diagnostic switch for both modules (`Diag`)

**Done**

- **`com.botmaker.shared.Diag`** — the process-wide diagnostic-output flag, with `log`/`error(String)`/
  `error(String, Throwable)`. The SDK's `api.Debug` is now a **thin delegate** over it, so the single Studio
  "Debug output" toggle governs `shared` too. The flag had to move *down*: `shared` can't depend on the SDK,
  and a second flag here would have silently diverged the first time only one was flipped.
- **Every diagnostic print in `shared` now goes through `Diag`** — ~35 raw
  `System.out/err.println` + `printStackTrace` calls in `LinuxController`, `UinputBackend`, `XTestBackend`
  and `WindowCapture`. They printed unconditionally before, which is why a bot with debug **off** was still
  noisy. Paired "message + stack trace" sites collapsed into the `error(String, Throwable)` overload —
  a bare `printStackTrace()` is the one thing that can't be silenced.

**Caveat worth knowing:** `Diag` defaults to **on** and the SDK narrows it in `Debug`'s static initializer.
Anything `shared` prints before the SDK's `Debug` class is first touched therefore prints under the default.
In practice a bot reaches `shared` through an SDK facade that traces first, so the window is a line or two at
most; it is not worth a lazier seeding mechanism unless it actually bites.

---

## 2026-07-22 — Escalating to an input backend whose clicks actually land

**Done**

- **`NativeController.useReliableInput()`** (default `true`, i.e. nothing to do) — a consumer that needs its
  input to *reach the target* can ask the controller to give up the cursor-preserving guarantee. Windows
  keeps the default: `PostMessage` is already both reliable and cursor-safe.
- **`LinuxController.useReliableInput()`** swaps the live `inputBackend` (now `volatile`, no longer final)
  from `XSendEventBackend` to uinput, else XTest, else stays put and returns `false`. `XSendEvent`'s events
  carry `send_event=True`, which every Wine/Proton game and many toolkits drop — which is why the pilot's
  Interact mode clicked nothing on exactly the targets it exists for. Idempotent; a no-op when the active
  backend already moves the cursor (including an explicit `botmaker.linux.input=uinput/xtest` at startup).
- The swap is **process-wide and sticky**, so `supportsBackgroundInput()` starts returning `false` afterwards
  — that's the honest signal Studio forwards to the pilot's "moves the computer's real cursor" warning. Only
  Studio's `PilotInputService` calls it, lazily on first Interact use, so bot-only sessions keep the
  cursor-safe default.

---

## 2026-07-22 — Targeted keyboard input on `NativeController`

**Done**

- **`NativeController.keyDown/keyUp/typeText(GenericWindow, …)`** — the keyboard counterpart of the existing
  `postLeftClick(GenericWindow, …)`. All three are `default` methods delegating to the window-less path, so the
  change is additive (a `null` window also falls back to the focused-window path).
- **Windows** (`WindowsController`) posts `WM_KEYDOWN`/`WM_KEYUP`/`WM_CHAR` straight to the target HWND
  (`User32` gained those three constants), inheriting `Clicker`'s "no focus stolen, background-capable"
  property — and its caveat (raw-input/DirectInput games ignore posted messages).
- **Linux** (`LinuxController` + `LinuxInputBackend.key(Pointer, int, boolean)`) routes to a specific window's
  client via the cursor-preserving `XSendEventBackend` (sends the `Key*` events to that window instead of
  `focusedWindow()`); the cursor-moving `XTest`/`uinput` backends keep the global default (they drive the one
  real device — no per-window notion). `typeText` was refactored to a shared `typeVia(window, text)` helper.

---

## 2026-07-20 — `PlatformId` enum + emulator discovery de-duplication

**Done**

- **`PlatformId` replaces the free-form `String platformId`.** A closed enum of the five known products,
  each carrying its stable wire `id()` (unchanged, so stored values still resolve) and its `displayName()`.
  `EmulatorInstance.platformId` and `Platforms.PlatformStatus.platformId` are now typed, `EmulatorPlatform.id()`
  returns it, and `displayName()` is a default derived from it — so the name lives in exactly one place.
  `fromId` is total (unknown/null → `UNKNOWN`) and a null platform defaults to `UNKNOWN` rather than NPE-ing
  later. Deliberately **not** a Jackson type: shared has no Jackson dependency and nothing serializes an
  `EmulatorInstance` today, so persisting `id()` + reading through `fromId` is the contract instead of pulling
  a new dependency into the module both consumers inherit.
  - Fixed a real drift this exposed: the platform said "MuMu Player" while Studio's own brand switch said
    "MuMu". One enum, one answer (now "MuMu Player").
- **`EmulatorInstance.identity()`** (`platformId@host:adbPort`) and **`brand()`**, so the scanner and the two
  Studio pickers stop building the key three different ways.
- **`Platforms.PlatformStatus.statusLine()`** — the one-line per-product summary both pickers rendered from
  their own byte-identical copies.
- **`WindowsRegistry.firstNonBlank`** replaces the same private helper copy-pasted into all five platforms.
- **`PlatformScan.directory`** collapses the near-identical "list the install dir, parse each entry" walk in
  `LdPlayer`/`Memu`/`MuMu` into a template method taking a per-entry lambda. Side benefit: MuMu now gets the
  per-entry error isolation the other two already had, so one corrupt instance config can no longer hide the
  working instances beside it.

## 2026-07-20 — Emulator discovery diagnostics (per-product status)

**Done**
- **`EmulatorPlatform.isInstalled()`** — a cheap "is this product installed at all" check (registry/install-dir
  present), independent of how many instances are configured/running. Implemented across all five platforms by
  reusing their existing install detection.
- **`Platforms.discoverDetailed()` → `DiscoveryReport`** — discovery plus a per-product `PlatformStatus`
  `(platformId, displayName, installed, instanceCount, error)`, so a consumer UI can tell the user what it
  actually saw ("MuMu: installed · 2 instances", "BlueStacks: not installed", "LDPlayer: scan error") instead of
  a bare empty list. `discoverAll()` now delegates to it (single code path); a per-product failure is recorded as
  a status `error` rather than sinking the scan.

---

## 2026-07-19 — Emulator launch/stop + app queries (Phase 2)

**Done**
- **`EmulatorInstance` now carries host `launchCommand` / `stopCommand`** (both `List<String>`, empty when
  the product ships no console tool or it couldn't be located) alongside the existing name/port. A 4-arg
  convenience constructor keeps the parsers' pure form; `withCommands(...)`, `canLaunch()`, `canStop()`,
  `endpoint()` round it out. This is what lets a consumer bring an instance *up* — the ADB transport can only
  talk to an already-running one.
- **New `EmulatorLauncher`** (best-effort, never-throws) spawns those commands via `ProcessBuilder`
  (fire-and-forget: the console tools return while the emulator boots, so `true` = "dispatched", not "up").
- **Each platform now resolves its console tool at discovery and attaches launch/stop** (grounded via the
  vendors' CLI docs): LDPlayer `ldconsole.exe launch|quit --index <i>`; MuMu
  `shell/MuMuManager.exe control -v <i> launch|shutdown`; MEmu `memuc.exe start|stop -n <vmFolder>`;
  BlueStacks `HD-Player.exe --instance <token>` (launch only — no documented clean-stop CLI; selector is the
  config *token*, not the display name); Gameloop = the engine exe (launch only). The command-builders
  (`withLaunch(...)` / `parseConf(conf, hdPlayer)`) are package-private + pure and unit-tested.
- **`AdbDevice` gained app-query helpers**: `installedApps()` (`pm list packages -3`), `isInstalled(pkg)`,
  `currentApp()` (foreground package from `dumpsys activity activities`). Output parsers (`parsePackageList`,
  `parseForegroundPackage`) are package-private + pure (`AdbDeviceTest`).

**Deferred / next**
- MEmu/MuMu launch selectors assume folder-name == VM name / index alignment — smoke-test on a live install.
- A readiness helper (poll ADB after launch) lives SDK-side; shared stays fire-and-forget.

## 2026-07-19 — Gameloop discovery (last scaffolded emulator platform)

**Done**
- **`GameloopPlatform` now discovers for real**, replacing the `ScaffoldPlatforms` stub (file deleted).
  Gameloop (Tencent AndroidEmulator, `<install>/ui/AndroidEmulator(En).exe`) exposes no per-instance
  ADB-port config, so — unlike the config-parsing platforms — discovery detects the install (registry
  uninstall/Tencent keys, else the default `%ProgramFiles%/TxGameAssistant/ui/` engine path) and returns its
  single primary instance on the documented fixed loopback port **5555** (ADB debugging must be enabled in
  Gameloop settings). Multi-instance ports are undocumented and deliberately not fabricated.
- `singleInstance()` / `defaultEnginePath()` are package-private + pure for unit testing without an install
  (`GameloopPlatformTest`). All five platforms in `Platforms.ALL` now discover for real.

**Deferred / next**
- Gameloop Multi-Instance manager ports (undocumented) — add if a user needs more than the primary instance.

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
- Studio emulator-screen capture picker (the reason the transport lives here) — not built yet.

---

## 2026-07-18 — MEmu + MuMu discovery (emulator)

**Done**
- **`MemuPlatform`** — MEmu is VirtualBox-based: registry `InstallDir` → `<install>\MemuHyperv VMs\<vm>\<vm>.memu`
  (a `.vbox` XML); the ADB port is the `hostport` of the NAT `<Forwarding>` rule whose `guestport="5555"`
  (attribute-order-independent). Name from `<Machine name>`, else the folder. Pure `parseVm` + 4 unit tests.
- **`MuMuPlatform`** — MuMu Player 12: registry `InstallDir` → `<install>\vms\MuMuPlayer(-Global)-12.0-<index>`;
  ADB port = `16384 + 32*index`; name from `config\vm_config.json`'s `playerName`, else `MuMu-<index>`. Pure
  `parseInstance` + 4 unit tests.
- Both promoted out of `ScaffoldPlatforms` into their own classes; only **Gameloop** remains scaffolded.

**Caveat:** the `.memu` forwarding format and the MuMu `16384+32·index` convention are the documented layouts
but haven't been checked against a live install here — same live-smoke-test caveat as BlueStacks/LDPlayer.

**Deferred / next**
- Gameloop discovery (still a scaffold).

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
