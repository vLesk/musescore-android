package io.github.vlesk.stave.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import io.github.vlesk.stave.model.Score;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Score playback through the platform's built-in General MIDI synthesizer (Sonivox EAS via
 * MediaPlayer). Reports the score position being played so the view can follow along.
 */
public final class Player {
    public interface Listener {
        void onPosition(int measure, int tick);

        void onStopped();

        void onError(String msg);
    }

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer mp;
    private Timeline timeline;
    private Listener listener;
    private double tempoScale = 1.0;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (mp == null) return;
            try {
                double ms = mp.getCurrentPosition() * tempoScale;
                int[] pos = timeline.positionAt(ms);
                if (pos != null && listener != null) listener.onPosition(pos[0], pos[1]);
            } catch (IllegalStateException ignored) {
            }
            handler.postDelayed(this, 40);
        }
    };

    public Player(Context c) {
        ctx = c.getApplicationContext();
    }

    public boolean isPlaying() {
        return mp != null;
    }

    public void setTempoScale(double s) {
        tempoScale = Math.max(0.25, Math.min(3, s));
    }

    public double getTempoScale() {
        return tempoScale;
    }

    public void play(Score score, int fromMeasure, int fromTick, Listener l) {
        stop();
        listener = l;
        try {
            timeline = Timeline.build(score);
            byte[] midi = MidiWriter.write(score, timeline, tempoScale);
            File f = new File(ctx.getCacheDir(), "playback.mid");
            FileOutputStream out = new FileOutputStream(f);
            out.write(midi);
            out.close();
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setDataSource(f.getAbsolutePath());
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer m) {
                    stop();
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    Listener li = listener;
                    stop();
                    if (li != null) li.onError("error " + what);
                    return true;
                }
            });
            mp.prepare();
            int startMs = (int) (timeline.msAt(fromMeasure, fromTick) / tempoScale);
            if (startMs > 0) mp.seekTo(startMs);
            mp.start();
            handler.post(tick);
        } catch (Exception e) {
            Listener li = listener;
            stop();
            if (li != null) li.onError(e.getMessage());
        }
    }

    public void stop() {
        handler.removeCallbacks(tick);
        if (mp != null) {
            try {
                mp.stop();
            } catch (IllegalStateException ignored) {
            }
            mp.release();
            mp = null;
            Listener l = listener;
            listener = null;
            if (l != null) l.onStopped();
        }
    }
}
