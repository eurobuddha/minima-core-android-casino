package org.minimarex.casino;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Procedural sound-effect synthesiser, ported 1:1 from the Zero Edge Casino dapp's
 * Web-Audio {@code SFX} object (index.html). No external libs: every effect is rendered
 * to a 16-bit mono PCM buffer at 44100 Hz and pushed through a one-shot STATIC
 * {@link AudioTrack}.
 *
 * <p>Each public method:
 * <ul>
 *   <li>returns immediately if {@link Theme#sound()} is false,</li>
 *   <li>does all synthesis + playback on a single background thread so the UI never
 *       blocks,</li>
 *   <li>is wrapped in try/catch so a device with no/odd audio HW never crashes the app.</li>
 * </ul>
 */
public final class Sfx {

    private static final int SR = 44100;                 // sample rate
    private static final float MASTER = 2.6f;            // master gain (per-tone vols are low)
    // Small pool so a short click can overlap a longer spin instead of serialising.
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2);
    private static final Random RND = new Random();

    private Sfx() {}

    /** Optional hook; nothing needed currently (kept for API symmetry / future use). */
    public static void init(Context ctx) { /* no-op */ }

    /** Shut the synth executor down (call from Activity onDestroy if desired). */
    public static void release() {
        try { EXEC.shutdownNow(); } catch (Throwable ignored) {}
    }

    // =====================================================================
    //  Public effects (mirror the dapp SFX names)
    // =====================================================================

    /** 3 rising square blips ~400 -> 600 -> 800 Hz. */
    public static void chipStack() {
        play(() -> {
            float[] b = buf(0.21f);
            tone(b, Wave.SQUARE, 400, 0.06f, 0.10f, 0.00f);
            tone(b, Wave.SQUARE, 600, 0.06f, 0.10f, 0.07f);
            tone(b, Wave.SQUARE, 800, 0.06f, 0.12f, 0.14f);
            return b;
        });
    }

    /** Short high-passed white-noise swish. */
    public static void cardDeal() {
        play(() -> {
            float[] b = buf(0.10f);
            noise(b, 0.08f, 0.10f, Filter.HIGHPASS, 2000, 0.7f, 0.0f);
            return b;
        });
    }

    /** Two sine notes 880 Hz then 1320 Hz. */
    public static void chime() {
        play(() -> {
            float[] b = buf(0.30f);
            tone(b, Wave.SINE, 880, 0.25f, 0.12f, 0.00f);
            tone(b, Wave.SINE, 1320, 0.20f, 0.08f, 0.08f);
            return b;
        });
    }

    /** Band-passed noise sweep + a few sine pings, ~1s feel (dapp uses 1.8s). */
    public static void coinSpin() {
        play(() -> {
            float dur = 1.0f;
            float[] b = buf(dur + 0.05f);
            int n = (int) (SR * dur);
            // amplitude-modulated band-passed noise
            for (int i = 0; i < n; i++) {
                float mod = 0.3f + 0.7f * Math.abs((float) Math.sin(i / (float) SR * Math.PI * 5));
                b[i] += (RND.nextFloat() * 2 - 1) * mod * 0.07f;
            }
            bandpass(b, 0, n, 800, 0.8f);
            envelope(b, 0, n, 0.01f, 0.10f);     // gentle fade so the sweep doesn't click
            tone(b, Wave.SINE, 2400, 0.08f, 0.03f, 0.00f);
            tone(b, Wave.SINE, 2400, 0.08f, 0.02f, 0.45f);
            tone(b, Wave.SINE, 2400, 0.08f, 0.02f, 0.90f);
            return b;
        });
    }

    /** ~11 short filtered-noise clicks over ~0.7s. */
    public static void diceRoll() {
        play(() -> {
            float[] delays = {0f, .06f, .13f, .21f, .3f, .4f, .52f, .66f, .82f, 1.0f, 1.2f};
            // compress the dapp's 1.5s into ~0.7s by scaling delays
            float scale = 0.55f;
            float[] b = buf(1.4f * scale + 0.1f);
            for (int i = 0; i < delays.length; i++) {
                float vol = 0.12f * (1 - i / (float) delays.length * 0.7f);
                float clickDur = 0.015f + RND.nextFloat() * 0.01f;
                float freq = 1200 + RND.nextFloat() * 800;
                noise(b, clickDur, vol, Filter.BANDPASS, freq, 2f, delays[i] * scale);
            }
            return b;
        });
    }

    /** ~40 accelerating clicks + a pitch-sliding sine, ~2s. */
    public static void roulette() {
        play(() -> {
            float dur = 2.0f;
            float[] b = buf(dur + 0.1f);
            float t = 0, interval = 0.04f;
            for (int i = 0; i < 40 && t < dur; i++) {
                float vol = 0.06f + 0.04f * (i / 40f);
                noise(b, 0.008f, vol, Filter.HIGHPASS, 3000, 0.7f, t);
                t += interval;
                interval *= 1.06f;
            }
            slide(b, Wave.SINE, 120, 60, dur, 0.04f, 0.0f);   // descending hum
            return b;
        });
    }

    /** 4-note ascending square jingle. */
    public static void win() {
        play(() -> {
            float[] b = buf(0.60f);
            tone(b, Wave.SQUARE, 523, 0.10f, 0.10f, 0.00f);
            tone(b, Wave.SQUARE, 659, 0.10f, 0.10f, 0.10f);
            tone(b, Wave.SQUARE, 784, 0.10f, 0.10f, 0.20f);
            tone(b, Wave.SQUARE, 1047, 0.25f, 0.12f, 0.30f);
            return b;
        });
    }

    /** Descending sawtooth wobble ~400 -> 120 Hz. */
    public static void lose() {
        play(() -> {
            float[] b = buf(0.55f);
            slide(b, Wave.SAW, 400, 120, 0.5f, 0.08f, 0.0f);
            return b;
        });
    }

    /** Tiny hi-pass noise pop for UI taps. */
    public static void click() {
        play(() -> {
            float[] b = buf(0.03f);
            noise(b, 0.02f, 0.06f, Filter.HIGHPASS, 3000, 0.7f, 0.0f);
            return b;
        });
    }

    // =====================================================================
    //  Synthesis helpers
    // =====================================================================

    private enum Wave { SINE, SQUARE, SAW }
    private enum Filter { HIGHPASS, LOWPASS, BANDPASS }

    /** Lambda that renders a float[-1..1] mix buffer. */
    private interface Render { float[] run(); }

    /** Allocate a zeroed mix buffer of the given length in seconds. */
    private static float[] buf(float seconds) {
        return new float[Math.max(1, (int) (SR * seconds))];
    }

    /**
     * Add a tone (sine / square / saw) at fixed frequency into the mix buffer with a
     * short linear attack and an exponential-ish decay (matches the dapp's gain ramp).
     */
    private static void tone(float[] b, Wave w, float freq, float dur, float vol, float delay) {
        int start = (int) (delay * SR);
        int n = (int) (dur * SR);
        double phase = 0, inc = 2 * Math.PI * freq / SR;
        for (int i = 0; i < n; i++) {
            int idx = start + i;
            if (idx < 0 || idx >= b.length) break;
            float s;
            switch (w) {
                case SQUARE: s = Math.sin(phase) >= 0 ? 1f : -1f; break;
                case SAW:    s = (float) (2.0 * ((freq * (i / (float) SR)) % 1.0) - 1.0); break;
                default:     s = (float) Math.sin(phase); break;
            }
            b[idx] += s * vol * env(i, n);
            phase += inc;
        }
    }

    /**
     * Add a frequency-sliding tone (linear glide from f0 to f1 over dur). Used for the
     * roulette hum and the lose wobble.
     */
    private static void slide(float[] b, Wave w, float f0, float f1, float dur, float vol, float delay) {
        int start = (int) (delay * SR);
        int n = (int) (dur * SR);
        double phase = 0;
        for (int i = 0; i < n; i++) {
            int idx = start + i;
            if (idx < 0 || idx >= b.length) break;
            float f = f0 + (f1 - f0) * (i / (float) n);
            float s;
            switch (w) {
                case SQUARE: s = Math.sin(phase) >= 0 ? 1f : -1f; break;
                case SAW:    s = (float) (2.0 * ((f * (i / (float) SR)) % 1.0) - 1.0); break;
                default:     s = (float) Math.sin(phase); break;
            }
            b[idx] += s * vol * env(i, n);
            phase += 2 * Math.PI * f / SR;
        }
    }

    /**
     * Add a filtered white-noise burst. We render noise then apply a cheap one-pole
     * high/low-pass or a band-pass (low- then high-pass) to approximate the dapp's
     * BiquadFilter feel.
     */
    private static void noise(float[] b, float dur, float vol, Filter f, float freq, float q, float delay) {
        int start = (int) (delay * SR);
        int n = (int) (dur * SR);
        if (n <= 0) return;
        float[] tmp = new float[n];
        for (int i = 0; i < n; i++) tmp[i] = (RND.nextFloat() * 2 - 1) * (1 - i / (float) n);
        switch (f) {
            case HIGHPASS:  highpass(tmp, 0, n, freq); break;
            case LOWPASS:   lowpass(tmp, 0, n, freq);  break;
            case BANDPASS:  bandpass(tmp, 0, n, freq, q); break;
        }
        for (int i = 0; i < n; i++) {
            int idx = start + i;
            if (idx < 0 || idx >= b.length) break;
            b[idx] += tmp[i] * vol * env(i, n);
        }
    }

    // ---- envelopes ----

    /** Per-sample attack/decay envelope (5% attack, decay to ~0 at the tail). */
    private static float env(int i, int n) {
        float a = n * 0.05f;                       // attack window
        float attack = i < a ? i / a : 1f;
        float decay = 1f - (i / (float) n);        // linear decay
        return attack * decay;
    }

    /** Apply a fixed attack/decay (in seconds) over a region — used by the coin sweep. */
    private static void envelope(float[] b, int off, int n, float attackSec, float decaySec) {
        int a = (int) (attackSec * SR), d = (int) (decaySec * SR);
        for (int i = 0; i < n; i++) {
            float g = 1f;
            if (i < a) g = i / (float) a;
            else if (i > n - d) g = (n - i) / (float) d;
            b[off + i] *= g;
        }
    }

    // ---- one-pole filters (cheap BiquadFilter stand-ins) ----

    private static float rc(float freq) {
        // smoothing factor for a one-pole filter at the given cutoff
        float dt = 1f / SR;
        float rc = 1f / (2f * (float) Math.PI * freq);
        return rc / (rc + dt);
    }

    private static void lowpass(float[] b, int off, int n, float freq) {
        float a = 1f - rc(freq);   // alpha for LP
        float prev = b[off];
        for (int i = 1; i < n; i++) {
            prev = prev + a * (b[off + i] - prev);
            b[off + i] = prev;
        }
    }

    private static void highpass(float[] b, int off, int n, float freq) {
        float a = rc(freq);
        float prevIn = b[off], prevOut = b[off];
        for (int i = 1; i < n; i++) {
            float in = b[off + i];
            prevOut = a * (prevOut + in - prevIn);
            prevIn = in;
            b[off + i] = prevOut;
        }
    }

    /** Band-pass = low-pass above the band then high-pass below it (Q widens the band). */
    private static void bandpass(float[] b, int off, int n, float freq, float q) {
        float spread = Math.max(1.05f, 1f + 0.6f / Math.max(0.1f, q));
        lowpass(b, off, n, freq * spread);
        highpass(b, off, n, freq / spread);
    }

    // =====================================================================
    //  Playback
    // =====================================================================

    private static void play(Render r) {
        if (!Theme.sound()) return;
        try {
            EXEC.execute(() -> {
                AudioTrack t = null;
                try {
                    float[] mix = r.run();
                    short[] pcm = toPcm(mix);
                    int min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT);
                    int bytes = Math.max(min, 8192);
                    t = makeTrack(bytes);
                    if (t == null) return;
                    t.play();
                    // STREAM mode: write() blocks while the clip drains through the buffer.
                    int off = 0;
                    while (off < pcm.length) {
                        int wrote = t.write(pcm, off, pcm.length - off);
                        if (wrote <= 0) break;
                        off += wrote;
                    }
                    // Let the tail buffered in the HAL finish before stopping.
                    long ms = (pcm.length * 1000L / SR) + 60;
                    try { Thread.sleep(Math.min(ms, 4000)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    try { t.stop(); } catch (Throwable ignored) {}
                } catch (Throwable ignored) {
                    // never let an audio failure crash the app
                } finally {
                    if (t != null) { try { t.release(); } catch (Throwable ignored) {} }
                }
            });
        } catch (Throwable ignored) {
            // executor may be shut down
        }
    }

    /** Clamp the float mix to 16-bit PCM, applying master gain with hard limiting. */
    private static short[] toPcm(float[] mix) {
        short[] out = new short[mix.length];
        for (int i = 0; i < mix.length; i++) {
            float v = mix[i] * MASTER;
            if (v > 1f) v = 1f; else if (v < -1f) v = -1f;
            out[i] = (short) (v * 32767f);
        }
        return out;
    }

    /**
     * Build a one-shot STREAM AudioTrack. STREAM (vs STATIC) is far more reliable for these
     * short synthesised clips across devices — we play() then block-write the PCM. Uses the
     * Builder on API >= 23 (within minSdk 28) and falls back to the deprecated constructor.
     */
    private static AudioTrack makeTrack(int bufferBytes) {
        try {
            return new AudioTrack.Builder()
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SR)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (Throwable t) {
            try {
                return new AudioTrack(AudioManager.STREAM_MUSIC, SR,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        bufferBytes, AudioTrack.MODE_STREAM);
            } catch (Throwable t2) {
                return null;
            }
        }
    }
}
