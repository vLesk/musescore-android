package io.github.vlesk.stave.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.SparseArray;

/**
 * Low-latency note preview: a small additive "soft piano" synthesizer on AudioTrack, so that
 * tapping keys or the staff gives immediate audible feedback (MediaPlayer MIDI playback has too
 * much start-up latency for that).
 */
public final class Synth {
    private static final int RATE = 22050;
    private final SparseArray<short[]> cache = new SparseArray<short[]>();
    private AudioTrack[] tracks = new AudioTrack[6];
    private int next;
    public boolean enabled = true;

    private short[] render(int pitch) {
        short[] cached = cache.get(pitch);
        if (cached != null) return cached;
        double f = 440.0 * Math.pow(2, (pitch - 69) / 12.0);
        int n = (int) (RATE * 0.9);
        short[] pcm = new short[n];
        double[] amps = {1.0, 0.45, 0.22, 0.12, 0.06};
        double decay = 3.2 + (pitch - 60) * 0.04;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double env = Math.min(1, t / 0.004) * Math.exp(-t * decay);
            if (i > n - 600) env *= (n - i) / 600.0;
            double v = 0;
            for (int h = 0; h < amps.length; h++) {
                double hf = f * (h + 1);
                if (hf > RATE / 2.2) break;
                v += amps[h] * Math.sin(2 * Math.PI * hf * t) * Math.exp(-t * h * 1.5);
            }
            pcm[i] = (short) Math.max(-32767, Math.min(32767, v * env * 9000));
        }
        cache.put(pitch, pcm);
        return pcm;
    }

    public void play(final int pitch) {
        if (!enabled || pitch < 0 || pitch > 127) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    playNow(pitch);
                } catch (Throwable ignored) {
                    // audio unavailable: previews are best effort
                }
            }
        }, "note-preview").start();
    }

    private synchronized void playNow(int pitch) {
        short[] pcm = render(pitch);
        int slot = next++ % tracks.length;
        AudioTrack old = tracks[slot];
        if (old != null) {
            try {
                old.stop();
            } catch (IllegalStateException ignored) {
            }
            old.release();
        }
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.length * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        t.write(pcm, 0, pcm.length);
        t.play();
        tracks[slot] = t;
    }

    public synchronized void release() {
        for (int i = 0; i < tracks.length; i++) {
            if (tracks[i] != null) {
                tracks[i].release();
                tracks[i] = null;
            }
        }
    }
}
