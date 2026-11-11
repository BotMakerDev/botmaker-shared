package com.botmaker.shared.steps;

import com.botmaker.shared.steps.DeviceSteps.Step;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceStepsTest {

    private final List<Step> steps = new CopyOnWriteArrayList<>();
    private final List<String> progress = new CopyOnWriteArrayList<>();
    private final DeviceSteps.Recorder recorder = new DeviceSteps.Recorder() {
        @Override
        public void step(Step step) {
            steps.add(step);
        }

        @Override
        public void progress(String device, String command, long done, long total) {
            progress.add(device + " " + command + " " + done + "/" + total);
        }
    };

    @Test
    void aStepInsideTheScopeIsToldWithWhatTheDeviceSaid() {
        String said = DeviceSteps.recording(recorder,
                () -> DeviceSteps.step("127.0.0.1:5555", "dumpsys window", () -> "\n  mCurrentFocus=Window{a}\nmore",
                        DeviceSteps::clip));

        assertEquals("\n  mCurrentFocus=Window{a}\nmore", said);
        Step step = steps.getFirst();
        assertEquals("127.0.0.1:5555", step.device());
        assertEquals("dumpsys window", step.command());
        assertTrue(step.ok());
        assertEquals("mCurrentFocus=Window{a}", step.result());
    }

    @Test
    void aStepOutsideAnyScopeIsOnlyCounted() {
        long before = DeviceSteps.unscoped();
        DeviceSteps.step("127.0.0.1:5555", "push x /sdcard/x", () -> {
            DeviceSteps.step("127.0.0.1:5555", "mkdir -p /sdcard", () -> null);
            return null;
        });

        assertTrue(steps.isEmpty());
        assertEquals(before + 1, DeviceSteps.unscoped());
    }

    @Test
    void aStepInsideAnotherIsTheOuterOnes() {
        DeviceSteps.recording(recorder, () -> {
            DeviceSteps.step("d", "install base.apk", () -> {
                DeviceSteps.step("d", "pm install-create", () -> null);
                return null;
            });
            return null;
        });

        assertEquals(List.of("install base.apk"), steps.stream().map(Step::command).toList());
    }

    @Test
    void aThrownStepIsToldFailedAndStillThrows() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> DeviceSteps.recording(recorder,
                () -> DeviceSteps.step("d", "screencap", () -> {
                    throw new IllegalStateException("closed");
                }, result -> "")));

        assertEquals("closed", thrown.getMessage());
        assertFalse(steps.getFirst().ok());
        assertEquals("closed", steps.getFirst().result());
    }

    @Test
    void aFailureTheDeviceReportsIsToldFailed() {
        DeviceSteps.recording(recorder, () -> DeviceSteps.step("d", "adb connect", () -> "refused", text -> text,
                text -> false));

        assertFalse(steps.getFirst().ok());
    }

    @Test
    void progressTakenInTheScopeReachesItFromAnotherThread() throws InterruptedException {
        DeviceSteps.Progress report = DeviceSteps.recording(recorder, () -> DeviceSteps.progress("d", "install a.apk"));
        Thread other = Thread.ofPlatform().start(() -> report.report(5, 10));
        other.join();

        assertEquals(List.of("d install a.apk 5/10"), progress);
        assertEquals(DeviceSteps.Progress.NONE, DeviceSteps.progress("d", "install a.apk"));
    }

    @Test
    void aRecorderThatFailsNeverFailsTheWork() {
        DeviceSteps.Recorder failing = new DeviceSteps.Recorder() {
            @Override
            public void step(Step step) {
                throw new IllegalStateException("gone");
            }

            @Override
            public void progress(String device, String command, long done, long total) {
            }
        };

        assertEquals("ok", DeviceSteps.recording(failing, () -> DeviceSteps.step("d", "true", () -> "ok", t -> t)));
    }

    @Test
    void aResultIsItsFirstLineCut() {
        assertEquals("", DeviceSteps.clip(null));
        assertEquals("a", DeviceSteps.clip("\n \na\nb"));
        String cut = DeviceSteps.clip("x".repeat(500));
        assertEquals(DeviceSteps.RESULT_CHARS, cut.length());
        assertTrue(cut.endsWith("…"));
    }
}
