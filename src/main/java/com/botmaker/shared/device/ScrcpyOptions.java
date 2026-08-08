package com.botmaker.shared.device;

/**
 * What to ask the encoder for. Two numbers, because everything else worth setting is either fixed by the
 * design ({@code max_size}, which must not exist — see {@link ScrcpyChannel}) or not ours to choose.
 *
 * @param bitRate bits per second. The default is high on purpose: the stream is <b>native resolution</b>, and
 *                it is what a bot's template matching runs against, so encoder artefacts are not a cosmetic
 *                cost here the way they are for a human watching a mirror. A game's UI is mostly flat colour
 *                and text, which compresses well; the bit rate is spent on the moments that don't.
 * @param maxFps  frames per second cap. Capping matters less for latency than for the device's battery and
 *                thermals — an uncapped encode on a phone throttles, and a throttled phone is slower at the
 *                thing being automated than a capped one.
 */
public record ScrcpyOptions(int bitRate, int maxFps) {

    public ScrcpyOptions {
        bitRate = Math.max(1_000_000, bitRate);
        maxFps = Math.max(1, Math.min(240, maxFps));
    }

    /** 16 Mbps at 60 fps — enough headroom for a native-resolution phone screen in motion. */
    public static ScrcpyOptions defaults() {
        return new ScrcpyOptions(16_000_000, 60);
    }
}
