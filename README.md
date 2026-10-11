# botmaker-shared

The **host platform layer** of BotMaker: the native work that the Studio host and every plugin it loads may
use — windows, input, screen capture, emulators, game launching, template matching, game VMs. The
[SDK](https://github.com/BotMakerDev/botmaker-sdk) is its main consumer, not its owner: a plugin that wants to
list windows reaches them here without depending on the SDK.

It has no JavaFX and no UI. Its API breaks freely between releases; the SDK's `api.*` is what a bot calls.

## Using it

```xml
<repositories>
    <repository>
        <id>jitpack</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.BotMakerDev</groupId>
    <artifactId>botmaker-shared</artifactId>
    <version><!-- latest tag: https://github.com/BotMakerDev/botmaker-shared/tags --></version>
</dependency>
```

It brings JNA (windows and input), OpenCV (`org.openpnp:opencv`, matching), dadb (a pure-JVM adb) and
Jackson.

## What it holds

| Package (`com.botmaker.shared.*`) | What it does |
|---|---|
| `capture` | `NativeController` — list, capture, focus, move and resize windows, and drive keyboard and mouse — with one implementation per OS (`windows/`, `linux/` over X11, `mac/`); `ScreenCapture` grabs the whole desktop, through a screenshot program (Spectacle, grim, gnome-screenshot) under Wayland |
| `input` | global key and mouse listeners (X11, macOS), which the stop key rides on |
| `emulator` | Android emulators and devices: BlueStacks, LDPlayer, MEmu, MuMu Player, Gameloop, Waydroid and a phone over adb — find instances, read their state, install and start apps |
| `device` | the scrcpy fast path to an Android screen and input |
| `game` | installed-game discovery: Steam, Epic, GOG, Heroic, Faugus, Lutris and desktop entries |
| `launch` | `LaunchSpec` and `Launcher` — start a Steam, Epic, Heroic, Faugus or Lutris game, an app, a command, an `.exe` or an emulator app, and tell whether it is running |
| `opencv` | template and colour matching; returns raw records (`RawMatch`, `RawColorMatch`) its consumer maps to its own types |
| `vm` | game VMs: set up and start a Windows or Linux guest in VMware Workstation or QEMU, or a guest on an Apple Silicon Mac through Tart |
| `vnc` | `VncController` — drive a VM's screen over VNC |
| `ipc` | the telemetry channel between a running bot and Studio (`TelemetryClient`, `TelemetryServer`) |
| `github` | `GitHubClient` and its sign-in: Studio's sync, publishing and gallery, the dashboard's releases |
| `config`, `tools`, `platform` | user and cache folders, downloaded tools, which OS and session this is |
| the root package | `Diag` (debug output), `Executables` (the external programs it looks for), `Spawn` (starting them) |

Private displays are not here: they are [`botmaker-session`](https://github.com/BotMakerDev/botmaker-session),
which depends on this module. OCR is in the SDK.

The design notes are in [`docs/architecture/`](docs/architecture): capture and the package map, matching,
emulators, the scrcpy device path and GitHub.

## Building

```bash
mvn test                              # here
mvn -pl botmaker-shared -am install   # from the umbrella root, at main's -SNAPSHOT
```

## Releasing

From the umbrella only, with `--shared <version>` plus the downstreams it forces (session, sdk, studio);
`/release-check` names them.
