# The device fast path (`com.botmaker.shared.device`)

`emulator/` is the **floor**: a `screencap` per frame, and `input tap` — which is a shell script that execs
`app_process`, i.e. a JVM start on the device, per tap. No transport work reaches that; only a control socket
does. `device/` is that path. `ScrcpyDevice` is the facade and deliberately mirrors the `AdbDevice` verbs it
replaces (`grab`/`tap`/`swipe`/`key`), so a consumer holds one or the other behind its own interface and
falling back is a change of field. `ScrcpyChannel` pushes `scrcpy-server`, runs it under `app_process` and
opens the video + control sockets; `ScrcpyFrames` decodes through a piped `ffmpeg` keeping **one** picture
(the newest — a queued frame is by definition a stale one); `ScrcpyControl` is the pure message encoder.

Three things here are load-bearing and easy to undo by accident:

- **No `max_size`, ever.** `docs/display-pipeline.md` §3: framebuffer, stream and reference resolution are
  *one number*. A scaler between a bot's templates and the pixels it taps fails quietly — matching keeps
  succeeding while every tap lands wrong. `ScrcpyChannelTest` asserts the argument's absence.
- **The server is located or downloaded, never vendored** (`ScrcpyServer`, ≥ 2.1). A real scrcpy install is
  searched first and wins; `ensure()` — called only from `ScrcpyDevice.Builder.open()`, the capture path —
  falls back to fetching `tools/ManagedTools.SCRCPY_SERVER`, which is the **one automatic download** in the
  stack (a headless bot has no dialog to click). `available()` stays a pure probe and never downloads.
  **Version detection has one trap:** it is read from the installed *client* binary, so for our own managed
  file it is taken from the pin instead — a v4.1 file announced as some PATH client's version is a server that
  exits and a socket that never accepts. Missing `ffmpeg` ⇒ the `emulator/` floor, which needs neither.
- **Nothing here has spoken to a real server yet.** The tests pin the layout *we transcribed*, not the
  layout a device reads. A gesture landing wrong on hardware is a `ScrcpyControl` transcription bug first.
