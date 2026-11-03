package com.botmaker.shared.runlog;

import com.botmaker.runlog.api.Level;
import com.botmaker.runlog.api.LogLine;
import com.botmaker.shared.ipc.TelemetryEvent;

/** A traced line as the run's log writes it: {@link TelemetryEvent.Log} is the host's, {@link LogLine} the log's. */
public final class LogLines {

    private LogLines() {
    }

    /** {@code log} as a line of the run's log, its level read from its id ({@link Level#UNKNOWN} when none). */
    public static LogLine of(TelemetryEvent.Log log) {
        TelemetryEvent.Rect r = log.rect();
        return new LogLine(log.atMillis(), Level.fromId(log.level()), log.source(), log.text(), log.count(),
                r == null ? null : new LogLine.Rect(r.x(), r.y(), r.width(), r.height()),
                log.writerClass(), log.writerMethod(), log.className(), log.line());
    }
}
