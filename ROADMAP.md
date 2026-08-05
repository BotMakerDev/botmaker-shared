# ROADMAP

A running history of features and refactors for `botmaker-shared`, for future Claude Code sessions.
**Append here whenever you add a feature or refactor** (required — see `CLAUDE.md` › Planning).

Format: newest first. Each dated entry has a **Done** list and, when relevant, **Deferred / next**
(intentionally left for later, with enough context to pick up cold).

---

## 2026-08-05 — ADB is the wrong instrument for starting a Waydroid app

**225 tests (+8).** New `emulator/WaydroidApps.java` + `WaydroidAppsTest`; the gamescope wrapping extracted
to `WaydroidPlatform.gamescoped`; `EmulatorAppLauncher` gains a per-platform start ladder.

**Done.** With the emulator long since up, an `emu-app:` target still didn't start. The waiting wasn't the
problem — the instrument was. Read out of `tools/actions/app_manager.py`, `waydroid app launch` does three
things `monkey -p … LAUNCHER 1` does none of: it **unfreezes** the container (Waydroid's default
`suspend_action = freeze` leaves an idle container frozen — still answering on its ADB port, still "running"
to every probe we have, and acting on nothing), it sets **`waydroid.active_apps`**, which is what decides
which app Waydroid actually renders, and it **starts the session** when one isn't up. So a Waydroid cold
start is now one command — `gamescope --expose-wayland waydroid app launch <pkg>` — with the app, not the
Android launcher, as the surface.

Verified live: `waydroid app launch com.android.documentsui` returns 0 immediately and
`waydroid prop get waydroid.active_apps` reports that package. That property is now the primary launch
verification precisely because it needs no ADB, so it survives the in-guest trust prompt (`ro.adb.secure=1`).
ADB remains the ladder's second rung — `monkey`, then a `cmd package resolve-activity` + `am start -n` for an
app whose launcher intent monkey won't match — and the only rung for the console-tool products.
`WaydroidApps.list()` reads apps *with their display names* from the same CLI, guarded on a running session:
the command reaches the container through a **session-bus** object lookup, which D-Bus activates — measured,
running it against a stopped session left the session up, which is fine when launching and startling when
merely listing.

---

## 2026-08-05 — An open ADB port is not a booted Android

**217 tests (+8).** New `emulator/EmulatorReadiness.java` + `EmulatorReadinessTest`; `PlatformId.bootTimeout()`;
`AdbDevice.startApp` returns its output, plus `startedApp`/`bootCompleted`; `EmulatorAppLauncher` rewritten
around an `Outcome`; `Launcher.start` gains a progress consumer and throws for `emu-app:`.

**Done.** An `emu-app:` target brought the emulator up and then never started the app. Three causes, all
hidden behind the fourth. `EmulatorAppLauncher` treated a TCP connect to the ADB port as "the instance is
up" — on a container `adbd` listens minutes before the package manager will resolve a launcher intent, so
the monkey command went to a half-booted Android and did nothing. Its budget was 120 s where Studio's picker
already allowed Waydroid 240 s (three numbers for one question, in two modules; the launcher held the
shortest). `AdbDevice.startApp` discarded monkey's output, which is where monkey *reports* "no activities
found to run". And `start` returned `void`, so every one of those was invisible and the UI said "Launched".

Readiness is now one thing — `EmulatorReadiness.isReady` = port open **and** `sys.boot_completed` — asked by
both the launcher and Studio's picker, replacing their two byte-identical probes. `awaitReady` re-runs
discovery on each pass, because a Waydroid instance discovered while stopped carries the fallback address
rather than the one it comes up on. The budget lives on `PlatformId` beside `id()`/`displayName()`. And
`start` returns an `Outcome` naming which step failed, which `Launcher.start` raises exactly as an
uninstalled Steam already does, with an optional progress consumer so a UI can narrate a multi-minute boot.

---

## 2026-08-05 — "No child command" was two different facts wearing one answer

**209 tests (+2).** `launch/LaunchKind.runsOffDesktop()`; `LaunchIsolation.noChildCommandReason` split by it.

**Done.** `LaunchCommands.childLadder` yields an empty ladder for both `epic:` and `emu-app:`, so both got
`Blocker.NO_CHILD_COMMAND` and one shared sentence about URL openers — which Studio surfaced as a failure.
The two aren't the same situation. An Epic game does reach a desktop, just not one we handed it: refusing is
right. An emulator app never maps a window anywhere — it is started, captured and clicked over ADB inside
the emulator — so a private display has nothing to offer it and the refusal is a category error, not a
problem to report. `runsOffDesktop()` names that (true for `EMULATOR_APP` alone, asserted over `values()` so
a later kind must decide), the reason now explains rather than apologises, and Studio's launch surfaces route
on the property instead of testing the kind themselves.

---

## 2026-08-04 — App launcher icons over ADB, without pulling the APK

**207 tests (+6).** Added `emulator/ApkIcon.java` + `ApkIconTest.java`; `AdbDevice` gains `appIcon`,
`apkPath`, `fileSize`, `readBytes` and the package-private `parseApkPath`.

**Done.** A package list is a list of reverse-DNS strings, and `com.supercell.clashofclans` only reads as a
game if you already know it — the icon is what identifies one at a glance. Android has no command that will
hand an icon over (`pm` lists, `dumpsys` describes, neither renders a resource), so it has to come out of the
APK. **Not by pulling it:** a game APK is routinely hundreds of megabytes and the icon is a few kilobytes.
An APK is a ZIP and a ZIP is read backwards, so `ApkIcon` walks end-of-central-directory → central directory
→ one local header → one entry: four bounded byte ranges regardless of archive size (a test asserts it reads
under a tenth of a 4 MB archive). The ranges come over `exec:dd` on the binary-safe channel `screencap`
already uses — widened to 512-byte blocks and trimmed in Java, since toybox `dd` counts in blocks and
`iflag=skip_bytes` is not portable.

Entry choice is by file name (`res/mipmap-<density>/ic_launcher.png` and neighbours), *not* by resolving the
manifest's `android:icon`: that is binary XML pointing at a resource id needing `resources.arsc` and a
density pass — two more formats to parse, for a thumbnail. Largest density wins; an adaptive icon's
*foreground* layer outranks its background (the background is a flat colour, and the foreground is often the
only raster left). Everything returns `null` rather than throwing — Zip64, a vector-only icon, an odd `dd`
are all just a missing thumbnail.

**Deferred / next.** The app *label* has the same motivation and is strictly harder: it needs the
`resources.arsc` string pool, which is the parser this deliberately avoided. Icon plus package name is
already enough to recognise a game.

## 2026-08-04 — Waydroid as an emulator platform, plus its troubleshooting probes

**201 tests (+17).** Added: `emulator/WaydroidPlatform.java`, `WaydroidCli.java`, `WaydroidStatus.java`,
`WaydroidResolution.java`, `WaydroidDiagnostics.java` and two test classes. Changed: `PlatformId.java`,
`Platforms.java`, `EmulatorPlatform.java`. Improvements plan phase 6.

### Done

- **`PlatformId.WAYDROID` + `WaydroidPlatform`.** Waydroid is a Linux Android container, not a Windows
  emulator, and it breaks every assumption the other five implementations share: no registry key, no install
  directory, no per-instance config (one container per machine), and — the one that would bite silently —
  **not on loopback**. Its `adbd` is on the container's own LXC address (`192.168.240.112:5555`), so keying it
  to `127.0.0.1:5555` would have connected to whatever else was listening there, LDPlayer's instance 0 being
  the obvious candidate. The install probe is `waydroid` on `PATH`.
- **`EmulatorPlatform.discover()`'s javadoc no longer promises "empty on non-Windows".** That was true only
  because every implementation opened with a `WindowsRegistry` read behind an OS gate; the contract is now
  per-implementation, and each class states which OS it can appear on.
- **The launch argv is the verified one-liner.** `gamescope [-W…-h…] --expose-wayland waydroid show-full-ui` —
  gamescope is the *parent* of the Waydroid UI, not something it attaches to afterwards. `show-full-ui` is a
  Wayland-only client, so on an X11 desktop a compositor of its own is the only place it can run at all.
  Without gamescope on `PATH` it falls back to the bare `waydroid show-full-ui`, which works on a real Wayland
  desktop and fails *visibly* on X11 — better than offering no launch.
- **`WaydroidResolution` keeps Android's framebuffer and gamescope's window the same size.** A mismatch puts a
  scaler between the pixels a template was authored against and the pixels a tap lands on: matching is
  scale-tolerant so it keeps succeeding while the click goes somewhere else, which is the worst shape a bug
  can have. `read()` returns **null** when the properties are unset rather than a fabricated default (a wrong
  guess is worse than gamescope's own sizing), and the platform then omits the sizing flags. `apply()` is a
  no-op when the values already match — the `prop set` + session restart it otherwise does is user-visible, so
  it must not fire on every launch.
- **`WaydroidDiagnostics` detects and *presents*; it never executes.** Five probes — container down, session
  stopped, no internet (host `ip_forward` off), no ARM native bridge, resolution mismatch — each returning a
  `Finding` with the symptom, the remedy and the commands **as strings**. Every fix but the last needs `sudo`
  and reaches outside anything BotMaker owns (the host packet filter, the Android system image); prompting for
  a root password to silently rewrite either is not a trade worth making for a convenience, and a fix applied
  without being read is a fix the user cannot undo. `RESOLUTION_MISMATCH` is the one exception (`selfFixable()`)
  — no root, entirely inside Waydroid's own configuration.
- **The network remedy is the sequence verified on a live Fedora/KDE box**, deliberately *not* upstream's
  troubleshooting page, which did not fix it there: nftables NAT masquerade out of the default-route interface
  (parsed from `ip route`) plus `net.ipv4.ip_forward=1`, with `ufw` down while the rule is added and back up
  afterwards — dropping either `ufw` half was the trap.
- **Every probe is pure over the text it examines**, with a thin reader around it, so all 17 tests run on a
  machine with no Waydroid. What they mostly pin down is the *negative* half: an unreadable `waydroid.cfg`, a
  missing `systemctl` and an unasked-about resolution are all asserted **silent**. A probe that always fires
  teaches the user to ignore the panel.

### Deferred / next

- **Emulator capture is still `exec:screencap -p`, whose ~1s is device-side PNG encoding.** The ladder, in
  order: (1) raw `exec:screencap` without `-p` — skips the encode for a header + raw pixel buffer, lossless,
  no new dependency, ~30 lines in `AdbDevice`; (2) host-window capture via `NativeController.captureWindow`,
  lossless and an order of magnitude faster, ADB kept only for `input tap`, cost is client-area → device-pixel
  mapping — and it is the *same* mechanism as the deferred gamescope-window capture in
  `../botmaker-session/ROADMAP.md`, so one backend serves both. (3) `adb screenrecord` is **not** suitable for
  the matching path: H.264 4:2:0 chroma subsampling smears exactly the thin coloured HUD edges `Pixel` /
  `Precision.EXACT` read, the encode/decode pipeline makes the "latest" frame stale, it needs an H.264 decoder
  in the JVM, and it caps at 180s. Viable only for a live preview pane.

---

## 2026-08-04 — CI: `Spawn.run` leaked the shell's child; two tests assumed a desktop

**184 tests, unchanged.** Changed: `Spawn.java`, `launch/ProcessOriginTest.java`, `ocr/OcrEngineTest.java`,
`.github/workflows/build.yml`. This module's GitHub Actions run was red on all three counts.

### Done

- **`Spawn.run`'s timeout now kills the process *tree*, not just the direct child.** It did
  `p.destroyForcibly()` alone, which is enough only when the shell exec-optimized into the command. On this
  desktop `/bin/sh` is bash, which does exec a lone `sleep 120`, so `SpawnTest` passed locally; on
  `ubuntu-latest` `/bin/sh` is dash, which forks, so the kill hit the shell and orphaned the worker — the
  exact leak the timeout's javadoc promises not to trade the hang for. The test was right and the production
  code was wrong. Descendants are collected *before* the parent dies: killing it reparents them to init,
  where `descendants()` no longer reaches them. Not a test-only concern — any compound command (a probe
  pipeline, `floodBothPipes()`) forks under every shell.
- **`ProcessOriginTest.aProcessOnAnotherDisplayIsNotOnTheHostDesktop` now assumes a host `DISPLAY`.** Here
  the production code was right: `onHostDisplay` deliberately answers "on the host" when it cannot read a
  host display, so on a headless runner a `:9` process is indistinguishable from a local one and the
  assertion cannot hold. The test's old comment claimed `:9` "holds on a desktop and in CI alike", which is
  false once `DISPLAY` is unset entirely. Guarded, not weakened — changing the null-display fallback would
  alter a deliberate safety property.
- **`OcrEngineTest` skips instead of erroring where there is no system `libtesseract`,** via a `@BeforeEach`
  assumption probing `Class.forName("net.sourceforge.tess4j.TessAPI")`. The probe catches `Throwable`: the
  binding loads lazily and fails as an `UnsatisfiedLinkError` (then `NoClassDefFoundError` on every later
  attempt), which surefire counts as an *error*, and `OcrEngine` intentionally does not catch it.
- **CI installs the OCR natives** (`libtesseract-dev libleptonica-dev`) so the guard above never fires there
  — a skipped test is not a passing one. The `-dev` packages carry the unversioned `.so` symlinks JNA
  resolves by bare name; no language package is needed, since the traineddata is bundled in the jar.

---

## 2026-08-02 — `ColorMatcher` gains the count gate

**181 → 184 tests.** Changed: `opencv/ColorMatcher.java`, `opencv/ColorMatcherTest.java`.

### Done

- **`findClusters` / `findClustersInRange` take `minArea` *and* `minCount`.** The area filter (renamed from
  `minPixels`, so the unit is in the name where the filter is applied) drops connected components below a
  size; the new count gate runs on the raw mask *before* clustering and returns nothing at all when fewer
  than `minCount` pixels match. Two gates, one job each: "is there one real patch?" and "is there enough of
  this colour?". The SDK's `MinMatch` is the pair as one value.
- **The gates deliberately do not compose,** and the javadoc says so: a frame of scattered specks can pass
  the count and still yield no blob large enough to keep, so `minCount` is not "the total area of what comes
  back". Wrong only at the boundary, right everywhere else — the kind of thing nobody infers correctly.
  `ColorMatcherTest` pins both directions of the independence.
- **`matchCount(image, target, tolerance)`** — the absolute number of matching pixels, which is what an
  editor needs to explain a `minCount` threshold (`coverage` gives the fraction). Both now come off one
  private `tally(...)` so the number shown and the number compared cannot drift.

---

## 2026-08-01 — improvements Phase 7: the bot's tuning becomes eight project keys

### Done

- **Eight new `ProjectProperties` keys**, plus their typed accessors: `clicks.foundDelay`,
  `clicks.notFoundDelay`, `clicks.randomize`, `vision.confidence`, `vision.compareMargin`,
  `bot.maxRetryAttempts`, `input.real` and `input.linuxBackend`. They carry what Studio used to write as a
  generated `BotSettings.java` and read back with a per-statement regex — a storage format that was, in
  effect, "whatever that parser still recognises", while this file was already the thing both sides speak.
  The SDK's new `api.BotSettings` reads them on first use.
- **Out-of-domain values filter to `null`, not to a clamp.** The accessors reject a negative delay, a
  confidence outside 0–1, a retry budget below 1 — and return `null` so the *caller's* default applies. The
  SDK setters these feed throw on a bad value, and the load happens inside whatever call first reads a
  setting, so a hand-typed `vision.confidence=5` must leave the bot on its default rather than raise an
  exception out of a bot's first vision call.
- **`setForTesting` is public.** The SDK seeds `api.BotSettings` from these keys and it is the *ordering*
  that needs testing — the real-input swap has to precede the first click — which cannot be exercised from
  inside this module.

### Deferred / next

- Nothing new. The remaining bot-facing setting that is still a system property rather than a key is
  `botmaker.linux.input`, and `input.linuxBackend` now feeds it; the property still wins when set explicitly
  on the command line, which is deliberate.

---

## 2026-08-01 — refactor Phase 4: B7, the spawns that could hang on a full pipe (S6)

**177 → 181 tests** (three un-disabled, four added). New: `com.botmaker.shared.Spawn`.

### Done

- **B7 — five spawns could block forever on an undrained pipe.** A child writes into a ~64 KB pipe buffer
  (as little as 4 KB on Windows); if nobody reads it the child blocks in `write()` and a parent in an untimed
  `waitFor()` blocks with it. `GameLauncher.exe` and `tryStart` redirected neither stream, the `tasklist` and
  `pgrep` probes in `isProcessRunning` read stdout but never stderr, and `SpectacleCapture` merged both streams
  into a pipe it never read at all. None of them crash — the symptom arrives layers away as "the game froze on
  startup".
- **One helper, not five patches.** `Spawn.detached(command)` (both streams `DISCARD`, no wait) for the
  fire-and-forget launches; `Spawn.run(timeout, command)` (merged, drained, bounded, child killed on expiry)
  for the probes. `DISCARD` rather than `inheritIO()` deliberately: inheriting spills a game's chatter into the
  bot's own stdout, where it reads as the bot's. `Executables` is the precedent — one answer in shared, because
  the private copies had already drifted.
- **`WindowsRegistry.read` came along.** It drained its pipe but waited untimed, which is the same defect one
  wedged `reg query` away, and emulator discovery calls it in a loop.
- **The drain runs off the calling thread**, which is not an optimisation. Reading the pipe to EOF and *then*
  calling `waitFor(timeout)` leaves the timeout unreachable — EOF arrives when the child exits, so the read is
  the unbounded wait, and B7 has simply moved one line down. `SpawnTest` pins it: a `sleep 120` bounded at
  300 ms returns in well under 5 s with no child left behind.

### Finding

The bug and its fix had opposite shapes. B7 was five copies of a missing line; the fix was one type, and the
only hard part was the part the audit could not see — that the natural implementation of the helper reproduces
the bug it exists to remove. Worth remembering when the next "apply this pattern at N sites" item comes up.

---

## 2026-08-01 — refactor Phase 4: B6, the resilience that stopped at the wire (S7)

**176 → 177 tests** (two un-disabled, one added), and the first production change here since Phase 3.

### Done

- **B6 — a telemetry listener that throws no longer takes the channel with it.** `acceptLoop` decoded and
  dispatched in one statement, guarded only for `FrameFormatException` and `IOException`, so a
  `RuntimeException` out of the caller-supplied `onEvent` unwound the loop, ended the daemon
  `telemetry-server-accept` thread and left the run silent — while `close()` and `port()` kept answering
  normally, so nothing downstream could tell. Decode and dispatch are now two steps, and `dispatch` catches
  `RuntimeException`, prints the stack through `Diag` and reports the cause once through `onError`. `Error`
  still unwinds: that says the JVM is in no state to keep serving, which is not the same claim as "a
  subscriber has a bug".
- **Two one-shot guards, not one.** `reportErrorOnce` now takes its `AtomicBoolean` as a parameter. Sharing
  the single latch with wire-skew reporting — the literal prescription in `bugs.md` — would let whichever
  failure happened first suppress the other for the whole run, and a listener bug and an old-SDK wire version
  are different diagnoses that both deserve their notice.
- **The class javadoc's claim is true now.** It advertised "resilient by design", meaning the wire; the
  consumer is the side we do not control, and in Studio it is an `EventBus` any handler can subscribe to.

### The finding worth keeping

Phase 3 wrote this gate (`TelemetryServerResilienceTest`, MISSING 5) and left it `@Disabled` with a re-enable
condition naming S7. Deleting those two lines *is* the red-on-the-previous-commit evidence, so this was the
one Phase 4 item that needed no reconstruction dance — re-confirmed anyway by stashing only the production
file: **3 failures of 4**. A test written before the fix costs one line to prove; a test written after it
costs an afternoon of arranging to see it fail.

---

## 2026-07-31 — refactor Phase 3: the test floor (S1, S2, S3)

Part of the repo-wide refactor scheduled in `../docs/refactor/02-execution-order.md`; this module's share is
units **S1–S3**, all test-only. **142 → 176 tests**, no production code touched.

### Done

- **MISSING 1 — `LinuxControllerCaptureTest`.** `captureWindow` has four exits (composite pixmap, root crop,
  on-window drawable, give up) and all four are chosen by one predicate, `isAllBlack`. It is pure, so it is
  now covered exhaustively — including the two cases that pin the **sparse grid** (`min(w,h)/17`): content
  between grid points on a large frame is *missed*, and below 17px the step floors to 1 and nothing is. That
  approximation is a deliberate trade against scanning a 4K frame per capture, and it reads as sloppiness to
  anyone who has not measured the alternative. The exits themselves are live-gated on `DISPLAY`.
- **MISSING 2 — `ProcessSpawnStreamTest`, and B7 is reproduced rather than argued.** A child writing 256 KB
  through `GameLauncher.exe` never exits: it blocks in `write()` at the 64 KB pipe buffer and stays there.
  Three tests red on this commit, `@Disabled` pending **S6** in Phase 4. Two *reference* tests (the `DISCARD`
  and drain patterns the fix must apply) stay live, so the shape the fix targets is itself proven.
- **MISSING 3 — `SharedNoOcvLeakTest`.** session's pom excludes our OpenCV and Tess4J; nothing verified that
  `capture/` and `launch/` stay free of those types, so the contract was a comment. It now scans the compiled
  **constant pools** — which sees a field type, a cast or a caught exception, none of which need an `import`
  line — and fails at build time instead of at a standalone consumer's runtime.
- **MISSING 4 — `InputBackendChoiceTest`.** S11's enum does not exist yet and testing it afterwards would
  prove nothing, so what is pinned is what S11 must *preserve*: the four wire ids, the trim-then-lowercase
  normalisation, the total parse, and the precedence ladder — including that the property beats the
  environment variable, because the env var is read first and passed as `getProperty`'s **default**. Inverting
  those two while refactoring is invisible: the wrong backend still works, it just stops preserving the cursor.
- **MISSING 5 — `TelemetryServerResilienceTest`.** Two tests red on this commit (B6), `@Disabled` pending
  **S7**; the healthy-stream case stays live.
- **MISSING 6 — `WindowsControllerTest`.** `capture.windows` is 0.0% over 251 lines. This raises what can be
  raised (the Win32 constants the alt-tab filter is written in terms of — a wrong one does not throw, it
  silently changes which windows the picker offers) and **says why the rest stays zero**: `WindowFinder`'s
  filter reads `User32.INSTANCE`, which binds `user32.dll` at class-load, so on Linux there is nothing to
  assert against. Those are `@EnabledOnOs(WINDOWS)` and will not run until there is a Windows runner. An
  unexplained 0.0% reads as neglect and gets re-derived by the next person.
- **MISSING 7 — `X11InputListenerLifecycleTest`, which found B15** (see below).
- **S3 — `LinuxControllerTest` deleted, not reworked.** It covered `isAllBlack` in three cases and nothing
  else; all three are in `LinuxControllerCaptureTest`. Two test classes on one predicate is how the two drift.

### Found: B15 — every recording session leaks three X connections and a live thread

The audit read this code and logged a **race**: "`close()` returns before `cleanupDisplays()` has run"
(item 4), a window of milliseconds. Measuring it found something else. `XRecordEnableContext` **never
returns**, so the cleanup never runs at all — the fd count grows by exactly three per start/close cycle and
never comes back, and a thread dump shows every `botmaker-input-recorder` still `alive` and `RUNNABLE` inside
the JNA call. Invisible because the thread is a daemon (it never delays JVM exit) and `close()` returns
promptly reporting success; the cost lands on Studio as three fds and one thread per interaction-recording
session, surfacing much later as "too many open files" somewhere unrelated. Full evidence in
`../docs/refactor/bugs.md` § B15; the leak test is `@Disabled` pending the fix, scheduled beside **S8**.

This is the case for writing the test floor before the refactors rather than after: the same code had already
been read carefully, and reading it produced the wrong severity.

---

## 2026-07-30 — the session stack moved out into `botmaker-session`

`com.botmaker.shared.session` (25 main + 16 test files) is now its own module and repo,
[`botmaker-session`](../botmaker-session/ROADMAP.md), as `com.botmaker.session.*` split by role. shared keeps
the native window plumbing, OCR, matching, config, emulator **and the whole launch stack**.

**The launch package deliberately did not follow it**, including `LaunchIsolation`, `HostLauncherProbe` and
`ProcessOrigin` — which are conceptually session code. `RunningProbe` uses `ProcessOrigin`, so it cannot leave
without inverting the dependency; isolation logic is split across the two modules whichever way it goes, and
splitting it twice would also widen `RunningProbe.programNames` to public for no gain.

**Session history stays here.** Entries below are interleaved — one dated entry routinely covers both session
and launch/capture work (Phase 11 step 1 is `HostLauncherProbe`, which stayed; Phase 12 is `PointerPolicy`,
which moved) — so they were left whole rather than torn in half, and the new module's ROADMAP points back at
them. Tests: shared 211 → **142**, session **69**.

**If a second lightweight consumer ever appears**, the option not taken here is marking `opencv`, `tess4j` and
`dadb` `<optional>` in this pom and re-declaring them in sdk/studio. `botmaker-session` instead excludes
OpenCV and Tess4J at its own dependency, which leaves this module's contract with the SDK and Studio — both of
which genuinely want the OCR and emulator stacks transitively — untouched.

---

## 2026-07-30 — Phase 13 step 1: capture what the launched app actually says

`NestedSession.launchAndAttach` spawned the app with `Redirect.DISCARD` on **both** streams, so a launch that
failed inside a session left no trace but its exit code. What that cost, concretely: a Steam title that wouldn't
start, reported with the only artefact the run left behind — a `bwrap` `SIGSYS` coredump — which turned out to
be unrelated (see below). The app's own explanation had been discarded microseconds after it was written.

### Done

- **`AppOutputLog`** — one file per session (`botmaker-app-<id>-*.log` in the system temp dir) taking the app's
  stdout **and** stderr, plus a daemon tailer echoing it into `Diag` under `[App]`.
- **File first, log second, and bounded.** Everything is kept in the file; only the first `MAX_ECHOED_LINES`
  (200) reach the log, truncated at `MAX_LINE_CHARS` (300), then one line pointing at the file. Unbounded
  echoing is its own defect — a Proton cold start would bury every `[Session]` line under winetricks output.
- **Both streams into one file on purpose:** their interleaving is evidence of what preceded what.
- **Not `SessionReaper.tempOutputFile`**, which marks its file `deleteOnExit` — right for a display number
  parsed during start-up, wrong here: a bot process exits as its run ends, which would delete the log of the
  launch that just failed at exactly the moment it is wanted.
- The tailer reads by byte offset with a carry-over buffer rather than through a `BufferedReader`: the writer is
  another process appending as we read, so a reader would hand back half-arrived lines and tear each one across
  two log entries. Asserted directly in `AppOutputLogTest`.
- The "mapped no window on :N" and `noWindowDiagnosis` failure messages now name the log path — that is when
  anyone wants it — and `close()` says where the file was kept rather than removing it.
- `AppOutputLogTest` (4): a line split mid-write arrives whole and not before; blanks dropped and long lines
  cut; the echo stops at the cap and names the file exactly once; and a real child's stdout *and* stderr both
  arrive through `redirect()`.

### Closes Phase 11 step 5 — the `bwrap` SIGSYS is not ours

Measured, so it needs no phase: the dumping command is
`bwrap --unshare-all … --seccomp <fd> /usr/bin/true` — the Steam Linux Runtime (pressure-vessel) probing
whether `bwrap` works, whose seccomp filter is *meant* to kill it at `execve`. It fires on **every** Steam
launch inside a session and is **uncorrelated with failure**: a launch that succeeded produced the identical
dump. The planned `:0` comparison is no longer worth running.

### Windows isolation — asked and answered, not built

"Is there a gamescope equivalent for Windows?" came up while planning this phase. Recorded so it isn't
re-derived; the decision is to keep the current "bring-up declines, run on the normal desktop" path.

- **No equivalent exists.** gamescope is a nested Wayland compositor with an embedded Xwayland; nothing on
  Windows puts a GPU game's window on a surface the host can still capture.
- **`CreateDesktop` + `STARTUPINFO.lpDesktop` is the analogue of *Xephyr*, not of gamescope.** Input isolation
  would be real, and our `keybd_event`/`mouse_event` synthesis (`capture/windows/WindowsController.java`)
  follows `SetThreadDesktop`. But a secondary desktop runs **no DWM**, so capture is GDI-only — and
  `WindowCapture` already leans on `PrintWindow(PW_RENDERFULLCONTENT)` *with an on-screen framebuffer fallback*
  that a secondary desktop cannot provide. Plausible for 2D/launcher/emulator targets, not for Proton-class 3D,
  and it would need a capture spike before any API commitment.
- **Hyper-V / Windows Sandbox with GPU-P solves the GPU and not the architecture.** `<vGPU>Enable</vGPU>` and
  `Add-VMGpuPartitionAdapter` do give a guest a real GPU partition — but the game then runs in another OS
  instance, where nothing on the host can `PrintWindow` it or inject into it. BotMaker would have to run *inside
  the guest* with Studio driving it over a transport: a remote-agent product (wire protocol, second Windows
  license, driver-file copy ritual, guest-side SDK + OCR), against gamescope's cost of one process spawn with
  every existing X11 path unchanged.

### Deferred / next

Phase 13 steps 2–4: don't let a dead session's leftovers veto a launch (`HostLauncherProbe` must name the pid
it refuses on, and skip remnants/zombies); attach provisionally and gate readiness on the *target's* own window
rather than the launcher's first one; and SIGTERM the game tree before SIGKILLing the launcher.

---

## 2026-07-30 — Phase 12: a click in a session must not hand the cursor back

Live report: a bot on an adopted gamescope session found its template, clicked it, and the game rendered the
**hover** effect instead of registering the click. Adoption and targeting were both fine — the button press was
being thrown away by the courtesy warp that follows it.

### Done

- **`PointerPolicy`** — `ownsPointer(session)`, `click(controller, session, x, y, button)` and
  `restoreTo(controller, session, origin)`. On the user's desktop a gesture hands the cursor back; inside a session
  (`BACKGROUND_CLICK`) it leaves the pointer on the target, because a UI that samples the pointer per frame rather
  than reading the event coordinate sees it somewhere else by the next frame — which is the hover above.
- **Why it is a shared type and not two `if`s.** The rule was already written down in `NativeController.click`'s
  javadoc ("Session callers take this method") and already implemented in Studio's `PilotInputService`
  (`sessionOwnsPointer()`) — and *not* in the SDK's `Mouse`, which called `clickRestoringCursor` unconditionally.
  One consumer honouring a policy and the other not is exactly the drift shared exists to prevent, so the decision
  now lives here and both call it. `PilotInputService`'s private copy is gone.
- `controller` stays a parameter rather than being taken off the session: the pilot deliberately drives an
  *escalated* `:0` controller when no session is active, and re-deriving one here would silently swap it.
- `PointerPolicyTest` (5) asserts the difference as the presence or absence of one trailing `move 7,9`, in both
  directions, plus the two "don't restore" cases (pointer is ours; origin unreadable).

---

## 2026-07-30 — Phase 11 (step 4): don't show the session's window until there is something in it

Reported from a live run: *"would be cool to not show the window until the real process is ran, sometimes I have
black flashes — not saying to not show the launcher at all"*. Xephyr maps its output window on `:0` the instant it
starts and nothing is drawn into it until a client maps something on `:N`; for a store launcher that is up to two
minutes of black rectangle on the user's real desktop.

### Done

- **`SessionHostWindow`** — finds the display server's own window on the host desktop and minimizes it until the
  session has content, restoring it on the first window to appear on `:N`. That trigger is the launcher-friendly
  one: `awaitWindow` returns the *first* new top-level, launcher UI included, so the launcher is shown as soon as
  it maps and only the empty stretch is hidden. `XIconifyWindow` (ICCCM `WM_CHANGE_STATE`) rather than
  `XUnmapWindow`, so the host WM stays in charge and a plain `XMapWindow` undoes it.
- **The search runs off the start path**, in a daemon thread with a 15s budget, because gamescope publishes its
  output window *seconds* after its Xwayland accepts connections — a 3s in-line probe simply missed it, and a
  cosmetic nicety must never delay the launch it is hiding. `revealRequested` + `anythingMappedOn` make a search
  still in flight decline to hide a session that has meanwhile filled up.
- **Two false-positive guards, both from live misfires of my own first cut.** Matching a window whose *title*
  mentions the server binary would minimize a terminal running `gamescope …`, so the fallback is `WM_CLASS` only
  (pid first, always); and two windows of that class with no pid to separate them ⇒ minimize neither, since the
  other one is another session's server. Xephyr sets no `_NET_WM_PID`, so the class path is the real one for it.
- **`SessionDisplay.serverPid()`** — both backends already held the `Process`; the host-window search needs the
  pid tree (`systemd-run --scope` sits between us and the server, so descendants count).
- Escape hatch `-Dbotmaker.session.hideuntilready=false`, because this is a host-WM-mediated operation on a window
  we don't own and a compositor that throttled an iconified server's frames would turn a cosmetic flash into a
  stalled capture.

### Measured live on the dev box (Fedora/KDE X11), both backends

- **The gate holds: capture keeps producing frames while the host window is iconified**, on Xephyr *and* gamescope
  (two frames a second apart, window confirmed non-viewable on `:0` in between). This was the condition the step
  was allowed to land under.
- **gamescope needs none of this, and now provably doesn't get it.** With no client on its Xwayland its host window
  is not mapped at all — no `WM_STATE`, absent from `_NET_CLIENT_LIST` — and it appears the instant a client maps
  something. So there is no empty black window to hide; the black flash a gamescope user sees is the gap between
  its window being mapped and its first rendered frame, which iconifying cannot help. `anythingMappedOn` is what
  keeps us from minimizing a window that already has the launcher in it.
- **"Mapped" alone was not a usable emptiness test**: an empty Xephyr+openbox display already carries a viewable
  window — openbox's 1x1 support window parked at `-100,-100` — so the first version of the check called every
  session occupied and quietly did nothing. Content now means viewable *and* bigger than a pixel.
- `SessionHostWindowLiveTest` (opt-in, `-Dbotmaker.live=true`, `-Dbotmaker.live.backend=xephyr|gamescope`) asserts
  all of it against X state rather than log lines: an empty session's window is not viewable, an occupied one's is,
  and capture answers twice while it is hidden. Reactor green: shared 202 (8 skipped live), sdk 114, studio 352.

---

## 2026-07-30 — Phase 11 (step 3): a bot joins the session instead of starting a rival one

The workflow the last two steps were clearing the way for: launch the game once ("▶ Launch now"), watch it, then
run the bot. Left to itself the bot brings up a *second* private display and launches the game into it — and every
store launcher is single-instance, so that launch is handed to the copy already running in the first session and
the game appears where nobody is watching.

### Done

- **`AdoptedSession`** — a `DesktopSession` over a display someone else brought up. It **owns nothing**: `close()`
  drops its own two X connections and touches neither the server, the WM, the private bus nor the game. That is
  why it is a separate class rather than a flag on `NestedSession` — a reap from the wrong side would tear down a
  session its owner is still using. No `WINDOW_LAUNCH` (launching is the owner's job; `launch()` logs and declines
  rather than starting a rival copy), but `BACKGROUND_CLICK`/`ISOLATED_FOCUS` hold — a private display is private
  whoever made it. `screen()` is read off the display; `health()` is "does it still accept a connection", the only
  liveness observable without process handles.
- **The hand-off lives in one class, both halves of it.** `handoffArguments(NestedSession)` writes the `-D`
  arguments and `fromProperties()` reads them, so a renamed property can't silently stop every bot adopting
  anything. The **backend** rides along because it sets the pointer-warp convention — gamescope's Xwayland reads an
  absolute warp as window-relative, and getting that wrong misplaces every click rather than failing outright. The
  attached **window id** rides along because the owner knows which window the launcher chain finally settled on;
  it is advisory, and `adopt` falls back to the newest top-level when it has since gone.
- **`SessionAttachment`** — the re-attach rule (and the `ProtonFixes → Firestone` bug behind it) extracted from
  `NestedSession` so an adopted session watching the same launcher chain doesn't need a second copy of it.
- `NestedSession.backend()` / `attachedWindowId()` so a consumer never unwraps a JNA `Pointer` — Studio has no
  other reason to know JNA exists.
- `AdoptedSessionTest` (4, no X needed) pins the property names and the id parsing; `AdoptedSessionLiveTest`
  (opt-in, `-Dbotmaker.live=true`) is the real proof — a second consumer joins a live Xephyr session, drives the
  same window, and closing it leaves the owner's session `HEALTHY`. **Written but not yet run**: it needs a live
  box. Reactor green otherwise: shared 198 (6 skipped live), sdk 114, studio 352.

### Deferred / next

- Step 4: don't map the nested window until there is something in it (the black flash), gated on capture still
  producing frames while iconified.
- An adopted session has no `DEGRADED` state — it can't see the game's process, only the display. If a bot ever
  needs to notice the game dying under it, that wants a probe on the attached window, not a process handle.

---

## 2026-07-30 — Phase 11 (step 2): a closed session stops existing

Step 1 stopped the probes counting our own sessions; this stops the leftovers being there to count. The
prompting observation: `botmaker-sess-s167520-1-dbus.scope` found `active running` — a private `dbus-daemon`
whose display server had been gone for hours — under a Studio JVM that was still alive. Nothing could collect
it: the sweep spares any slice whose owner pid is alive, and it only ever ran inside a successful
`NestedSession.start`, the one path a *refused* launch never reaches.

### Done

- **A live registry, so "ours" and "live" stop being the same word.** `NestedSession.LIVE` holds the ids this JVM
  currently owns (added once the tree is up, removed on close). `SessionReaper.reapOrphans(liveIds)` now also
  stops slices owned by *this* pid that no session object is keyed by. The trap that introduces is guarded and
  tested: systemd derives a parent slice from every dash, so a live `s123-1` sits inside a
  `botmaker-sess-s123.slice` no object is keyed by — `isLive` treats a parent of a live id as live, or the sweep
  would have taken running sessions down with it.
- **The reap verifies itself.** `reap()` re-lists `botmaker-sess-<id>*` after stopping the slice and stops
  whatever is still loaded, saying so at error level. It used to log "stopped slice" unconditionally, which is
  how the `dbus.scope` above went unnoticed.
- **`NestedSession.closeIfDead()`** — a dead display is not recoverable, and now something acts on it. Studio's
  `BackgroundLauncher` polls it (2 s) and drops the session, so a session whose gamescope died no longer blocks
  the next bring-up *and* no longer leaves a slice for the launch probes to misread.
- **Swept before the verdict, not only inside `start`.** The bot runtime sweeps in
  `SessionBootstrap.launchIsolated` ahead of `LaunchIsolation.check`, and Studio sweeps on boot (off the FX
  thread). Sweeping late means refusing a launch on a dead session's account.
- `SessionReaperSweepTest` (4) pins the parent-slice rule and the id parsing. Reactor green: shared 193, sdk 114,
  studio 352.

### Deferred / next

- Step 3: `AdoptedSession` — the bot adopts a live session instead of relaunching into a new one.
- The watchdog is Studio-side only; a bot JVM that outlives its own session's display still relies on
  `closeIfDead` being called by someone. Worth revisiting when the SDK grows a supervisor loop.

---

## 2026-07-30 — Phase 11 (step 1): "the launcher is open" now means *on the host desktop*

Two live readings, one cause: the launch probes ask questions about the **host desktop** while reading a process
table that also contains our own private sessions and their leftovers.

- Launch a game into a session with "▶ Launch now", then run the bot, and it refused —
  `isolated launch declined — running on :0. Can't run heroic games in a private display while Heroic is open` —
  naming the Heroic *inside that very session*. The setup that works was the one being refused, and the bot then
  ran on the user's real desktop. (Which is also why "the SDK clicks on my desktop but BotPilot doesn't": both
  call the same `clickRestoringCursor`; only the refusal differed.)
- Long after Heroic was closed, `RunningProbe` still reported `heroic:43d4…: a live process mentions '43d4…'`,
  so the bot skipped its launch entirely. Those processes were remnants of a session whose owning JVM was gone.

### Done

- **New `launch/ProcessOrigin`** — "where does this pid live?", best-effort and total (no `/proc`, an unreadable
  file or an absent variable all answer *no evidence*, never an exception). Two independent signals because
  neither alone suffices: `DISPLAY` out of `/proc/<pid>/environ` is decisive and stays true for the
  Flatpak-portal-escaped children that are deliberately *not* in our cgroup; the cgroup name is the fallback when
  `environ` is unreadable, and the only way to see a **remnant** (the session id carries its owning JVM's pid, so
  a dead owner is readable off the cgroup path). `SESSION_UNIT_PREFIX` lives here because this class parses back
  the name the reaper writes, and the two are in different packages.
- **`HostLauncherProbe.isRunning` filters on `onHostDisplay`** and logs the one line that matters
  (`ignoring pid N — Heroic is on :1 (BotMaker session s1234-1), not the host desktop, so it can't swallow the
  launch`). A launcher on a private display cannot swallow an isolated launch: our child is handed that same
  private `DISPLAY`, so a single-instance handoff lands where we wanted it anyway.
- **`RunningProbe.mentions` drops session remnants** — running, but nothing a caller asked about: the session
  that launched them is gone, nobody is driving them, and they are about to be reaped.
- `LaunchIsolation`'s `HOST_LAUNCHER_OPEN` note narrowed to what is now true; whether a private bus also hides a
  *genuine* host instance from a single-instance check is still unverified and still a refusal.
- `ProcessOriginTest` (7) asserts the readings against **real children** (`sleep` with a rigged `DISPLAY`) rather
  than a fixture — the class is entirely about what the kernel reports, so a fake would only test the parser
  against itself. Reactor green: shared 189, sdk 114, studio 352.

### Deferred / next

- Step 2: nothing may survive a close to lie about it later — sweep orphans before the isolation verdict (not
  only inside a successful `NestedSession.start`), verify the reap actually emptied the slice, and self-close a
  session whose display died. A live `botmaker-sess-s167520-1-dbus.scope` with its display server long gone is
  what prompted it.
- Step 3: `AdoptedSession` — the bot adopts a live session instead of relaunching into a new one.

---

## 2026-07-30 — Phase 10 (B3): the teardown stops sweeping

`SessionMembers.shutdown` got the eldest member right — asked first, killed before anything it spawned — and
then threw that ordering away for everything behind it: one blanket `SIGTERM` across every survivor, then a
single wait. That is the same "helpers killed underneath a live supervisor" mistake in miniature, it is what
Chromium aborts on, and it is what ran immediately before the coredump on the last run that still produced one.

### Done

- **`terminateOneByOne`** replaces the sweep: each remaining member is asked, given `REMNANT_GRACE_MS`, killed
  if it is still there, and only then is the next one touched — so at no point is a live process watching a
  sibling die. Members that exited on their own (the common case: killing a parent usually takes its children)
  are skipped rather than re-signalled, and the walk respects the overall deadline so one slow member can't
  spend the whole teardown budget. The final whole-remainder `destroyForcibly` stays as the backstop.
- **`REMNANT_GRACE_MS` = 500 ms**, deliberately far below the launcher's 8 s: a remnant has no shutdown of its
  own worth waiting for, and the reason it's signalled alone is the ordering, not politeness. It also bounds the
  walk — a session's chain runs to ~35 processes, so a per-process 8 s would be a teardown measured in minutes.
- Test `remnantsAreSignalledOneAtATimeRatherThanSwept` asserts it from inside the processes: three spinners
  trap `SIGTERM`, timestamp it and keep running, so the walk has to escalate to `SIGKILL` on each. The recorded
  times must be ≥250 ms apart — a sweep lands them in the same millisecond.

### Deferred / next

- **B1 is still the deciding experiment** and still needs a live session: `SIGTERM` only the heroic browser pid
  and capture the launcher's stderr (the reaper discards it today) for the `[FATAL:…] Check failed:` line that
  names the cause outright. B3 was worth doing either way — it is a real defect on the path — but whether it is
  *the* fix for the coredump is unproven.
- B2 (ask the game's tree first, then `SIGKILL` the launcher) stays gated on B1.

---

## 2026-07-30 — Phase 10 (A3/A4): a click is a click, and a press lasts a frame

The two secondary defects behind "the tap produced a hover highlight instead of a click", both of which are
real regardless of what the A1 measurement says about focus. Neither is the root cause — a real mouse click
never touches this code, and the user reported the same symptom with a real mouse — but both would still be
defects after the root cause is fixed, and both are cheap.

### Done

- **`NativeController.click(x, y, button)` — the plain click.** `clickRestoringCursor` is now *this plus a
  warp back*, delegating rather than keeping its own copy of the sequence, so the two can only differ in the
  thing they're supposed to differ in. The restoring warp exists for the host `:0`, where the cursor is the
  user's; in a session there is no user cursor to return and warping away right after the release is a good
  way to leave a UI rendering hover feedback where a click should have registered — the pointer is somewhere
  else by the time the next frame samples it. Studio's pilot TAP picks the path by
  `Capability.BACKGROUND_CLICK`.
- **`postLeftClickScreen` folded onto it** — it was the same call hardcoded to button 1, under a name left
  over from the `XSendEvent` era (permitted: the API is freely breakable, no published bot consumes it). The
  window-relative `postLeftClick(window, …)` stays; it is a genuinely different path. Linux implements `click`
  as `inputBackend.clickScreen`, which is the point — each backend's own sequence rounds the motion through
  `XSync` before pressing, which a naive move/press/release can't.
- **A press now outlasts a frame.** The interface default pressed and released back-to-back: 0 ms, well under
  one frame at 60 fps, so a game sampling input per frame can observe no press at all. Default hold is
  `CLICK_HOLD_MS` = 12 ms (matching `InputTiming.DEFAULT`); a session raises it to 40 ms — two frames —
  via the new `SessionBackends.inputTimingFor(backend)`, plumbed through
  `LinuxController(displayName, backendChoice, warp, timing)` to the XTest backend.
- **`NativeController.pressHoldMs()`** so a caller assembling its own press/release pair asks the backend
  instead of inventing a hold. `SessionPointer.click(button)` (via `ControllerPointer`) was exactly that
  caller and had no hold at all. It deliberately does *not* route through `click(x, y, button)`: it is a click
  *where the pointer already is*, and re-deriving a coordinate only to warp back to it would add a round trip
  through the very warp path currently under suspicion.
- `LinuxController` now remembers the driven-window supplier, so `useReliableInput()`'s backend swap doesn't
  silently drop it with the replaced backend.
- Tests: `ClickPathTest` (the two paths differ only by the warp back; an unreadable cursor means don't
  restore; the press really does elapse), `SessionBackendsTest.aSessionHoldsAButtonLongerThanOneFrame`, and
  in Studio `aSessionTapDoesNotWarpThePointerBack` / `aHostTapPutsTheUsersCursorBack`.

### Deferred / next

- **A1's data half is still outstanding** and still gates A2 — the trace and the probe script exist, but the
  reading needs a live session, Firestone up, and the user's phone.
- **The drag gestures still restore on `:N`.** `PilotInputService` `UP` warps back to `dragOrigin` on every
  path; on a session that is the same pointless warp as the tap's. Left alone deliberately — the approved plan
  scoped A3 to TAP, and a drag's restore is at least not mid-gesture.
- A5 (BotPilot taps at the pointer-**down** coordinate) and workstream B (the teardown SIGTRAP) are untouched.

---

## 2026-07-30 — Phase 10 (A1): membership by environment, and instrumenting the click problem

Two threads, one commit. Both are about the same thing: the session had guarantees it could not actually keep,
because it was asking the wrong source for the answer.

### `SessionMembers` — who belongs to a session, when the cgroup can't say

The reap guarantee ("`kill -9` the JVM ⇒ zero orphans") was **false for every Flatpak target**, and the crash
proved it: the coredump showed *our* argv in *someone else's* cgroup.

```
PID: 312266 (heroic)   Signal: 5 (TRAP)
Command Line: /app/bin/heroic/heroic --no-gui --no-sandbox heroic://launch/43d4…
Control Group: …/app.slice/app-flatpak-com.heroicgameslauncher.hgl-3053722396.scope
```

`flatpak run` moves the app into its own transient scope over `/run/user/<uid>/systemd/private` — the systemd
user manager's private socket, **not** D-Bus, so the session's private bus cannot intercept it and no flag
disables it (confirmed in the flatpak 1.18 binary's strings). So `systemctl stop <our slice>` never signalled
the launcher or the game at all. It killed gamescope instead, Xwayland `:N` vanished under a live Chromium, and
Chromium's X11 IO-error path aborted — which is also the *only* reason nothing was left running afterwards. A
launcher that survived losing its X connection would simply have leaked.

Membership is now asked of **the environment**: every process in the chain carries the session's `DISPLAY=:N`
or its unique private bus address, and neither is handed out by accident. Two exclusions carry the correctness:
this JVM and its ancestors (signalling them is how a teardown becomes a suicide), and the session's own
infrastructure by cgroup (the bus and WM are launched *with* `DISPLAY=:N`, so they match the environment test
and must not be killed alongside the game). Ordering is by **start time**, not parentage — under Flatpak
`zypak` reparents Chromium's helpers onto `flatpak-portal`, so the process tree ranks them *ahead* of the
launcher that spawned them, and a parents-first walk kills a browser's helpers underneath it.

Also fixed: `SessionReaper`'s orphan sweep matched only the leaf slice, so the parent slices systemd derives
from each dash in a unit name were left loaded-and-empty forever (six had accumulated in one afternoon).

The teardown coredump is **not yet gone** — three orderings have failed. What is established: idle Heroic exits
cleanly on SIGTERM (measured), so the signal is not inherently fatal; something about supervising a running
game is. Next step is the deciding experiment (SIGTERM the launcher alone, capture its stderr for the
`[FATAL:…] Check failed:` line) rather than a fourth guess.

### Instrumenting the clicks — measurement before fix

Clicks land wrong or register as hover, **from BotPilot and from a real mouse in the session**. The second half
rules out the whole injection path: a real click never touches XTest. What is common to both lives inside `:N`,
and the prime suspect is focus — gamescope is the WM for its own Xwayland and routes input through the focused
surface, while `NestedSession.attached()` self-heals only the *capture* target. Nothing keeps those two in
agreement.

So this adds the seam and the measurement, and **changes no behaviour**:

- `LinuxInputBackend.setDrivenWindow(Supplier<Pointer>)` (default no-op) — the caller names the window it
  believes it is driving, as a supplier because `attached()` re-resolves and a captured handle goes stale.
  `NestedSession` wires its own `attached()` in.
- `XTestBackend` gains a trace behind `-Dbotmaker.input.trace=true`: requested coords, the correction applied,
  the driven window vs the focused window (flagged `DIVERGED` when they differ), and a pointer read-back
  saying where the server actually put the cursor. `warpOrigin()` still takes its origin from focus exactly as
  before — deliberately, so the reading is not contaminated by the fix it is meant to justify.

Answering a question raised alongside: **resizing the session window does not reduce the game's resolution.**
`GamescopeDisplay.defaultCommand` passes the size twice — `-W/-H` is the output (the nested window on the host)
and `-w/-h` the internal resolution Xwayland advertises. Only the former follows a resize; the game renders,
and `TargetCapture` reads, at `-w/-h` regardless. It is a real suspect for the *real-mouse* path only, since
gamescope maps the host pointer through the output→internal scale.

**Deferred / next:** the fix itself, once the trace says which suspect it is — focus (session owns focus, warp
origin comes from the driven window) or geometry (a gamescope argv change). Then the secondary injection-path
defects: the session tap warps the cursor away immediately after release, presses for 0 ms, and BotPilot sends
the tap at the finger-lift position. The trace is temporary and comes out once it has answered.

---

## 2026-07-29 — Phase 9: live verification — a real game runs and is captured inside the private session

The end-to-end result the previous phases were building toward, measured rather than argued: **Firestone,
launched through Flatpak Heroic into a private gamescope display, renders there and `session.capture()` returns
its frame.** Every process that escaped to `:0` in Phase 5 now carries the private display:

```
pv-adverb                    DISPLAY=:1     (was :0)
wineserver                   DISPLAY=:1     (was :0)
umu.exe                      DISPLAY=:1     (was :0)
steam-runtime-launch-client  DISPLAY=:1
Firestone.exe                DISPLAY=:1
```

The only `:0` processes left were the harness JVM (correctly — it lives on the host) and unrelated desktop apps.
Refusal path: with Heroic open, the launch is declined in **0.22s** with the exact wording, having spawned
nothing — against ~2 minutes and a coredump before this work. Both live suites green (openbox under Xephyr,
gamescope refusing a WM), teardown leak-free.

**Three defects the live run found, all fixed**

- **`GameLauncher.kill` killed the process performing the launch.** It was `pkill -f <token>`, a raw substring
  match over every command line on the machine — and the harness JVM's own argv carried the app id, so the
  bring-up SIGKILLed itself mid-launch (exit 144, twice, before the cause was clear). The same match also hits
  the user's Heroic, whose argv carries its whole library. It now goes through the new
  `RunningProbe.processesRunning` — the *same* observation the "is it running?" question uses, launcher
  deny-list included — and additionally spares our own process, its ancestors and its descendants.
  `GameLauncherKillTest` pins both halves with real processes (`setsid --fork` is load-bearing in the negative
  case: plain `setsid` doesn't fork from a JVM child, so the "unrelated" process would still be ours).
- **The session attached to a window that then died, and never noticed.** A launcher chain does not map the game
  first: the attach took Heroic's `ProtonFixes` dialog, the dialog closed, Firestone opened beside it — and the
  session held a destroyed window, returning `null` from `capture()` for the rest of the run while reporting
  `HEALTHY` (it *was* healthy; only the attachment had rotted). `attached()` now re-resolves through one cheap
  `XGetWindowAttributes`, replacing a **dead** window only — a session that never attached stays unattached, so
  a failed launch can't masquerade as a successful one. Live: `re-attached to 'Firestone'`, then 7/7 captures,
  zero nulls.
- **Missing ladder rungs were spawned anyway.** Heroic is Flatpak-only on a typical box, so the session ran the
  absent native `heroic`, watched it exit at once, and logged that it "mapped no window within 120000ms" — a
  timeout it never waited for. `LaunchIsolation.runnableLadder` filters to the forms that exist.
  `NestedSession.commandFor` was a delegate nothing called after that and is gone.

**Open — teardown still SIGTRAPs Electron.** Every live run leaves a 5–16 MB `heroic` coredump at *close*
(`coredumpctl`: 19:30, 19:32, 19:38). The launch itself is fine and teardown is complete and leak-free, but the
signature is identical to the original bug report, so it reads worse than it is. Likely cause: the reaper stops
the whole slice at once, so gamescope dies while Heroic is still connected to it and Chromium aborts on the
broken X connection. Proposed fix: stop the `app` scope first, give it a moment, then the rest — untested.
Also cosmetic: emptied `botmaker-sess-*.slice` units linger until the next `start()` sweeps them.

---

## 2026-07-29 — Phase 7: ask whether a target *can* be isolated, before spending anything on it

Phase 6 made isolation hold for a Flatpak launcher; this phase makes the cases where it still can't hold say so
**up front, in their own words**. Until now every one of them looked identical from the outside: the private
display sat empty for the whole window budget (up to two minutes for a store launcher), the half-booted child
was reaped — which is how an Electron launcher dies with a `SIGTRAP` coredump — and the user got a single
guess ("a host launcher daemon may be stealing it") as the explanation.

**Done**

- **`launch/LaunchIsolation`** — one question, `check(spec)` → `Verdict(blocker, command, reason)`, asked before
  bring-up. The `Blocker` enum names the four distinguishable causes rather than collapsing them into a
  timeout: `NO_CHILD_COMMAND` (`epic:` hands its launch to a URL opener, `emu-app:` runs over ADB — nothing to
  give a `DISPLAY` to), `HOST_LAUNCHER_OPEN` (single-instance UI already on `:0`), `PORTAL_WOULD_ESCAPE` (only
  the Flatpak rung is installed and there is no `dbus-daemon` to own its portal — the Phase 6 failure mode when
  Phase 6's mechanism is unavailable), `NOT_INSTALLED`. The reason is a finished sentence a caller surfaces
  verbatim, so the SDK, Studio and the session itself cannot word the same refusal three ways.
  `HOST_LAUNCHER_OPEN` reuses `HostLauncherProbe.refusalMessage` rather than restating it.
- **`LaunchIsolation.noWindowDiagnosis(spec)`** — the backstop for what a probe can't see, and it *observes*
  instead of guessing: a process carrying the target's own launch identity (`RunningProbe.commandLineMentions`)
  means the game is running **somewhere else** — it escaped to the desktop; nothing running means it never got
  that far. Two different actions, previously offered to the user as one "or".
- **Consumers now refuse rather than launch.** `NestedSession.launch` replaces its two ad-hoc guards with the
  single verdict (and its post-mortem with the diagnosis); the SDK's `SessionBootstrap.launchIsolated` declines
  to `:0` with the reason before spawning anything; Studio's `BackgroundLauncher.start` reports it immediately.
- **`Executables` (new, module root)** — `onPath(name)` and `exists(argv0)`. There were three private copies of
  the `PATH` walk (`SessionBackends`, `SpectacleCapture`, and a fourth about to be written here) and they had
  already drifted. The split matters: an `exe:` target is routinely an absolute path, and searching `PATH` for
  `/opt/game/game.x86_64` answers a confident, wrong "no". `SessionBus` now takes the daemon name from
  `LaunchIsolation.PRIVATE_BUS_BINARY`, so "can we isolate a Flatpak target?" and "what do we spawn?" cannot
  drift onto different binaries.

**Verified** — `LaunchIsolationTest` (8 cases, injected `PATH`/process probes; the flatpak-with/without-bus
pair is the one that pins Phase 6's mechanism to the refusal). Both live suites still green, no leaks. Run
against this box: `heroic:` → `HOST_LAUNCHER_OPEN` instantly (Heroic was open — the original bug report, now a
sentence instead of a coredump); Heroic is Flatpak-only here, so its isolatability rests entirely on the
`dbus-daemon` Phase 6 uses.

**Deferred / next** — the `HOST_LAUNCHER_OPEN` refusal is deliberately kept even though a private bus should
hide the host instance from a single-instance check. That it does is a live-verification result and isn't one
yet; until it is, "close it and retry" beats re-running the launch that produced the coredump.

---

## 2026-07-29 — Phase 6: the session owns a D-Bus bus (and its own Flatpak portal), not just a display

**The defect this fixes is the one that invalidated the whole isolation model.** Phase 5's live run showed the
Heroic argv fix working and the game starting — on `:0`. Handing a launcher `DISPLAY=:N` only confines its
*descendants*, and a Flatpak launcher's game usually isn't one: Heroic runs it through umu, which re-enters the
host with `steam-runtime-launch-client --bus-name=org.freedesktop.portal.Flatpak`. `flatpak-portal` is a
**D-Bus-activated service of the host session** whose own environment holds `DISPLAY=:0`, and it spawns the
container from *its* environment. Measured process by process:

```
steam-runtime-launch-client  DISPLAY=:3   <- still ours
pv-adverb                    DISPLAY=:0   <- escaped
proton / wineserver          DISPLAY=:0
```

`--pass-env-matching=*` does not save it. The lesson generalises past Heroic: **passing an environment variable
is cooperative, and any layer that re-spawns through a portal, a D-Bus activation or a single-instance daemon
resets it.**

**Done:**
- **New `SessionBus`** — a private `dbus-daemon --session` started through the session's `SessionReaper` (so it
  joins the slice and is reaped with everything else, no new teardown path), given the private `DISPLAY` in its
  own environment. The portal *it* activates therefore inherits `:N`, and so does the container that portal
  spawns. Modelled on `NestedDisplay`: launch, poll an output file for the address, guard against a partial
  write (`guid=` is the completeness marker, written last).
- **The crux is one omitted line.** The stock
  `/usr/share/dbus-1/services/org.freedesktop.portal.Flatpak.service` carries `SystemdService=`, which defers
  activation to the user-global `flatpak-portal.service` — the very portal already holding `DISPLAY=:0`. We
  generate our own service file with the same `Name=`/`Exec=` (read out of the stock file, so no distro path is
  hardcoded) and **no** `SystemdService=`. Without that omission the class is a silent no-op, which is why
  `SessionBusTest` asserts on it directly.
- **Proven live before the code was written.** A hand-built private bus produced a second `flatpak-portal` on it
  carrying `DISPLAY=:9`, and re-running the exact Heroic chain then showed `pv-adverb` and `wineserver` on `:9`
  — the two processes that had escaped. This was verified first precisely because the whole design rests on it.
- **`sessionEnv()` gains `DBUS_SESSION_BUS_ADDRESS`** and blanks `WAYLAND_DISPLAY` (a Wayland-capable client
  offered both prefers Wayland — i.e. the host compositor this session exists to stay out of).
- **`SessionBackends.usesPrivateBus(options)`** holds the policy, next to `windowManagerFor`/`pointerWarpFor`.
  On for **both** backends: this is a property of confining *launchers*, not of the display server.
  `Options.withoutPrivateBus()` is a bisect handle, documented as such.
- **A private bus is genuinely private**, and that is mostly the point: it also hides the host's launcher
  instance from a single-instance check, which was the original "close Heroic and try again" failure. Host
  session services (notifications, secret store) are not reachable from it — acceptable for a launcher that
  keeps its own credentials, and verified live (Heroic enumerated all 426 games and launched normally).
  **There is deliberately no `xdg-dbus-proxy` bridge**: `dbus-daemon` cannot forward unknown names upstream, so
  a hybrid "our portal, the host's everything else" bus is not expressible — it is one bus or the other.
- Best-effort by contract: no `dbus-daemon`, or no address, degrades to a display-only session with a `Diag`
  trace, exactly as before.

**Deferred / next:** Phase 7 (isolatability probe + honest refusal), then Phase 9's live end-to-end. Also
recorded: splitting the session stack into its own `botmaker-session` module once the contract settles.

---

## 2026-07-29 — Isolated-launch fixes, Phase 3 (shared's share): `NestedSession.Backend.fromId`

**Done:** `Backend` gains a stable lowercase `id()` and a **total** `fromId(String)` → `Optional<Backend>`, empty
for `null`, blank, `"auto"` and anything unrecognised. It lives here because both the SDK (`Session.useBackend`,
the `session.backend` key) and Studio (its backend combo) parse the same names, and the single-sourcing rule
says the shared type owns what its consumers would otherwise each rebuild — they already had. `id()` is kept
distinct from `binaryName()` deliberately: the latter is capitalised `Xephyr` because it must match the
executable, and persisting a value that has to track an executable's spelling is how a rename breaks stored
configs. Empty means "no override, choose by launch kind", never a fallback to a particular backend — the
SDK-side bug this enabled is recorded in `botmaker-sdk/ROADMAP.md`.

---

## 2026-07-29 — Isolated-launch fixes, Phase 2b: no X error can kill a bot, and gamescope clicks land on target

The two defects the first live gamescope run found (previous entry), both root-caused with purpose-built JNA
probes against a real gamescope rather than reasoned about.

**Done:**
- **`X11ErrorSilencer` → `X11ErrorTrap`, installed process-wide from `LinuxController`'s static block.** Xlib's
  *default* error handler prints the protocol error and then calls `exit(1)` — no exception, no stack trace, no
  `hs_err`; the JVM just vanishes. That is what killed the live run, and it is not gamescope-specific: a window
  unmapping between enumeration and capture does the same thing on `:0` today. The trap logs each distinct
  `(error, request, minor)` triple **once** through `Diag` (with Xlib's own `XGetErrorText` wording) and returns,
  so the failing call simply yields null and the caller's fallback ladder continues. The old class swallowed
  every error unconditionally, which is precisely why the fatal `BadMatch` stayed invisible until it was fatal.
  Studio still installs it before `Application.launch` (GDK complains if `XSetErrorHandler` is called after its
  error trap is pushed); the static block covers every bot process with nobody opting in.
- **The `BadMatch` itself: `captureWindow`'s root-crop rung asked for an out-of-bounds rect.** `X_GetImage`
  answers `BadMatch` for any rectangle not wholly inside the drawable, and gamescope places its focus window at
  root `(2,2)` at full screen size — so the window's rect is guaranteed 2px past the root on both axes. The crop
  is now `rect.intersection(rootRect)`, skipped when empty. With the rect fixed *and* the trap installed,
  `session.capture()` returns a real frame on gamescope through the XComposite pixmap rung; no new capture path
  was needed. (A `BadMatch` on request 73 is still logged once per run and is now correctly harmless.)
- **`X11.XErrorEvent` / `XGetErrorText` bound properly.** Worth recording because hand-decoding the struct got it
  wrong first: Xlib's field order is `type, display, resourceid, serial, error_code, request_code, minor_code` —
  `resourceid` comes **before** `serial`, so reading `error_code` at the "obvious" offset yields serial-number
  bytes, i.e. plausible-looking nonsense (error 9/22/85, `request=0`, which is impossible).
- **The gamescope +2,+2 pointer offset is in the WRITE, not the read — clicks were landing 2px off target.**
  Settled by having a probe create its own X window, select `ButtonPressMask` and read `x_root`/`y_root` out of
  the `ButtonPress` the server actually delivered: a warp to `(202,152)` produced a press at `(204,154)`. The
  read-back was truthful all along. Cause: gamescope's Xwayland routes injected motion through the **focused
  surface**, so `XTestFakeMotionEvent` coordinates land window-relative.
- **Fix: `PointerWarp` (`ROOT_ABSOLUTE` | `FOCUS_RELATIVE`), chosen once in
  `SessionBackends.pointerWarpFor(backend)` and threaded `NestedSession` → `LinuxController` → `XTestBackend`.**
  On `FOCUS_RELATIVE`, `move` subtracts the focused window's root origin (read live via `XGetInputFocus` +
  geometry — one round trip on a path that already pays an `XSync` and a settle sleep). Derived from live
  geometry rather than hardcoded "+2", so it self-cancels if gamescope stops insetting; degrades to the
  uncorrected warp on any read failure, never to no motion. An enum rather than a boolean per the repo's
  closed-set rule — neither case is "broken", each is correct for its server. Validated at four points with
  `delta=(0,0)`, in both the fullscreen-forced and plain gamescope configurations.
- **Tests:** `SessionBackendsTest` covers the warp policy; `NestedSessionGamescopeLiveTest` no longer carries a
  KNOWN FAILURE — its `capture()` assertion is restored and the pointer assertion tightened from a 4px tolerance
  to **exact equality** (the poll loop remains, but only for gamescope's one-frame settle). Both live suites
  (gamescope and Xephyr) pass on this box.

**Deferred / next:** Phase 3 — the `api.Session` SDK facade and the default-on isolation setting.

---

## 2026-07-29 — Isolated-launch fixes, Phase 2: window-manager policy, gamescope argv — and the first live
gamescope run

**Done:**
- **A window manager is now part of the backend's definition, in one place.** `SessionBackends.windowManagerFor(
  backend)`: Xephyr → `openbox --sm-disable` when it's on `PATH`, gamescope → never. Rationale, and the reason
  this was a real defect: production never configured a WM at all (only the live/soak tests did), and a bare
  Xephyr has **no EWMH** — nothing answers `_NET_ACTIVE_WINDOW`, so no client takes input focus and nothing
  honours a fullscreen request, while the session's window-targeted key injection depends on exactly that focus.
  gamescope is the opposite case: it *is* the window manager for its embedded Xwayland, so a nested openbox
  would fight it for the manager selection — `NestedSession.windowManagerCommandFor` refuses one there whoever
  asks. `Options` now distinguishes *unstated* (use the backend policy) from `withoutWindowManager()`
  (explicitly none); an absent openbox still degrades softly to WM-less.
- **gamescope argv** carries the project resolution on both axes — `-W/-H` (the output window) and `-w/-h` (the
  internal resolution apps see) — plus `--force-windows-fullscreen` so the game fills the display the capture
  and click coordinates assume. The window stays **visible** by design; `--backend headless` is documented on
  `GamescopeDisplay.defaultCommand` as the override for an invisible run.
- `NestedSessionLiveTest` now exercises the *default* WM rather than passing openbox explicitly. Live run on
  this box: openbox comes up and claims each `:N` ("window manager is up"), including for the concurrent
  sessions that previously ran WM-less. Both live tests pass.

**Live gamescope run — first ever on real hardware, and it found two defects.** The backend starts cleanly
(`:1` up at 1280x720, xmessage maps, `attached to 'xmessage'`), but:

1. **`capture()` kills the process.** `X_GetImage` answers **BadMatch** on a gamescope Xwayland window, and
   nothing in shared installs an `XSetErrorHandler`, so Xlib's default handler calls `exit(1)` — the JVM dies
   with no exception, no stack, no `hs_err`. Two defects in one: gamescope's windows are composited so the
   XComposite/XGetImage route doesn't apply to them, **and** no X error should ever be able to kill a bot.
   The X-error-handler half is not gamescope-specific — an ordinary race (a window closing between enumerate
   and capture) can take a bot down on `:0` today.
2. **Pointer reads on gamescope are offset and lag.** Measured with plain `xdotool` against a hand-started
   gamescope, with a window mapped: a warp is applied a frame late (an immediate read returns the *old*
   position), and a settled read is a **constant +2,+2** off the requested point (640,360→642,362;
   200,150→202,152; 1000,600→1002,602). Relative motion is exact. With *no* window mapped, only the first warp
   takes effect at all. `NestedSessionGamescopeLiveTest` now polls for the settle and asserts a 4px tolerance;
   whether the 2px is in the write (clicks land 2px off) or only in the read is not yet established.

**Deferred / next:** the two findings above, before gamescope-by-default can be trusted for games — an X error
handler that logs instead of exiting, and a capture path for gamescope's composited windows.

## 2026-07-29 — Isolated-launch fixes, Phase 1: the Heroic argv, and refusing a launch that can't work

A live isolated launch of a Heroic game failed with "didn't map a window there" and a `heroic` SIGTRAP
coredump. The journal plus Heroic's own log identified three separate faults, all fixed here.

**Done:**
- **`LaunchCommands.heroic` — the command line was never valid.** We emitted `--no-gui launch <id>`; Heroic has
  no `launch` subcommand (its `app.asar` reads `--no-gui` plus a `heroic://` URL out of `process.argv`, and its
  own Steam-shortcut generator writes `--no-gui --no-sandbox "heroic://launch?appName=…&runner=…"`). Heroic
  booted its full frontend with the window hidden, launched nothing, and we timed out waiting for a window that
  was never coming. Now: `heroic --no-gui --no-sandbox heroic://launch/<id>`, then the Flatpak form. Note this
  is *not* the `xdg-open` handoff `GameLauncher.heroic` tries first — as our own child, Heroic inherits `:N`.
- **`LaunchCommands.steam`** gained the missing Flatpak rung (`flatpak run com.valvesoftware.Steam -applaunch`),
  so the two store ladders have the same shape.
- **New `HostLauncherProbe`** — the mirror image of `RunningProbe`'s launcher deny-list: is the launcher *UI*
  itself up? These launchers are single-instance, so with one open our child forwards its request to the copy
  on `:0` and the game starts on the real desktop. `NestedSession.launch` now refuses such a launch immediately
  with `refusalMessage(kind)` instead of burning the window budget and then reaping a half-booted Electron —
  which is exactly what produced the coredump. Both probes read the process table through the now
  package-visible `RunningProbe.programNames`, so a wrapper script / Electron shell / `flatpak run` child is
  recognised identically by the two.
- **Per-kind window budget.** `WINDOW_TIMEOUT_MS` (20 s) still applies to `exe:`/`cli:` — those *are* the
  process we spawned — but a store kind gets `LAUNCHER_WINDOW_TIMEOUT_MS` (120 s), because the window we're
  waiting for is the game's, after a Proton prefix and (first run) a winetricks/umu download.
  `Options.withWindowTimeout(ms)` overrides both; `NestedSession.windowTimeoutFor(spec, options)` is the pure,
  tested policy.
- Tests: rewritten `LaunchCommandsTest` ladders, new `HostLauncherProbeTest` (real processes, as
  `RunningProbeLauncherTest` does), `NestedSessionTest` cases for the timeout policy and the override.

**Deferred / next:** Phase 2 — window-manager policy (openbox by default on Xephyr, never on gamescope, since
gamescope *is* the WM for its Xwayland) and the gamescope argv (project-resolution nested window,
`--force-windows-fullscreen`, `--backend headless` documented as the invisible-run override).

## 2026-07-29 — Bot-owned-display plan, Phase H: `session.isolated` project setting (default true)

Isolation was gated on a system property nothing set, so it was reachable only from BotPilot. Make it a
persisted, single-sourced project setting so *every* launch path agrees, defaulting on with a real opt-out.

**Done:**
- `ProjectProperties`: new keys `KEY_SESSION_ISOLATED = "session.isolated"` and `KEY_SESSION_BACKEND =
  "session.backend"`. `sessionIsolated() → Boolean` has a baked-in **default of true** (absent/unparseable →
  `TRUE`; only an explicit `false`/`0`/`no`/`off` opts out); `sessionBackend()` returns the raw override or
  null. Factored the boolean parse `debug()` open-coded into a private `parseBool(key)` now shared by both.
- Added a package-private `setForTesting(Properties)` seam (mirrors `NativeControllerFactory.setForTesting`)
  so the accessors are testable without a classpath resource.
- `ProjectPropertiesTest` — default-true, explicit-false, unparseable→true, raw backend, and that `debug()`
  still parses.

**Consumed by:** the SDK's `ProjectDefaults.sessionIsolated()` / `SessionBootstrap.isolationRequested()`
(same phase, sdk side).

## 2026-07-29 — Bot-owned-display plan, Phase G: single-sourced backend choice (`SessionBackends`)

Isolation had one backend knob (`botmaker.session.backend`, defaulting to Xephyr) that nothing set, so an
isolated Heroic/Steam launch ran into **Xephyr's software GL** and SIGTRAPped (Electron/Chromium GPU process
abort). The backend must instead be a function of *what* is launched, single-sourced so the SDK runtime and
Studio can't drift.

**Done:**
- New `session.SessionBackends` (stateless): `preferredBackend(LaunchSpec)` — game kinds (`STEAM`/`EPIC`/
  `HEROIC`/`FAUGUS`/`EXE`) → `GAMESCOPE` (a real GPU in the private display), `CLI`/`EMULATOR_APP`/`UNKNOWN`/
  null → `XEPHYR`; `availableBackendFor(LaunchSpec)` — the preferred backend filtered by a `PATH` probe on its
  `binaryName()`, **empty = required backend not installed** (the loud-failure signal, *no* silent Xephyr
  fallback for a game); `isAvailable(Backend)`; `installHint(Backend)`. A package-visible
  `availableBackendFor(spec, Predicate<String>)` overload is the testable seam.
- `SessionBackendsTest` — kind→backend mapping, availability against a fake PATH, game-needs-gamescope-but-
  missing → empty, install hints name the backend.

**Consumed by:** the SDK's `SessionBootstrap` (same phase, sdk side) — backend/options now take the
`LaunchSpec` and route through `SessionBackends`, with the system property kept only as an explicit override.

**Deferred / next:** factor `NestedSessionLauncher.backendAvailable`'s duplicate PATH probe (Studio) onto
`SessionBackends.isAvailable` in Phase J so the two share one copy.

## 2026-07-28 — Bot-owned-display plan, Phase C: gamescope live harness

The gamescope backend (`GamescopeDisplay`) was implemented and unit-tested but never live-run — its javadoc
carried an "unverified on the dev box" note because this machine has no `gamescope` binary and only software GL.
Phase C adds the missing live proof so a maintainer on a real GPU/gamescope box can verify the 3D backend
end-to-end, reproducibly, instead of by hand.

**Done:**
- New `NestedSessionGamescopeLiveTest` — the gamescope counterpart to `NestedSessionLiveTest`. Same
  background-input proof (bring up a private `:N`, launch a client into it, drive its pointer to `(640,360)`,
  confirm the real `:0` cursor did **not** follow, capture a frame, then confirm `close()` reaps the display),
  plus the one assertion unique to this backend: the session advertises `Capability.HARDWARE_GL` **and**
  `Capability.VULKAN` (Xephyr's software path advertises neither). It exercises the standalone-host bring-up
  path — gamescope's stderr `Starting Xwayland on :N` banner → `parseDisplayNumber` → `DisplayReadiness`.
- **Opt-in and self-skipping**, exactly like the Xephyr suite: runs only with `-Dbotmaker.live=true` **and**
  `gamescope` on `PATH` (via `Backend.GAMESCOPE.binaryName()`), else `assumeTrue`-skips. Verified locally it
  skips (this box has no gamescope) while the pure `NestedSessionTest` stays green.
- **Per-box argv escape hatch:** `-Dbotmaker.gamescope.args="gamescope --backend sdl -W 1280 -H 720"` overrides
  the whole gamescope argv (via `Options.withGamescopeCommand`) so a desktop that isn't a DRM session can force
  the nested SDL backend — the fallback the `GamescopeDisplay` bring-up note anticipated, with no code change.

**Run it (real gamescope box):**
```bash
mvn -pl botmaker-shared test -Dtest=NestedSessionGamescopeLiveTest -Dbotmaker.live=true \
    -Dsurefire.failIfNoSpecifiedTests=false
# add -Dbotmaker.gamescope.args="…" if the default standalone bring-up is fragile on that box
```

**Deferred / next:** not wired into `session-live.yml` CI — GitHub runners have no GPU, so gamescope can't
nest there (the Xephyr `NestedSessionLiveTest` remains the headless CI proof). This harness is the on-a-GPU-box
complement, run manually. If a self-hosted GPU runner ever lands, add a `workflow_dispatch` job mirroring the
Xephyr one but installing gamescope and selecting `NestedSessionGamescopeLiveTest`.

## 2026-07-28 — Bot-owned-display plan, Phase F (shared slice): `Backend.binaryName()`

Studio's pilot UI now preselects background mode only when the backend's host binary is on `PATH`. Rather than
let Studio hardcode the executable names (which would silently drift from what shared actually spawns),
`NestedSession.Backend` now carries `binaryName()` — `XEPHYR → "Xephyr"`, `GAMESCOPE → "gamescope"` —
single-sourced next to `NestedDisplay`/`GamescopeDisplay`, the code that runs them. Covered by
`NestedSessionTest.backendNamesTheBinaryItSpawns`. (The Studio-side UX that consumes it is in
`../botmaker-studio/ROADMAP.md`.)

## 2026-07-28 — Bot-owned-display plan, Phase E: launch store targets into `:N`, fail loudly

Live testing with **Heroic → Firestone** showed the pilot still moved the real cursor: `NestedSession` refused
every store-launcher kind (`commandFor` handled only `exe:`/`cli:`), so a Heroic target never mapped on `:N`,
the session silently closed, and the pilot fell back to the cursor-moving `:0` device controller. Heroic's
`heroic://launch/<id>` URL is worse than useless here — it hands off to the Heroic daemon already running on
`:0`, which ignores our private `DISPLAY`.

**Done:**
- New `com.botmaker.shared.launch.LaunchCommands` — the single source of the *child-launchable* argv ladders a
  target runs under (native binary, then Flatpak). `heroic(id)` / `steam(id)` / `faugus(id)` return ordered
  ladders; `childLadder(spec)` dispatches by kind (single-rung `exe:`/`cli:`, the store ladders for
  heroic/steam/faugus, empty for `epic:` (URL-only) and `emu-app:` (ADB)). Both launch paths now draw from it
  so they can't drift.
- `GameLauncher.{heroic,steam,faugus}` CLI fallbacks now iterate `LaunchCommands.*` (replacing the inline
  `tryStart(...)` ladders); `tryStart` takes a `List<String>` and a new `runFirst(ladder)` walks it.
- `NestedSession.commandFor` returns `List<List<String>>` (the ladder) via `LaunchCommands.childLadder`;
  `launch()` runs each rung as our own child (inheriting `DISPLAY=:N`), attaching to the first that maps a
  window on `:N`. When none does (a launcher daemon's single-instance lock stole it to `:0`), `attached()`
  stays null — a **loud** failure, never a silent `:0` fallback. `stopHostInstance` documents why it stops the
  game but not the user's launcher daemon (too disruptive; the loud failure is the safety net).
- Tests: new `LaunchCommandsTest` (ladders per kind, dispatch, empty kinds, blank-token rejection);
  `NestedSessionTest` updated for the ladder shape (heroic/steam now *have* nested commands; epic/emulator
  don't).

**Deferred / next:** the Heroic/Steam single-instance daemon caveat is documented, not solved — if the user's
launcher is already running it can still swallow the CLI invocation and map on `:0` (loud failure tells them to
close it and retry). Studio Phase F surfaces/defaults the isolated-session control and a persistent status line.

## 2026-07-28 — Bot-owned-display plan, Phase D: ranked window matching (`WindowMatch`)

Live testing exposed a capture bug: pointing at "Firestone" selected a wiki tab / chat channel / launcher
entry named after the game instead of the game itself, because both consumers (Studio's
`TargetCapture.resolveWindow` and the SDK's `Window.find`) took the **first** window whose title merely
*contained* the needle. On the pilot's `:0` path the wrong window's rect then became the Interact coordinate
frame, so clicks also missed.

**Done:**
- New `com.botmaker.shared.capture.WindowMatch` — the single ranked matcher both consumers call (so they can't
  drift). `best(Iterable<GenericWindow>, needle)` / `ranked(...)` score candidates best→worst: exact title,
  suffix-stripped exact (drops a trailing ` - …`/` – …`/` — …`/`: …` score/level suffix), `startsWith`,
  whole-word, plain substring; ties break by shortest title then largest on-screen area then input order.
  Null/blank titles and null/zero-area rects are excluded; pure over the only two fields `GenericWindow` has
  (title + rect — no PID/class). `WindowMatchTest` covers the Firestone case, tier ordering, suffix-strip,
  tie-breaks and the exclusions.

**Deferred / next:** Phase E — teach `NestedSession.commandFor` to launch store-launcher targets (Heroic/…)
into `:N` (today it no-ops all but EXE/CLI, so a Heroic pilot silently falls back to the cursor-moving `:0`).

## 2026-07-28 — Bot-owned-display plan, Phase B: `ActiveSession` holder (SDK-reachable)

The nested-session infrastructure had a Studio-side session holder (`PilotSession`) but nothing the **SDK bot
runtime** could reach — a generated bot is a separate process from Studio. Phase B adds the process-wide twin.

**Done**

- `session.ActiveSession` — a tiny mutable singleton (the kind this module already keeps for
  `NativeControllerFactory`) holding the `DesktopSession` a bot is driving: `set`/`get`/`isActive`/`clear`,
  defaulting to `null` (no session → today's `:0` behaviour, unchanged). Lifecycle-agnostic: `clear()` detaches
  but does **not** close; closing is the setter's job. This is what the SDK's `Mouse`/`Keyboard`/`Source`
  consult (see the sdk Phase B entry) so a bot drives its private `:N` display with no call-site change.
- `ActiveSessionTest` covers set/get/isActive and that `clear()` never closes.

**Deferred / next** — Phase C: a gamescope-variant of `NestedSessionLiveTest` (opt-in, self-skipping off a GPU
box). Longer: XI2 grab-state auto-switch for mouselook; store-launcher kinds into `:N`.

---

## 2026-07-28 — Bot-owned-display plan, Phase 6: live proof, soak & CI

Every prior phase deferred its *live* exit ("needs Xephyr, which CI does not provide"). A real X server turned
up on the dev box, so the whole premise is now proven **through our own `NestedSession` supervisor**, not just
in unit fakes — and the proof is reproducible (guarded tests + an Xvfb CI job), replacing the old "manual run
recorded in the ROADMAP" convention.

**Done**

- **The premise, proven live.** `NestedSessionLiveTest` (opt-in: `-Dbotmaker.live=true` + a real
  `DISPLAY`/`Xephyr`/`openbox`, else self-skips): our supervisor allocates a private display via
  `Xephyr -displayfd`, pins **XTest on `:N`**, brings up openbox, launches a client into `:N`, drives that
  display's pointer to an exact target — and the real `:0` cursor does **not** follow it. Observed run:
  `:N` pointer landed on (640,360) while `:0` stayed elsewhere; `close()` stopped the systemd slice with
  **zero orphan Xephyr**. A stricter idle-box shell run (scratchpad `smoke.sh`) showed the real cursor
  *perfectly still* (before==after); the committed assertion is the leak-proof "real cursor is not at the `:N`
  target" so it's also robust on a live, in-use desktop (ambient hand movement) and deterministic under Xvfb.
- **Distinct displays for concurrent sessions** — 3 sessions started together own 3 distinct `:N` (no
  `-displayfd` collision).
- **Soak & chaos** (`NestedSessionSoakTest`): repeated bring-up/teardown leaks nothing — after every cycle
  **0 orphan Xephyr** and the JVM's `/proc/self/fd` count is flat (observed 57→57 over 5 cycles; the `:N`
  controller + EWMH connections are actually closed). Scale to a 24h soak with `-Dbotmaker.soak.iterations=N`.
  Health chaos: a self-closing client (`xmessage -timeout`) drives the session to `DEGRADED` (game dead,
  display alive) with no fragile external kill; `close()` → `DEAD`.
- **CI** — `.github/workflows/session-live.yml` runs the live suite headless under `Xvfb` (Xephyr nests inside
  it) on changes to `session/` or `capture/linux/`. GitHub runners have no per-user systemd, so `SessionReaper`
  exercises its **ProcessBuilder descendant-kill fallback** there; the `systemd --scope` path is exercised on
  the real box. Plain `mvn test` stays clean (the live/soak tests skip without the opt-in flag — verified: a
  normal build spawns no Xephyr).

**Deferred / next**

- **gamescope live exit** — the 3D path (`GamescopeDisplay`) still needs a GPU box; only the Xephyr (2D) backend
  is proven live here. Same `NestedSessionLiveTest`, swap `Options.gamescope(...)`.
- **CI package names unverified from here** — the workflow's apt list (esp. the `xmessage` provider) is
  best-effort; the first CI run confirms. `xrestop` GPU/X-resource sampling from the plan is not wired (the
  fd-count + orphan-count leak signals cover the JVM side).
- **Longer real soak** — a genuine 24h `-Dbotmaker.soak.iterations` run per backend on a dedicated box.

---

## 2026-07-23 — Bot-owned-display plan, Phase 4: input hardening (deterministic keys, no stuck input, mouselook)

The device-level (XTest) injection the nested session pins was a naive warp-and-click: it dropped events
toolkits sampled a frame late, silently swallowed any character the active layout didn't map to a keycode, and
could leave a modifier stuck if a sequence was interrupted. Phase 4 makes it observable, total over Unicode,
and self-healing — all within `capture.linux.input`, backend-agnostic where it can be, and fully unit-tested on
this box (X11-only, no gamescope needed for any of it).

**Done**

- **Observable click timing** (`InputTiming`, immutable + tunable) — `XTestBackend.clickScreen` now runs
  move → `XSync` (a real round-trip so the motion is applied server-side, not just flushed) →
  `motionSettleMs` → press → `pressHoldMs` → release. Typing paces itself with `interKeyMs` between characters
  (surfaced via `LinuxInputBackend.interKeyDelayMs()`), so a fast `typeText` can't outrun the target's queue.
  Defaults 12/12/8 ms; `with*` for a target that needs longer holds.
- **Deterministic Unicode keys** (`Keymap` + `KeymapOps`/`XlibKeymapOps`) — XTest injects *keycodes*, so a
  keysym the layout maps to none (`XKeysymToKeycode → 0`, e.g. `é`, `€`, CJK) was undeliverable and silently
  dropped. `Keymap` now **borrows a spare (unbound) keycode**, points it at the keysym via
  `XChangeKeyboardMapping`, hands it to XTest, and **restores** the original mapping after the release. The
  spare-selection + restore bookkeeping is behind a `KeymapOps` seam and unit-tested against an in-memory table
  (`KeymapTest`, 6 cases: high-to-low spare pick, per-keysym idempotence, distinct spares, occupied-keycode
  never disturbed, `restoreAll`, exhausted-keymap → drop). New X11 bindings: `XDisplayKeycodes`,
  `XGetKeyboardMapping`, `XChangeKeyboardMapping`.
- **No stuck input** — `XTestBackend` tracks every key/button it presses; `releaseHeld()` (new
  `LinuxInputBackend` default) lets them all go and calls `Keymap.restoreAll()`. `LinuxController.typeVia` wraps
  the whole string in `try/finally → releaseHeld()`, so an exception or interrupt mid-stroke can't leave a Shift
  (or a borrowed-keycode layout change) hanging — the archetypal "typed fine then everything broke" failure.
  `XTestBackend.close()` releases too.
- **True relative motion (mouselook)** — `XTestBackend.moveRelative` injects a real
  `XTestFakeRelativeMotionEvent`, added as `LinuxInputBackend.moveRelative(dx,dy) → boolean` (default `false` =
  unsupported) and `NativeController.mouseMoveRelative` (default = portable read-back-then-warp).
  `LinuxController` prefers the backend's device-relative injection and falls back to the warp;
  `ControllerPointer.moveRelative` now delegates there (replacing its read-back+skip stub). Device-relative
  motion survives a game's pointer grab/warp, where reading an absolute position to add a delta to is unreliable.

**Verified** (unit + build): `KeymapTest` (6) + `InputTimingTest` (3) new; full shared suite **113 green, 0
failures**; `mvn -pl botmaker-shared install` clean.

**Deferred / next (Phase 4 remainder, needs a live grabbing target to verify honestly)**

- **Grab-state detection / `RELATIVE_GRABBED` session mode.** The plan's `XIQueryPointer`-based auto-switch
  (detect that a game has grabbed+warped the pointer, flip the session to relative, integrate deltas toward a
  target, read the actual position after each event) is **not** built — it needs an XInput2 binding *and* a real
  mouselook app to verify against, the same "can't confirm on this box" situation Phase 3 hit with gamescope.
  The mechanism it depends on (true relative injection) is in place, so this is a self-contained follow-up.
- **Live end-to-end typing/mouselook run** (the plan's Phase 4 exit): confirm deterministic Unicode typing into
  a real toolkit and a mouselook app tracking a scripted path, on a nested `:N`. Logic is unit-covered; the
  live pass remains.

---

## 2026-07-23 — Bot-owned-display plan, Phase 3: gamescope backend (hardware 3D)

The hardware-3D display backend, behind the *same* `DesktopSession` contract as Phase 2. Xephyr is
software-rendered here (2D-only); a Proton/DXVK/Vulkan title needs a real GPU, which **gamescope** provides via
its embedded Xwayland. The win of Phase 2's design shows here: the supervisor (launch game → find window →
inject XTest → reap) didn't change at all — only the display server did.

**Done**

- **`SessionDisplay` seam** — extracted the tiny surface `NestedSession` actually needs from a display
  (`displayName` / `width` / `height` / `alive` / `hardwareAccelerated`). `NestedDisplay` (Xephyr) and the new
  `GamescopeDisplay` both implement it; `NestedSession` holds a `SessionDisplay` and `start()` branches on
  `Options.Backend` (`XEPHYR` | `GAMESCOPE`). The whole supervisor below the seam is backend-agnostic.
- **`GamescopeDisplay`** — launches gamescope via the reaper and discovers its nested display number. gamescope
  has **no `-displayfd`**: it announces its Xwayland on **stderr** (`Starting Xwayland on :N`). So we run
  gamescope in its **standalone-compositor** form (no `--` child — the SteamOS session model, where gamescope
  hosts an Xwayland apps then join with `DISPLAY=:N`), capture stderr (new `SessionReaper.launch` stderr-redirect
  overload), and `parseDisplayNumber` it out with a tolerant `(?i)xwayland on (:\d+)` regex. Readiness still
  gated on a real `XOpenDisplay` (shared `DisplayReadiness.awaitConnectable`, extracted from `NestedDisplay`).
  Reports `hardwareAccelerated() = true`, so a gamescope session additionally advertises **HARDWARE_GL** +
  **VULKAN** (`capabilities()` now an `EnumSet` gated on the display).
- **`Options.gamescope(w, h)`** factory + `withGamescopeCommand(...)` override — the exact gamescope argv is
  tunable without touching `GamescopeDisplay`, so a real box can adjust flags (`--backend`, HDR) or switch to
  the child-launch form. `displayServerCommand()` returns the override or `GamescopeDisplay.defaultCommand`.

**Verified** (unit only — see caveat): `NestedSessionTest` grew backend-selection + gamescope stderr-parse
cases (5 tests); full shared suite **104 green, 0 failures**. `mvn -pl botmaker-shared install` clean.

**Not verified live — no gamescope on this box.** The dev machine has a real GPU (`/dev/dri`, `glxinfo`,
`vulkaninfo`, `Xwayland`) but **no `gamescope` binary** and only software GL, so the gamescope path is
implemented + unit-tested but has **not** been run end-to-end. This matches the plan's Phase 3 exit
("a DXVK/Proton title driven end-to-end in the background"), which inherently needs a GPU+gamescope box.

**Deferred / next (real-box bring-up)**

- **Live-run gamescope** on a GPU+gamescope machine: confirm the standalone-host form stays up and the stderr
  banner matches `parseDisplayNumber`. **If fragile** (a build that exits without a `--` child, or a banner the
  regex misses), switch to the **child form** — launch the game *as* gamescope's child so it inherits
  `DISPLAY` — via `Options.withGamescopeCommand(...)` (no code change needed) and read the number from the same
  stderr. `GpuProbe` (Phase 0) already reports whether gamescope is installed and whether Xephyr can do 3D, to
  drive the backend choice.
- **Store-launcher kinds** (steam/heroic/epic) still deferred: they hand off to a `:0` daemon, so they can't be
  given a private `DISPLAY`; `NestedSession.launch` supports only `exe:`/`cli:`. gamescope's child form is the
  likely home for a Proton title once the Steam/Heroic launch-into-`:N` story is designed.
- Phase 4 (input hardening: click timing, keymap determinism, modifier tracking, mouselook grab) is unchanged
  by this phase and applies to both backends.

---

## 2026-07-23 — Bot-owned-display plan, Phase 2: nested supervisor (Xephyr `:N`)

The supervisor that makes background input **flawless**: a bot launches its game into a private nested Xephyr
`:N`, whose global pointer/focus are the bot's alone — so device-level XTest that would hijack the real cursor
on `:0` is, on `:N`, both accepted by the game *and* invisible to the user. All in `com.botmaker.shared.session`;
no new module.

**Done**

- **`NestedSession implements DesktopSession`** — `start(Options)` brings up the display (+ optional WM);
  `launch(LaunchSpec)` stops any `:0` instance then launches the game into `:N` and attaches to its window.
  Advertises the three capabilities a shared desktop can't: **BACKGROUND_CLICK / ISOLATED_FOCUS /
  MULTI_SESSION** (plus ABSOLUTE/RELATIVE_POINTER, SCREEN_CAPTURE, WINDOW_LAUNCH/ATTACH). Pins the **XTest**
  backend on `:N` (`LinuxController.forDisplay(name, "xtest")`) — private display ⇒ device-level input is both
  accepted and non-intrusive, and the process-wide `botmaker.linux.input` property must not decide `:N`'s
  backend. `health()` → DEAD (display gone) / DEGRADED (game died, display up) / HEALTHY. `Options` (immutable)
  = screen size + optional WM command + extra per-session env; `DISPLAY` is always injected.
- **`NestedDisplay`** — race-free display allocation: Xephyr `-displayfd 1` picks a free number and writes it
  back (never scan `/tmp/.X11-unix`); readiness gated on an actual `XOpenDisplay` succeeding (no `sleep`).
- **`SessionReaper`** — launches every session process into a per-session systemd **scope in a shared
  `.slice`** (`systemd-run --user --scope --slice=botmaker-sess-<id>.slice`), so one `systemctl --user stop`
  reaps the whole cgroup. `--scope` inherits the `ProcessBuilder`'s stdio, which is how `-displayfd` output is
  read back. Env via `--setenv=`. Falls back to plain `ProcessBuilder` children reaped via
  `ProcessHandle.descendants()` when there's no user systemd. **Orphan sweep** (`reapOrphans()`): the session
  id is `s<pid>-<seq>`, so a leftover `botmaker-sess-s<pid>-*.slice` whose owner pid is dead is reaped by slice
  name — the reliable answer to "`kill -9` the JVM ⇒ zero orphans" (a `--scope` outlives its JVM by design;
  `NestedSession.start` sweeps before each start, and `NestedSession.reapOrphanSessions()` is public for boot).
- **Window targeting** — `X11Utils.getCardinalProperty` / `getWindowPid` (`_NET_WM_PID`) /
  `hasWindowManager` (`_NET_SUPPORTING_WM_CHECK`). Attach prefers a window whose `_NET_WM_PID` is in the
  launched process subtree, else the newest window that appeared since a pre-launch snapshot (covers WM-less
  displays with no `_NET_CLIENT_LIST`, and apps that don't set the pid).
- **`ControllerPointer`/`ControllerKeyboard`** extracted from `HostSession` so both sessions share one input
  delegation (keyboard reads the attached target via a `Supplier`, so it always reflects the current target).
- **Bug fixed: `XInternAtom(..., onlyIfExists=true)` crashes the process.** JNA marshals Java `true` as
  `0xFFFFFFFF`; Xlib copies the low byte (`0xFF`) into the request's 1-byte `only_if_exists` field, and the
  server rejects anything but 0/1 with `BadValue` — but *only* when the atom is actually missing. On `:0` the
  EWMH atoms already exist so a stray `true` looks fine; on a fresh nested display they don't, so it aborts.
  All new property reads use `onlyIfExists=false` (the idiom every other caller in `X11Utils` already uses).
  (Pre-existing `promoteAboveFullscreen` still passes `true`, latent-safe on `:0`; left as-is.)

**Verified** (live, this box — 2D Xephyr path): `NestedSessionTest` (launch-kind→argv, immutable Options) +
`HostSessionTest` still green (102 shared tests, 0 failures). End-to-end run: Xephyr `:1` allocated via
`-displayfd`, `cli:xterm` launched into it with `DISPLAY=:1` and attached; driving the `:N` pointer 40 moves +
4 clicks (device-level XTest) left the **real `:0` cursor at exactly its start position** — flawless
background input. `close()` stopped the slice with zero orphans; a `kill -9`'d session's orphan Xephyr was
reaped by a second JVM's sweep (and a *live* session's tree was correctly left alone).

**Deferred / next**

- **openbox + a real game** untested here (this box has neither; only `kwin_x11`). The WM launch + readiness
  (`hasWindowManager`) code path exists and returns `false` correctly WM-less. Verify on a box with openbox and
  a Wine/Proton title (which set `_NET_WM_PID`, exercising the strong attach path).
- **Phase 3 — gamescope backend (3D):** same `DesktopSession` contract; needs a real GPU + `gamescope`
  (absent here). Store-launcher kinds (steam/heroic) that hand off to a `:0` daemon are deferred with it —
  `NestedSession.launch` currently supports only `exe:`/`cli:` (the kinds you can hand a private `DISPLAY`).
- **Per-session isolation env** (`Options.withExtraEnv`) is wired but defaults empty; Phase 2+ should populate
  private `HOME`/`XDG_RUNTIME_DIR`/`WINEPREFIX` + `dbus-run-session` to stop a single-instance game escaping
  back to `:0`.
- Empty structural parent slices (`botmaker.slice`, `botmaker-sess.slice`) linger after a session — no
  processes, systemd GCs them; not worth racing a concurrent session to stop.

---

## 2026-07-23 — Bot-owned-display plan, Phase 1: display retargeting + session seam

Second phase of **flawless background input** (see `../.claude/plans/review-this-draft-plan-spicy-flask.md`).
Makes a `LinuxController` targetable at any display and introduces the thin session abstraction bot code will
be written against, all as a pure wrap of today's behaviour (nothing on `:0` changes).

**Done**

- **`LinuxController` retargeting** — new `LinuxController(String displayName)` ctor calls
  `XOpenDisplay(name)` (was hardcoded `XOpenDisplay(null)`); `LinuxController.forDisplay(":9")` factory
  **bypasses** the `NativeControllerFactory` singleton so a nested-`:N` controller and the default `:0`
  controller coexist in one JVM. The no-arg ctor still opens the default `$DISPLAY`. Every backend/X11 helper
  already threads the resulting `Display*`, so a `:9`-bound instance does input+capture+window-mgmt entirely on
  `:9`. `displayName()` accessor added.
- **Session seam** in `com.botmaker.shared.session`:
  - `Capability` enum (ABSOLUTE/RELATIVE_POINTER, BACKGROUND_CLICK, ISOLATED_FOCUS, MULTI_SESSION,
    HARDWARE_GL, VULKAN, SCREEN_CAPTURE, WINDOW_LAUNCH, WINDOW_ATTACH) — so a bot fails fast via
    `session.has(cap)` instead of silently no-op'ing.
  - `DesktopSession` (AutoCloseable): `capabilities()`, `screen()`, `pointer()`, `keyboard()`,
    `attach(GenericWindow)`/`attached()`, `launch(LaunchSpec)`, `capture()`, `health()`, and a `controller()`
    migration bridge (the SDK/pilot still hold a `NativeController` directly; routing them through a session
    means handing them *this* one instead of the global singleton).
  - `SessionPointer` exposes **both** `moveAbsolute` and `moveRelative` from day one (mouselook reads deltas;
    retrofitting later would touch every call site). `SessionKeyboard`, `SessionHealth`.
  - `HostSession` — wraps the default controller, **changes nothing**. Advertises ABSOLUTE/RELATIVE_POINTER,
    SCREEN_CAPTURE, WINDOW_ATTACH, WINDOW_LAUNCH — but **not** BACKGROUND_CLICK / ISOLATED_FOCUS / MULTI_SESSION
    (a shared `:0` desktop genuinely can't offer them). Does **not** own its controller — `close()` never
    closes the shared X11 connection. Keyboard routes to the attached window's targeted calls when attached,
    else the focused-window path; `moveRelative` anchors on the read-back cursor position (skips if unreadable
    rather than warping to the bare delta).
- **Characterization test** `HostSessionTest` (8 tests, green) pins HostSession as a pure pass-through:
  capability honesty, attached-vs-unattached keyboard routing, raw pointer delegation, relative-move anchoring,
  capture targeting the attached window, and `close()` not touching the shared controller.

**Verified:** `HostSessionTest` green; and a live two-controller run (`:0` + a throwaway Xephyr `:9` in one
JVM) — both opened independent X11 connections, each picked its own backend, the `:9` click posted to `:9`,
both closed cleanly. Phase-1 exit criterion met.

**Deferred / next (Phase 2 — nested supervisor, `session/`):** launch Xephyr/gamescope + WM + game with
readiness gating (no `sleep`), `-displayfd` allocation (proven in Phase 0), XTest backend on `:N`, per-session
env (`HOME`/`XDG_RUNTIME_DIR`/`dbus-run-session`/`WINEPREFIX`), `systemd-run --user --scope` tree reaping,
`_NET_WM_PID` window targeting. Template on `EmulatorAppLauncher` (launch → poll-readiness → act → teardown).
`NestedSession` fills in the BACKGROUND_CLICK/ISOLATED_FOCUS/MULTI_SESSION capabilities `HostSession` withholds.

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
