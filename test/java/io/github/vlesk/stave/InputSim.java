package io.github.vlesk.stave;

import io.github.vlesk.stave.edit.EditorState;
import io.github.vlesk.stave.model.*;

import java.awt.Font;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Simulation of manual input (tools/simulate.sh). Enters a score through the editor the way a person does with Stave's controls (duration, dot and
 * triplet buttons, piano keys, accidental buttons, chord mode, tie, voices, articulation and dynamic
 * palettes, lyric entry mode), then compares the result with the source note by note.
 */
@SuppressWarnings("unchecked")
public class InputSim {
    static final TreeMap<String, Integer> taps = new TreeMap<String, Integer>();
    static final TreeMap<String, List<String>> problems = new TreeMap<String, List<String>>();

    static void tap(String k) {
        Integer v = taps.get(k);
        taps.put(k, v == null ? 1 : v + 1);
    }

    static void problem(String k, String example) {
        List<String> l = problems.get(k);
        if (l == null) problems.put(k, l = new ArrayList<String>());
        l.add(example);
    }

    static int ticks(Object ql) {
        return (int) Math.round(((Double) ql) * Duration.DIVISION);
    }

    static int i(Object o) {
        return ((Double) o).intValue();
    }

    static int durType(String t) {
        switch (t) {
            case "whole": return Duration.WHOLE;
            case "half": return Duration.HALF;
            case "quarter": return Duration.QUARTER;
            case "eighth": return Duration.EIGHTH;
            case "16th": return Duration.N16;
            case "32nd": return Duration.N32;
            case "64th": return Duration.N64;
            default: return -1;
        }
    }

    static int clef(String c) {
        switch (c) {
            case "bass": return Clef.BASS;
            case "alto": return Clef.ALTO;
            case "tenor": return Clef.TENOR;
            case "treble8vb": return Clef.TREBLE_8VB;
            case "treble8va": return Clef.TREBLE_8VA;
            case "bass8vb": return Clef.BASS_8VB;
            default: return Clef.TREBLE;
        }
    }

    static int stepIdx(String letter) {
        return "CDEFGAB".indexOf(letter.charAt(0));
    }

    public static void main(String[] a) throws Exception {
        String fonts = a[2];
        RenderPng.leland = Font.createFont(Font.TRUETYPE_FONT, new File(fonts, "Leland.otf"));
        RenderPng.edwin = Font.createFont(Font.TRUETYPE_FONT, new File(fonts, "Edwin-Roman.otf"));
        RenderPng.edwinItalic = Font.createFont(Font.TRUETYPE_FONT, new File(fonts, "Edwin-Italic.otf"));
        RenderPng.edwinBold = Font.createFont(Font.TRUETYPE_FONT, new File(fonts, "Edwin-Bold.otf"));
        Map<String, Object> d = (Map<String, Object>) Json.parse(new String(Files.readAllBytes(new File(a[0]).toPath()), StandardCharsets.UTF_8));
        List<Map<String, Object>> staves = (List<Map<String, Object>>) d.get("staves");
        long t0 = System.currentTimeMillis();
        // exact positions: the source stores triplet durations with limited precision (0.332 for 1/3), so
        // durations come from type, dots and tuplet, and positions follow on from the previous event
        for (Map<String, Object> st : staves) {
            for (Map<String, Object> mm : (List<Map<String, Object>>) st.get("measures")) {
                for (List<Map<String, Object>> evs : (List<List<Map<String, Object>>>) mm.get("voices")) {
                    int end = -1;
                    for (Map<String, Object> ev : evs) {
                        int type = durType((String) ev.get("type"));
                        int len = type < 0 ? ticks(ev.get("ql")) : Duration.ticks(type, Math.min(2, i(ev.get("dots"))));
                        // a whole-bar rest is labelled "whole" in any time signature
                        if (ev.containsKey("rest") && ((Double) ev.get("ql")).equals(mm.get("bar"))) len = ticks(ev.get("ql"));
                        // the same articulation listed twice is one articulation (a second tap would remove it)
                        if (ev.containsKey("arts")) ev.put("arts", new ArrayList<Object>(new LinkedHashSet<Object>((List<Object>) ev.get("arts"))));
                        List<Object> tup = (List<Object>) ev.get("tuplet");
                        if (tup != null) len = len * i(tup.get(1)) / i(tup.get(0));
                        int off = ticks(ev.get("off"));
                        if (end >= 0 && Math.abs(off - end) <= 6) off = end;
                        ev.put("tick", (double) off);
                        ev.put("len", (double) len);
                        end = off + len;
                    }
                }
            }
        }

        // ---- the new-score form: instruments, time and key signature, pickup, number of measures
        List<Part> parts = new ArrayList<Part>();
        for (int s = 0; s < staves.size(); s++) {
            String inst = ((String) staves.get(s).get("instrument")).toLowerCase(Locale.ROOT);
            String id = inst.contains("piano") ? "piano" : inst.contains("violin") ? "violin" : inst.contains("viola") ? "viola"
                    : inst.contains("cello") ? "violoncello" : inst.contains("voice") ? "voice" : "piano";
            parts.add(Instruments.byId(id).create());
            if (id.equals("piano")) s++; // its second staff
        }
        List<Map<String, Object>> m0 = (List<Map<String, Object>>) staves.get(0).get("measures");
        Map<String, Object> first = m0.get(0);
        List<Object> ts = (List<Object>) first.get("ts");
        int pickup = ((Double) first.get("ql")) < ((Double) first.get("bar")) ? ticks(first.get("ql")) : 0;
        Score score = Score.create((String) d.get("title"), (String) d.get("composer"), parts, i(ts.get(0)), i(ts.get(1)),
                first.containsKey("key") ? i(first.get("key")) : 0, 100, m0.size(), pickup);
        check(score.staffCount() == staves.size(), "staff count " + score.staffCount() + " vs " + staves.size());
        EditorState es = new EditorState(score);

        // ---- signatures and clefs first, as a user would set them up
        for (int m = 1; m < m0.size(); m++) {
            Map<String, Object> mm = m0.get(m);
            if (mm.containsKey("ts")) {
                List<Object> t = (List<Object>) mm.get("ts");
                es.selectMeasure(m, 0);
                es.setTimeSig(i(t.get(0)), i(t.get(1)), false);
                tap("time signature palette");
            }
            if (mm.containsKey("key") && es.score.keyAt(m) != i(mm.get("key"))) {
                es.selectMeasure(m, 0);
                es.setKeySig(i(mm.get("key")));
                tap("key signature palette");
            }
        }
        for (int s = 0; s < staves.size(); s++) {
            List<Map<String, Object>> ms = (List<Map<String, Object>>) staves.get(s).get("measures");
            for (int m = 0; m < ms.size(); m++) {
                if (!ms.get(m).containsKey("clef")) continue;
                int c = clef((String) ms.get(m).get("clef"));
                if (es.score.clefAt(s, m) == c) continue;
                es.selectMeasure(m, s);
                es.setClef(c);
                tap("clef palette");
            }
        }

        // ---- notes, staff by staff and voice by voice
        for (int s = 0; s < staves.size(); s++) {
            List<Map<String, Object>> ms = (List<Map<String, Object>>) staves.get(s).get("measures");
            int maxV = 0;
            for (Map<String, Object> mm : ms) maxV = Math.max(maxV, ((List<Object>) mm.get("voices")).size());
            for (int v = 0; v < Math.min(maxV, StaffMeasure.VOICES); v++) {
                es.clearSelection();
                es.setVoice(v);
                es.startInput();
                Map<String, Object> prev = null;
                Pos prevPos = null;
                for (int m = 0; m < ms.size(); m++) {
                    List<List<Map<String, Object>>> vs = (List<List<Map<String, Object>>>) ms.get(m).get("voices");
                    if (v >= vs.size()) continue;
                    for (Map<String, Object> ev : vs.get(v)) {
                        Pos at = enter(es, s, v, m, ev, prev, prevPos);
                        prev = ev;
                        prevPos = at;
                    }
                }
                es.stopInput();
            }
            if (maxV > StaffMeasure.VOICES) problem("more than 4 voices", "staff " + s);
        }

        // ---- dynamics from the palette, on the note where they stand
        for (int s = 0; s < staves.size(); s++) {
            List<Map<String, Object>> ms = (List<Map<String, Object>>) staves.get(s).get("measures");
            for (int m = 0; m < ms.size(); m++) {
                for (Object o : (List<Object>) ms.get(m).get("dyn")) {
                    List<Object> dv = (List<Object>) o;
                    String val = (String) dv.get(1);
                    if (Dynamics.smufl(val) == null) {
                        problem("dynamic not in the palette", val + " m" + m);
                        continue;
                    }
                    Pos p = ScoreEdit.snapToElement(es.score, new Pos(m, s, 0, ticks(dv.get(0))));
                    es.selectNote(p, -1);
                    es.setDynamic(val);
                    tap("dynamics palette");
                }
            }
        }

        // ---- lyrics in lyric entry mode: text, then Space, "-" or "_" as in MuseScore
        for (int s = 0; s < staves.size(); s++) {
            List<Map<String, Object>> ms = (List<Map<String, Object>>) staves.get(s).get("measures");
            List<Object[]> chords = new ArrayList<Object[]>();
            for (int m = 0; m < ms.size(); m++) {
                List<List<Map<String, Object>>> vs = (List<List<Map<String, Object>>>) ms.get(m).get("voices");
                for (Map<String, Object> ev : vs.get(0)) {
                    if (ev.containsKey("rest")) continue;
                    chords.add(new Object[]{new Pos(m, s, 0, i(ev.get("tick"))), ev});
                }
            }
            int firstLyric = -1;
            for (int k = 0; k < chords.size() && firstLyric < 0; k++) if (((Map<String, Object>) chords.get(k)[1]).containsKey("lyric")) firstLyric = k;
            if (firstLyric < 0) continue;
            es.beginLyric((Pos) chords.get(firstLyric)[0]);
            tap("lyrics button");
            boolean prevHyph = false;
            for (int k = firstLyric; k < chords.size(); k++) {
                Pos at = (Pos) chords.get(k)[0];
                if (es.selPos == null || es.selPos.measure != at.measure || es.selPos.tick != at.tick) {
                    problem("lyric cursor not on the expected note", "expected m" + at.measure + " t" + at.tick + " got " + es.selPos);
                    es.beginLyric(at);
                    tap("tap a note to move lyric entry");
                }
                Map<String, Object> ev = (Map<String, Object>) chords.get(k)[1];
                boolean last = k + 1 >= chords.size();
                boolean nextHasLyric = !last && ((Map<String, Object>) chords.get(k + 1)[1]).containsKey("lyric");
                String sep;
                if (ev.containsKey("lyric")) {
                    String text = (String) ev.get("lyric");
                    es.lyricType(text);
                    for (int c = 0; c < text.length(); c++) tap("keyboard: letters");
                    String syl = (String) ev.get("syllabic");
                    prevHyph = syl.equals("begin") || syl.equals("middle");
                    sep = prevHyph ? "-" : nextHasLyric ? " " : "_";
                } else {
                    sep = prevHyph ? "-" : "_";
                }
                if (last) break;
                es.lyricType(sep);
                tap("keyboard: " + (sep.equals(" ") ? "space" : sep));
            }
            es.endLyric();
            tap("Done");
        }
        long t1 = System.currentTimeMillis();

        // ---- compare with the source
        int notes = 0, events = 0;
        for (int s = 0; s < staves.size(); s++) {
            List<Map<String, Object>> ms = (List<Map<String, Object>>) staves.get(s).get("measures");
            for (int m = 0; m < ms.size(); m++) {
                List<List<Map<String, Object>>> vs = (List<List<Map<String, Object>>>) ms.get(m).get("voices");
                for (int v = 0; v < Math.min(vs.size(), StaffMeasure.VOICES); v++) {
                    Voice voice = es.score.measures.get(m).staves.get(s).voices[v];
                    int[] starts = voice.starts();
                    for (Map<String, Object> ev : vs.get(v)) {
                        events++;
                        int off = i(ev.get("tick")), len = i(ev.get("len"));
                        String where = staves.get(s).get("name") + " m" + ms.get(m).get("number") + " v" + (v + 1) + " @" + off;
                        int idx = -1;
                        for (int k = 0; k < voice.items.size(); k++) if (starts[k] == off) idx = k;
                        if (idx < 0) {
                            problem("no element at the source position", where);
                            continue;
                        }
                        ChordRest cr = voice.items.get(idx);
                        if (cr.ticks() != len) problem("duration differs", where + " " + cr.ticks() + " vs " + len);
                        if (ev.containsKey("rest")) {
                            if (!(cr instanceof Rest)) problem("rest became a chord", where);
                            continue;
                        }
                        if (!(cr instanceof Chord)) {
                            problem("chord became a rest", where);
                            continue;
                        }
                        Chord c = (Chord) cr;
                        List<List<Object>> ps = (List<List<Object>>) ev.get("pitches");
                        List<Object> ties = (List<Object>) ev.get("tie");
                        if (c.notes.size() != ps.size()) problem("chord size differs", where + " " + c.notes.size() + " vs " + ps.size());
                        for (int k = 0; k < ps.size(); k++) {
                            notes++;
                            int midi = i(ps.get(k).get(0)), alter = i(ps.get(k).get(1));
                            int step = stepIdx((String) ps.get(k).get(2));
                            Note n = null;
                            for (Note x : c.notes) if (x.pitch == midi) n = x;
                            if (n == null) {
                                problem("pitch missing", where + " " + midi);
                                continue;
                            }
                            if (Pitch.step(n.tpc) != step || Pitch.alter(n.tpc) != alter) {
                                problem("spelling differs", where + " " + Pitch.name(n.pitch, n.tpc) + " vs step " + ps.get(k).get(2) + " alter " + alter);
                            }
                            String tie = (String) ties.get(k);
                            boolean wantTie = "start".equals(tie) || "continue".equals(tie);
                            if (n.tieForward != wantTie) problem("tie differs", where + " pitch " + midi + " ours " + n.tieForward + " src " + tie);
                        }
                        List<Object> arts = (List<Object>) ev.get("arts");
                        for (Object ar : arts) if (!c.articulations.contains(ar)) problem("articulation missing", where + " " + ar);
                        String want = null;
                        if (ev.containsKey("lyric")) {
                            String syl = (String) ev.get("syllabic");
                            want = ev.get("lyric") + (syl.equals("begin") || syl.equals("middle") ? "-" : "");
                        }
                        if (!Objects.equals(want, c.lyric)) problem("lyric differs", where + " '" + c.lyric + "' vs '" + want + "'");
                    }
                }
            }
        }
        // the editor's own invariants
        for (int m = 0; m < es.score.measures.size(); m++) {
            Measure mm = es.score.measures.get(m);
            for (int s = 0; s < mm.staves.size(); s++) {
                for (int v = 0; v < StaffMeasure.VOICES; v++) {
                    Voice voice = mm.staves.get(s).voices[v];
                    if (voice.isEmpty()) continue;
                    if (voice.totalTicks() != mm.ticks()) problem("voice does not fill its measure", "m" + m + " s" + s + " v" + v);
                }
            }
        }
        // round trip through the file formats
        byte[] mscx = io.github.vlesk.stave.io.MscxWriter.write(es.score).getBytes(StandardCharsets.UTF_8);
        Score again = io.github.vlesk.stave.io.MscxReader.read(mscx).score;
        if (!io.github.vlesk.stave.io.MscxWriter.write(again).equals(new String(mscx, StandardCharsets.UTF_8))) problem("mscx round trip changes the score", "");
        File out = new File(a[1]);
        out.getParentFile().mkdirs();
        Files.write(new File(a[1] + ".mscx").toPath(), mscx);
        Files.write(new File(a[1] + ".musicxml").toPath(), io.github.vlesk.stave.io.MusicXmlWriter.write(es.score).getBytes(StandardCharsets.UTF_8));
        RenderPng.render(es.score, 52, 14, new File(a[1] + ".png"));
        // layout checks at phone width (52 staff spaces, as the editor at zoom 1 on a 360 dp screen)
        io.github.vlesk.stave.engrave.Layout.Options lo = new io.github.vlesk.stave.engrave.Layout.Options();
        lo.width = 52;
        io.github.vlesk.stave.engrave.DisplayList dl = io.github.vlesk.stave.engrave.Layout.layout(es.score, RenderPng.TM, lo);
        for (io.github.vlesk.stave.engrave.DisplayList.SystemInfo si : dl.systems) {
            if (si.x1 > lo.width - lo.marginX + 0.05f) problem("layout: system wider than the page", "m" + si.firstMeasure + ".." + si.lastMeasure + " ends at " + si.x1);
        }
        for (io.github.vlesk.stave.engrave.DisplayList.Item it : dl.items) {
            float right = Math.max(it.x, it.x2);
            if (it.kind == io.github.vlesk.stave.engrave.DisplayList.TEXT && it.text != null) {
                float tw = RenderPng.TM.width(it.text, it.font, it.w);
                right = it.align == io.github.vlesk.stave.engrave.DisplayList.ALIGN_CENTER ? it.x + tw / 2
                        : it.align == io.github.vlesk.stave.engrave.DisplayList.ALIGN_RIGHT ? it.x : it.x + tw;
            }
            if (right > lo.width + 0.05f) {
                problem("layout: drawn past the right edge", it.kind + " " + (it.text != null ? it.text : "") + " at " + right);
            }
        }
        for (int k = 0; k + 1 < dl.lyricHits.size(); k++) {
            io.github.vlesk.stave.engrave.DisplayList.Hit x = dl.lyricHits.get(k), y = dl.lyricHits.get(k + 1);
            if (x.pos.staff != y.pos.staff || dl.measures.get(x.pos.measure).system != dl.measures.get(y.pos.measure).system) continue;
            if (Math.abs(x.baseline - y.baseline) > 0.01f) continue;
            if (y.x0 < x.x1 - 0.01f) problem("layout: lyrics overlap", x.cr.lyric + " / " + y.cr.lyric + " m" + x.pos.measure + " by " + (x.x1 - y.x0));
        }
        long t2 = System.currentTimeMillis();

        System.out.println("== " + d.get("title") + ": " + es.score.measures.size() + " measures, " + staves.size() + " staves, "
                + events + " events, " + notes + " notes; entry " + (t1 - t0) + " ms, checks+render " + (t2 - t1) + " ms");
        int total = 0;
        for (int v : taps.values()) total += v;
        System.out.println("taps/keys: " + total + " " + taps);
        System.out.println("unsupported in source: " + d.get("unsupported"));
        if (problems.isEmpty()) System.out.println("no differences");
        for (Map.Entry<String, List<String>> e : problems.entrySet()) {
            System.out.println("PROBLEM " + e.getKey() + " x" + e.getValue().size() + ": " + e.getValue().subList(0, Math.min(6, e.getValue().size())));
        }
    }

    static void check(boolean c, String msg) {
        if (!c) problem("setup", msg);
    }

    static Pos enter(EditorState es, int s, int v, int m, Map<String, Object> ev, Map<String, Object> prev, Pos prevPos) {
        int off = i(ev.get("tick"));
        if (es.cursor == null || es.cursor.measure != m || es.cursor.tick != off || es.cursor.staff != s || es.cursor.voice != v) {
            es.setCursor(new Pos(m, s, v, off));
            tap("tap on the staff to move the cursor");
        }
        int type = durType((String) ev.get("type"));
        if (type < 0) {
            problem("duration not in the toolbar", (String) ev.get("type"));
            return null;
        }
        int dots = i(ev.get("dots"));
        if (dots > 1) problem("double dot not available", "m" + m);
        List<Object> tup = (List<Object>) ev.get("tuplet");
        boolean triplet = tup != null && i(tup.get(0)) == 3 && i(tup.get(1)) == 2;
        if (tup != null && !triplet) problem("tuplet other than a triplet", tup + " m" + m);
        // triplets as in the app: select the triplet's unit, tap the triplet button, then the value
        int unit = triplet ? durType((String) ev.get("tunit")) : -1;
        if (triplet && unit < 0) unit = type;
        if (triplet && (!es.triplet || es.tripletUnit != unit)) {
            if (es.triplet) {
                es.setTriplet(false);
                tap("triplet button");
            }
            if (es.durType != unit) {
                es.setDuration(unit);
                tap("duration button");
            }
            es.setTriplet(true);
            tap("triplet button");
        } else if (!triplet && es.triplet) {
            es.setTriplet(false);
            tap("triplet button");
        }
        if (es.durType != type) {
            es.setDuration(type);
            tap("duration button");
        }
        if ((dots > 0) != (es.dots > 0)) {
            es.toggleDot();
            tap("dot button");
        }
        Pos at = es.cursor.copy();
        if (ev.containsKey("rest")) {
            es.enterRest();
            tap("rest button");
            return at;
        }
        List<List<Object>> ps = (List<List<Object>>) ev.get("pitches");
        List<Object> ties = (List<Object>) ev.get("tie");
        boolean allContinue = true;
        for (Object t : ties) if (!"stop".equals(t) && !"continue".equals(t)) allContinue = false;
        boolean samePitches = prev != null && prev.containsKey("pitches") && ((List<Object>) prev.get("pitches")).size() == ps.size();
        if (samePitches) {
            List<List<Object>> pp = (List<List<Object>>) prev.get("pitches");
            for (int k = 0; k < ps.size(); k++) if (i(pp.get(k).get(0)) != i(ps.get(k).get(0))) samePitches = false;
        }
        if (allContinue && samePitches && es.enterTie()) {
            tap("tie button (+)");
        } else {
            for (int k = 0; k < ps.size(); k++) {
                if (k == 1) {
                    es.chordMode = true;
                    tap("add-to-chord toggle");
                }
                enterSpelled(es, i(ps.get(k).get(0)), i(ps.get(k).get(1)), stepIdx((String) ps.get(k).get(2)));
            }
            if (es.chordMode) {
                es.chordMode = false;
                tap("add-to-chord toggle");
            }
            // some (not all) notes of the previous chord tie into this one: select it and press tie
            if (prev != null && prev.containsKey("tie") && prevPos != null) {
                boolean anyTie = false;
                for (Object t : (List<Object>) prev.get("tie")) if ("start".equals(t) || "continue".equals(t)) anyTie = true;
                if (anyTie) {
                    Pos keep = es.cursor.copy();
                    es.selectNote(prevPos, -1);
                    es.toggleTie();
                    tap("select note + tie button");
                    es.setCursor(keep);
                }
            }
        }
        for (Object ar : (List<Object>) ev.get("arts")) {
            es.toggleArticulation((String) ar); // the entered note is selected
            tap("articulation palette");
        }
        return at;
    }

    /** A piano key; when the written spelling is not the default one, the letter's key and an accidental button. */
    static void enterSpelled(EditorState es, int midi, int alter, int step) {
        int tpc = Pitch.spell(midi, es.keyAtCursor());
        if (Pitch.step(tpc) == step && Pitch.alter(tpc) == alter) {
            es.enterPitch(midi, Integer.MIN_VALUE);
            tap("piano key");
            return;
        }
        es.enterPitch(midi, Integer.MIN_VALUE);
        tap("piano key");
        for (int k = 0; k < 2; k++) {
            Note r = es.selectedNote();
            if (r != null && Pitch.step(r.tpc) == step && Pitch.alter(r.tpc) == alter) return;
            es.respell();
            tap("respell button");
        }
        Note r = es.selectedNote();
        if (r != null && Pitch.step(r.tpc) == step && Pitch.alter(r.tpc) == alter) return;
        problem("respell cannot reach it", "pitch " + midi + " step " + "CDEFGAB".charAt(step) + " alter " + alter
                + " got " + (r == null ? "?" : Pitch.name(r.pitch, r.tpc)));
    }
}
