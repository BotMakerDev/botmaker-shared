package com.botmaker.shared.device;

/**
 * What to ask the encoder for, and what to ask the phone's screen to do while we do — everything else worth
 * setting is either fixed by the design ({@code max_size}, which must not exist — see {@link ScrcpyChannel}) or
 * not ours to choose.
 *
 * @param bitRate   bits per second. The default is high on purpose: the stream is <b>native resolution</b>, and
 *                  it is what a bot's template matching runs against, so encoder artefacts are not a cosmetic
 *                  cost here the way they are for a human watching a mirror. A game's UI is mostly flat colour
 *                  and text, which compresses well; the bit rate is spent on the moments that don't.
 * @param maxFps    frames per second cap. Capping matters less for latency than for the device's battery and
 *                  thermals — an uncapped encode on a phone throttles, and a throttled phone is slower at the
 *                  thing being automated than a capped one.
 * @param stayAwake keeps the screen from sleeping for the duration of the session, and only for that: scrcpy
 *                  restores the previous setting on clean exit. <b>On by default because a sleeping screen is
 *                  the single most common way an unattended run dies</b> — the encoder keeps producing frames
 *                  of a black screen, so nothing errors, matching simply stops finding anything. It was a
 *                  warning in the connect dialog; it is now a default. Requires the control socket, which this
 *                  stack always opens.
 * @param powerOn   turns the display on at connect, so a phone found asleep does not need a physical press
 *                  before the first frame means anything.
 */
public record ScrcpyOptions(int bitRate, int maxFps, boolean stayAwake, boolean powerOn) {

    public ScrcpyOptions {
        bitRate = Math.max(1_000_000, bitRate);
        maxFps = Math.max(1, Math.min(240, maxFps));
    }

    /** The two numbers, with the two screen behaviours at their defaults — both on. */
    public ScrcpyOptions(int bitRate, int maxFps) {
        this(bitRate, maxFps, true, true);
    }

    /** 16 Mbps at 60 fps, screen kept awake and woken on connect. */
    public static ScrcpyOptions defaults() {
        return new ScrcpyOptions(16_000_000, 60);
    }
}
