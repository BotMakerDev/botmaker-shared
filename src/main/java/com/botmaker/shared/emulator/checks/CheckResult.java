package com.botmaker.shared.emulator.checks;

import java.util.List;

/**
 * One check's answer, everything a row shows.
 *
 * @param check    which check
 * @param state    how it came out
 * @param reason   what was found, in the user's words
 * @param remedy   what Fix does, or what the user must do when there is no Fix; empty when OK
 * @param fixable  whether BotMaker's Fix button can repair it
 * @param undoable whether the last Fix left a backup Undo restores
 * @param commands what to run by hand when there is no Fix; empty otherwise
 * @param link     a page that helps (an install guide, Google's registration), or {@code null}
 */
public record CheckResult(EmulatorCheck check, CheckState state, String reason, String remedy, boolean fixable,
                          boolean undoable, List<String> commands, String link) {

    public CheckResult {
        reason = reason == null ? "" : reason;
        remedy = remedy == null ? "" : remedy;
        commands = commands == null ? List.of() : List.copyOf(commands);
    }

    static CheckResult ok(EmulatorCheck check, String reason) {
        return new CheckResult(check, CheckState.OK, reason, "", false, false, List.of(), null);
    }

    static CheckResult info(EmulatorCheck check, String reason) {
        return new CheckResult(check, CheckState.INFO, reason, "", false, false, List.of(), null);
    }

    static CheckResult afterAbove(EmulatorCheck check) {
        return new CheckResult(check, CheckState.AFTER_ABOVE, "", "", false, false, List.of(), null);
    }

    /** A failure the Fix button repairs. */
    static CheckResult fixable(EmulatorCheck check, String reason, String remedy) {
        return new CheckResult(check, CheckState.FAILED, reason, remedy, true, false, List.of(), null);
    }

    /** A failure the user repairs: what to do, the commands and a page. */
    static CheckResult byHand(EmulatorCheck check, String reason, String remedy, List<String> commands, String link) {
        return new CheckResult(check, CheckState.FAILED, reason, remedy, false, false, commands, link);
    }

    public boolean failed() {
        return state == CheckState.FAILED;
    }

    /** The commands as one copy-pasteable block. */
    public String commandBlock() {
        return String.join("\n", commands);
    }

    /** This result with {@link #undoable} set. */
    public CheckResult withUndo(boolean undo) {
        return new CheckResult(check, state, reason, remedy, fixable, undo, commands, link);
    }
}
