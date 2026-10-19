# ROADMAP

Completed work up to 2026-10-05: see CHANGELOG.md, docs/refactor/, and `git show ed3d1aa:ROADMAP.md` (it
also holds `botmaker-session`'s history before 2026-07-30).

## Open

- **macOS: `MacController` has never run on a Mac** (written 2026-10-11 without one). Owed on a real Mac or a
  macOS VM: the Screen Recording and Accessibility prompts and their one-time hints; a window list with titles;
  a covered window's capture at 2× and 1×, and the frame lining up with the rect; background clicks
  (`CGEventPostToPid`) on a Cocoa app and on a game, and the ignored-click hint; take-over clicks, drags, wheel,
  keys with Shift held, `typeText` of non-ASCII; focus, move and resize through AX; a second display at another
  scale. `MacInputListener` likewise: the Input Monitoring prompt; keys under a non-US layout and with Shift;
  F12 seen with fn held; clicks and drags at the right pixels on a Retina display; the tap surviving a timeout
  (re-enabled); whether a background (`CGEventPostToPid`) key passes the session tap.
- **macOS: `CGWindowListCreateImage` is deprecated** (macOS 14) in favour of ScreenCaptureKit, which has only
  an Objective-C/Swift async API. When a macOS release stops answering it, capture needs an SCK bridge (a
  small helper binary, or the Objective-C runtime through JNA).
- **macOS: overlays.** `promoteOverlayAboveFullscreen` and `makeInputTransparent` keep their no-op defaults:
  an `NSWindow`'s level and `ignoresMouseEvents` need AppKit, on its main thread (`objc_msgSend` through JNA).
  Until then the SDK's run overlay can catch a take-over click aimed at the game beneath it.
- **macOS: Tart game VMs have never run** (umbrella doc 45 §3 lists the checks: the download, the clone, the
  VNC address and its auth, Metal frames over VNC, `tart exec`'s user, a guest restart).
- **macOS: Linux guests on Tart have never run** (doc 45 §3, the `Linux:` rows). The most likely gap: snapd
  may move Steam out of the display's `botmaker-game-N` unit into a scope of its own, and then a game stop
  misses it. Also: each display's window list is read by a `tart exec` of its own, about once a second (QEMU
  reuses a socket); with several bots, one long-lived reader per VM would be cheaper.
- **macOS guest: the published `admin`/`admin` password, with Remote Login on.** Its automatic sign-in stores
  the password, so it can't change as a Linux guest's does. Only the Mac and its VMs reach the guest.
- **macOS guest: what is still missing.** A window list, so `window("…")` finds more than the whole screen.
  Steam and Epic games' processes (`GuestGame.macScript` answers `UNKNOWN`). APFS growing into the disk
  `tart set --disk-size` enlarged. Auto-stop: Studio's watcher is PowerShell, and Tart reports no VNC clients.

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
- **Emulator discovery runs each product's console tool in turn** (`ldconsole list2`, `memuc listvms`,
  `MuMuManager info`), up to 10 s each when a product's service is cold, and `EmulatorInstances.byName` scans
  at bot runtime. About 1 s here; run them in parallel, or only when a picker needs the names, if it grows.
- **Windows launchers have never run on Windows** (2026-10-19, fixtures only): `WindowsApps` (`Get-StartApps`,
  `shell:AppsFolder`), `UbisoftLibraryScanner`, `BattleNetLibraryScanner` and `ItchLibraryScanner`. Owed: the lists
  on a real install, a Store app and a `uplay://`/`battlenet://` link starting their game, and the Start menu's
  icons. `BattleNetLibraryScanner.PRODUCTS` names 19 games; one it lacks is only in the Start menu. The EA app's
  library is encrypted, so its games come only through the Start menu, folded under "Other apps".
- **A Steam shortcut's running check is unverified**: the reaper's `AppId=` is taken to be the shortcut's 32-bit app
  id, and Steam's `RunningAppID` the same; neither was seen live.
- **Game readiness: what `GameReadiness` doesn't read** (2026-10-19). Epic on Windows and Mac, Lutris, Faugus,
  Bottles and Legendary in a game VM are `UNKNOWN` (launched as before); a game VM's Legendary install
  (`legendary install`, `legendary auth`'s browser code) isn't offered. Steam's signed-out test is a remembered
  account in `loginusers.vdf`, not seen after a real sign-out; an update Steam starts only when the game is
  launched (`UPDATE_PENDING`) runs inside the launch's window wait, which a long download outlasts.
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
