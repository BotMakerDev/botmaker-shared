package com.botmaker.shared.capture.linux.input;

import com.botmaker.shared.Diag;

import java.util.Locale;

/**
 * The closed set of Linux input-synthesis strategies — the value of the {@code botmaker.linux.input} system
 * property / {@code BOTMAKER_LINUX_INPUT} env var, and the thing
 * {@link com.botmaker.shared.capture.linux.LinuxController} selects a {@link LinuxInputBackend} from.
 *
 * <p>The {@code PlatformId} pattern the repo prescribes for this shape: a stable wire {@link #id()} (it is
 * persisted, in {@code botmaker-project.properties}, so it must never change) plus a total
 * {@link #fromId(String)}. Before this existed the set was spelled five times — once as a {@code switch} in
 * {@code LinuxController.selectBackend}, and once more in each backend's own {@code name()} — and the two
 * session call sites passed the bare literal {@code "xtest"}.
 *
 * <p><b>{@link #fromId} is total <em>and</em> loud.</b> The old {@code switch} shared one arm between
 * {@code case "auto"}, {@code case "xsendevent"} and {@code default}, so a typo ({@code xtets}) was
 * indistinguishable from {@link #AUTO} and silently reached {@link XSendEventBackend} — a bot that opted into
 * uinput and got cursor-preserving xsendevent instead, with nothing in the log to say so. An unrecognised
 * non-blank value now still resolves to {@link #AUTO} (the value is user-editable text, and a project written
 * by a newer Studio must still load in an older bot) but says so via {@link Diag}.
 */
public enum LinuxInputBackendId {

    /** Let {@link com.botmaker.shared.capture.linux.LinuxController} choose — today the cursor-safe xsendevent. */
    AUTO("auto"),
    /** {@link XSendEventBackend}: cursor-preserving, delivers events straight to a target window. */
    XSENDEVENT("xsendevent"),
    /** {@link XTestBackend}: in-process XTEST warp-and-click; moves the shared cursor. */
    XTEST("xtest"),
    /** {@link XdotoolBackend}: XTEST via the {@code xdotool} CLI; moves the shared cursor. */
    XDOTOOL("xdotool"),
    /** {@link UinputBackend}: a kernel virtual device via {@code /dev/uinput}; reaches games and native Wayland. */
    UINPUT("uinput");

    private final String id;

    LinuxInputBackendId(String id) {
        this.id = id;
    }

    /** The persisted wire id — what appears in the property, the env var and the project properties file. */
    public String id() {
        return id;
    }

    /**
     * The backend for {@code id}, case-insensitively and ignoring surrounding blanks. Never throws: a blank or
     * {@code null} value is {@link #AUTO} silently (nothing was asked for), and an unrecognised non-blank value
     * is {@link #AUTO} with a diagnostic naming the valid ids.
     */
    public static LinuxInputBackendId fromId(String id) {
        if (id == null || id.isBlank()) {
            return AUTO;
        }
        String needle = id.trim().toLowerCase(Locale.ROOT);
        for (LinuxInputBackendId backend : values()) {
            if (backend.id.equals(needle)) {
                return backend;
            }
        }
        StringBuilder valid = new StringBuilder();
        for (LinuxInputBackendId backend : values()) {
            if (valid.length() > 0) {
                valid.append(", ");
            }
            valid.append(backend.id);
        }
        Diag.error("[Linux] unknown input backend '" + id.trim() + "' (valid: " + valid
            + ") — using " + AUTO.id + ".");
        return AUTO;
    }
}
