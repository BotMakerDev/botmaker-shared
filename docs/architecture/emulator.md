# Android emulator (`com.botmaker.shared.emulator`)

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
`internal.emulator.Emulator` wraps it as a `CaptureSource` at runtime (a bot writes `CaptureSource.emulator(name)`), and a Studio capture picker can screen-grab an
emulator at edit time. `AdbDevice` is one dadb connection (`dev.mobile:dadb` — pure-JVM ADB, no `adb.exe`; `screencap()` plus
`tap`/`swipe`/`key`/`text`/`startApp`/`shell`). Capture has **two** paths and picks between them by
`AdbEndpoint.local()`: raw `exec:screencap` (no device-side encode, decoded by `RawFramebuffer`) on loopback,
`exec:screencap -p` everywhere else — raw skips the PNG encode but moves ~10 MB, which is a win on loopback
and a loss over a cable or a radio. Both are lossless, so it is a latency choice only. `shell()` runs through
one `sh` held open across calls (`AdbShellSession`, marker-framed) rather than forking one per command. Note the
Kotlin package is `dadb.*`, not the `dev.mobile` groupId, and dadb self-manages the RSA key (`~/.android/adbkey`).
Discovery (`Platforms.discoverAll()`) reads each product's local config/registry → `EmulatorInstance`s (name +
ADB port). A product's folder comes from `InstallLocator`: its own registry key, then its *Apps & features*
uninstall entry (current LDPlayer, MEmu and MuMu write no other key), then its default folder; registry reads go
through JNA, not `reg.exe`. `BlueStacksPlatform` reads every `HKLM\SOFTWARE\BlueStacks_*` edition's
`bluestacks.conf`, `LdPlayerPlatform` every version's `leidian<i>.config` (port 5555+2·i), `MemuPlatform` the
VirtualBox `.memu` NAT forwarding rule (host port of guest 5555) and `MuMuPlatform` `vms\MuMuPlayer*-<v>-<i>`
(the port `configs\vm_config.json` forwards, else 16384+32·i). Names come from each product's console
(`ldconsole list2`, `memuc listvms`, `MuMuManager info -v all`) when it answers, and from its last answer
(`UserDirs.cache()/emulator-names`) when it doesn't, so a saved name doesn't flip with a slow tool. `GameloopPlatform` returns its
single primary instance on port 5555 once its engine is downloaded, and a status note until then. Several
products ask for 5555; `Platforms.dedupe` keeps them all and only drops a phone a product already reported.
Beyond discovery, each
`EmulatorInstance` also carries the host `launchCommand`/`stopCommand` its platform resolved (LDPlayer
`ldconsole`, MuMu `MuMuManager`, MEmu `memuc`, BlueStacks `HD-Player --instance`, Gameloop engine exe), which
`EmulatorLauncher` spawns to start/stop an instance the ADB transport can't reach until it's up. `AdbDevice`
also does app queries (`installedApps`/`isInstalled`/`currentApp`). Windows-first, best-effort, never throws.
dadb pulls kotlin-stdlib, which now rides into every consumer (Studio included) — the accepted cost of
shipping no adb binary.
