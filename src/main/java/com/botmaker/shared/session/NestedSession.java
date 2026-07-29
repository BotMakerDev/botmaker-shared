package com.botmaker.shared.session;

import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.capture.linux.LinuxController;
import com.botmaker.shared.capture.linux.X11;
import com.botmaker.shared.capture.linux.X11Utils;
import com.botmaker.shared.launch.GameLauncher;
import com.botmaker.shared.launch.HostLauncherProbe;
import com.botmaker.shared.launch.LaunchCommands;
import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.shared.launch.Launcher;
import com.sun.jna.Pointer;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link DesktopSession} over a private nested display the bot owns — the piece that makes background input
 * <em>flawless</em>. Because the game runs in its own Xephyr {@code :N}, that display's global pointer and
 * keyboard focus are the bot's alone: the same device-level XTest injection that would hijack the real cursor
 * on {@code :0} is, on {@code :N}, both accepted by the game <em>and</em> invisible to the user driving their
 * real desktop. That is why — unlike {@link HostSession} — a nested session honestly advertises
 * {@link Capability#BACKGROUND_CLICK}, {@link Capability#ISOLATED_FOCUS} and {@link Capability#MULTI_SESSION}.
 *
 * <p>A nested session <b>launches</b> its target (it cannot attach across servers — X11 has no window
 * migration), stopping any instance already running on {@code :0} first. Bring one up with {@link #start},
 * then {@link #launch(LaunchSpec)} the game into it. Everything it spawns — the X server, an optional window
 * manager, the game — lives in one {@link SessionReaper} group, so {@link #close()} reaps the whole tree.
 *
 * <p>Two display backends sit behind one {@link SessionDisplay} seam, chosen by {@link Options}: {@link
 * NestedDisplay} (Xephyr, 2D — no {@link Capability#HARDWARE_GL}/{@link Capability#VULKAN}) and {@link
 * GamescopeDisplay} (gamescope, hardware 3D — adds both). The supervisor here is identical for both. Launch
 * covers every kind with a <em>child-launchable</em> command (via {@link
 * com.botmaker.shared.launch.LaunchCommands}): {@code exe:}/{@code cli:} directly, and the store launchers
 * that expose a CLI form ({@code heroic:}/{@code steam:}/{@code faugus:}) run as our own child so they inherit
 * {@code DISPLAY=:N} instead of the daemon-routed protocol URL, which a launcher already on {@code :0} would
 * swallow. Kinds with no CLI form — {@code epic:} (URL-only) and {@code emu-app:} (ADB) — cannot map onto a
 * private display and are refused (a loud failure, never a silent {@code :0} fallback).
 */
public final class NestedSession implements DesktopSession {

	/** Monotonic per-JVM counter so concurrent sessions get distinct reap-group ids (display numbers come from Xephyr). */
	private static final AtomicInteger SEQ = new AtomicInteger();

	/** How long to wait for a launched game's window to appear on the nested display before giving up the attach. */
	static final long WINDOW_TIMEOUT_MS = 20_000;
	/**
	 * The same budget for a <em>store launcher</em> kind, where the window we're waiting for is the game's and
	 * not the process we spawned. Heroic/Steam boot their own runtime, then a Proton prefix, then (first run)
	 * download winetricks/umu before the game ever maps — minutes, not seconds. Timing those out at the
	 * {@link #WINDOW_TIMEOUT_MS} budget meant reaping a launcher that was still working, which is how a
	 * perfectly healthy Heroic ended up producing a SIGTRAP coredump.
	 */
	static final long LAUNCHER_WINDOW_TIMEOUT_MS = 120_000;
	/** How long to wait for an optional window manager to claim the display; a WM-less session proceeds anyway. */
	private static final long WM_TIMEOUT_MS = 5_000;
	private static final long POLL_MS = 150;

	private final String id;
	private final SessionReaper reaper;
	private final SessionDisplay display;
	private final LinuxController controller;
	/** A second connection to {@code :N} for EWMH reads (pid/geometry), separate from the controller's own. */
	private final Pointer ewmhDisplay;
	private final ControllerPointer pointer;
	private final ControllerKeyboard keyboard;
	private final Options options;

	private volatile GenericWindow attached;
	private volatile Process gameProc;
	private volatile boolean closed;

	private NestedSession(String id, SessionReaper reaper, SessionDisplay display,
						  LinuxController controller, Pointer ewmhDisplay, Options options) {
		this.id = id;
		this.reaper = reaper;
		this.display = display;
		this.controller = controller;
		this.ewmhDisplay = ewmhDisplay;
		this.options = options;
		this.pointer = new ControllerPointer(controller);
		this.keyboard = new ControllerKeyboard(controller, this::attached);
	}

	/**
	 * Bring up a nested display (and its optional window manager), ready for a game to be {@link #launch}ed into
	 * it. On any failure the partially-started tree is reaped before the exception propagates, so a caller can
	 * cleanly fall back to a {@link HostSession}.
	 */
	public static NestedSession start(Options options) throws SessionStartException {
		// Sweep any trees left by a previously-SIGKILLed JVM before starting a fresh one (systemd strategy only).
		reapOrphanSessions();
		// Id shape s<pid>-<seq> is a contract: the orphan sweep parses the owner pid back out of the slice name.
		String id = "s" + ProcessHandle.current().pid() + "-" + SEQ.incrementAndGet();
		SessionReaper reaper = new SessionReaper(id);
		SessionDisplay display = null;
		LinuxController controller = null;
		Pointer ewmh = null;
		try {
			display = startDisplay(reaper, options);
			// Pin XTest: on a private display device-level input is both accepted and non-intrusive, and the
			// process-wide botmaker.linux.input property (which steers :0) must not decide :N's backend. The
			// warp convention comes with the backend — gamescope's Xwayland reads an absolute warp as
			// window-relative, so its clicks need the focus origin subtracted (SessionBackends.pointerWarpFor).
			controller = LinuxController.forDisplay(display.displayName(), "xtest",
				SessionBackends.pointerWarpFor(options.backend()));
			ewmh = X11.INSTANCE.XOpenDisplay(display.displayName());
			if (ewmh == null) {
				throw new SessionStartException("could not open a second connection to " + display.displayName());
			}
			NestedSession session = new NestedSession(id, reaper, display, controller, ewmh, options);
			session.startWindowManager();
			return session;
		} catch (SessionStartException e) {
			cleanupFailedStart(reaper, controller, ewmh);
			throw e;
		} catch (Exception e) {
			cleanupFailedStart(reaper, controller, ewmh);
			throw new SessionStartException("nested session start failed: " + e.getMessage(), e);
		}
	}

	/** Bring up the display server the options ask for: Xephyr (2D) or gamescope (hardware 3D). */
	private static SessionDisplay startDisplay(SessionReaper reaper, Options options) throws SessionStartException {
		return switch (options.backend()) {
			case XEPHYR -> NestedDisplay.startXephyr(reaper, options.width(), options.height());
			case GAMESCOPE -> GamescopeDisplay.start(reaper, options.displayServerCommand(),
				options.width(), options.height());
		};
	}

	/** Reap a half-built session's resources in the reverse order they were acquired. */
	private static void cleanupFailedStart(SessionReaper reaper, LinuxController controller, Pointer ewmh) {
		if (ewmh != null) {
			try { X11.INSTANCE.XCloseDisplay(ewmh); } catch (Throwable ignored) { }
		}
		if (controller != null) {
			try { controller.close(); } catch (Throwable ignored) { }
		}
		reaper.reap();
	}

	/** Launch the resolved window manager (if any) into the nested display and wait, best-effort, for it. */
	private void startWindowManager() {
		List<String> wm = windowManagerCommandFor(options);
		if (wm.isEmpty()) {
			Diag.log("[Session] " + id + ": no window manager " + (options.backend() == Backend.GAMESCOPE
				? "(gamescope manages its own Xwayland)" : "— running WM-less"));
			return;
		}
		try {
			reaper.launch("wm", wm, sessionEnv(), ProcessBuilder.Redirect.DISCARD);
		} catch (Exception e) {
			Diag.error("[Session] " + id + ": window manager launch failed: " + e.getMessage());
			return;
		}
		long deadline = System.currentTimeMillis() + WM_TIMEOUT_MS;
		while (System.currentTimeMillis() < deadline) {
			if (X11Utils.hasWindowManager(ewmhDisplay)) {
				Diag.log("[Session] " + id + ": window manager is up");
				return;
			}
			sleep();
		}
		// A WM that never claims the display is a soft failure: input still reaches a mapped window without one.
		Diag.error("[Session] " + id + ": window manager did not claim " + display.displayName()
			+ " within " + WM_TIMEOUT_MS + "ms — continuing WM-less");
	}

	@Override
	public Set<Capability> capabilities() {
		// The whole point of a bot-owned display: BACKGROUND_CLICK/ISOLATED_FOCUS/MULTI_SESSION, which a shared
		// :0 desktop cannot offer. HARDWARE_GL/VULKAN come only from the gamescope backend (Xephyr is 2D here).
		EnumSet<Capability> caps = EnumSet.of(
			Capability.ABSOLUTE_POINTER,
			Capability.RELATIVE_POINTER,
			Capability.BACKGROUND_CLICK,
			Capability.ISOLATED_FOCUS,
			Capability.MULTI_SESSION,
			Capability.SCREEN_CAPTURE,
			Capability.WINDOW_LAUNCH,
			Capability.WINDOW_ATTACH);
		if (display.hardwareAccelerated()) {
			caps.add(Capability.HARDWARE_GL);
			caps.add(Capability.VULKAN);
		}
		return caps;
	}

	@Override
	public Rectangle screen() {
		return new Rectangle(0, 0, display.width(), display.height());
	}

	@Override
	public SessionPointer pointer() {
		return pointer;
	}

	@Override
	public SessionKeyboard keyboard() {
		return keyboard;
	}

	@Override
	public void attach(GenericWindow window) {
		this.attached = window;
	}

	@Override
	public GenericWindow attached() {
		return attached;
	}

	/**
	 * Launch {@code spec} into this nested display and attach to the window it produces. Any instance already
	 * running on {@code :0} is force-stopped first (a game can't run in two places, and we want <em>ours</em>).
	 * A kind with no child-launchable command ({@code epic:}/{@code emu-app:}) is refused, as is a store kind
	 * whose launcher UI is already open on the host ({@link HostLauncherProbe} — it would forward our request to
	 * that copy and start the game on {@code :0}). Otherwise each form of the ladder is tried in turn until one
	 * maps a window on {@code :N}, within {@link #windowTimeoutFor the kind's window budget}. When none does,
	 * {@link #attached()} stays null — the caller must treat that as a loud failure, not fall back to {@code :0}.
	 */
	@Override
	public void launch(LaunchSpec spec) {
		if (closed || spec == null) {
			return;
		}
		List<List<String>> candidates = commandFor(spec);
		if (candidates.isEmpty()) {
			Diag.error("[Session] " + id + ": nested launch not supported for kind " + spec.kind()
				+ " (" + spec.spec() + ") — no child-launchable command (Epic is URL-only, emulator apps run over ADB)");
			return;
		}
		if (HostLauncherProbe.isRunning(spec)) {
			// Single-instance: our child would forward the request to the copy on :0 and exit, mapping the game
			// on the real desktop. Refuse now rather than burning the window budget and reaping a launcher that
			// was never going to draw here.
			Diag.error("[Session] " + id + ": " + HostLauncherProbe.refusalMessage(spec.kind()));
			return;
		}
		stopHostInstance(spec);

		long windowTimeoutMs = windowTimeoutFor(spec, options);
		for (List<String> command : candidates) {
			Set<Long> before = windowIdsOnDisplay();
			Process proc;
			try {
				proc = reaper.launch("app", command, sessionEnv(), ProcessBuilder.Redirect.DISCARD);
			} catch (Exception e) {
				Diag.error("[Session] " + id + ": launching `" + String.join(" ", command) + "` failed: "
					+ e.getMessage() + " — trying the next launch form");
				continue;
			}
			gameProc = proc;
			GenericWindow target = awaitWindow(proc, before, windowTimeoutMs);
			if (target != null) {
				attach(target);
				Diag.log("[Session] " + id + ": attached to '" + target.getTitle() + "' on " + display.displayName());
				return;
			}
			Diag.error("[Session] " + id + ": `" + String.join(" ", command) + "` mapped no window on "
				+ display.displayName() + " within " + windowTimeoutMs + "ms — trying the next launch form");
		}
		// Every form ran but nothing appeared on :N. For a store launcher this is usually its own daemon,
		// already running on :0 with a single-instance lock, swallowing our child and mapping the game there.
		Diag.error("[Session] " + id + ": " + spec.spec() + " launched but no window appeared on "
			+ display.displayName() + " — a host launcher daemon may be stealing it; close it and retry");
	}

	@Override
	public BufferedImage capture() {
		GenericWindow target = attached;
		return target == null ? null : controller.captureWindow(target);
	}

	@Override
	public SessionHealth health() {
		if (closed || !display.alive()) {
			return SessionHealth.DEAD;
		}
		Process g = gameProc;
		if (g != null && !g.isAlive()) {
			// Display and (any) WM are up but the game died — recoverable by relaunching into the same display.
			return SessionHealth.DEGRADED;
		}
		return SessionHealth.HEALTHY;
	}

	@Override
	public NativeController controller() {
		return controller;
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		try { controller.close(); } catch (Throwable t) { Diag.error("[Session] " + id + ": controller close: " + t.getMessage()); }
		try { X11.INSTANCE.XCloseDisplay(ewmhDisplay); } catch (Throwable t) { Diag.error("[Session] " + id + ": ewmh close: " + t.getMessage()); }
		reaper.reap();
		Diag.log("[Session] " + id + ": closed");
	}

	/** The nested display this session drives, e.g. {@code ":9"} — for diagnostics and tests. */
	public String displayName() {
		return display.displayName();
	}

	/** This session's reap-group id — for diagnostics and tests. */
	public String sessionId() {
		return id;
	}

	/**
	 * Reap the process trees of nested sessions whose owning JVM has died — the reliable answer to "a bot
	 * crashed and left a Xephyr running". Call it at startup (a supervisor/Studio boot) as well; {@link #start}
	 * already runs it before each new session. No-op where there is no user systemd.
	 */
	public static void reapOrphanSessions() {
		SessionReaper.reapOrphans();
	}

	// --- internals ---

	/** The child environment every process launched into this session gets: its private DISPLAY, plus extras. */
	private Map<String, String> sessionEnv() {
		Map<String, String> env = new LinkedHashMap<>();
		env.put("DISPLAY", display.displayName());
		env.putAll(options.extraEnv());
		return env;
	}

	/**
	 * The ordered ladder of child-launchable argvs for {@code spec} (most-preferred first), or empty for a kind
	 * we can't hand a private {@code DISPLAY}. Single-sourced in {@link com.botmaker.shared.launch.LaunchCommands}
	 * so the nested path and the on-host {@code :0} path can't drift on how a launcher is spelled.
	 */
	static List<List<String>> commandFor(LaunchSpec spec) {
		return LaunchCommands.childLadder(spec);
	}

	/**
	 * The window manager to actually run for {@code options}: what the caller asked for when it said anything at
	 * all (including {@link Options#withoutWindowManager() "none"}), else the backend's policy from
	 * {@link SessionBackends#windowManagerFor}. A window manager on a gamescope session is refused whoever asked
	 * for it — gamescope already manages its Xwayland, and a second manager would fight it for the selection.
	 */
	static List<String> windowManagerCommandFor(Options options) {
		if (options.backend() == Backend.GAMESCOPE) {
			if (options.hasExplicitWindowManager() && !options.windowManagerCommand().isEmpty()) {
				Diag.error("[Session] ignoring window manager `" + String.join(" ", options.windowManagerCommand())
					+ "` — gamescope is the window manager for its own Xwayland");
			}
			return List.of();
		}
		return options.hasExplicitWindowManager()
			? options.windowManagerCommand()
			: SessionBackends.windowManagerFor(options.backend());
	}

	/**
	 * How long to wait for {@code spec}'s window: an explicit {@link Options#windowTimeoutMs()} when one is set,
	 * else {@link #LAUNCHER_WINDOW_TIMEOUT_MS} for a kind whose launch is routed through a store launcher (we're
	 * waiting on the game it starts, not on the process we spawned) and {@link #WINDOW_TIMEOUT_MS} otherwise —
	 * an {@code exe:}/{@code cli:} target <em>is</em> the process we spawned, so a window that hasn't appeared in
	 * twenty seconds isn't coming.
	 */
	static long windowTimeoutFor(LaunchSpec spec, Options options) {
		long explicit = options == null ? 0L : options.windowTimeoutMs();
		if (explicit > 0) {
			return explicit;
		}
		return spec != null && HostLauncherProbe.routesThroughDaemon(spec.kind())
			? LAUNCHER_WINDOW_TIMEOUT_MS
			: WINDOW_TIMEOUT_MS;
	}

	/**
	 * Force-stop any incarnation of {@code spec} already running on the host, so ours is the only one. This
	 * stops the <em>game</em> by name; it deliberately does not kill the user's launcher <em>daemon</em>
	 * (Heroic/Steam), which would disrupt their whole session. That a running daemon would swallow our launch
	 * entirely is handled one step earlier, by {@link HostLauncherProbe} refusing the launch outright.
	 */
	private void stopHostInstance(LaunchSpec spec) {
		if (!Launcher.isRunning(spec)) {
			return;
		}
		String name = spec.fileName();
		if (name != null && !name.isBlank()) {
			Diag.log("[Session] " + id + ": stopping host instance of " + spec.spec() + " (" + name + ")");
			GameLauncher.kill(name);
		} else {
			Diag.error("[Session] " + id + ": " + spec.spec() + " is running on the host but can't be stopped by name");
		}
	}

	/** All window ids currently on the nested display — the "before" snapshot the new-window attach diffs against. */
	private Set<Long> windowIdsOnDisplay() {
		Set<Long> ids = new HashSet<>();
		for (GenericWindow w : controller.getAllWindows()) {
			ids.add(handleId(w));
		}
		return ids;
	}

	/**
	 * Wait for the game's window and return it. Preference order: a window whose {@code _NET_WM_PID} is in the
	 * launched process subtree (the robust match — Wine/Proton set it); else a window that appeared since
	 * {@code before} (covers apps/WMs that don't set {@code _NET_WM_PID}, and WM-less displays with no client
	 * list); else {@code null} on timeout.
	 */
	private GenericWindow awaitWindow(Process proc, Set<Long> before, long timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			Set<Long> pids = subtreePids(proc);
			GenericWindow newest = null;
			for (GenericWindow w : controller.getAllWindows()) {
				long pid = X11Utils.getWindowPid(ewmhDisplay, (Pointer) w.getNativeHandle());
				if (pid > 0 && pids.contains(pid)) {
					return w; // strongest evidence — this window's own client is our process
				}
				if (!before.contains(handleId(w))) {
					newest = w; // last new window wins (the most recently mapped top-level)
				}
			}
			if (newest != null && !proc.isAlive() && subtreePids(proc).isEmpty()) {
				// Process already exited and left a new window (e.g. a launcher shim) — take it rather than spin.
				return newest;
			}
			if (newest != null) {
				return newest;
			}
			if (!proc.isAlive() && subtreePids(proc).isEmpty()) {
				Diag.error("[Session] " + id + ": launched process exited before a window appeared");
				return null;
			}
			sleep();
		}
		return null;
	}

	/** The pid of {@code proc} plus all its live descendants — under systemd the payload is a descendant of the scope. */
	private static Set<Long> subtreePids(Process proc) {
		Set<Long> pids = new HashSet<>();
		if (proc.isAlive()) {
			pids.add(proc.pid());
		}
		try {
			proc.descendants().forEach(h -> pids.add(h.pid()));
		} catch (Exception ignored) {
			// descendants() can race with exit; the pids we already have are enough.
		}
		return pids;
	}

	private static long handleId(GenericWindow w) {
		return Pointer.nativeValue((Pointer) w.getNativeHandle());
	}

	private static void sleep() {
		try {
			Thread.sleep(POLL_MS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Which display server hosts the nested session — the 2D vs. hardware-3D choice. */
	public enum Backend {
		/** Xephyr: cheap 2D host, software-rendered here. */
		XEPHYR("Xephyr"),
		/** gamescope: embedded Xwayland on the real GPU — for Proton/DXVK/Vulkan 3D targets. */
		GAMESCOPE("gamescope");

		private final String binaryName;

		Backend(String binaryName) {
			this.binaryName = binaryName;
		}

		/**
		 * The executable this backend spawns to host the nested display ({@code Xephyr} / {@code gamescope}).
		 * Single-sourced here so a consumer probing {@code PATH} for availability can't drift from what
		 * {@link NestedDisplay} / {@link GamescopeDisplay} actually run.
		 */
		public String binaryName() {
			return binaryName;
		}

		/**
		 * The stable lowercase wire id ({@code "xephyr"} / {@code "gamescope"}) — what the project file's
		 * {@code session.backend} key holds and what a generated bot passes to {@code Session.useBackend}. Kept
		 * distinct from {@link #binaryName()} on purpose: that one is capitalised {@code Xephyr} because it is
		 * the executable's actual name, and persisting a value that has to match an executable's spelling is how
		 * a rename breaks stored configs.
		 */
		public String id() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}

		/**
		 * Parses a backend {@link #id()} — total, and empty for anything that isn't one, which includes
		 * {@code null}, blank and the explicit {@code "auto"}. Empty therefore means <em>"no override, choose by
		 * launch kind"</em> ({@link SessionBackends#preferredBackend}), never a silent fallback to a particular
		 * backend: mapping an unrecognised value onto Xephyr is exactly the software-GL crash the kind-driven
		 * choice exists to avoid.
		 */
		public static java.util.Optional<Backend> fromId(String id) {
			if (id == null || id.isBlank()) {
				return java.util.Optional.empty();
			}
			String normalized = id.trim().toLowerCase(java.util.Locale.ROOT);
			for (Backend backend : values()) {
				if (backend.id().equals(normalized)) {
					return java.util.Optional.of(backend);
				}
			}
			return java.util.Optional.empty();
		}
	}

	/**
	 * How a nested session is shaped: which {@link Backend} hosts it, the display size, an optional window
	 * manager to run in it, and any extra per-session environment (a private {@code HOME}/{@code XDG_RUNTIME_DIR}/
	 * {@code WINEPREFIX} to stop a single-instance game escaping back to {@code :0}). The {@code DISPLAY} is
	 * always set for you. For {@link Backend#GAMESCOPE} the exact gamescope argv is overridable
	 * ({@link #withGamescopeCommand}) so a real box can tune it — or switch to the child-launch form — without a
	 * code change.
	 */
	public static final class Options {
		private final Backend backend;
		private final int width;
		private final int height;
		private final List<String> windowManagerCommand;
		private final Map<String, String> extraEnv;
		private final List<String> gamescopeCommand;
		private final long windowTimeoutMs;

		private Options(Backend backend, int width, int height, List<String> wm,
						Map<String, String> extraEnv, List<String> gamescopeCommand, long windowTimeoutMs) {
			this.backend = backend;
			this.width = width;
			this.height = height;
			// null = "not stated, use the backend's default policy"; empty = "explicitly none".
			this.windowManagerCommand = wm == null ? null : List.copyOf(wm);
			this.extraEnv = Map.copyOf(extraEnv);
			this.gamescopeCommand = gamescopeCommand == null ? List.of() : List.copyOf(gamescopeCommand);
			this.windowTimeoutMs = Math.max(0, windowTimeoutMs);
		}

		/**
		 * A 2D Xephyr session at {@code width}x{@code height}, no extra env, running the backend's default
		 * window manager ({@link SessionBackends#windowManagerFor} — openbox when it's installed, since a bare
		 * Xephyr has no EWMH and therefore no input focus to inject keys into).
		 */
		public static Options xephyr(int width, int height) {
			return new Options(Backend.XEPHYR, width, height, null, Map.of(), List.of(), 0);
		}

		/**
		 * A hardware-3D gamescope session at {@code width}x{@code height}, no extra env. Always WM-less:
		 * gamescope is itself the window manager for its embedded Xwayland.
		 */
		public static Options gamescope(int width, int height) {
			return new Options(Backend.GAMESCOPE, width, height, null, Map.of(), List.of(), 0);
		}

		/**
		 * This session, but running {@code command} as its window manager (e.g. {@code "openbox"}) instead of the
		 * backend default. Passing no arguments means <em>explicitly none</em>, which is how a caller opts out of
		 * the Xephyr default.
		 */
		public Options withWindowManager(String... command) {
			return new Options(backend, width, height, List.of(command), extraEnv, gamescopeCommand, windowTimeoutMs);
		}

		/** This session, but with no window manager at all — the explicit opt-out of the backend default. */
		public Options withoutWindowManager() {
			return withWindowManager();
		}

		/** This session, but with {@code env} overlaid on every child's environment (in addition to DISPLAY). */
		public Options withExtraEnv(Map<String, String> env) {
			return new Options(backend, width, height, windowManagerCommand, env, gamescopeCommand, windowTimeoutMs);
		}

		/**
		 * This session, but waiting {@code millis} for the launched target's window instead of the per-kind
		 * default ({@link #windowTimeoutFor}). Zero or negative restores the default. The knob exists because
		 * "how long can this game take to draw the first time" is a property of the user's machine — a cold
		 * Proton prefix on a slow disk — not something this class can know.
		 */
		public Options withWindowTimeout(long millis) {
			return new Options(backend, width, height, windowManagerCommand, extraEnv, gamescopeCommand, millis);
		}

		/**
		 * This session, but launching gamescope with {@code command} instead of the default argv. Only meaningful
		 * for {@link Backend#GAMESCOPE}; lets a real box adjust flags (backend, HDR, {@code --} child form) without
		 * touching {@link GamescopeDisplay}.
		 */
		public Options withGamescopeCommand(String... command) {
			return new Options(backend, width, height, windowManagerCommand, extraEnv, List.of(command), windowTimeoutMs);
		}

		public Backend backend() { return backend; }
		public int width() { return width; }
		public int height() { return height; }
		/** The <em>explicit</em> window-manager argv, or empty when none was stated (or none was wanted). */
		public List<String> windowManagerCommand() {
			return windowManagerCommand == null ? List.of() : windowManagerCommand;
		}

		/** Whether a caller stated a window manager (including {@link #withoutWindowManager()}'s "none"). */
		boolean hasExplicitWindowManager() {
			return windowManagerCommand != null;
		}
		public Map<String, String> extraEnv() { return extraEnv; }

		/** The explicit window-wait budget in ms, or {@code 0} to use the per-kind default. */
		public long windowTimeoutMs() { return windowTimeoutMs; }

		/** The gamescope argv to launch: an explicit override if set, else {@link GamescopeDisplay#defaultCommand}. */
		public List<String> displayServerCommand() {
			return gamescopeCommand.isEmpty() ? GamescopeDisplay.defaultCommand(width, height) : gamescopeCommand;
		}
	}
}
