# Changelog

What each released version of `botmaker-shared` changes, in a few bullets. `ROADMAP.md` stays the detailed
engineering log; this is the short answer, and it is what `release.sh` publishes as the GitHub Release body.

**`release.sh` refuses to cut a version with no section here** (`check_changelog`, decide pass, before
anything is tagged). If the top section still says `## [Unreleased]`, rename it to the version being cut and
date it.

No bot ever names shared directly — it is a transitive dependency of the SDK — so this file is written for
whoever is debugging a capture, a launch or an OCR result, not for a bot author.

Sections are `## [x.y.z] — YYYY-MM-DD`, newest first.

## [Unreleased]

### Added

- **A game of this PC copied into a game VM.** `vm.GameCopy.onThisPc()` lists the Steam and Epic games here;
  `GameCopy.copy` has a QEMU guest fetch one's folder from a loopback `FolderServer` (token, read-only, at
  `10.0.2.2`) into the folder its launcher installs to, skipping files already there whole, then writes the
  launcher's record (Steam's `.acf`; Epic's `.item` and `LauncherInstalled.dat` entry) so it checks rather
  than downloads. No account or share on this PC. Measured: Firestone, 754 MB, in 47 s. VMware: not yet.
  `GuestAgent.readFile`; `SteamLibraryScanner.manifestOf`, `EpicLibraryScanner.manifestOf`.
- **A VM's screen as a `NativeController`.** `vnc.VncController` connects to the VNC server a hypervisor
  serves for a virtual machine on this computer. It captures the screen and sends clicks, drags, the wheel,
  keys and text as VNC messages, so this computer's cursor and keyboard are never touched. The protocol is
  Vernacular's (`com.shinyhut:vernacular` 1.14, MIT, no dependencies of its own). `Keysyms` turns the Windows
  virtual-key codes a bot passes into the X keysyms VNC sends. Once the server is gone, it degrades rather
  than throws.

- **The parts of a game VM, for VMware Workstation and QEMU** (`vm`). `Hypervisor.detect()` picks VMware
  when it is installed, else QEMU. `VmwareWorkstation` drives `vmrun` and `vmware-vdiskmanager`. `Qemu`
  installs from winget and builds a headless command line: the Windows Hypervisor Platform, UEFI, NVMe, a USB
  tablet, and VNC and QMP on loopback. `QmpClient` speaks QMP. `VmxFile` edits a `.vmx` in place, in its own
  encoding. `GuestUnattend` writes the `autounattend.xml` that installs Windows 11 with nobody at the keyboard:
  - it skips the TPM and Secure Boot checks;
  - it creates a local account that signs in automatically;
  - at first sign-in it installs the guest tools and creates a launch task.

  `IsoImage` writes the ISO 9660 + Joliet disc that carries that file. `VmCredentials` keeps the guest and
  VNC passwords encrypted with DPAPI.

- **Setting a game VM up, end to end** (`vm.VmSetup`). `problems()` names what stops it: no hypervisor, the
  Windows Hypervisor Platform off for QEMU (`enableHypervisorPlatform()` turns it on), too little disk or
  memory, no Windows disc. `prepare()` makes the VM's folder under `UserDirs.config()/vm`:
  - its passwords;
  - its answer disc, in the Windows disc's own language, carrying virtio-win's guest tools for QEMU;
  - its disk and configuration.

  `install()` starts the VM, presses the key the Windows disc waits for, and follows Setup over VNC until the
  guest says the first sign-in is done. It resumes after a restart of the host, and ejects and deletes the
  answer disc at the end. QEMU runs with `-no-reboot` on the Hypervisor Platform's own interrupt controller:
  a restart inside it hangs the firmware or stops the processor, so `install()` starts QEMU again each time
  Windows restarts, and when QEMU pauses the VM (umbrella doc 44 §4b.2.1). A VM's disk is 64 GB by default,
  Windows 11's minimum, and takes only what Windows writes.
  `start()` starts a VM and connects to its screen, choosing new ports for any that were taken since.
  `VmRecord` keeps each VM's record and `VmInventory` lists them (`find(name)` reads one). `GuestAgent` reads,
  writes and runs inside a QEMU guest. `guestReady()` says whether a guest has signed in and its tools
  answer, and `runOnDesktop()` runs a command on the guest's desktop: through the guest agent and the launch
  task under QEMU, through `vmrun -interactive` under VMware. `GuestLaunch` turns a launch target into that
  command: a path, a command line, Steam or Epic, each handed off with `start ""`.
- **A game VM's store launchers** (`vm.GuestLauncher`, Steam and Epic). `VmSetup.guestHas()` says whether the
  guest has one, and `installInGuest()` downloads its installer in the guest and runs it silently, waiting for
  it (both installed on the `live` VM; Steam's bootstrapper took 5 s). Epic's installer of
  2026-10 puts it under `Program Files`, older ones under `Program Files (x86)`: both are looked for.
  `GuestAgent.run()` waits for a program and hands back its exit code and output.
- **Shutting a game VM down, and knowing why one stopped.** `VmSetup.shutDown()` presses the VM's power
  button (QEMU's `system_powerdown`, VMware's `stop soft`), waits for Windows, and powers it off after three
  minutes. A QEMU VM has a second QMP port (`VmRecord.eventsPort`, given to an older record at its next
  start): `QmpEvents` listens there and says why QEMU ended (a Windows restart, a shutdown, ended from
  outside). `VmAutoStop` keeps a VM's own settings in its folder: shut down when Studio closes, which a
  PowerShell process watching Studio's process does even after a crash, and after some minutes with no VNC
  client (`QmpClient.vncClients()`, QEMU only).

- **Installing a game on an emulator, and making a new instance.** `PlayStoreSearch` finds an app on Google
  Play by name from this computer. `EmulatorInstall` starts the instance if needed, then either opens the app's
  Google Play page there and waits for the install, or installs an `.apk`, `.xapk` or `.apks` file (splits
  and OBB data included, `ApkFile`). `Platforms.newInstances()` lists how each installed product adds an
  instance: the LDPlayer, MEmu and MuMu consoles create one; BlueStacks and GameLoop open their own manager.
  `EmulatorReadiness.bringUp` is the start-and-wait step that installing and app launches now share.

- **Phones found on the network, and QR pairing.** `AdbTools.mdnsServices()` reads what a running adb server
  has heard announced (`host:mdns:services`, over its socket, so it starts nothing), and `unconnected` keeps the
  phones announcing a debugging port that adb isn't connected to. `AdbTools.pairByQr` pairs the phone that
  scans a `QrPairing` code: it waits for the phone to announce the code's pairing port, runs `adb pair`, then
  connects to the port the phone announces next.

- **`WindowsLiveInputTest`: the Windows input and capture paths against a real window.** Opt-in
  (`-Dbotmaker.live=true`, Windows only), since the take-over part moves the real cursor and types. It uses a
  bare Win32 stand-in with a title bar that records what its window procedure receives. It checks every
  button, a drag, the wheel, keys, Alt+Enter and text in the background, and that the cursor never moves there.
  It checks the click through a covering window, the capture's size, and that no title bar or covering window
  ends up in it. It checks the ignored-click warning (once for a window that never repaints, never for one
  that does), WGC on a covered window and across a resize, and take-over clicks, drags, the wheel, relative
  moves, AltGr `@` and a character with no key on the layout. It also lists the installed launchers' games. A
  fullscreen stand-in checks the screen-sized capture and a click through a click-through overlay box, and
  that an overlay excluded from capture (`WDA_EXCLUDEFROMCAPTURE`) stays out of the game's frame.

- **Lutris and the app menu are launch targets.**
  - `lutris:<id>` starts a Lutris game the way its own shortcut does (`lutris lutris:rungameid/<id>`, then the
    Flatpak). `LutrisLibrary` lists the installed games through `lutris -l -o -j`, cached for a minute, and a
    running one is found by the title its `lutris-wrapper` carries.
  - `desktop:<id>` starts a menu entry with `gtk-launch`. `DesktopEntries` reads the XDG `applications/` folders
    the way a menu does. It recognises the shortcuts Steam, Faugus, Lutris, Heroic and Waydroid write for their
    games, so each game is that launcher's target, not a second entry.
- **More game libraries:** `LutrisLibraryScanner`, `DesktopEntryScanner` and, on Windows, `GogLibraryScanner`,
  which reads GOG's registry key and lists each game as an `exe:` target.
- `FaugusEntries` adds a Windows program to Faugus Launcher's `games.json` with Faugus's own default prefix and
  runner, and gives the Flathub command that installs Faugus.
- **Windows: every gesture runs in the background.** Right and middle clicks, the side buttons, moves, drags,
  the wheel and keys are now posted to the game window, the way left clicks already were. The cursor and the
  keyboard stay the user's. Before, all of them moved the real pointer or pressed the real keys. A key carries its
  scan code, and Alt, F10 and Alt-held keys go as `WM_SYSKEY*`. A click on a window reaches it even when it is
  covered.
- **Windows: background clicks that change nothing are reported.** The first three background clicks of a run
  are checked against the window before and after. If none changed it, the run warns once and names "Take over
  the mouse and keyboard" (`IgnoredClickWatch`). `Diag.warn` prints such a line whether debug output is on or off.
- **Windows: Windows.Graphics.Capture, opt-in.** `-Dbotmaker.windows.capture=wgc` reads a window through the
  compositor, which covers a DirectX game and a covered window. It has not run on Windows yet, so it is off by
  default.

### Fixed

- **An emulator app has its name, not its package.** `AdbDevice.appLabel` reads `<application android:label>` out
  of the installed APK's binary manifest and follows it into `resources.arsc` (default language, then English),
  over the same ranged reads as the icon (`ApkZip`), so "Clash of Clans" instead of `com.supercell.clashofclans`.
  `EmulatorProbe` lists apps with their names, reading each once and remembering it, and `EmulatorProbe.refresh`
  keeps an instance's apps, names and icons in `EmulatorAppCache` (`iconPath` gives the picture as a file).
  A BlueStacks or GameLoop with no engine process running anywhere is stopped, so MuMu, which also answers on
  5555, no longer passes for it.

- **Emulator discovery finds current LDPlayer, MEmu, MuMu, MSI App Player and GameLoop installs.** Each was
  looked for under a registry key its current version no longer writes; a product is now also found by its
  *Apps & features* entry and default folder, and every BlueStacks edition is read. Instances carry the names
  the product's own console shows (`ldconsole list2`, `memuc listvms`, `MuMuManager info`), MuMu's port is the
  one its config forwards, and two products asking for the same port are both listed instead of one hiding the
  other. A GameLoop with no Android engine yet says so. Registry reads no longer spawn `reg.exe`.

- **An emulator is running when its product says so, not when its port answers.** BlueStacks, LDPlayer's
  first instance and GameLoop share `127.0.0.1:5555`, so one running made all of them look running, and a launch
  aimed at a stopped LDPlayer drove BlueStacks. Each instance now carries the `EmulatorState` its console tool
  or engine process reports, and `EmulatorLiveness` combines it with the port: a stopped instance whose port
  answers names who holds it ("port 5555 is in use by BlueStacks: Pie64"), two products both up on one address
  are both refused, and a product up with its port
  closed has its ADB off and says where to turn it on (LDPlayer 14 ships that way). `EmulatorProbe.isRunning`,
  `EmulatorReadiness.isReady` and `EmulatorAppLauncher` use it.

- **Game covers: Steam's are found again, and an Epic game has one.** A newer Steam client keeps each library
  picture one folder down, under a content hash (`librarycache/<appid>/<hash>/library_capsule.jpg`), so
  `SteamLibraryScanner` found no cover for a game it had cached that way; both levels are looked at now, the
  portrait capsule first. Epic keeps no cover on disk, so an Epic game's `artwork()` is its program's own icon,
  read from the program's icon resource at 256 px into the cache's `game-icons/` (Windows only). A game Epic
  launches through its online-services bootstrapper, whose icon is Epic's logo on every game, gets the icon of
  its own program beside it.
- **Windows: an arrow key is an arrow, not number-pad 4.** Windows 11 maps `VK_LEFT` to a bare `0x4B`, with
  no extended prefix. So a background arrow arrived without the extended bit, and a take-over arrow arrived as
  `VK_NUMPAD4`, typing a `4`. Every key with an `E0` scan code is now always sent extended, on both paths:
  arrows, Insert, Delete, Home, End, Page Up/Down, Win, the menu key, and the media keys
  (`WindowMessages.ScanCode.of(vk, vscEx)`).

- **Windows: clicks on a windowed game no longer land a title bar too high.** A window's rect was its outer
  rect, while the capture was its client area. `PrintWindow` also drew the title bar into the client-sized frame.
  The rect is now the client area, `PrintWindow` draws the client area only, and `moveWindow`/`resizeWindow` place
  and size the client area.
- **Windows: scaled screens.** Every coordinate call runs per-monitor DPI aware, and the process asks for
  per-monitor v2 at start-up, so the window rect, the capture and the click agree at 125% and 150%. A screen copy
  is taken in device pixels.
- **Windows: a covered window is never captured as the window on top of it.** A screen copy is taken only when
  the window is on top at its own rect. A black frame is detected on a fixed grid, not 10 random pixels.
- **Windows: take-over input uses `SendInput` with scan codes**, extended keys included, in place of
  `mouse_event`/`keybd_event`. Before, the arrow keys arrived as the numeric keypad's. A character with no key on
  the layout is typed as Unicode, and one behind AltGr (an AZERTY `@`) gets its Ctrl+Alt. Relative motion, for mouselook, is a real relative event.

- **A scaled desktop is read in device pixels.** Under `GDK_SCALE=2` (KDE's 200% on X11), AWT reported a
  1920×1080 screen as 960×540 and Robot grabbed it at that size, while XTEST clicks in 1920×1080, so a match was
  clicked at half its distance from the corner. `ScreenCapture` (`getVirtualScreenBounds`, `monitorBounds`,
  `screens`) and `RobotCapture` now answer in device pixels, through the new `ScreenGeometry`, which multiplies
  each screen's logical rectangle by that screen's own scale. `RobotCapture.capture(robot, rect)` grabs any
  device rectangle at full resolution.
- **The window capture's composite rung works.** It reads a pixmap, whose image Xlib returns with every colour
  mask 0, and decoding masked with 0 gave pure black. So every capture fell through to the rungs that miss covered
  pixels, and inside gamescope the frame was black.
- **Clicks inside gamescope land on the pixel.** The focus-relative warp correction now uses the focused window's
  top-level. Before, it used the input-focus window itself, and AWT keeps focus on a 1×1 child at (-1,-1), so
  every click on such a game landed 1 px right and down.

### Changed

- `launch.LaunchIsolation` and `capture.GamescopeHost` moved to botmaker-session (`session.launch`,
  `session.display`): the isolation policy is the session module's, and nothing here used either.
- Steam's own tools are no longer listed as games: Proton, the Steam Linux Runtimes and the Steamworks
  redistributables (`SteamLibraryScanner.isTool`).

- The pom carries a real version, `-SNAPSHOT` on `main` and the release version on a tag, instead of the
  cosmetic `0.0.0-SNAPSHOT` (umbrella `docs/refactor/43-real-versions.md`).
- Published as `com.github.BotMakerDev:botmaker-shared` (was `com.github.LiQiyeDev`). Tags already built
  under the old groupId still resolve under it.

### Added

- **Click-through windows.** `NativeController.makeInputTransparent(title)` gives a shown window an empty
  input region (X11 Shape extension, libXext), so every click, the user's and the bot's own, reaches the
  window beneath; it answers false where it cannot (Windows, Wayland, no libXext). For an overlay drawn over a
  running bot.
- **Where the bot is in its program.** `TelemetryEvent.Step(activity, action, line)`, tag 8. An older host
  skips the new tag, as it does any tag it does not know.

## [0.1.2] — 2026-10-01

### Added

- **The telemetry channel works both ways.** A bot sends a question (`TelemetryEvent.Ask`, tag 6) with
  `TelemetryClient.ask`, and the host answers it with `TelemetryServer.reply` (`TelemetryEvent.Answer`, tag 7)
  on the same socket. A question still open when the connection drops fails. `TelemetryFrame.ask(frame)`
  reads a question out of a relayed frame. An older host skips the new tags, as it does any tag it does not
  know.

### Changed

- **`Diag.error` prints and traces whatever the debug switch says.** The switch governs debug lines only, so a
  run with debugging off still shows its crashes.
- **A repeated line prints its count once**: `Diag` appends `(×N)` to the console line, so a caller no longer
  writes the count into its text, where the trace showed it a second time.

## [0.1.1] — 2026-09-29

### Added

- **`EmulatorSurface.key(int)` and `text(String)`**, so the remote pilot's keyboard reaches the emulator
  route: `input keyevent` and `input text` over the held ADB connection (the scrcpy surface uses its ADB
  floor for both). Both default to doing nothing, so a test double needs no code.

### Fixed

- **`AdbDevice.text` single-quotes what it types.** The text went into the device's shell bare, with only
  spaces escaped, so `&`, `;`, a quote or `$(…)` broke the command or ran what followed. Non-ASCII and
  control characters, which `input text` cannot type, are dropped.

## [0.1.0] — 2026-09-29

### Added

- **`Diag.Callers`**, the one walk of the stack for "who called us": `first(skip)` returns the first frame the
  caller's own plumbing does not account for, and `method(name)` names a lambda after the method it was
  written in. `Diag` finds a line's writer with it, and the SDK's trace sources and telemetry use it too. A
  line written from a lambda is now attributed to its method (`body`, not `lambda$body$0`) for every caller.
- **`GitHubClient.getOrFail`**: a GET that fails its future with a `GitHubError` on anything but 200. The error
  carries the status, GitHub's own message and `X-RateLimit-Remaining`, and says plainly when the rate limit is
  used up. `get` is unchanged and still answers `null`.
- **Conditional GETs.** `GitHubClient.get` and `getOrFail` remember each 200's `ETag` in memory and send
  `If-None-Match` next time. An unchanged resource comes back as a 304, which does not spend the rate limit,
  and is read as the body it stands for.
- **`GitHubAuth.pollForToken(code, cancelled)`** stops the device-flow poll when asked. Cancelling the future did
  not stop the loop, so a closed sign-in dialog kept polling and could still store a token.
- **Debug lines cross the telemetry wire.** `TelemetryEvent.Log` (frame tag 5) carries a line's level, source,
  text, repeat count, time, desktop rectangle, the class and method that wrote it, and the bot's class and line
  it was written for. An older reader skips the frame and keeps reading.
- **`Diag.Origin(source, className, method)`** and `Diag.log(Origin, …)`/`Diag.error(Origin, …)`: a caller that
  knows where a line was written passes it. Otherwise the writer is the first caller outside `Diag`, looked up
  only while a trace sink is set.
- **Frames can be relayed without being decoded.** `TelemetryFrame.readFrame` reads one whole frame as bytes,
  `decode` reads those bytes back, and `isLog`/`log` pick out a debug line without decoding anything else.
  `TelemetryServer.relaying(token, onFrame, onError)` hands each frame on as bytes, for a host that passes
  frames to plugins without reading them.
- **`Diag.setSink`**: each diagnostic printed while debugging is on also goes to the sink as a `Log`, with its
  source taken from the leading `[Name]`. The printed output is unchanged.
- **`Diag.log(source, message, count, where)` and `Diag.error(source, message[, t])`** print and trace a line
  under a source the caller names. A message's own leading `[Name]` still wins.
- **`-Dbotmaker.debug=true|false`** (`Diag.RUN_PROPERTY`) sets whether diagnostics start on; `Diag.runOverride()`
  reads it.

### Changed

- **`TelemetryFrame`'s javadoc no longer says shared has no Jackson.** It does, for the GitHub client; the
  frames are binary because every published SDK writes them and a host must keep reading them.
- **Desktop capture under Wayland uses grim (Sway, Hyprland) or gnome-screenshot (GNOME) when Spectacle is not
  installed**, instead of falling back to Robot, which returns black or asks the portal on every grab.
  `SpectacleCapture` is replaced by `ToolCapture`, one constant per program.

### Removed

- **`config.ProjectProperties`, `config.ProjectFile` and `config.CaptureSourceKind`.** A bot has no
  `botmaker-project.properties` any more: its tuning is the SDK's `@Managed("settings")` value in its own
  Java, and what it launches on this machine arrives as the `botmaker.launch.target` system property.

## [0.0.28] — 2026-09-27

### Changed

- **uinput presses every key the SDK's `Key` names.** Punctuation, Home/End/Page Up/Page Down/Insert, Caps
  and Num Lock and the numpad were dropped silently under real input on Linux; each now has its evdev code.
  Keypad Enter is `KEY_KPENTER` rather than the main Enter.

### Fixed

- **uinput follows the keyboard layout.** It emitted the US board's positions, so on AZERTY `a` typed `q`
  (and `z`/`w`, `m` and punctuation landed on the wrong keys) while XTest and Windows typed what was asked.
  The virtual device now reads the X keyboard mapping once when it is created and sends each key where the
  layout puts it, falling back to the US position for a key the layout lacks. A layout switched mid-run is not
  followed; as with XTest, only the key is pressed — a character on a shifted level (AZERTY's digits) gets no
  Shift added.

### Added

- **`ColorMatcher.matchMask(image, target, tolerance)`** — the per-pixel ΔE mask `findClusters` labels,
  row-major, so an editor (the SDK's Precision overlay) draws exactly what a search sees rather than a second
  threshold of its own.

- **`ProjectFile.set(resourcesDir, key, value)`** — one key written into
  `botmaker-project.properties`, load-modify-store, `false` rather than a throw when it cannot be written.
  This class was deliberately read-only, deferring every write to the schema ledger that owned the file
  being written beside; that ledger (`Authoring`, `capture.json`) is deleted, so what the rule protected no
  longer exists and the one caller left — the SDK's emulator picker naming the instance it just chose — had
  nowhere to write. A blank or null value removes the key rather than storing an empty one, so *unset* and
  *set to nothing* stay the same state they have always been for every reader here.

### Removed

- **The project-wide capture resolution, both halves of it.** `ProjectProperties.KEY_CAPTURE_WIDTH`,
  `KEY_CAPTURE_HEIGHT` and `defaultResolution()`, `ProjectFile.captureSize`, and `ResolutionScaler`'s
  fallback onto them. **Nothing in any module ever wrote either key**, so every reader took its own default
  on every call and always had: `primaryScale` returned `1.0`, and a background session started at
  `BackgroundLauncher`'s own size. The authoring half of the same fact — `capture.json`'s `reference` — was
  written by nothing either, so both halves went together rather than one of them acquiring a writer to
  justify the other. A template's own `captureWidth`/`captureHeight`, recorded beside the picture by the
  capture that made it, **is** written and is untouched: it is the `authored` argument `OpencvManager`
  already passes, and the only resolution scaling that has ever happened.

No source changes since v0.0.26; re-released for updated upstream pins.

No source changes since v0.0.25; re-released for updated upstream pins.

### Changed

- **Every repository is `BotMakerDev`'s now.** `GitHubConfig`'s gallery, registry, Studio, CLI and issue
  owners name the organization the repositories moved to on 2026-09-18. `STUDIO_REPO` is spelled
  `botmaker-studio`, the repository's real name. The raw index URLs still fall back to the old owner,
  now `PREVIOUS_OWNER`, which is `NEXT_OWNER`'s replacement.

### Added

- **`GitHubConfig.MAINTAINER`**, the maintainer's GitHub login, for the two places that ask "is this the
  maintainer?". They compared a login with the repository owner, and since an owner can now be an
  organization, no login would ever match.

## [0.0.27] — 2026-09-23

### Added

- **`ProjectFile.set(resourcesDir, key, value)`** — one key written into
  `botmaker-project.properties`, load-modify-store, `false` rather than a throw when it cannot be written.
  This class was deliberately read-only, deferring every write to the schema ledger that owned the file
  being written beside; that ledger (`Authoring`, `capture.json`) is deleted, so what the rule protected no
  longer exists and the one caller left — the SDK's emulator picker naming the instance it just chose — had
  nowhere to write. A blank or null value removes the key rather than storing an empty one, so *unset* and
  *set to nothing* stay the same state they have always been for every reader here.

### Removed

- **The project-wide capture resolution, both halves of it.** `ProjectProperties.KEY_CAPTURE_WIDTH`,
  `KEY_CAPTURE_HEIGHT` and `defaultResolution()`, `ProjectFile.captureSize`, and `ResolutionScaler`'s
  fallback onto them. **Nothing in any module ever wrote either key**, so every reader took its own default
  on every call and always had: `primaryScale` returned `1.0`, and a background session started at
  `BackgroundLauncher`'s own size. The authoring half of the same fact — `capture.json`'s `reference` — was
  written by nothing either, so both halves went together rather than one of them acquiring a writer to
  justify the other. A template's own `captureWidth`/`captureHeight`, recorded beside the picture by the
  capture that made it, **is** written and is untouched: it is the `authored` argument `OpencvManager`
  already passes, and the only resolution scaling that has ever happened.

No source changes since v0.0.26; re-released for updated upstream pins.

No source changes since v0.0.25; re-released for updated upstream pins.

### Changed

- **Every repository is `BotMakerDev`'s now.** `GitHubConfig`'s gallery, registry, Studio, CLI and issue
  owners name the organization the repositories moved to on 2026-09-18. `STUDIO_REPO` is spelled
  `botmaker-studio`, the repository's real name. The raw index URLs still fall back to the old owner,
  now `PREVIOUS_OWNER`, which is `NEXT_OWNER`'s replacement.

### Added

- **`GitHubConfig.MAINTAINER`**, the maintainer's GitHub login, for the two places that ask "is this the
  maintainer?". They compared a login with the repository owner, and since an owner can now be an
  organization, no login would ever match.

## [0.0.26] — 2026-09-21

No source changes since v0.0.25; re-released for updated upstream pins.

### Changed

- **Every repository is `BotMakerDev`'s now.** `GitHubConfig`'s gallery, registry, Studio, CLI and issue
  owners name the organization the repositories moved to on 2026-09-18. `STUDIO_REPO` is spelled
  `botmaker-studio`, the repository's real name. The raw index URLs still fall back to the old owner,
  now `PREVIOUS_OWNER`, which is `NEXT_OWNER`'s replacement.

### Added

- **`GitHubConfig.MAINTAINER`**, the maintainer's GitHub login, for the two places that ask "is this the
  maintainer?". They compared a login with the repository owner, and since an owner can now be an
  organization, no login would ever match.

## [0.0.25] — 2026-09-19

### Changed

- **Every repository is `BotMakerDev`'s now.** `GitHubConfig`'s gallery, registry, Studio, CLI and issue
  owners name the organization the repositories moved to on 2026-09-18. `STUDIO_REPO` is spelled
  `botmaker-studio`, the repository's real name. The raw index URLs still fall back to the old owner,
  now `PREVIOUS_OWNER`, which is `NEXT_OWNER`'s replacement.

### Added

- **`GitHubConfig.MAINTAINER`**, the maintainer's GitHub login, for the two places that ask "is this the
  maintainer?". They compared a login with the repository owner, and since an owner can now be an
  organization, no login would ever match.

## [0.0.24] — 2026-09-18

### Changed

- **The gallery and plugin-registry indexes are read from `BotMakerDev` first, then `LiQiyeDev`.**
  `GitHubConfig.catalogRawUrls()`, `indexRawUrls()` and `registryIndexRawUrls()` return both in that order,
  and `GitHubClient.getFirstString` takes the first that answers. This is the first half of moving the
  repositories into the organization; nothing has moved yet, so today the first URL answers 404 and the
  second serves. The single-URL methods are gone.

## [0.0.23] — 2026-09-18

No source changes since v0.0.22; re-released for updated upstream pins.

### Added

- **The gallery's tiered catalog.** `GitHubConfig.CATALOG_PATH` and `catalogRawUrl()` name `catalog.json`,
  which lists every bot with its tier (Vetted or Community). `VETTED_DIR` and `vettedPath(owner, repo)` name
  the maintainer-only records saying which release of a bot is vetted. `indexRawUrl()` is unchanged: that
  file now holds Vetted bots only, for the Studios already installed.

## [0.0.22] — 2026-09-17

### Added

- **The gallery's tiered catalog.** `GitHubConfig.CATALOG_PATH` and `catalogRawUrl()` name `catalog.json`,
  which lists every bot with its tier (Vetted or Community). `VETTED_DIR` and `vettedPath(owner, repo)` name
  the maintainer-only records saying which release of a bot is vetted. `indexRawUrl()` is unchanged: that
  file now holds Vetted bots only, for the Studios already installed.

## [0.0.21] — 2026-09-16

### Added

- **`com.botmaker.shared.github` — the GitHub layer.** `GitHubClient` (async REST over the JDK `HttpClient`),
  `GitHubAuth` (the OAuth device flow, with the token stored `0600` under the cache dir), `GitHubConfig` (the
  gallery / plugin-registry / Studio / CLI repository names and the raw-CDN URLs) and `SemVer`. Moved
  verbatim from `botmaker-studio`, which is no longer the only operator of those repositories — the coming
  `botmaker-dashboard` reads the same registry and the same pull requests, and a device flow with token
  storage is not code that may exist twice.

## [0.0.20] — 2026-09-02

- **Compiled for Java 25 (LTS).** Every consumer — the SDK, session, Studio and any plugin depending on this
  module directly — needs a 25 runtime. Nothing about the native plumbing changed.

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
