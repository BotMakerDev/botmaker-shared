package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The measurement half of the capture floor — <b>not</b> a unit test, and skipped unless pointed at a real
 * device.
 *
 * <p>The whole phone effort is justified by an assumption ("the ADB verbs are the ceiling, not the
 * transport") that nothing in this repo had ever measured. This is what turns that into numbers, on the
 * hardware that matters rather than on someone else's. It exists as a test class because that is the one
 * entry point already wired to the module's classpath; it asserts almost nothing and prints a table.
 *
 * <pre>
 * mvn -pl botmaker-shared test -Dtest=AdbCaptureBenchmark -Dbotmaker.adb.benchmark=192.168.1.5:5555
 * mvn -pl botmaker-shared test -Dtest=AdbCaptureBenchmark -Dbotmaker.adb.benchmark=R5CT30ABCDE   # via adb server
 * </pre>
 *
 * <p>A value containing {@code :} with a numeric port is dialled directly by dadb; anything else is treated
 * as a serial and goes through the host's adb server. Record what it prints into {@code ROADMAP.md} — the
 * point of the exercise is a table with real numbers in it, and one that could still say Phase 3 is not worth
 * building.
 */
@EnabledIfSystemProperty(named = "botmaker.adb.benchmark", matches = ".+")
class AdbCaptureBenchmark {

    /** Enough samples to see a median through one GC pause or one scheduler hiccup, and still finish quickly. */
    private static final int SAMPLES = 20;
    private static final int WARMUP = 3;

    @Test
    void measuresTheCaptureAndInputFloor() {
        AdbEndpoint endpoint = parse(System.getProperty("botmaker.adb.benchmark"));
        try (AdbDevice device = AdbDevice.connect(endpoint)) {
            BufferedImage png = device.screencapPng();
            BufferedImage raw = device.screencapRaw();
            assertNotNull(png, "the PNG path must work; it is the fallback everything else rests on");

            System.out.println();
            System.out.println("=== capture/input floor on " + endpoint.label()
                    + " (local=" + endpoint.local() + ") ===");
            System.out.println("device: " + device.getProp("ro.product.model")
                    + " / Android " + device.getProp("ro.build.version.release")
                    + " (API " + device.getProp("ro.build.version.sdk") + ")");

            if (raw == null) {
                System.out.println("raw screencap: UNSUPPORTED on this device — screencap() stays on PNG");
            } else {
                // The sizing invariant, asserted rather than reported: the two paths must be one number, or a
                // template authored against one would mis-click through the other.
                assertEquals(png.getWidth(), raw.getWidth(), "raw and PNG must agree on width");
                assertEquals(png.getHeight(), raw.getHeight(), "raw and PNG must agree on height");
                System.out.printf("frame: %dx%d%n", raw.getWidth(), raw.getHeight());
                System.out.printf("identical pixels: %.4f%%%n", identicalPercent(png, raw));
                report("screencapRaw()", () -> device.screencapRaw());
            }
            report("screencapPng()", () -> device.screencapPng());
            report("tap (held shell)", () -> device.tap(1, 1));
            report("getprop (held shell)", () -> device.getProp("ro.product.model"));
            System.out.println();
        }
    }

    /** Median, min and max in ms — a mean would hide exactly the outlier a bot feels as a missed frame. */
    private static void report(String label, Runnable action) {
        for (int i = 0; i < WARMUP; i++) {
            action.run();
        }
        List<Long> timings = new ArrayList<>(SAMPLES);
        for (int i = 0; i < SAMPLES; i++) {
            long start = System.nanoTime();
            action.run();
            timings.add((System.nanoTime() - start) / 1_000_000);
        }
        Collections.sort(timings);
        System.out.printf("%-24s median %4d ms   min %4d ms   max %4d ms   (n=%d)%n",
                label, timings.get(timings.size() / 2), timings.get(0),
                timings.get(timings.size() - 1), SAMPLES);
    }

    /**
     * Sample fidelity, <em>reported</em> — per the measurement doctrine in {@code docs/display-pipeline.md}
     * §10, geometry is asserted and samples are only reported. Both paths are lossless, so this should read
     * 100%; a lower number means the device composited between the two grabs, not that a path is lossy.
     */
    private static double identicalPercent(BufferedImage a, BufferedImage b) {
        long same = 0;
        long total = (long) a.getWidth() * a.getHeight();
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if ((a.getRGB(x, y) & 0xFFFFFF) == (b.getRGB(x, y) & 0xFFFFFF)) {
                    same++;
                }
            }
        }
        return 100.0 * same / total;
    }

    /** {@code host:port} dials directly; anything else is a serial for the adb server to resolve. */
    private static AdbEndpoint parse(String value) {
        int colon = value.lastIndexOf(':');
        if (colon > 0) {
            try {
                return new AdbEndpoint.Tcp(value.substring(0, colon),
                        Integer.parseInt(value.substring(colon + 1).trim()));
            } catch (NumberFormatException ignored) {
                // not a port — fall through and treat the whole thing as a serial
            }
        }
        return new AdbEndpoint.Server(value);
    }
}
