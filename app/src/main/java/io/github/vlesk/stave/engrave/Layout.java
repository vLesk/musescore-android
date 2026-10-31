package io.github.vlesk.stave.engrave;

import io.github.vlesk.stave.model.Articulation;
import io.github.vlesk.stave.model.Chord;
import io.github.vlesk.stave.model.ChordRest;
import io.github.vlesk.stave.model.ChordSymbols;
import io.github.vlesk.stave.model.Clef;
import io.github.vlesk.stave.model.Duration;
import io.github.vlesk.stave.model.Dynamics;
import io.github.vlesk.stave.model.Measure;
import io.github.vlesk.stave.model.Note;
import io.github.vlesk.stave.model.Part;
import io.github.vlesk.stave.model.Pitch;
import io.github.vlesk.stave.model.Pos;
import io.github.vlesk.stave.model.Rest;
import io.github.vlesk.stave.model.Score;
import io.github.vlesk.stave.model.StaffMeasure;
import io.github.vlesk.stave.model.Tuplet;
import io.github.vlesk.stave.model.Voice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Engraving: turns a score into a {@link DisplayList}. Systems are reflowed to the available
 * width (the "continuous / fit to screen" view that works best on phones), or paginated for PDF.
 * All coordinates are in staff spaces with y pointing down; staff lines are at y = 0..4.
 */
public final class Layout {

    public static final class Options {
        /** Total width in staff spaces. */
        public float width = 60;
        public float marginX = 1.5f;
        public float marginTop = 2f;
        public boolean showTitle = true;
        /** Page height in staff spaces for paginated output (0 = one continuous page). */
        public float pageHeight;
        public float marginBottom = 3f;
        public float systemGap = 5f;
    }

    // --------------------------------------------------------------------------- data

    private static final class Col {
        int tick;
        float accPad;
        float minW;
        float spring;
        float width;
    }

    private static final class MPrep {
        List<Col> cols = new ArrayList<Col>();
        float natural; // without headers
        boolean showTime, showKey;
        int oldKey;
        int[] clefChange;
        float midHeader;
        boolean allRests;
    }

    private static final class ChordGeom {
        Chord chord;
        int voice, tick, measure, staff;
        float x;
        int[] lines;
        float[] noteDx;
        boolean up;
        boolean forcedDir;
        int head;
        float headW;
        float stemX, stemEndY;
        float noteTopY, noteBottomY;
        boolean beamed;
        boolean hasStem;
    }

    private static final class NoteRec {
        int system;
        float x, y, headW;
        boolean tieUp;
        int staff;
    }

    private static final class StaffGen {
        final List<DisplayList.Item> items = new ArrayList<DisplayList.Item>();
        final List<DisplayList.Hit> hits = new ArrayList<DisplayList.Hit>();
        float top = 0, bottom = 4;

        void ext(float y0, float y1) {
            if (y0 < top) top = y0;
            if (y1 > bottom) bottom = y1;
        }
    }

    private final Score s;
    private final TextMetrics tm;
    private final Options o;
    private final DisplayList dl = new DisplayList();
    private final IdentityHashMap<Note, Integer> accidental = new IdentityHashMap<Note, Integer>();
    private final IdentityHashMap<Note, NoteRec> noteRecs = new IdentityHashMap<Note, NoteRec>();
    private final List<MPrep> preps = new ArrayList<MPrep>();
    private final int staffCount;

    private static final float HEAD_W = Sym.width(Sym.noteheadBlack);

    private Layout(Score s, TextMetrics tm, Options o) {
        this.s = s;
        this.tm = tm;
        this.o = o;
        this.staffCount = s.staffCount();
    }

    public static DisplayList layout(Score s, TextMetrics tm, Options o) {
        Layout l = new Layout(s, tm, o);
        l.run();
        return l.dl;
    }

    // --------------------------------------------------------------------------- helpers

    private DisplayList.Item glyph(List<DisplayList.Item> out, int g, float x, float y) {
        DisplayList.Item it = new DisplayList.Item();
        it.kind = DisplayList.GLYPH;
        it.glyph = g;
        it.x = x;
        it.y = y;
        it.w = 1f;
        out.add(it);
        return it;
    }

    private DisplayList.Item line(List<DisplayList.Item> out, float x1, float y1, float x2, float y2, float w) {
        DisplayList.Item it = new DisplayList.Item();
        it.kind = DisplayList.LINE;
        it.x = x1;
        it.y = y1;
        it.x2 = x2;
        it.y2 = y2;
        it.w = w;
        out.add(it);
        return it;
    }

    private DisplayList.Item text(List<DisplayList.Item> out, String t, int font, float size, float x, float y, int align) {
        DisplayList.Item it = new DisplayList.Item();
        it.kind = DisplayList.TEXT;
        it.text = t;
        it.font = font;
        it.w = size;
        it.x = x;
        it.y = y;
        it.align = align;
        out.add(it);
        return it;
    }

    private DisplayList.Item poly(List<DisplayList.Item> out, float[] pts) {
        DisplayList.Item it = new DisplayList.Item();
        it.kind = DisplayList.POLY;
        it.pts = pts;
        out.add(it);
        return it;
    }

    private static float gTop(int g, float y) {
        return y - Sym.BBOX[g][3];
    }

    private static float gBottom(int g, float y) {
        return y - Sym.BBOX[g][1];
    }

    private static int clefGlyph(int clef) {
        switch (clef) {
            case Clef.BASS:
                return Sym.fClef;
            case Clef.BASS_8VB:
                return Sym.fClef8vb;
            case Clef.ALTO:
            case Clef.TENOR:
                return Sym.cClef;
            case Clef.TREBLE_8VB:
                return Sym.gClef8vb;
            case Clef.TREBLE_8VA:
                return Sym.gClef8va;
            case Clef.PERC:
                return Sym.unpitchedPercussionClef1;
            default:
                return Sym.gClef;
        }
    }

    private boolean isDrums(int staff) {
        return s.partOfStaff(staff).drums;
    }

    /** Drum kit display positions (staff line) for General MIDI percussion pitches. */
    private static int drumLine(int pitch) {
        switch (pitch) {
            case 35:
            case 36:
                return 7;
            case 37:
            case 38:
            case 39:
            case 40:
                return 3;
            case 41:
                return 6;
            case 43:
                return 5;
            case 45:
            case 47:
                return 2;
            case 48:
            case 50:
                return 1;
            case 42:
            case 46:
                return -1;
            case 44:
                return 9;
            case 49:
            case 57:
            case 52:
            case 55:
                return -2;
            case 51:
            case 59:
            case 53:
                return 0;
            default:
                return 4;
        }
    }

    private static boolean drumX(int pitch) {
        return pitch == 42 || pitch == 44 || pitch == 46 || pitch == 49 || pitch == 51 || pitch == 52 || pitch == 53
                || pitch == 55 || pitch == 57 || pitch == 59;
    }

    public static int noteLine(int clef, boolean drums, Note n) {
        if (drums && clef == Clef.PERC) return drumLine(n.pitch);
        return Clef.line(clef, n.pitch, n.tpc);
    }

    private static float spring(int dt) {
        return 1.1f + 2.2f * (float) Math.pow(Math.max(dt, 30) / 480.0, 0.6);
    }

    private static int restGlyph(int type) {
        switch (type) {
            case Duration.WHOLE:
                return Sym.restWhole;
            case Duration.HALF:
                return Sym.restHalf;
            case Duration.QUARTER:
                return Sym.restQuarter;
            case Duration.EIGHTH:
                return Sym.rest8th;
            case Duration.N16:
                return Sym.rest16th;
            case Duration.N32:
                return Sym.rest32nd;
            default:
                return Sym.rest64th;
        }
    }

    private static int accGlyph(int alter) {
        switch (alter) {
            case -2:
                return Sym.accidentalDoubleFlat;
            case -1:
                return Sym.accidentalFlat;
            case 1:
                return Sym.accidentalSharp;
            case 2:
                return Sym.accidentalDoubleSharp;
            default:
                return Sym.accidentalNatural;
        }
    }

    private int nonEmptyVoices(StaffMeasure sm) {
        int n = 0;
        for (Voice v : sm.voices) if (!v.isEmpty()) n++;
        return n;
    }

    // --------------------------------------------------------------------------- accidentals

    private boolean tiedFromPrev(int m, int st, int v, int idx, int pitch) {
        Voice voice = s.measures.get(m).staves.get(st).voices[v];
        ChordRest prev = null;
        if (idx > 0) prev = voice.items.get(idx - 1);
        else if (m > 0) {
            Voice pv = s.measures.get(m - 1).staves.get(st).voices[v];
            if (!pv.isEmpty()) prev = pv.items.get(pv.items.size() - 1);
        }
        if (!(prev instanceof Chord)) return false;
        for (Note n : ((Chord) prev).notes) if (n.pitch == pitch && n.tieForward) return true;
        return false;
    }

    private void computeAccidentals() {
        for (int m = 0; m < s.measures.size(); m++) {
            Measure mm = s.measures.get(m);
            for (int st = 0; st < staffCount; st++) {
                if (isDrums(st)) continue;
                // collect (tick, note) in time order across voices
                List<Object[]> evs = new ArrayList<Object[]>();
                for (int v = 0; v < StaffMeasure.VOICES; v++) {
                    Voice voice = mm.staves.get(st).voices[v];
                    int[] starts = voice.starts();
                    for (int i = 0; i < voice.items.size(); i++) {
                        ChordRest cr = voice.items.get(i);
                        if (!(cr instanceof Chord)) continue;
                        for (Note n : ((Chord) cr).notes) {
                            evs.add(new Object[]{starts[i], n, tiedFromPrev(m, st, v, i, n.pitch)});
                        }
                    }
                }
                java.util.Collections.sort(evs, new java.util.Comparator<Object[]>() {
                    @Override
                    public int compare(Object[] a, Object[] b) {
                        return ((Integer) a[0]).compareTo((Integer) b[0]);
                    }
                });
                Map<Integer, Integer> state = new HashMap<Integer, Integer>();
                for (Object[] e : evs) {
                    Note n = (Note) e[1];
                    boolean tied = (Boolean) e[2];
                    int dia = Pitch.diatonic(n.pitch, n.tpc);
                    int step = Pitch.step(n.tpc);
                    int alter = Pitch.alter(n.tpc);
                    Integer cur = state.get(dia);
                    int curAlter = cur != null ? cur : Pitch.keyAlter(mm.keyFifths, step);
                    if (tied) {
                        state.put(dia, alter);
                        continue;
                    }
                    if (alter != curAlter || n.forceAccidental) accidental.put(n, accGlyph(alter));
                    state.put(dia, alter);
                }
            }
        }
    }

    // --------------------------------------------------------------------------- measure preparation

    private int keySigWidthCount(int key, int oldKey) {
        if (key == 0) return Math.abs(oldKey);
        return Math.abs(key);
    }

    private float keySigWidth(int key, int oldKey) {
        int n = keySigWidthCount(key, oldKey);
        return n == 0 ? 0 : n * 1.0f + 0.6f;
    }

    private float timeSigWidth(Measure m) {
        if (m.timeSymbol) return Sym.width(Sym.timeSigCommon) + 1.0f;
        float wn = digitsWidth(m.timeN), wd = digitsWidth(m.timeD);
        return Math.max(wn, wd) + 1.0f;
    }

    private static float digitsWidth(int v) {
        String str = String.valueOf(v);
        float w = 0;
        for (int i = 0; i < str.length(); i++) w += Sym.width(Sym.timeSig0 + (str.charAt(i) - '0'));
        return w;
    }

    private float clefWidth(int clef) {
        return Sym.width(clefGlyph(clef)) + 0.9f;
    }

    private void prepareMeasures() {
        for (int m = 0; m < s.measures.size(); m++) {
            Measure mm = s.measures.get(m);
            MPrep p = new MPrep();
            Measure prev = m > 0 ? s.measures.get(m - 1) : null;
            p.showTime = prev == null || prev.timeN != mm.timeN || prev.timeD != mm.timeD || prev.timeSymbol != mm.timeSymbol;
            p.showKey = prev == null ? mm.keyFifths != 0 : prev.keyFifths != mm.keyFifths;
            p.oldKey = prev == null ? 0 : prev.keyFifths;
            p.clefChange = new int[staffCount];
            float clefW = 0;
            for (int st = 0; st < staffCount; st++) {
                p.clefChange[st] = m > 0 ? mm.staves.get(st).clef : -1;
                if (p.clefChange[st] >= 0) clefW = Math.max(clefW, clefWidth(p.clefChange[st]) * 0.8f);
            }
            p.midHeader = clefW + (p.showKey ? keySigWidth(mm.keyFifths, p.oldKey) : 0) + (p.showTime ? timeSigWidth(mm) : 0);
            // columns
            TreeSet<Integer> ticks = new TreeSet<Integer>();
            boolean allRests = true;
            for (int st = 0; st < staffCount; st++) {
                for (Voice v : mm.staves.get(st).voices) {
                    int[] starts = v.starts();
                    for (int i = 0; i < v.items.size(); i++) {
                        ChordRest cr = v.items.get(i);
                        if (cr instanceof Rest && ((Rest) cr).measureRest) continue;
                        allRests = false;
                        ticks.add(starts[i]);
                    }
                }
            }
            p.allRests = allRests;
            if (ticks.isEmpty()) ticks.add(0);
            Integer[] tk = ticks.toArray(new Integer[0]);
            for (int i = 0; i < tk.length; i++) {
                Col c = new Col();
                c.tick = tk[i];
                int next = i + 1 < tk.length ? tk[i + 1] : mm.ticks();
                c.spring = spring(next - c.tick);
                float right = HEAD_W + 0.5f;
                float acc = 0;
                float lyricW = 0;
                for (int st = 0; st < staffCount; st++) {
                    int clef = s.clefAt(st, m);
                    for (Voice v : mm.staves.get(st).voices) {
                        int idx = v.indexAt(c.tick);
                        if (idx < 0) continue;
                        ChordRest cr = v.items.get(idx);
                        if (cr.dots > 0) right = Math.max(right, HEAD_W + 0.5f + 0.5f * cr.dots + 0.3f);
                        if (cr instanceof Chord) {
                            Chord ch = (Chord) cr;
                            int accs = 0;
                            boolean second = false;
                            int prevLine = Integer.MIN_VALUE;
                            for (Note n : ch.notes) {
                                if (accidental.containsKey(n)) accs++;
                                int ln = noteLine(clef, isDrums(st), n);
                                if (Math.abs(ln - prevLine) == 1) second = true;
                                prevLine = ln;
                            }
                            if (accs > 0) acc = Math.max(acc, 0.3f + Math.min(accs, 3) * 1.15f);
                            if (second) right += HEAD_W;
                            if (ch.lyric != null) lyricW = Math.max(lyricW, tm.width(ch.lyric, TextMetrics.FONT_TEXT, 1.9f) + 0.8f);
                        }
                        if (cr.chordSymbol != null) {
                            lyricW = Math.max(lyricW, tm.width(ChordSymbols.display(cr.chordSymbol), TextMetrics.FONT_TEXT, 2.2f) + 0.8f);
                        }
                    }
                }
                c.accPad = acc;
                c.minW = Math.max(right, lyricW);
                c.width = c.accPad + Math.max(c.spring, c.minW);
                p.cols.add(c);
            }
            float w = 1.1f;
            for (Col c : p.cols) w += c.width;
            w += 0.4f;
            if (allRests) w = Math.max(w, 6.5f);
            if (mm.startRepeat) w += 1.3f;
            if (mm.endBarline == Measure.BAR_END_REPEAT) w += 1.3f;
            p.natural = w;
            preps.add(p);
        }
    }

    // --------------------------------------------------------------------------- main

    private float namesWidth(boolean first) {
        if (s.parts.size() == 1 && s.parts.get(0).staves.size() == 1 && !first) return 0;
        float w = 0;
        for (Part p : s.parts) {
            String n = first ? p.name : p.shortName;
            if (n == null || n.isEmpty()) continue;
            w = Math.max(w, tm.width(n, TextMetrics.FONT_TEXT, 2.0f));
        }
        if (s.parts.size() == 1 && s.parts.get(0).staves.size() == 1) {
            // single-staff scores: no instrument names, like MuseScore's default for solo parts
            return 0;
        }
        return w > 0 ? w + 1.0f : 0;
    }

    private float bracketWidth() {
        float w = 0;
        if (s.parts.size() > 1) w += 1.2f;
        for (Part p : s.parts) if (p.staves.size() > 1) {
            w += 1.6f;
            break;
        }
        return w;
    }

    private float systemHeaderWidth(int m) {
        float clefW = 0;
        for (int st = 0; st < staffCount; st++) clefW = Math.max(clefW, clefWidth(s.clefAt(st, m)));
        Measure mm = s.measures.get(m);
        float w = 0.6f + clefW + keySigWidth(mm.keyFifths, 0) + (mm.keyFifths == 0 ? 0 : 0);
        if (preps.get(m).showTime) w += timeSigWidth(mm);
        return w;
    }

    private void run() {
        computeAccidentals();
        prepareMeasures();
        dl.width = o.width;
        float y = o.marginTop;
        if (o.showTitle) y = titleBlock(y);

        int m = 0;
        int sysIdx = 0;
        int page = 0;
        while (m < s.measures.size()) {
            boolean first = sysIdx == 0;
            float left = o.marginX + namesWidth(first) + bracketWidth();
            float header = systemHeaderWidth(m);
            float avail = o.width - o.marginX - left - header;
            int end = m;
            float used = preps.get(m).natural;
            while (end + 1 < s.measures.size()) {
                MPrep np = preps.get(end + 1);
                float add = np.natural + np.midHeader;
                if (used + add > avail) break;
                used += add;
                end++;
            }
            boolean last = end == s.measures.size() - 1;
            float stretchTotal = 0;
            for (int i = m; i <= end; i++) for (Col c : preps.get(i).cols) stretchTotal += c.spring;
            float extra = avail - used;
            float ratio = 0;
            if (extra > 0 && stretchTotal > 0 && !(last && used < avail * 0.5f)) ratio = extra / stretchTotal;
            if (extra < 0 && stretchTotal > 0) ratio = Math.max(-0.5f, extra / stretchTotal);
            float sysY = layoutSystem(sysIdx, m, end, left, header, ratio, y);
            DisplayList.SystemInfo si = dl.systems.get(dl.systems.size() - 1);
            if (o.pageHeight > 0) {
                float h = si.bottom - si.top;
                float pageBottom = (page + 1) * o.pageHeight - o.marginBottom;
                if (si.bottom > pageBottom && si.top > page * o.pageHeight + o.marginTop + 1) {
                    // move system to the next page
                    page++;
                    float target = page * o.pageHeight + o.marginTop - (si.top - si.y);
                    float dy = target - si.y;
                    shiftSystem(sysIdx, dy);
                    sysY = si.bottom;
                }
            }
            y = sysY + o.systemGap;
            m = end + 1;
            sysIdx++;
        }
        drawTies();
        if (o.pageHeight > 0) dl.height = (page + 1) * o.pageHeight;
        else dl.height = y + 2;
    }

    private int systemItemStart = 0, systemHitStart = 0;

    private void shiftSystem(int sysIdx, float dy) {
        DisplayList.SystemInfo si = dl.systems.get(sysIdx);
        si.y += dy;
        si.top += dy;
        si.bottom += dy;
        for (int i = 0; i < si.staffY.length; i++) si.staffY[i] += dy;
        for (int i = systemItemStart; i < dl.items.size(); i++) {
            DisplayList.Item it = dl.items.get(i);
            it.y += dy;
            it.y2 += dy;
            if (it.pts != null) for (int k = 1; k < it.pts.length; k += 2) it.pts[k] += dy;
        }
        for (int i = systemHitStart; i < dl.hits.size(); i++) {
            DisplayList.Hit h = dl.hits.get(i);
            h.y0 += dy;
            h.y1 += dy;
        }
        for (NoteRec r : noteRecs.values()) if (r.system == sysIdx) r.y += dy;
    }

    private float titleBlock(float y) {
        List<DisplayList.Item> out = dl.items;
        float cx = o.width / 2;
        boolean any = false;
        if (s.title != null && !s.title.isEmpty()) {
            y += 4.2f;
            text(out, s.title, TextMetrics.FONT_TEXT, 4.4f, cx, y, DisplayList.ALIGN_CENTER);
            any = true;
        }
        if (s.subtitle != null && !s.subtitle.isEmpty()) {
            y += 2.8f;
            text(out, s.subtitle, TextMetrics.FONT_TEXT, 2.6f, cx, y, DisplayList.ALIGN_CENTER);
            any = true;
        }
        boolean comp = s.composer != null && !s.composer.isEmpty();
        boolean lyr = s.lyricist != null && !s.lyricist.isEmpty();
        if (comp || lyr) {
            y += 3.0f;
            if (comp) text(out, s.composer, TextMetrics.FONT_TEXT, 2.0f, o.width - o.marginX, y, DisplayList.ALIGN_RIGHT);
            if (lyr) text(out, s.lyricist, TextMetrics.FONT_TEXT, 2.0f, o.marginX, y, DisplayList.ALIGN_LEFT);
            any = true;
        }
        return any ? y + 3.5f : y;
    }

    // --------------------------------------------------------------------------- system

    private float layoutSystem(int sysIdx, int m0, int m1, float left, float header, float ratio, float y) {
        systemItemStart = dl.items.size();
        systemHitStart = dl.hits.size();
        DisplayList.SystemInfo si = new DisplayList.SystemInfo();
        si.firstMeasure = m0;
        si.lastMeasure = m1;
        si.x0 = left;
        si.x1 = o.width - o.marginX;
        // horizontal positions
        float x = left + header;
        float[] mx = new float[m1 - m0 + 1];
        for (int m = m0; m <= m1; m++) {
            MPrep p = preps.get(m);
            Measure mm = s.measures.get(m);
            DisplayList.MeasureInfo mi = new DisplayList.MeasureInfo();
            mi.system = sysIdx;
            float mid = m == m0 ? 0 : p.midHeader;
            mi.x = x;
            float cx = x + mid + 1.1f + (mm.startRepeat ? 1.3f : 0);
            mi.contentX = cx;
            mi.colTicks = new int[p.cols.size()];
            mi.colX = new float[p.cols.size()];
            for (int i = 0; i < p.cols.size(); i++) {
                Col c = p.cols.get(i);
                float w = c.accPad + Math.max(c.spring, c.minW) + c.spring * ratio;
                w = Math.max(w, c.accPad + c.minW * 0.85f);
                mi.colTicks[i] = c.tick;
                mi.colX[i] = cx + c.accPad;
                cx += w;
            }
            float width = (cx + 0.4f + (mm.endBarline == Measure.BAR_END_REPEAT ? 1.3f : 0)) - x;
            if (p.allRests) {
                // measure-rest bars keep a minimum width and stretch like the others
                float natural = p.natural + mid;
                float springSum = 0;
                for (Col c : p.cols) springSum += c.spring;
                width = Math.max(width, natural + springSum * Math.max(ratio, 0));
            }
            mi.width = width;
            while (dl.measures.size() <= m) dl.measures.add(null);
            dl.measures.set(m, mi);
            mx[m - m0] = x;
            x += width;
        }
        // the last measure ends exactly at the system end when justified
        if (ratio > 0) {
            DisplayList.MeasureInfo last = dl.measures.get(m1);
            float end = last.x + last.width;
            if (Math.abs(end - si.x1) < 3f) last.width = si.x1 - last.x;
        }
        DisplayList.MeasureInfo lastMi = dl.measures.get(m1);
        si.x1 = lastMi.x + lastMi.width;

        // generate staves
        StaffGen[] gens = new StaffGen[staffCount];
        for (int st = 0; st < staffCount; st++) {
            gens[st] = new StaffGen();
            genStaff(gens[st], sysIdx, st, m0, m1, left, si);
        }
        // vertical placement
        si.staffY = new float[staffCount];
        float cur = y + Math.max(0, -gens[0].top);
        if (sysIdx == 0 && hasTempoOrRehearsal(m0, m1)) cur += 0.5f;
        for (int st = 0; st < staffCount; st++) {
            if (st > 0) {
                boolean samePart = s.partOfStaff(st) == s.partOfStaff(st - 1);
                float minDist = samePart ? 6.5f : 7.5f;
                float need = (gens[st - 1].bottom - 4) + (-gens[st].top) + 1.2f;
                cur = si.staffY[st - 1] + 4 + Math.max(minDist - 4, need);
            }
            si.staffY[st] = cur;
        }
        si.y = si.staffY[0];
        si.top = si.staffY[0] + gens[0].top;
        si.bottom = si.staffY[staffCount - 1] + gens[staffCount - 1].bottom;
        // copy items with offset
        for (int st = 0; st < staffCount; st++) {
            float oy = si.staffY[st];
            for (DisplayList.Item it : gens[st].items) {
                it.y += oy;
                it.y2 += oy;
                if (it.pts != null) for (int k = 1; k < it.pts.length; k += 2) it.pts[k] += oy;
                dl.items.add(it);
            }
            for (DisplayList.Hit h : gens[st].hits) {
                h.y0 += oy;
                h.y1 += oy;
                dl.hits.add(h);
            }
        }
        // translate note records of this system
        for (Map.Entry<Note, NoteRec> e : pendingRecs.entrySet()) {
            NoteRec r = e.getValue();
            r.y += si.staffY[r.staff];
            noteRecs.put(e.getKey(), r);
        }
        pendingRecs.clear();
        systemDecorations(sysIdx, si, m0, m1, left);
        dl.systems.add(si);
        return si.bottom;
    }

    private boolean hasTempoOrRehearsal(int m0, int m1) {
        for (int m = m0; m <= m1; m++) {
            if (s.measures.get(m).tempo > 0 || s.measures.get(m).rehearsal != null) return true;
        }
        return false;
    }

    private final IdentityHashMap<Note, NoteRec> pendingRecs = new IdentityHashMap<Note, NoteRec>();

    // --------------------------------------------------------------------------- staff generation

    private void drawKeySig(StaffGen g, int clef, int key, int oldKey, float x) {
        if (clef == Clef.PERC) return;
        if (key == 0 && oldKey != 0) {
            int[] lines = Clef.keySigLines(clef, oldKey > 0);
            for (int i = 0; i < Math.abs(oldKey); i++) {
                glyph(g.items, Sym.accidentalNatural, x + i * 1.0f, lines[i] * 0.5f);
            }
            return;
        }
        int[] lines = Clef.keySigLines(clef, key > 0);
        int g2 = key > 0 ? Sym.accidentalSharp : Sym.accidentalFlat;
        for (int i = 0; i < Math.abs(key); i++) {
            glyph(g.items, g2, x + i * 1.0f, lines[i] * 0.5f);
            g.ext(lines[i] * 0.5f - 1.5f, lines[i] * 0.5f + 1);
        }
    }

    private void drawTimeSig(StaffGen g, Measure mm, float x, float width) {
        if (mm.timeSymbol) {
            int gl = mm.timeD == 2 ? Sym.timeSigCutCommon : Sym.timeSigCommon;
            glyph(g.items, gl, x, 2f);
            return;
        }
        drawDigits(g, String.valueOf(mm.timeN), x, width, 1f);
        drawDigits(g, String.valueOf(mm.timeD), x, width, 3f);
    }

    private void drawDigits(StaffGen g, String str, float x, float width, float y) {
        float w = 0;
        for (int i = 0; i < str.length(); i++) w += Sym.width(Sym.timeSig0 + (str.charAt(i) - '0'));
        float cx = x + (width - 1.0f - w) / 2;
        for (int i = 0; i < str.length(); i++) {
            int gl = Sym.timeSig0 + (str.charAt(i) - '0');
            glyph(g.items, gl, cx, y);
            cx += Sym.width(gl);
        }
    }

    private void genStaff(StaffGen g, int sysIdx, int st, int m0, int m1, float left, DisplayList.SystemInfo si) {
        // staff lines
        for (int i = 0; i < 5; i++) {
            DisplayList.Item it = line(g.items, si.x0, i, si.x1, i, Sym.staffLineThickness);
            it.color = DisplayList.C_INK;
        }
        // system header
        float hx = left + 0.6f;
        int clef = s.clefAt(st, m0);
        glyph(g.items, clefGlyph(clef), hx, Clef.glyphLine(clef) * 0.5f);
        g.ext(-1.5f, 5.5f);
        hx += clefWidth(clef);
        Measure first = s.measures.get(m0);
        float maxClef = 0;
        for (int k = 0; k < staffCount; k++) maxClef = Math.max(maxClef, clefWidth(s.clefAt(k, m0)));
        hx = left + 0.6f + maxClef;
        if (first.keyFifths != 0) {
            drawKeySig(g, clef, first.keyFifths, 0, hx);
            hx += keySigWidth(first.keyFifths, 0);
        }
        if (preps.get(m0).showTime) drawTimeSig(g, first, hx, timeSigWidth(first));

        for (int m = m0; m <= m1; m++) {
            Measure mm = s.measures.get(m);
            MPrep p = preps.get(m);
            DisplayList.MeasureInfo mi = dl.measures.get(m);
            if (m > m0) {
                float x = mi.x + 0.4f;
                if (p.clefChange[st] >= 0) {
                    int c = p.clefChange[st];
                    DisplayList.Item it = glyph(g.items, clefGlyph(c), x, Clef.glyphLine(c) * 0.5f);
                    it.w = 0.8f;
                }
                float clefW = 0;
                for (int k = 0; k < staffCount; k++) if (p.clefChange[k] >= 0) clefW = Math.max(clefW, clefWidth(p.clefChange[k]) * 0.8f);
                x += clefW;
                if (p.showKey) {
                    drawKeySig(g, s.clefAt(st, m), mm.keyFifths, p.oldKey, x);
                    x += keySigWidth(mm.keyFifths, p.oldKey);
                }
                if (p.showTime) drawTimeSig(g, mm, x, timeSigWidth(mm));
            }
            genMeasureContent(g, sysIdx, st, m, mi);
        }
        genMarkings(g, sysIdx, st, m0, m1);
    }

    private void genMeasureContent(StaffGen g, int sysIdx, int st, int m, DisplayList.MeasureInfo mi) {
        Measure mm = s.measures.get(m);
        StaffMeasure sm = mm.staves.get(st);
        int clef = s.clefAt(st, m);
        boolean drums = isDrums(st);
        int voicesUsed = nonEmptyVoices(sm);
        for (int v = 0; v < StaffMeasure.VOICES; v++) {
            Voice voice = sm.voices[v];
            if (voice.isEmpty()) continue;
            int[] starts = voice.starts();
            List<ChordGeom> geoms = new ArrayList<ChordGeom>();
            ChordGeom[] byIdx = new ChordGeom[voice.items.size()];
            for (int i = 0; i < voice.items.size(); i++) {
                ChordRest cr = voice.items.get(i);
                if (cr instanceof Chord) {
                    ChordGeom cg = chordGeom((Chord) cr, clef, drums, dl.xAt(m, starts[i]), voicesUsed > 1, v);
                    cg.voice = v;
                    cg.tick = starts[i];
                    cg.measure = m;
                    cg.staff = st;
                    byIdx[i] = cg;
                    geoms.add(cg);
                }
            }
            // beams
            List<List<Integer>> groups = beamGroups(voice, starts, mm);
            for (List<Integer> grp : groups) {
                layoutBeam(g, grp, byIdx, voice, v);
            }
            for (int i = 0; i < voice.items.size(); i++) {
                ChordRest cr = voice.items.get(i);
                Pos pos = new Pos(m, st, v, starts[i]);
                if (cr instanceof Chord) {
                    emitChord(g, byIdx[i], pos, sysIdx);
                } else {
                    emitRest(g, (Rest) cr, pos, mi, voicesUsed > 1, v);
                }
            }
            emitTuplets(g, voice, starts, byIdx, m, v);
        }
    }

    // --------------------------------------------------------------------------- chords

    private ChordGeom chordGeom(Chord c, int clef, boolean drums, float x, boolean multiVoice, int voice) {
        ChordGeom cg = new ChordGeom();
        cg.chord = c;
        cg.x = x;
        int n = c.notes.size();
        cg.lines = new int[n];
        for (int i = 0; i < n; i++) cg.lines[i] = noteLine(clef, drums, c.notes.get(i));
        int minLine = Integer.MAX_VALUE, maxLine = Integer.MIN_VALUE;
        for (int l : cg.lines) {
            minLine = Math.min(minLine, l);
            maxLine = Math.max(maxLine, l);
        }
        if (c.stemDir != Chord.STEM_AUTO) {
            cg.up = c.stemDir == Chord.STEM_UP;
            cg.forcedDir = true;
        } else if (multiVoice) {
            cg.up = voice % 2 == 0;
            cg.forcedDir = true;
        } else {
            cg.up = (maxLine - 4) > (4 - minLine);
        }
        if (c.type == Duration.WHOLE) cg.head = Sym.noteheadWhole;
        else if (c.type == Duration.HALF) cg.head = Sym.noteheadHalf;
        else cg.head = Sym.noteheadBlack;
        cg.headW = Sym.width(cg.head);
        cg.hasStem = c.type >= Duration.HALF;
        cg.noteTopY = minLine * 0.5f;
        cg.noteBottomY = maxLine * 0.5f;
        computeNoteOffsets(cg);
        defaultStem(cg);
        return cg;
    }

    private void computeNoteOffsets(ChordGeom cg) {
        int n = cg.lines.length;
        cg.noteDx = new float[n];
        // notes sorted by pitch ascending -> lines descending (mostly). Work on index order by line.
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        final int[] lines = cg.lines;
        java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return lines[b] - lines[a]; // bottom (largest line) first
            }
        });
        if (cg.up) {
            boolean prevShifted = false;
            int prevLine = Integer.MIN_VALUE;
            for (int k = 0; k < n; k++) {
                int i = order[k];
                boolean shift = k > 0 && Math.abs(prevLine - lines[i]) == 1 && !prevShifted;
                cg.noteDx[i] = shift ? cg.headW - 0.05f : 0;
                prevShifted = shift;
                prevLine = lines[i];
            }
        } else {
            boolean prevShifted = false;
            int prevLine = Integer.MIN_VALUE;
            for (int k = n - 1; k >= 0; k--) {
                int i = order[k];
                boolean shift = k < n - 1 && Math.abs(prevLine - lines[i]) == 1 && !prevShifted;
                cg.noteDx[i] = shift ? -(cg.headW - 0.05f) : 0;
                prevShifted = shift;
                prevLine = lines[i];
            }
        }
    }

    private void defaultStem(ChordGeom cg) {
        float len = 3.5f;
        int flags = Math.max(0, cg.chord.type - Duration.EIGHTH);
        if (cg.chord.type >= Duration.N16) len += 0.5f * flags;
        if (cg.up) {
            cg.stemX = cg.x + Sym.STEM_UP_SE[Sym.noteheadBlack][0] - Sym.stemThickness / 2;
            if (cg.head == Sym.noteheadHalf) cg.stemX = cg.x + Sym.STEM_UP_SE[Sym.noteheadHalf][0] - Sym.stemThickness / 2;
            cg.stemEndY = Math.min(cg.noteTopY - len, 2f);
        } else {
            cg.stemX = cg.x + Sym.stemThickness / 2;
            cg.stemEndY = Math.max(cg.noteBottomY + len, 2f);
        }
    }

    private List<List<Integer>> beamGroups(Voice voice, int[] starts, Measure mm) {
        List<List<Integer>> groups = new ArrayList<List<Integer>>();
        boolean compound = mm.timeD == 8 && mm.timeN % 3 == 0;
        List<Integer> cur = new ArrayList<Integer>();
        int curGroup = -1;
        Tuplet curTuplet = null;
        // does the measure contain 16ths or shorter (affects grouping in simple meters)
        boolean shortNotes = false;
        for (ChordRest cr : voice.items) if (cr instanceof Chord && cr.type >= Duration.N16) shortNotes = true;
        int groupLen;
        if (compound) groupLen = 720;
        else if (mm.timeD == 4 && mm.timeN == 4 && !shortNotes) groupLen = 960;
        else if (mm.timeD == 2) groupLen = 960;
        else if (mm.timeD == 8) groupLen = shortNotes ? 240 : 720;
        else if (mm.timeD == 16) groupLen = 240;
        else groupLen = 480;
        if (mm.timeD == 8 && mm.timeN == 3) groupLen = 720;
        for (int i = 0; i < voice.items.size(); i++) {
            ChordRest cr = voice.items.get(i);
            boolean beamable = cr instanceof Chord && cr.type >= Duration.EIGHTH;
            int grp = cr.tuplet != null ? -2 : starts[i] / groupLen;
            boolean sameTuplet = cr.tuplet == curTuplet;
            if (!beamable || (!cur.isEmpty() && (grp != curGroup || !sameTuplet))) {
                if (cur.size() > 1) groups.add(cur);
                cur = new ArrayList<Integer>();
            }
            if (beamable) {
                if (cur.isEmpty()) {
                    curGroup = grp;
                    curTuplet = cr.tuplet;
                }
                cur.add(i);
            }
        }
        if (cur.size() > 1) groups.add(cur);
        return groups;
    }

    private void layoutBeam(StaffGen g, List<Integer> grp, ChordGeom[] byIdx, Voice voice, int v) {
        ChordGeom first = byIdx[grp.get(0)];
        ChordGeom last = byIdx[grp.get(grp.size() - 1)];
        boolean up;
        if (first.forcedDir) {
            up = first.up;
        } else {
            float sum = 0;
            int cnt = 0;
            int minL = Integer.MAX_VALUE, maxL = Integer.MIN_VALUE;
            for (int idx : grp) {
                for (int l : byIdx[idx].lines) {
                    sum += l;
                    cnt++;
                    minL = Math.min(minL, l);
                    maxL = Math.max(maxL, l);
                }
            }
            up = (maxL - 4) > (4 - minL);
        }
        for (int idx : grp) {
            ChordGeom cg = byIdx[idx];
            if (cg.up != up) {
                cg.up = up;
                computeNoteOffsets(cg);
            }
            defaultStem(cg);
            cg.beamed = true;
        }
        int maxLevel = 1;
        for (int idx : grp) maxLevel = Math.max(maxLevel, byIdx[idx].chord.type - Duration.EIGHTH + 1);
        float minStem = 2.75f + (maxLevel - 1) * 0.75f;
        float x0 = first.stemX, x1 = last.stemX;
        float y0 = up ? first.noteTopY - 3.5f : first.noteBottomY + 3.5f;
        float y1 = up ? last.noteTopY - 3.5f : last.noteBottomY + 3.5f;
        float dx = Math.max(0.01f, x1 - x0);
        float slope = (y1 - y0) / dx;
        float maxRise = Math.min(1.0f, dx * 0.15f);
        if (Math.abs(y1 - y0) > maxRise) slope = Math.signum(y1 - y0) * maxRise / dx;
        // flat beam if inner notes are more extreme than the ends
        boolean innerExtreme = false;
        for (int k = 1; k < grp.size() - 1; k++) {
            ChordGeom cg = byIdx[grp.get(k)];
            if (up ? cg.noteTopY < Math.min(first.noteTopY, last.noteTopY) : cg.noteBottomY > Math.max(first.noteBottomY, last.noteBottomY)) innerExtreme = true;
        }
        if (innerExtreme) slope = 0;
        float base = y0;
        // ensure minimum stem lengths
        for (int idx : grp) {
            ChordGeom cg = byIdx[idx];
            float by = base + slope * (cg.stemX - x0);
            if (up) {
                float limit = cg.noteTopY - minStem;
                if (by > limit) base -= (by - limit);
            } else {
                float limit = cg.noteBottomY + minStem;
                if (by < limit) base += (limit - by);
            }
        }
        for (int idx : grp) {
            ChordGeom cg = byIdx[idx];
            cg.stemEndY = base + slope * (cg.stemX - x0);
        }
        // beam polygons
        float t = Sym.beamThickness;
        float dist = Sym.beamThickness + Sym.beamSpacing;
        ChordRest owner = first.chord;
        for (int level = 1; level <= maxLevel; level++) {
            float off = (level - 1) * dist * (up ? 1 : -1);
            int k = 0;
            while (k < grp.size()) {
                ChordGeom a = byIdx[grp.get(k)];
                if (a.chord.type - Duration.EIGHTH + 1 < level) {
                    k++;
                    continue;
                }
                int e = k;
                while (e + 1 < grp.size() && byIdx[grp.get(e + 1)].chord.type - Duration.EIGHTH + 1 >= level) e++;
                float xa, xb;
                if (e > k) {
                    xa = a.stemX;
                    xb = byIdx[grp.get(e)].stemX;
                } else {
                    // hook
                    boolean right = k == 0;
                    if (right) {
                        xa = a.stemX;
                        xb = a.stemX + 1.1f;
                    } else {
                        xa = a.stemX - 1.1f;
                        xb = a.stemX;
                    }
                }
                float ya = base + slope * (xa - x0) + off;
                float yb = base + slope * (xb - x0) + off;
                float sa = xa - Sym.stemThickness / 2, sb = xb + Sym.stemThickness / 2;
                float[] pts;
                if (up) pts = new float[]{sa, ya, sb, yb, sb, yb + t, sa, ya + t};
                else pts = new float[]{sa, ya - t, sb, yb - t, sb, yb, sa, ya};
                DisplayList.Item it = poly(g.items, pts);
                it.owner = owner;
                it.voice = v;
                g.ext(Math.min(ya, yb) - t, Math.max(ya, yb) + t);
                k = e + 1;
            }
        }
    }

    private void emitChord(StaffGen g, ChordGeom cg, Pos pos, int sysIdx) {
        Chord c = cg.chord;
        boolean drums = isDrums(pos.staff) && s.clefAt(pos.staff, pos.measure) == Clef.PERC;
        float minX = cg.x, maxX = cg.x + cg.headW;
        for (int i = 0; i < c.notes.size(); i++) {
            minX = Math.min(minX, cg.x + cg.noteDx[i]);
            maxX = Math.max(maxX, cg.x + cg.noteDx[i] + cg.headW);
        }
        // ledger lines
        int minLine = Integer.MAX_VALUE, maxLine = Integer.MIN_VALUE;
        for (int l : cg.lines) {
            minLine = Math.min(minLine, l);
            maxLine = Math.max(maxLine, l);
        }
        float lx0 = minX - Sym.legerLineExtension, lx1 = maxX + Sym.legerLineExtension;
        for (int l = -2; l >= minLine; l -= 2) {
            DisplayList.Item it = line(g.items, lx0, l * 0.5f, lx1, l * 0.5f, Sym.legerLineThickness);
            it.owner = c;
        }
        for (int l = 10; l <= maxLine; l += 2) {
            DisplayList.Item it = line(g.items, lx0, l * 0.5f, lx1, l * 0.5f, Sym.legerLineThickness);
            it.owner = c;
        }
        // noteheads, accidentals, dots
        List<int[]> accPlaced = new ArrayList<int[]>();
        Integer[] order = new Integer[c.notes.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        final int[] lines = cg.lines;
        java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return lines[a] - lines[b]; // top first
            }
        });
        float dotX = maxX + 0.35f;
        java.util.Set<Integer> dotLines = new java.util.HashSet<Integer>();
        for (int k = 0; k < order.length; k++) {
            int i = order[k];
            Note n = c.notes.get(i);
            float nx = cg.x + cg.noteDx[i];
            float ny = cg.lines[i] * 0.5f;
            int head = cg.head;
            if (drums && drumX(n.pitch)) head = Sym.noteheadXBlack;
            DisplayList.Item hi = glyph(g.items, head, nx, ny);
            hi.owner = c;
            hi.note = n;
            hi.voice = pos.voice;
            Integer acc = accidental.get(n);
            if (acc != null) {
                int col = 0;
                boolean clash = true;
                while (clash) {
                    clash = false;
                    for (int[] pl : accPlaced) {
                        if (pl[1] == col && Math.abs(pl[0] - cg.lines[i]) < 6) {
                            clash = true;
                            col++;
                            break;
                        }
                    }
                }
                accPlaced.add(new int[]{cg.lines[i], col});
                float ax = minX - 0.2f - Sym.width(acc) - col * 1.15f;
                DisplayList.Item ai = glyph(g.items, acc, ax, ny);
                ai.owner = c;
                ai.note = n;
                ai.voice = pos.voice;
            }
            for (int d = 0; d < c.dots; d++) {
                int dl2 = cg.lines[i];
                if (dl2 % 2 == 0) dl2 += (cg.up || pos.voice % 2 == 0) ? -1 : 1;
                if (dotLines.contains(dl2 * 10 + d)) continue;
                dotLines.add(dl2 * 10 + d);
                DisplayList.Item di = glyph(g.items, Sym.augmentationDot, dotX + d * 0.5f, dl2 * 0.5f);
                di.owner = c;
                di.voice = pos.voice;
            }
            g.ext(ny - 1, ny + 1);
            // hit + tie record
            DisplayList.Hit h = new DisplayList.Hit();
            h.x0 = nx - 0.5f;
            h.x1 = nx + cg.headW + 0.5f;
            h.y0 = ny - 0.6f;
            h.y1 = ny + 0.6f;
            h.pos = pos;
            h.cr = c;
            h.note = n;
            h.noteIdx = i;
            g.hits.add(h);
            NoteRec r = new NoteRec();
            r.system = sysIdx;
            r.x = nx;
            r.y = ny;
            r.headW = cg.headW;
            r.staff = pos.staff;
            if (c.notes.size() == 1) r.tieUp = !cg.up;
            else r.tieUp = k < (order.length + 1) / 2;
            if (cg.forcedDir) r.tieUp = cg.up;
            pendingRecs.put(n, r);
        }
        // chord-level hit (stem area) for easier tapping
        DisplayList.Hit ch = new DisplayList.Hit();
        ch.x0 = minX - 0.6f;
        ch.x1 = maxX + 0.6f;
        ch.y0 = Math.min(cg.noteTopY, cg.hasStem ? cg.stemEndY : cg.noteTopY) - 0.3f;
        ch.y1 = Math.max(cg.noteBottomY, cg.hasStem ? cg.stemEndY : cg.noteBottomY) + 0.3f;
        ch.pos = pos;
        ch.cr = c;
        g.hits.add(ch);
        // stem
        if (cg.hasStem) {
            float yNote = cg.up ? cg.noteBottomY - Sym.STEM_UP_SE[Sym.noteheadBlack][1] : cg.noteTopY + 0.16f;
            DisplayList.Item si2 = line(g.items, cg.stemX, yNote, cg.stemX, cg.stemEndY, Sym.stemThickness);
            si2.owner = c;
            si2.voice = pos.voice;
            g.ext(Math.min(yNote, cg.stemEndY), Math.max(yNote, cg.stemEndY));
            if (!cg.beamed && c.type >= Duration.EIGHTH) {
                int fl = flagGlyph(c.type, cg.up);
                DisplayList.Item fi = glyph(g.items, fl, cg.stemX - Sym.stemThickness / 2, cg.stemEndY);
                fi.owner = c;
                fi.voice = pos.voice;
            }
        }
        // articulations
        float aboveY = Math.min(cg.noteTopY, cg.hasStem && cg.up ? cg.stemEndY : cg.noteTopY) - 1.0f;
        float belowY = Math.max(cg.noteBottomY, cg.hasStem && !cg.up ? cg.stemEndY : cg.noteBottomY) + 1.0f;
        float cx = cg.x + cg.headW / 2;
        float nearTop = cg.noteTopY - 1.0f, nearBottom = cg.noteBottomY + 1.0f;
        for (String a : c.articulations) {
            boolean always = Articulation.alwaysAbove(a);
            boolean above = always || !cg.up;
            String name = Articulation.smufl(a, above);
            int gl = name == null ? -1 : Sym.byName(name);
            if (gl < 0) continue;
            float gy;
            if (always) {
                gy = Math.min(-1.2f, aboveY - 0.2f);
                aboveY = gy - 1.4f;
            } else if (above) {
                gy = nearTop;
                // avoid staff lines inside the staff
                if (gy > -0.5f && gy < 4.5f && Math.abs(gy - Math.round(gy)) < 0.1f) gy -= 0.5f;
                nearTop = gy - 1.0f;
            } else {
                gy = nearBottom;
                if (gy > -0.5f && gy < 4.5f && Math.abs(gy - Math.round(gy)) < 0.1f) gy += 0.5f;
                gy += 0.6f;
                nearBottom = gy + 0.6f;
            }
            DisplayList.Item ai = glyph(g.items, gl, cx - Sym.width(gl) / 2, gy);
            ai.owner = c;
            g.ext(gTop(gl, gy), gBottom(gl, gy));
        }
    }

    private static int flagGlyph(int type, boolean up) {
        int k = Math.min(3, type - Duration.EIGHTH);
        return up ? Sym.flag8thUp + k : Sym.flag8thDown + k;
    }

    private void emitRest(StaffGen g, Rest r, Pos pos, DisplayList.MeasureInfo mi, boolean multiVoice, int v) {
        int gl;
        float x, y;
        float voiceOff = multiVoice ? (v % 2 == 0 ? -2f : 2f) : 0;
        if (r.measureRest) {
            gl = Sym.restWhole;
            if (r.measureTicks >= 3840) gl = Sym.restDoubleWhole;
            float end = mi.x + mi.width;
            x = (mi.contentX - 0.8f + end) / 2 - Sym.width(gl) / 2;
            y = 1f + voiceOff;
        } else {
            gl = restGlyph(r.type);
            x = dl.xAt(pos.measure, pos.tick);
            y = (r.type == Duration.WHOLE ? 1f : 2f) + voiceOff;
        }
        DisplayList.Item it = glyph(g.items, gl, x, y);
        it.owner = r;
        it.voice = v;
        g.ext(gTop(gl, y), gBottom(gl, y));
        if (multiVoice && v % 2 == 1 && !r.measureRest) it.color = DisplayList.C_INK;
        for (int d = 0; d < r.dots; d++) {
            glyph(g.items, Sym.augmentationDot, x + Sym.width(gl) + 0.3f + d * 0.5f, 1.5f).owner = r;
        }
        DisplayList.Hit h = new DisplayList.Hit();
        h.x0 = x - 0.5f;
        h.x1 = x + Sym.width(gl) + 0.5f;
        h.y0 = Math.min(gTop(gl, y), y - 1) - 0.3f;
        h.y1 = Math.max(gBottom(gl, y), y + 1) + 0.3f;
        h.pos = pos;
        h.cr = r;
        g.hits.add(h);
        if (r.articulations.contains(Articulation.FERMATA)) {
            int fg = Sym.fermataAbove;
            glyph(g.items, fg, x + Sym.width(gl) / 2 - Sym.width(fg) / 2, -1.2f).owner = r;
            g.ext(-3, 4);
        }
    }

    private void emitTuplets(StaffGen g, Voice voice, int[] starts, ChordGeom[] byIdx, int m, int v) {
        int i = 0;
        while (i < voice.items.size()) {
            Tuplet t = voice.items.get(i).tuplet;
            if (t == null) {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < voice.items.size() && voice.items.get(j + 1).tuplet == t) j++;
            float x0 = dl.xAt(m, starts[i]);
            float x1 = dl.xAt(m, starts[j]) + HEAD_W;
            boolean allBeamed = true;
            int upVotes = 0;
            float top = 0, bottom = 4;
            for (int k = i; k <= j; k++) {
                ChordGeom cg = byIdx[k];
                if (cg == null) {
                    allBeamed = false;
                    continue;
                }
                if (!cg.beamed) allBeamed = false;
                if (cg.up) upVotes++;
                else upVotes--;
                top = Math.min(top, Math.min(cg.noteTopY, cg.hasStem ? cg.stemEndY : cg.noteTopY));
                bottom = Math.max(bottom, Math.max(cg.noteBottomY, cg.hasStem ? cg.stemEndY : cg.noteBottomY));
            }
            boolean above = upVotes >= 0;
            if (v % 2 == 1 && nonEmptyVoices(s.measures.get(m).staves.get(byIdxStaff(byIdx))) > 1) above = false;
            float y = above ? top - 1.3f : bottom + 1.3f;
            String num = String.valueOf(t.actual);
            float numW = 0;
            for (int k = 0; k < num.length(); k++) numW += Sym.width(Sym.tuplet0 + (num.charAt(k) - '0'));
            float cx = (x0 + x1) / 2 - numW / 2;
            float ny = y + 0.6f;
            for (int k = 0; k < num.length(); k++) {
                int gl = Sym.tuplet0 + (num.charAt(k) - '0');
                DisplayList.Item it = glyph(g.items, gl, cx, ny);
                it.owner = voice.items.get(i);
                cx += Sym.width(gl);
            }
            if (!allBeamed) {
                float hook = above ? 0.6f : -0.6f;
                float gap0 = (x0 + x1) / 2 - numW / 2 - 0.3f, gap1 = (x0 + x1) / 2 + numW / 2 + 0.3f;
                float w = Sym.tupletBracketThickness;
                line(g.items, x0 - 0.2f, y + hook, x0 - 0.2f, y, w);
                line(g.items, x0 - 0.2f, y, gap0, y, w);
                line(g.items, gap1, y, x1 + 0.2f, y, w);
                line(g.items, x1 + 0.2f, y, x1 + 0.2f, y + hook, w);
            }
            g.ext(y - 1, y + 1);
            i = j + 1;
        }
    }

    private int byIdxStaff(ChordGeom[] byIdx) {
        for (ChordGeom cg : byIdx) if (cg != null) return cg.staff;
        return 0;
    }

    // --------------------------------------------------------------------------- markings

    private void genMarkings(StaffGen g, int sysIdx, int st, int m0, int m1) {
        boolean hasDyn = false, hasLyrics = false, hasChords = false, hasText = false;
        for (int m = m0; m <= m1; m++) {
            for (Voice v : s.measures.get(m).staves.get(st).voices) {
                for (ChordRest cr : v.items) {
                    if (cr.dynamic != null) hasDyn = true;
                    if (cr.lyric != null) hasLyrics = true;
                    if (cr.chordSymbol != null) hasChords = true;
                    if (cr.staffText != null) hasText = true;
                }
            }
        }
        float dynY = Math.max(6.2f, g.bottom + 2.2f);
        float lyrY = hasDyn ? dynY + 3.0f : Math.max(6.8f, g.bottom + 2.8f);
        float chordY = Math.min(-2.0f, g.top - 1.0f);
        float textY = hasChords ? chordY - 2.8f : chordY;
        for (int m = m0; m <= m1; m++) {
            for (int v = 0; v < StaffMeasure.VOICES; v++) {
                Voice voice = s.measures.get(m).staves.get(st).voices[v];
                int[] starts = voice.starts();
                for (int i = 0; i < voice.items.size(); i++) {
                    ChordRest cr = voice.items.get(i);
                    float x = dl.xAt(m, starts[i]);
                    if (cr instanceof Rest && ((Rest) cr).measureRest) x = dl.measures.get(m).contentX;
                    if (cr.dynamic != null) {
                        String name = Dynamics.smufl(cr.dynamic);
                        int gl = name == null ? -1 : Sym.byName(name);
                        if (gl >= 0) {
                            DisplayList.Item it = glyph(g.items, gl, x - 0.3f, dynY);
                            it.owner = cr;
                            g.ext(gTop(gl, dynY), gBottom(gl, dynY));
                            hitBox(g, it, x - 0.3f, dynY - 1.5f, x + Sym.width(gl), dynY + 0.8f, new Pos(m, st, v, starts[i]), cr);
                        }
                    }
                    if (cr.lyric != null && cr instanceof Chord) {
                        float w = tm.width(cr.lyric, TextMetrics.FONT_TEXT, 1.9f);
                        DisplayList.Item it = text(g.items, cr.lyric, TextMetrics.FONT_TEXT, 1.9f, x + HEAD_W / 2 - Math.min(w / 2, 1.2f) + 0.0f, lyrY, DisplayList.ALIGN_LEFT);
                        it.owner = cr;
                        g.ext(lyrY - 1.6f, lyrY + 0.6f);
                    }
                    if (cr.chordSymbol != null) {
                        DisplayList.Item it = text(g.items, ChordSymbols.display(cr.chordSymbol), TextMetrics.FONT_TEXT, 2.2f, x, chordY, DisplayList.ALIGN_LEFT);
                        it.owner = cr;
                        g.ext(chordY - 2.0f, chordY);
                    }
                    if (cr.staffText != null) {
                        DisplayList.Item it = text(g.items, cr.staffText, TextMetrics.FONT_ITALIC, 2.0f, x, textY, DisplayList.ALIGN_LEFT);
                        it.owner = cr;
                        g.ext(textY - 1.8f, textY);
                    }
                }
            }
        }
        if (st == 0) {
            // tempo marks and rehearsal marks above the top staff
            float ty = Math.min(-2.5f, g.top - 1.4f);
            boolean any = false;
            for (int m = m0; m <= m1; m++) {
                Measure mm = s.measures.get(m);
                DisplayList.MeasureInfo mi = dl.measures.get(m);
                float x = m == m0 ? dl.measures.get(m).contentX - 1.0f : mi.x + 0.3f;
                if (mm.rehearsal != null) {
                    float w = tm.width(mm.rehearsal, TextMetrics.FONT_BOLD, 2.4f);
                    float rx = mi.x - 0.2f;
                    DisplayList.Item box = new DisplayList.Item();
                    box.kind = DisplayList.RECT_OUTLINE;
                    box.x = rx - 0.5f;
                    box.y = ty - 2.4f;
                    box.x2 = rx + w + 0.5f;
                    box.y2 = ty + 0.6f;
                    box.w = 0.15f;
                    g.items.add(box);
                    text(g.items, mm.rehearsal, TextMetrics.FONT_BOLD, 2.4f, rx, ty, DisplayList.ALIGN_LEFT);
                    x = rx + w + 1.6f;
                    any = true;
                }
                if (mm.tempo > 0) {
                    float tx = x;
                    if (mm.tempoText != null && !mm.tempoText.isEmpty()) {
                        text(g.items, mm.tempoText, TextMetrics.FONT_BOLD, 2.2f, tx, ty, DisplayList.ALIGN_LEFT);
                        tx += tm.width(mm.tempoText, TextMetrics.FONT_BOLD, 2.2f) + 0.8f;
                    }
                    DisplayList.Item ng = glyph(g.items, Sym.metNoteQuarterUp, tx, ty - 0.2f);
                    ng.w = 0.8f;
                    tx += Sym.width(Sym.metNoteQuarterUp) * 0.8f + 0.4f;
                    text(g.items, "= " + mm.tempo, TextMetrics.FONT_TEXT, 2.2f, tx, ty, DisplayList.ALIGN_LEFT);
                    any = true;
                }
            }
            if (any) g.ext(ty - 3.2f, 4);
            // measure number at the start of each system except the first
            if (m0 > 0) {
                DisplayList.MeasureInfo mi = dl.measures.get(m0);
                float ny = Math.min(-1.2f, g.top - 0.4f);
                if (any) ny = -1.4f;
                text(g.items, String.valueOf(s.measureNumber(m0)), TextMetrics.FONT_ITALIC, 1.6f, mi.x - 0.2f - (mi.contentX - mi.x) * 0 + 0.0f, ny, DisplayList.ALIGN_LEFT).color = DisplayList.C_MUTED;
            }
        }
        // measure hit regions (tap on empty space selects the measure)
        for (int m = m0; m <= m1; m++) {
            DisplayList.MeasureInfo mi = dl.measures.get(m);
            DisplayList.Hit h = new DisplayList.Hit();
            h.x0 = mi.x;
            h.x1 = mi.x + mi.width;
            h.y0 = -0.5f;
            h.y1 = 4.5f;
            h.pos = new Pos(m, st, 0, -1);
            g.hits.add(0, h);
        }
    }

    private void hitBox(StaffGen g, DisplayList.Item it, float x0, float y0, float x1, float y1, Pos p, ChordRest cr) {
        DisplayList.Hit h = new DisplayList.Hit();
        h.x0 = x0;
        h.y0 = y0;
        h.x1 = x1;
        h.y1 = y1;
        h.pos = p;
        h.cr = cr;
        g.hits.add(h);
    }

    // --------------------------------------------------------------------------- system decorations

    private void systemDecorations(int sysIdx, DisplayList.SystemInfo si, int m0, int m1, float left) {
        List<DisplayList.Item> out = dl.items;
        float topY = si.staffY[0], botY = si.staffY[staffCount - 1] + 4;
        float bw = Sym.thinBarlineThickness;
        if (staffCount > 1) line(out, si.x0, topY, si.x0, botY, bw);
        // brackets and braces
        float bx = si.x0;
        boolean anyBrace = false;
        for (Part p : s.parts) if (p.staves.size() > 1) anyBrace = true;
        if (anyBrace) {
            for (Part p : s.parts) {
                if (p.staves.size() < 2) continue;
                int f = s.firstStaffOf(p);
                float y0 = si.staffY[f], y1 = si.staffY[f + p.staves.size() - 1] + 4;
                brace(out, bx - 0.4f, y0, y1);
            }
            bx -= 1.6f;
        }
        if (s.parts.size() > 1) {
            float x = bx - 0.6f;
            line(out, x, topY - 0.5f, x, botY + 0.5f, Sym.bracketThickness);
            glyph(out, Sym.bracketTop, x - Sym.bracketThickness / 2, topY - 0.5f);
            glyph(out, Sym.bracketBottom, x - Sym.bracketThickness / 2, botY + 0.5f);
            bx -= 1.2f;
        }
        // instrument names
        boolean names = !(s.parts.size() == 1 && s.parts.get(0).staves.size() == 1);
        if (names) {
            for (Part p : s.parts) {
                String n = sysIdx == 0 ? p.name : p.shortName;
                if (n == null || n.isEmpty()) continue;
                int f = s.firstStaffOf(p);
                float y0 = si.staffY[f], y1 = si.staffY[f + p.staves.size() - 1] + 4;
                text(out, n, TextMetrics.FONT_TEXT, 2.0f, bx - 0.6f, (y0 + y1) / 2 + 0.7f, DisplayList.ALIGN_RIGHT);
            }
        }
        // bar lines
        for (int m = m0; m <= m1; m++) {
            Measure mm = s.measures.get(m);
            DisplayList.MeasureInfo mi = dl.measures.get(m);
            float x = mi.x + mi.width;
            if (mm.startRepeat) {
                float sx = m == m0 ? mi.contentX - 1.1f - 1.3f + 0.2f : mi.x + (m == m0 ? 0 : preps.get(m).midHeader) + 0.1f;
                for (Part p : s.parts) {
                    int f = s.firstStaffOf(p);
                    float y0 = si.staffY[f], y1 = si.staffY[f + p.staves.size() - 1] + 4;
                    float tw = Sym.thickBarlineThickness;
                    line(out, sx + tw / 2, y0, sx + tw / 2, y1, tw);
                    float tx = sx + tw + Sym.thinThickBarlineSeparation + bw / 2;
                    line(out, tx, y0, tx, y1, bw);
                    for (int k = 0; k < p.staves.size(); k++) {
                        float sy = si.staffY[f + k];
                        glyph(out, Sym.repeatDot, tx + Sym.repeatBarlineDotSeparation, sy + 1.5f);
                        glyph(out, Sym.repeatDot, tx + Sym.repeatBarlineDotSeparation, sy + 2.5f);
                    }
                }
            }
            for (Part p : s.parts) {
                int f = s.firstStaffOf(p);
                float y0 = si.staffY[f], y1 = si.staffY[f + p.staves.size() - 1] + 4;
                switch (mm.endBarline) {
                    case Measure.BAR_DOUBLE: {
                        line(out, x - bw / 2, y0, x - bw / 2, y1, bw);
                        float x2 = x - bw - Sym.barlineSeparation - bw / 2;
                        line(out, x2, y0, x2, y1, bw);
                        break;
                    }
                    case Measure.BAR_FINAL:
                    case Measure.BAR_END_REPEAT: {
                        float tw = Sym.thickBarlineThickness;
                        line(out, x - tw / 2, y0, x - tw / 2, y1, tw);
                        float x2 = x - tw - Sym.thinThickBarlineSeparation - bw / 2;
                        line(out, x2, y0, x2, y1, bw);
                        if (mm.endBarline == Measure.BAR_END_REPEAT) {
                            float dx = x2 - Sym.repeatBarlineDotSeparation - Sym.width(Sym.repeatDot);
                            for (int k = 0; k < p.staves.size(); k++) {
                                float sy = si.staffY[f + k];
                                glyph(out, Sym.repeatDot, dx, sy + 1.5f);
                                glyph(out, Sym.repeatDot, dx, sy + 2.5f);
                            }
                        }
                        break;
                    }
                    case Measure.BAR_DASHED: {
                        for (float yy = y0; yy < y1; yy += 1.0f) line(out, x - bw / 2, yy, x - bw / 2, Math.min(y1, yy + 0.55f), bw);
                        break;
                    }
                    default:
                        line(out, x - bw / 2, y0, x - bw / 2, y1, bw);
                }
            }
        }
    }

    private void brace(List<DisplayList.Item> out, float x, float y0, float y1) {
        // procedural brace: two mirrored S-curves with variable thickness
        int n = 24;
        float h = y1 - y0;
        float w = 1.1f;
        float[] pts = new float[(n + 1) * 4 + 4];
        int k = 0;
        // outer edge top->bottom, inner edge bottom->top
        float[] ox = new float[n + 1], oy = new float[n + 1], ix = new float[n + 1];
        for (int i = 0; i <= n; i++) {
            float t = i / (float) n;
            float yy = y0 + t * h;
            // shape: x offset peaks at the middle tip
            float u = Math.abs(t - 0.5f) * 2; // 1 at ends, 0 at middle
            float curve = (float) (Math.pow(u, 0.6) * w) - w; // middle is leftmost
            float thick = 0.12f + 0.35f * (float) Math.sin(Math.PI * Math.min(1, u) );
            ox[i] = x + curve * 0.9f + (1 - u) * 0f;
            oy[i] = yy;
            ix[i] = ox[i] + thick;
        }
        for (int i = 0; i <= n; i++) {
            pts[k++] = ox[i];
            pts[k++] = oy[i];
        }
        for (int i = n; i >= 0; i--) {
            pts[k++] = ix[i];
            pts[k++] = oy[i];
        }
        float[] p2 = new float[k];
        System.arraycopy(pts, 0, p2, 0, k);
        poly(out, p2);
    }

    // --------------------------------------------------------------------------- ties

    private void drawTies() {
        for (int m = 0; m < s.measures.size(); m++) {
            for (int st = 0; st < staffCount; st++) {
                for (int v = 0; v < StaffMeasure.VOICES; v++) {
                    Voice voice = s.measures.get(m).staves.get(st).voices[v];
                    int[] starts = voice.starts();
                    for (int i = 0; i < voice.items.size(); i++) {
                        ChordRest cr = voice.items.get(i);
                        if (!(cr instanceof Chord)) continue;
                        Chord nextChord = nextChordAfter(m, st, v, starts[i] + cr.ticks());
                        for (Note n : ((Chord) cr).notes) {
                            if (!n.tieForward) continue;
                            NoteRec a = noteRecs.get(n);
                            if (a == null) continue;
                            Note target = null;
                            if (nextChord != null) for (Note t : nextChord.notes) if (t.pitch == n.pitch) target = t;
                            NoteRec b = target != null ? noteRecs.get(target) : null;
                            float x1 = a.x + a.headW + 0.15f;
                            float y = a.y + (a.tieUp ? -0.45f : 0.45f);
                            if (b != null && b.system == a.system) {
                                tie(x1, b.x - 0.15f, y, a.tieUp, cr);
                            } else {
                                DisplayList.SystemInfo si = dl.systems.get(a.system);
                                tie(x1, si.x1 - 0.3f, y, a.tieUp, cr);
                                if (b != null) {
                                    DisplayList.SystemInfo sb = dl.systems.get(b.system);
                                    DisplayList.MeasureInfo mi = dl.measures.get(sb.firstMeasure);
                                    tie(mi.contentX - 1.4f, b.x - 0.15f, b.y + (a.tieUp ? -0.45f : 0.45f), a.tieUp, cr);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private Chord nextChordAfter(int m, int st, int v, int tick) {
        int mm = m, t = tick;
        while (mm < s.measures.size() && t >= s.measures.get(mm).ticks()) {
            t -= s.measures.get(mm).ticks();
            mm++;
        }
        if (mm >= s.measures.size()) return null;
        Voice voice = s.measures.get(mm).staves.get(st).voices[v];
        int idx = voice.indexAt(t);
        if (idx < 0) return null;
        ChordRest cr = voice.items.get(idx);
        return cr instanceof Chord ? (Chord) cr : null;
    }

    private void tie(float x1, float x2, float y, boolean up, ChordRest owner) {
        if (x2 - x1 < 0.6f) x2 = x1 + 0.6f;
        float len = x2 - x1;
        float h = Math.min(1.3f, 0.3f + 0.08f * len) * (up ? -1 : 1);
        float th = Sym.tieMidpointThickness * (up ? -1 : 1);
        int n = 16;
        float[] pts = new float[(n + 1) * 4];
        int k = 0;
        for (int i = 0; i <= n; i++) {
            float t = i / (float) n;
            float bx = bez(x1, x1 + len * 0.25f, x2 - len * 0.25f, x2, t);
            float by = bez(y, y + h * 1.33f, y + h * 1.33f, y, t);
            pts[k++] = bx;
            pts[k++] = by;
        }
        for (int i = n; i >= 0; i--) {
            float t = i / (float) n;
            float bx = bez(x1, x1 + len * 0.25f, x2 - len * 0.25f, x2, t);
            float by = bez(y, y + (h - th) * 1.33f, y + (h - th) * 1.33f, y, t);
            pts[k++] = bx;
            pts[k++] = by;
        }
        DisplayList.Item it = poly(dl.items, pts);
        it.owner = owner;
    }

    private static float bez(float p0, float p1, float p2, float p3, float t) {
        float u = 1 - t;
        return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3;
    }
}
