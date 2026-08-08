package com.botmaker.shared.device;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.DataInputStream;
import java.io.File;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

/**
 * <b>H.264 in, newest picture out.</b> Feeds the channel's Annex-B bytes to a piped {@code ffmpeg} and keeps
 * exactly one decoded frame — the last complete one — for whoever asks.
 *
 * <h2>Newest-only, not a queue</h2>
 *
 * <p>A bot asking for a frame wants the screen <em>now</em>; a frame from a queue is by definition a frame
 * from the past, and a queue that fills makes every subsequent answer older still. So decoding runs at the
 * stream's pace on its own thread and each picture overwrites the last. A consumer that reads slower than the
 * device encodes simply skips frames, which is the correct behaviour and the one that cannot drift.
 *
 * <p>This is also why there is no callback: the SDK's capture contract is a pull ({@code CaptureSource} asks
 * for an image), and turning a push into a pull is exactly a one-slot buffer.
 *
 * <h2>{@code ffmpeg} is optional</h2>
 *
 * <p>No binary on {@code PATH} means {@link #start} returns null and the caller uses the ADB floor — the same
 * degradation as a missing scrcpy. The decode is a subprocess rather than a library because that is the
 * pattern already established for video in this repo ({@code session/video/FfmpegVideoStream}), and because
 * the alternative is a JNI decoder in a build that has no reason to grow one.
 */
final class ScrcpyFrames implements AutoCloseable {

    private final Process ffmpeg;
    private final OutputStream toDecoder;
    private final Thread reader;
    private final int width;
    private final int height;

    /** The newest complete picture, or null before the first one. Written by {@link #reader}, read by anyone. */
    private volatile BufferedImage newest;
    private volatile boolean closed;

    private ScrcpyFrames(Process ffmpeg, int width, int height) {
        this.ffmpeg = ffmpeg;
        this.toDecoder = ffmpeg.getOutputStream();
        this.width = width;
        this.height = height;
        this.reader = new Thread(this::readFrames, "scrcpy-decode");
        this.reader.setDaemon(true);
        this.reader.start();
        drainErrors();
    }

    /**
     * Starts a decoder for a {@code width}×{@code height} stream, or null when {@code ffmpeg} is unavailable.
     *
     * <p>The size comes from the channel's header rather than from {@code ffmpeg}: the output is raw frames
     * with no container and no dimensions in them, so the reader has to know how many bytes a picture is
     * before it reads one. That the two agree is the §3 invariant, and it is asserted where the header is
     * read, not guessed at here.
     */
    static ScrcpyFrames start(int width, int height) {
        File binary = binary();
        if (binary == null || width <= 0 || height <= 0) {
            return null;
        }
        try {
            Process process = new ProcessBuilder(List.of(binary.getAbsolutePath(),
                    "-hide_banner", "-loglevel", "error",
                    // Every one of these says the same thing: do not buffer to smooth playback. There is no
                    // playback — a frame held back to make motion look even is pure latency here.
                    "-fflags", "nobuffer", "-flags", "low_delay", "-probesize", "32",
                    "-f", "h264", "-i", "pipe:0",
                    "-f", "rawvideo", "-pix_fmt", "bgr24", "pipe:1")).start();
            return new ScrcpyFrames(process, width, height);
        } catch (Exception e) {
            return null;
        }
    }

    /** The newest decoded frame, or null before the first one has arrived (or after the decoder died). */
    BufferedImage newest() {
        return closed ? null : newest;
    }

    /** Whether the decoder is still running. */
    boolean alive() {
        return !closed && ffmpeg.isAlive();
    }

    /** Hands one chunk of Annex-B to the decoder. Best-effort: a dead decoder ends the session, not the call. */
    void feed(byte[] chunk) {
        if (closed) {
            return;
        }
        try {
            toDecoder.write(chunk);
            toDecoder.flush();
        } catch (Exception e) {
            closed = true;
        }
    }

    /**
     * Reads whole pictures and publishes each one. {@code bgr24} is byte-for-byte the layout of
     * {@link BufferedImage#TYPE_3BYTE_BGR}'s backing array, so a frame is one {@code readFully} into a fresh
     * image with no per-pixel conversion at all.
     */
    private void readFrames() {
        int frameBytes = width * height * 3;
        DataInputStream in = new DataInputStream(ffmpeg.getInputStream());
        try {
            while (!closed) {
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
                byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
                in.readFully(pixels, 0, frameBytes);
                newest = image;
            }
        } catch (Exception e) {
            // EOF: the encoder stopped, or ffmpeg gave up on the stream.
        } finally {
            closed = true;
        }
    }

    /** {@code ffmpeg} writes its diagnostics to stderr; a full pipe would block the decode. */
    private void drainErrors() {
        Thread thread = new Thread(() -> {
            byte[] scratch = new byte[1024];
            try {
                while (ffmpeg.getErrorStream().read(scratch) >= 0) {
                    // discarded; -loglevel error means anything here is already a lost cause
                }
            } catch (Exception ignored) {
                // the process ended
            }
        }, "scrcpy-decode-err");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void close() {
        closed = true;
        try {
            toDecoder.close();
        } catch (Exception ignored) {
            // the pipe is already gone
        }
        ffmpeg.destroy();
        reader.interrupt();
    }

    /** The first {@code ffmpeg} on {@code PATH}, or null. */
    private static File binary() {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "ffmpeg.exe" : "ffmpeg";
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            File candidate = new File(dir, name);
            if (candidate.isFile() && candidate.canExecute()) {
                return candidate;
            }
        }
        return null;
    }
}
