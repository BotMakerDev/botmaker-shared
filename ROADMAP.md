# ROADMAP

Completed work up to 2026-10-05: see CHANGELOG.md, docs/refactor/, and `git show ed3d1aa:ROADMAP.md` (it
also holds `botmaker-session`'s history before 2026-07-30).

## Open

- **`WindowsControllerTest.childEnumerationOfANonWindowIsEmptyRatherThanThrowing` fails on Windows.**
  `EnumChildWindows` on a null parent enumerates the desktop's windows, so `WindowFinder.getChildWindows(new
  HWND())` is not empty. Decide which is wrong: the test, or the finder's missing null guard.
- **Windows input and capture: what the live test can't reach** (`-Dbotmaker.live=true`, which covers background
  and take-over input, the ignored-click warning, a covered window's capture, WGC's resize and a fullscreen
  window, at 100%, 125% and 150%). Still owed: two monitors at different scales, a DirectX game reading raw
  input (mouselook, the warning, a black `PrintWindow`), an elevated game ("SendInput was blocked"). Then
  `-Dbotmaker.windows.capture=wgc` on a covered DirectX game: if it works, make WGC the first rung by default
  (`WgcCapture.requested`), and keep the session open between captures as it already does.
- **Windows: the run overlay can end up in a screen copy.** `WindowCapture.onTop` uses `WindowFromPoint`, which
  skips click-through windows, so a game under the SDK's run overlay counts as on top and its screen copy holds
  the overlay's boxes. `PrintWindow` and WGC are unaffected. The fix: the overlay window set to
  `SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE)` (Windows 10 2004+) is left out of the copy, as the live
  test's fullscreen check shows. Studio owns that window, so the fix is a `NativeController` call it makes
  once the overlay is shown, beside `promoteOverlayAboveFullscreen`. Counting it as covering instead would send
  every run's capture to `PrintWindow`, black for many fullscreen games.
- **Windows: window-less keys are always real.** `keyDown(int)`/`typeText(String)` with no window go to the
  focused window through `SendInput` even in the background, because there is no target to post to. The SDK's
  `Keyboard` passes a window when the bot's source is one.

- **Keyboard layouts.** A layout switched mid-run is not followed (re-read on `XkbMapNotify`, or per key with
  a cache); only the first XKB group is read; no backend adds Shift for a character on a shifted level
  (`typeText("1")` on AZERTY types `&` under XTest and uinput alike) — a level lookup fixes both backends.
- **Emulator host-window capture** via `NativeController.captureWindow`, ADB kept for `input tap`; the cost
  is client-area → device-pixel mapping. Same mechanism as the gamescope-window capture in
  `botmaker-session`'s ROADMAP: one backend for both. `adb screenrecord` is not suitable for matching (4:2:0
  chroma smears HUD edges, stale frames, 180 s cap).
- **The phone timing table is empty.** Floor vs. scrcpy fast path has never been measured on hardware;
  `AdbCaptureBenchmark` against a real phone plus a `ScrcpyDevice` session answers it.
- **Pinned adb / scrcpy-server versions have no owner** to bump them. They fail loudly (404 or digest
  mismatch) when they age, never silently.
- **`capture.source` grammar**: `desktop`, `monitor:<i>`, `window:<t>` still spell themselves at each site;
  a sealed `CaptureSourceSpec` owning the whole grammar is the eventual answer.
- **Nothing tells the user gamescope was sized to the default** because nothing could say the project's
  resolution; `WaydroidDiagnostics.resolutionMismatch` is the only after-the-fact comparison.
- **Waydroid cold start**: the session-start budget is 300 s; if cold starts get slower, add a progress
  callback rather than a bigger number. `WaydroidResolution.read()` misses a size changed outside BotMaker
  until the next live read.
- **The natives in shared's jar** cost a session-only consumer 9.4 MB; split a `botmaker-shared-natives`
  artifact if somebody consumes session standalone and complains. Only `linux-x86_64` is bundled; ARM would
  take a second `artifactItem`.
- **App labels over ADB** need the `resources.arsc` string pool; icon plus package name is enough today.
- **Adopted sessions have no `DEGRADED` state**: they see the display, not the game's process. A bot that must
  notice the game dying wants a probe on the attached window.
- **The session watchdog is Studio-side only**; a bot JVM outliving its session's display relies on someone
  calling `closeIfDead`. Revisit when the SDK grows a supervisor loop.
- **Teardown `SIGTRAP` coredump (B1/B2), unproven fix**: `SIGTERM` only the Heroic browser pid on a live
  session and capture the launcher's stderr for the `[FATAL:…] Check failed:` line; B2 (game tree first,
  then `SIGKILL` the launcher) is gated on it.
- **Pilot tap path (A1–A5)**: A1's live trace reading (session, Firestone, phone) gates A2; drag `UP` still
  warps back to `dragOrigin` on `:N`; A5 (BotPilot taps at the pointer-down coordinate) untouched. Unverified
  whether later work closed these.
- **`HOST_LAUNCHER_OPEN` refusal is kept** until a live run shows a private D-Bus hides the host instance from
  a single-instance check; Heroic/Steam daemons can still swallow a CLI launch onto `:0`.
- **Gamescope live harness is not in CI** (no GPU on GitHub runners); add a `workflow_dispatch` job if a
  self-hosted GPU runner lands.
- **XI2 grab-state auto-switch for mouselook** — never started.

## Deliberately not planned

- No logging library (the user's call): `docs/refactor/40-run-trace.md` § *Why no logging library*.
- `GoogleAuth`/`GoogleConfig` stay in Studio; they share `credentials.json` with `GitHubAuth`, so the
  merge-don't-overwrite rule in `store` spans two repositories.
