package io.github.vlesk.stave.audio;

import io.github.vlesk.stave.model.Measure;
import io.github.vlesk.stave.model.Part;
import io.github.vlesk.stave.model.Score;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Standard MIDI file (format 1) export, also used for playback through the platform synthesizer. */
public final class MidiWriter {
    private MidiWriter() {}

    private static final class Ev {
        int tick;
        int order; // note-offs before note-ons at the same tick
        byte[] data;

        Ev(int tick, int order, byte... data) {
            this.tick = tick;
            this.order = order;
            this.data = data;
        }
    }

    public static byte[] write(Score s, Timeline tl, double tempoScale) {
        List<List<Ev>> tracks = new ArrayList<List<Ev>>();
        // conductor track: tempo + time signatures
        List<Ev> cond = new ArrayList<Ev>();
        int lastTempo = -1;
        int lastN = -1, lastD = -1;
        for (Timeline.MeasurePlay mp : tl.plays) {
            int tempo = (int) Math.round(mp.tempo * tempoScale);
            if (tempo != lastTempo) {
                int usPerQuarter = (int) (60000000L / Math.max(1, tempo));
                cond.add(new Ev(mp.startTick, 0, (byte) 0xFF, (byte) 0x51, (byte) 3,
                        (byte) (usPerQuarter >> 16), (byte) (usPerQuarter >> 8), (byte) usPerQuarter));
                lastTempo = tempo;
            }
            Measure m = s.measures.get(mp.measure);
            if (m.timeN != lastN || m.timeD != lastD) {
                int dd = 0;
                while ((1 << dd) < m.timeD) dd++;
                cond.add(new Ev(mp.startTick, 0, (byte) 0xFF, (byte) 0x58, (byte) 4, (byte) m.timeN, (byte) dd, (byte) 24, (byte) 8));
                lastN = m.timeN;
                lastD = m.timeD;
            }
        }
        tracks.add(cond);
        for (int p = 0; p < s.parts.size(); p++) {
            Part part = s.parts.get(p);
            List<Ev> tr = new ArrayList<Ev>();
            int ch = tl.partChannel[p];
            byte[] name = part.name.getBytes();
            byte[] meta = new byte[3 + name.length];
            meta[0] = (byte) 0xFF;
            meta[1] = 0x03;
            meta[2] = (byte) name.length;
            System.arraycopy(name, 0, meta, 3, name.length);
            tr.add(new Ev(0, 0, meta));
            if (ch != 9) tr.add(new Ev(0, 0, (byte) (0xC0 | ch), (byte) part.program));
            tr.add(new Ev(0, 0, (byte) (0xB0 | ch), (byte) 7, (byte) Math.min(127, part.volume)));
            tr.add(new Ev(0, 0, (byte) (0xB0 | ch), (byte) 10, (byte) part.pan));
            tracks.add(tr);
        }
        for (Timeline.NoteEvent e : tl.events) {
            List<Ev> tr = tracks.get(e.track + 1);
            tr.add(new Ev(e.startTick, 1, (byte) (0x90 | e.channel), (byte) e.pitch, (byte) e.velocity));
            tr.add(new Ev(e.startTick + e.lengthTicks, 0, (byte) (0x80 | e.channel), (byte) e.pitch, (byte) 0));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeStr(out, "MThd");
        write32(out, 6);
        write16(out, 1);
        write16(out, tracks.size());
        write16(out, 480);
        Comparator<Ev> cmp = new Comparator<Ev>() {
            @Override
            public int compare(Ev a, Ev b) {
                if (a.tick != b.tick) return a.tick < b.tick ? -1 : 1;
                return a.order - b.order;
            }
        };
        for (List<Ev> tr : tracks) {
            Collections.sort(tr, cmp);
            ByteArrayOutputStream tb = new ByteArrayOutputStream();
            int last = 0;
            for (Ev e : tr) {
                writeVar(tb, e.tick - last);
                last = e.tick;
                tb.write(e.data, 0, e.data.length);
            }
            writeVar(tb, 480);
            tb.write(0xFF);
            tb.write(0x2F);
            tb.write(0);
            writeStr(out, "MTrk");
            write32(out, tb.size());
            byte[] b = tb.toByteArray();
            out.write(b, 0, b.length);
        }
        return out.toByteArray();
    }

    /** A tiny MIDI file playing one chord (used for previews when no low-latency synth is available). */
    public static byte[] single(int program, int[] pitches, int velocity, int ms) {
        ByteArrayOutputStream tb = new ByteArrayOutputStream();
        tb.write(0);
        tb.write(0xC0);
        tb.write(program);
        for (int p : pitches) {
            tb.write(0);
            tb.write(0x90);
            tb.write(p);
            tb.write(velocity);
        }
        int ticks = ms * 480 / 500;
        for (int i = 0; i < pitches.length; i++) {
            writeVar(tb, i == 0 ? ticks : 0);
            tb.write(0x80);
            tb.write(pitches[i]);
            tb.write(0);
        }
        tb.write(0);
        tb.write(0xFF);
        tb.write(0x2F);
        tb.write(0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeStr(out, "MThd");
        write32(out, 6);
        write16(out, 0);
        write16(out, 1);
        write16(out, 480);
        writeStr(out, "MTrk");
        write32(out, tb.size());
        byte[] b = tb.toByteArray();
        out.write(b, 0, b.length);
        return out.toByteArray();
    }

    private static void writeStr(ByteArrayOutputStream o, String s) {
        for (int i = 0; i < s.length(); i++) o.write(s.charAt(i));
    }

    private static void write32(ByteArrayOutputStream o, int v) {
        o.write(v >>> 24);
        o.write(v >>> 16);
        o.write(v >>> 8);
        o.write(v);
    }

    private static void write16(ByteArrayOutputStream o, int v) {
        o.write(v >>> 8);
        o.write(v);
    }

    private static void writeVar(ByteArrayOutputStream o, int v) {
        if (v < 0) v = 0;
        int buf = v & 0x7F;
        while ((v >>= 7) > 0) {
            buf <<= 8;
            buf |= ((v & 0x7F) | 0x80);
        }
        while (true) {
            o.write(buf & 0xFF);
            if ((buf & 0x80) != 0) buf >>>= 8;
            else break;
        }
    }
}
