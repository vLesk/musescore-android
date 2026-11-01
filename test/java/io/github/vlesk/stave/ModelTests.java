package io.github.vlesk.stave;

import io.github.vlesk.stave.audio.MidiWriter;
import io.github.vlesk.stave.audio.Timeline;
import io.github.vlesk.stave.io.MscxReader;
import io.github.vlesk.stave.io.MscxWriter;
import io.github.vlesk.stave.io.Mscz;
import io.github.vlesk.stave.io.MusicXmlReader;
import io.github.vlesk.stave.io.MusicXmlWriter;
import io.github.vlesk.stave.model.*;

import io.github.vlesk.stave.engrave.DisplayList;
import io.github.vlesk.stave.engrave.Layout;
import io.github.vlesk.stave.engrave.TextMetrics;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Plain-JVM tests for the model, editing engine and file formats (run by tools/test.sh). */
public class ModelTests {
    static int failures = 0, checks = 0;

    static void check(boolean cond, String msg) {
        checks++;
        if (!cond) {
            failures++;
            System.out.println("FAIL: " + msg);
        }
    }

    static void checkVoicesFit(Score s, String ctx) {
        for (int m = 0; m < s.measures.size(); m++) {
            Measure mm = s.measures.get(m);
            check(mm.staves.size() == s.staffCount(), ctx + ": staff count m" + m);
            for (int st = 0; st < mm.staves.size(); st++) {
                for (int v = 0; v < 4; v++) {
                    Voice voice = mm.staves.get(st).voices[v];
                    if (voice.isEmpty()) {
                        check(v > 0, ctx + ": voice 1 empty m" + m + " s" + st);
                        continue;
                    }
                    check(voice.totalTicks() == mm.ticks(), ctx + ": voice length " + voice.totalTicks() + " != " + mm.ticks() + " at m" + m + " s" + st + " v" + v);
                }
            }
        }
    }

    static Score pianoScore() {
        List<Part> parts = new ArrayList<Part>();
        parts.add(Instruments.byId("piano").create());
        parts.add(Instruments.byId("violin").create());
        return Score.create("Test", "Me", parts, 4, 4, 2, 100, 4);
    }

    static String lyricAt(io.github.vlesk.stave.edit.EditorState es, Pos p) {
        ChordRest cr = ScoreEdit.elementAt(es.score, p);
        return cr == null ? null : cr.lyric;
    }

    static Chord chord(int... pitches) {
        Chord c = new Chord();
        for (int p : pitches) c.addNote(new Note(p, Pitch.spell(p, 2)));
        return c;
    }

    public static void main(String[] args) throws Exception {
        // pitch spelling
        check(Pitch.spell(61, 0) == 21, "C# in C");
        check(Pitch.spell(70, 0) == 12, "Bb in C");
        check(Pitch.spell(66, -1) == 20, "F# in F? " + Pitch.spell(66, -1));
        check(Pitch.spell(66, 2) == 20, "F# in D");
        check(Pitch.spell(63, -3) == 11, "Eb in Eb");
        check(Pitch.octave(60, 14) == 4, "C4");
        check(Pitch.octave(59, 7) == 4, "Cb4");
        check(Clef.line(Clef.TREBLE, 64, 18) == 8, "E4 on bottom line of treble");
        check(Clef.line(Clef.BASS, 57, 17) == 0, "A3 top line bass");
        check(Pitch.keyAlter(2, 3) == 1 && Pitch.keyAlter(2, 0) == 1 && Pitch.keyAlter(2, 4) == 0, "D major alters");
        check(Pitch.keyAlter(-1, 6) == -1 && Pitch.keyAlter(-1, 2) == 0, "F major alters");
        check(MathUtil.floorMod(-1, 7) == 6 && MathUtil.floorDiv(-1, 7) == -1, "floor");

        // durations
        check(Duration.ticks(Duration.QUARTER, 1) == 720, "dotted quarter");
        check(Duration.decomposeRests(480, 1440).size() == 2, "rests on beat 2: quarter+half");
        check(Duration.decomposeRests(480, 1440).get(0)[0] == Duration.QUARTER, "first is quarter");

        // note entry
        Score s = pianoScore();
        checkVoicesFit(s, "new");
        Pos p = new Pos(0, 0, 0, 0);
        p = ScoreEdit.enter(s, p, chord(62), Duration.QUARTER, 0, false);
        check(p.tick == 480 && p.measure == 0, "advance quarter " + p);
        p = ScoreEdit.enter(s, p, chord(64, 67), Duration.EIGHTH, 1, false);
        p = ScoreEdit.enter(s, p, chord(66), Duration.N16, 0, false);
        p = ScoreEdit.enter(s, p, null, Duration.QUARTER, 0, false);
        check(p.tick == 1440, "after rest " + p);
        // half note at beat 4 crosses the bar line -> tied
        p = ScoreEdit.enter(s, p, chord(69), Duration.HALF, 0, false);
        check(p.measure == 1 && p.tick == 480, "crossed bar " + p);
        ChordRest last = s.measures.get(0).staves.get(0).voices[0].items.get(s.measures.get(0).staves.get(0).voices[0].items.size() - 1);
        check(last instanceof Chord && ((Chord) last).notes.get(0).tieForward, "tie across bar");
        checkVoicesFit(s, "after entry");
        // triplets
        p = ScoreEdit.enter(s, p, chord(71), Duration.EIGHTH, 0, true);
        check(p.tick == 640, "triplet advance " + p);
        p = ScoreEdit.enter(s, p, chord(72), Duration.EIGHTH, 0, false);
        p = ScoreEdit.enter(s, p, chord(74), Duration.EIGHTH, 0, false);
        check(p.tick == 960, "triplet end " + p);
        checkVoicesFit(s, "after triplet");
        // overwrite inside the triplet with a normal half note dissolves it
        Pos q = ScoreEdit.enter(s, new Pos(1, 0, 0, 480), chord(60), Duration.HALF, 0, false);
        check(q.tick == 1440, "overwrite " + q);
        checkVoicesFit(s, "after overwrite");
        // voice 2
        ScoreEdit.enter(s, new Pos(2, 0, 1, 0), chord(50), Duration.HALF, 0, false);
        checkVoicesFit(s, "voice 2");
        // delete
        ScoreEdit.deleteChordRest(s, new Pos(2, 0, 1, 0));
        check(s.measures.get(2).staves.get(0).voices[1].isEmpty(), "voice 2 removed after deleting");
        // duration change
        ScoreEdit.changeDuration(s, new Pos(0, 0, 0, 0), Duration.HALF, 0);
        checkVoicesFit(s, "duration change");
        // time signature change
        s.setTimeSig(1, 3, 4, false);
        checkVoicesFit(s, "3/4");
        s.setTimeSig(1, 5, 4, false);
        checkVoicesFit(s, "5/4");
        // copy paste
        ScoreEdit.Clip clip = ScoreEdit.copyRange(s, 0, 0, 0, 1);
        ScoreEdit.paste(s, clip, 3, 0);
        checkVoicesFit(s, "paste");
        // transpose
        ScoreEdit.transposeRange(s, 0, 3, 0, 2, 2);
        checkVoicesFit(s, "transpose");
        // measures
        s.insertMeasure(1);
        s.appendMeasures(2);
        s.deleteMeasures(0, 1);
        checkVoicesFit(s, "measures");
        // markings
        ChordRest first = ScoreEdit.elementAt(s, new Pos(0, 0, 0, 0));
        first.dynamic = "mf";
        first.chordSymbol = "F#m7/C#";
        first.lyric = "Hel-";
        first.articulations.add(Articulation.STACCATO);
        first.articulations.add(Articulation.FERMATA);
        s.measures.get(0).rehearsal = "A";
        s.measures.get(1).endBarline = Measure.BAR_END_REPEAT;
        s.measures.get(0).startRepeat = true;

        // mscx round trip
        String xml = MscxWriter.write(s);
        Score r = MscxReader.read(xml.getBytes("UTF-8")).score;
        checkVoicesFit(r, "mscx reload");
        String xml2 = MscxWriter.write(r);
        check(xml.equals(xml2), "mscx round trip stable");
        if (!xml.equals(xml2)) {
            Files.write(new File("/tmp/claude-0/a.mscx").toPath(), xml.getBytes("UTF-8"));
            Files.write(new File("/tmp/claude-0/b.mscx").toPath(), xml2.getBytes("UTF-8"));
        }
        ChordRest rf = ScoreEdit.elementAt(r, new Pos(0, 0, 0, 0));
        check("mf".equals(rf.dynamic) && "F#m7/C#".equals(rf.chordSymbol) && "Hel-".equals(rf.lyric), "markings kept: " + rf.dynamic + " " + rf.chordSymbol + " " + rf.lyric);
        check(rf.articulations.contains(Articulation.FERMATA) && rf.articulations.contains(Articulation.STACCATO), "articulations kept");

        // mscz
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        Mscz.write(s, bo, null);
        Score z = Mscz.load(bo.toByteArray(), "x.mscz").score;
        check(MscxWriter.write(z).equals(xml), "mscz round trip");

        // musicxml round trip
        String mx = MusicXmlWriter.write(s);
        Score mr = MusicXmlReader.read(mx.getBytes("UTF-8")).score;
        checkVoicesFit(mr, "musicxml reload");
        String mx2 = MusicXmlWriter.write(mr);
        check(mx.equals(mx2), "musicxml round trip stable");
        if (!mx.equals(mx2)) {
            Files.write(new File("/tmp/claude-0/a.musicxml").toPath(), mx.getBytes("UTF-8"));
            Files.write(new File("/tmp/claude-0/b.musicxml").toPath(), mx2.getBytes("UTF-8"));
        }

        // playback
        Timeline tl = Timeline.build(s);
        check(tl.plays.size() == s.measures.size() + 2, "repeat unrolled: " + tl.plays.size());
        byte[] midi = MidiWriter.write(s, tl, 1.0);
        check(midi.length > 50 && midi[0] == 'M', "midi");
        check(tl.positionAt(0)[0] == 0, "position 0");

        // pickup
        List<Part> parts = new ArrayList<Part>();
        parts.add(Instruments.byId("flute").create());
        Score pk = Score.create("Pickup", "", parts, 3, 4, 0, 90, 4, 480);
        checkVoicesFit(pk, "pickup");
        check(pk.measures.get(0).ticks() == 480 && pk.measureNumber(1) == 1, "pickup length/numbering");
        Score pk2 = MscxReader.read(MscxWriter.write(pk).getBytes("UTF-8")).score;
        check(pk2.measures.get(0).ticks() == 480 && pk2.measures.get(0).irregular, "pickup reload");
        Score pk3 = MusicXmlReader.read(MusicXmlWriter.write(pk).getBytes("UTF-8")).score;
        check(pk3.measures.get(0).ticks() == 480, "pickup musicxml reload " + pk3.measures.get(0).ticks());

        // editor session (note input as the UI drives it)
        {
            io.github.vlesk.stave.edit.EditorState es = new io.github.vlesk.stave.edit.EditorState(pianoScore());
            es.startInput();
            es.setDuration(Duration.EIGHTH);
            for (int i = 0; i < 6; i++) es.enterPitch(60 + i * 2, Integer.MIN_VALUE);
            es.chordMode = true;
            es.enterPitch(76, Integer.MIN_VALUE);
            es.chordMode = false;
            check(((Chord) es.selectedChordRest()).notes.size() == 2, "chord mode adds note");
            es.transpose(1);
            es.applyAccidental(-1);
            es.enterTie();
            es.enterRest();
            es.triplet = true;
            es.enterPitch(67, 1);
            es.triplet = false;
            es.enterLetter(4, false);
            es.enterLetter(0, true);
            es.moveCursor(-1);
            es.deleteSelection();
            checkVoicesFit(es.score, "editor input");
            int before = es.score.measures.size();
            es.undo();
            es.redo();
            es.stopInput();
            es.selectMeasure(1, 0);
            es.extendTo(2, 2);
            es.copy();
            es.selectMeasure(3, 0);
            es.paste();
            es.transpose(-2);
            es.toggleArticulation(Articulation.ACCENT);
            es.setDynamic("ff");
            es.setClef(Clef.BASS);
            es.setKeySig(-3);
            es.setTimeSig(6, 8, false);
            es.setBarline(Measure.BAR_DOUBLE);
            es.insertMeasureBefore();
            es.appendMeasures(2);
            es.deleteSelectedMeasures();
            checkVoicesFit(es.score, "editor ops");
            check(es.score.measures.size() >= before - 2, "measures sane");
            while (es.canUndo()) es.undo();
            checkVoicesFit(es.score, "undo all");
        }

        // render culling: tall system items (system barline, bracket, brace) must stay visible
        // while the top of their system is scrolled off screen
        {
            List<Part> ps = new ArrayList<Part>();
            ps.add(Instruments.byId("piano").create());
            ps.add(Instruments.byId("soprano").create());
            ps.add(Instruments.byId("tenor").create());
            ps.add(Instruments.byId("bass").create());
            Score sc = Score.create("Cull", "", ps, 4, 4, 0, 100, 4);
            Layout.Options lo = new Layout.Options();
            lo.width = 40;
            DisplayList dl = Layout.layout(sc, new TextMetrics() {
                public float width(String t, int font, float sizeSp) {
                    return t.length() * sizeSp * 0.5f;
                }
            }, lo);
            DisplayList.SystemInfo si = dl.systems.get(0);
            float top = si.staffY[0], bottom = si.staffY[si.staffY.length - 1] + 4;
            check(bottom - top > 30, "cull test system is tall: " + (bottom - top));
            // a viewport showing only the bottom of each tall item, far below its top
            int tall = 0;
            for (DisplayList.Item it : dl.items) {
                float iTop, iBottom;
                if (it.kind == DisplayList.LINE && it.x == it.x2) {
                    iTop = Math.min(it.y, it.y2);
                    iBottom = Math.max(it.y, it.y2);
                } else if (it.kind == DisplayList.POLY) {
                    iTop = Float.MAX_VALUE;
                    iBottom = -Float.MAX_VALUE;
                    for (int k = 1; k < it.pts.length; k += 2) {
                        iTop = Math.min(iTop, it.pts[k]);
                        iBottom = Math.max(iBottom, it.pts[k]);
                    }
                } else continue;
                if (iBottom - iTop < 16) continue;
                tall++;
                check(it.intersects(iBottom - 2, iBottom, 2), "tall item visible from below: kind " + it.kind + " top " + iTop);
            }
            check(tall >= 3, "found system barline, bracket and brace: " + tall);
            float v0 = bottom - 4, v1 = bottom + 2;
            DisplayList.Item off = new DisplayList.Item();
            off.kind = DisplayList.LINE;
            off.y = off.y2 = top;
            check(!off.intersects(v0, v1, 12), "far-off line culled");
        }

        // playback cursor: moves steadily through each measure and on into the next one
        {
            List<Part> ps = new ArrayList<Part>();
            ps.add(Instruments.byId("piano").create());
            ps.add(Instruments.byId("soprano").create());
            Score sc = Score.create("Cursor", "", ps, 4, 4, 3, 100, 4);
            ScoreEdit.enter(sc, new Pos(0, 0, 0, 0), chord(60), Duration.WHOLE, 0, false);
            Layout.Options lo = new Layout.Options();
            lo.width = 40;
            DisplayList dl = Layout.layout(sc, new TextMetrics() {
                public float width(String t, int font, float sizeSp) {
                    return t.length() * sizeSp * 0.5f;
                }
            }, lo);
            float prev = -Float.MAX_VALUE;
            int samples = 0;
            for (int m = 0; m < sc.measures.size(); m++) {
                int len = sc.measures.get(m).ticks();
                // a new system starts at the left margin, so only compare within one system
                boolean sameSystem = m == 0 || dl.measures.get(m).system == dl.measures.get(m - 1).system;
                if (!sameSystem) prev = -Float.MAX_VALUE;
                for (int t = 0; t <= len; t += len / 16) {
                    float x = dl.playX(m, t);
                    check(x >= prev - 1e-4f, "cursor moves forward m" + m + " t" + t + ": " + x + " < " + prev);
                    prev = x;
                    samples++;
                }
                if (m + 1 < sc.measures.size() && dl.measures.get(m + 1).system == dl.measures.get(m).system) {
                    check(Math.abs(dl.playX(m, len) - dl.playX(m + 1, 0)) < 1e-4f, "cursor continuous into m" + (m + 1));
                }
            }
            check(samples > 60, "cursor samples: " + samples);
            // halfway through a measure with a single whole note is not stuck at the note
            check(dl.playX(0, sc.measures.get(0).ticks() / 2) > dl.playX(0, 0) + 0.5f, "cursor moves within a whole-note bar");
        }

        // inline lyric entry: typing, space, composing text, backspace, undo, empty syllables
        {
            io.github.vlesk.stave.edit.EditorState es = new io.github.vlesk.stave.edit.EditorState(pianoScore());
            es.startInput();
            es.setDuration(Duration.QUARTER);
            es.enterPitch(60, Integer.MIN_VALUE);
            es.enterPitch(62, Integer.MIN_VALUE);
            es.enterPitch(64, Integer.MIN_VALUE);
            es.stopInput();
            Pos a = new Pos(0, 0, 0, 0), b = new Pos(0, 0, 0, 480), c3 = new Pos(0, 0, 0, 960);
            check(es.beginLyric(a), "lyric starts on a chord");
            es.lyricType("Hel-");
            check(es.lyricEdit && es.selPos.tick == 480, "a hyphen after a syllable moves to the next note");
            es.lyricCompose("l");
            es.lyricCompose("lo");
            es.lyricFinishComposing();
            es.lyricType(" ");
            check(es.lyricEdit && es.selPos.tick == 960, "space moves to the next note");
            check(!es.lyricNext() && es.lyricEdit && es.selPos.tick == 960, "at the last note, nothing moves and the mode stays");
            es.lyricType("-");
            check(es.lyricEdit && es.selPos.tick == 960 && "-".equals(lyricAt(es, c3)), "a hyphen at the start of a syllable is plain text");
            es.lyricBackspace();
            es.lyricBackspace();
            check(es.lyricEdit && es.selPos.tick == 480 && "lo".equals(lyricAt(es, b)), "backspace on an empty syllable goes back");
            es.lyricType("-");
            check(es.selPos.tick == 960 && "lo-".equals(lyricAt(es, b)), "typing the hyphen again moves on");
            es.lyricPrevious();
            es.lyricPrevious();
            check(!es.lyricPrevious() && es.lyricEdit && es.selPos.tick == 0, "at the first note, nothing moves and the mode stays");
            es.lyricInsertLiteral('-');
            check("Hel--".equals(lyricAt(es, a)) && es.selPos.tick == 0, "a literal hyphen does not move");
            es.lyricBackspace();
            es.lyricNext();
            es.lyricBackspace();
            es.endLyric();
            check(!es.lyricEdit, "endLyric ends entry");
            check("Hel-".equals(lyricAt(es, a)) && "lo".equals(lyricAt(es, b)) && lyricAt(es, c3) == null,
                    "syllables typed: " + lyricAt(es, a) + " / " + lyricAt(es, b));
            es.beginLyric(a);
            es.lyricBackspace();
            es.endLyric();
            check("Hel".equals(lyricAt(es, a)), "backspace removes a character");
            es.undo();
            check("Hel-".equals(lyricAt(es, a)), "undo restores the syllable");
            es.beginLyric(c3);
            es.endLyric();
            check(lyricAt(es, c3) == null, "an empty syllable is not kept");
            // where lyric entry starts
            es.clearSelection();
            Pos start = es.lyricStartPos();
            check(start != null && start.measure == 0 && start.tick == 0, "nothing selected: starts at the first chord");
            es.selectNote(b, -1);
            start = es.lyricStartPos();
            check(start != null && start.tick == 480, "starts at the selected chord");
            check(new io.github.vlesk.stave.edit.EditorState(pianoScore()).lyricStartPos() == null, "no chords: no start");
        }

        // lyric positions are in page coordinates: below their staff, not at the top of the page
        {
            List<Part> ps = new ArrayList<Part>();
            ps.add(Instruments.byId("soprano").create());
            Score sc = Score.create("Lyr", "", ps, 4, 4, 0, 100, 40);
            for (int m = 0; m < sc.measures.size(); m += 4) {
                Chord ch = chord(67);
                ch.lyric = "la";
                ScoreEdit.enter(sc, new Pos(m, 0, 0, 0), ch, Duration.QUARTER, 0, false);
            }
            Layout.Options lo = new Layout.Options();
            lo.width = 40;
            DisplayList dl = Layout.layout(sc, new TextMetrics() {
                public float width(String t, int font, float sizeSp) {
                    return t.length() * sizeSp * 0.5f;
                }
            }, lo);
            check(dl.systems.size() > 2, "several systems: " + dl.systems.size());
            int checked = 0;
            for (DisplayList.Hit h : dl.lyricHits) {
                DisplayList.SystemInfo si = dl.systems.get(dl.measures.get(h.pos.measure).system);
                float staffBottom = si.staffY[0] + 4;
                check(h.y0 > staffBottom && h.y1 < staffBottom + 8, "lyric hit sits below its staff: " + h.y0 + " vs staff bottom " + staffBottom);
                checked++;
            }
            check(checked >= 3, "lyric hits found: " + checked);
        }

        // lyrics as MuseScore lays them out: centred on the notehead, hyphen drawn as a centred dash,
        // neighbours never overlap, and files carry <syllabic> instead of a literal hyphen
        {
            List<Part> ps = new ArrayList<Part>();
            ps.add(Instruments.byId("soprano").create());
            Score sc = Score.create("Dash", "", ps, 4, 4, 0, 100, 4);
            String[] words = {"Hal-", "le-", "lu-", "jah,", "ex-", "tra-", "or-", "di-", "na-", "ry", "a", "song"};
            Pos wp = new Pos(0, 0, 0, 0);
            for (String w : words) {
                Chord ch = chord(67);
                ch.lyric = w;
                wp = ScoreEdit.enter(sc, wp, ch, Duration.QUARTER, 0, false);
            }
            TextMetrics tm = new TextMetrics() {
                public float width(String t, int font, float sizeSp) {
                    return t.length() * sizeSp * 0.5f;
                }
            };
            Layout.Options lo = new Layout.Options();
            lo.width = 50;
            DisplayList dl = Layout.layout(sc, tm, lo);
            float head = io.github.vlesk.stave.engrave.Sym.width(io.github.vlesk.stave.engrave.Sym.noteheadBlack);
            DisplayList.Hit hal = null, le = null, jah = null;
            for (DisplayList.Hit h : dl.lyricHits) {
                if ("Hal-".equals(h.cr.lyric)) hal = h;
                if ("le-".equals(h.cr.lyric)) le = h;
                if ("jah,".equals(h.cr.lyric)) jah = h;
            }
            check(hal != null && le != null && jah != null, "lyric hits for the test words");
            float noteCentre = dl.xAt(hal.pos.measure, hal.pos.tick) + head / 2;
            check(Math.abs((hal.x0 + hal.x1) / 2 - noteCentre) < 1e-3f && Math.abs(hal.x1 - hal.x0 - 3 * 1.0f) < 1e-3f,
                    "a syllable is centred on its notehead, without its hyphen: " + hal.x0 + ".." + hal.x1 + " centre " + noteCentre);
            float jahCentre = dl.xAt(jah.pos.measure, jah.pos.tick) + head / 2;
            check(Math.abs((jah.x0 + 3 * 1.0f / 2) - jahCentre) < 1e-3f, "trailing punctuation hangs outside the centring");
            boolean hyphenText = false;
            int dashes = 0;
            for (DisplayList.Item it : dl.items) {
                if (it.kind == DisplayList.TEXT && it.text != null && it.text.endsWith("-") && it.text.length() > 1) hyphenText = true;
                if (it.kind == DisplayList.LINE && it.y == it.y2 && it.x >= hal.x1 && it.x2 <= le.x0) {
                    dashes++;
                    float gapMid = (hal.x1 + le.x0) / 2;
                    check(Math.abs((it.x + it.x2) / 2 - gapMid) < 1e-3f, "the dash is centred between the syllables");
                    check(it.x2 - it.x >= 0.4f - 1e-4f && it.x2 - it.x <= 0.6f + 1e-4f, "dash length " + (it.x2 - it.x));
                    float textBaseline = Float.NaN;
                    for (DisplayList.Item t : dl.items) if (t.kind == DisplayList.TEXT && "Hal".equals(t.text)) textBaseline = t.y;
                    check(it.y < textBaseline && it.y > textBaseline - 1.0f, "the dash sits at mid x-height of the text: " + it.y + " vs " + textBaseline);
                }
            }
            check(!hyphenText, "no hyphen is drawn as text");
            check(dashes == 1, "one dash between Hal and le: " + dashes);
            // neighbours on a system keep MuseScore's gap; hyphenated ones leave room for the dash
            for (int k = 0; k + 1 < dl.lyricHits.size(); k++) {
                DisplayList.Hit a = dl.lyricHits.get(k), b = dl.lyricHits.get(k + 1);
                if (dl.measures.get(a.pos.measure).system != dl.measures.get(b.pos.measure).system) continue;
                float need = io.github.vlesk.stave.model.Syllables.hyphenated(a.cr.lyric) ? 0.5f : 0.25f;
                check(b.x0 - a.x1 >= need * 0.85f - 1e-3f, "lyrics overlap: " + a.cr.lyric + " / " + b.cr.lyric + " gap " + (b.x0 - a.x1));
            }
            // files: <syllabic> begin/middle/end, no literal hyphen; reading gives the same syllables back
            String mscx = MscxWriter.write(sc);
            check(mscx.contains("<syllabic>begin</syllabic>") && mscx.contains("<syllabic>middle</syllabic>")
                    && mscx.contains("<syllabic>end</syllabic>") && !mscx.contains("<text>Hal-</text>") && mscx.contains("<text>Hal</text>"),
                    "mscx writes syllabic");
            Score back = MscxReader.read(mscx.getBytes("UTF-8")).score;
            Score backXml = MusicXmlReader.read(MusicXmlWriter.write(sc).getBytes("UTF-8")).score;
            for (int k = 0; k < 4; k++) {
                ChordRest o = sc.measures.get(0).staves.get(0).voices[0].items.get(k);
                ChordRest rb = back.measures.get(0).staves.get(0).voices[0].items.get(k);
                ChordRest rx = backXml.measures.get(0).staves.get(0).voices[0].items.get(k);
                check(o.lyric.equals(rb.lyric) && o.lyric.equals(rx.lyric), "syllable round trip: " + o.lyric + " / " + rb.lyric + " / " + rx.lyric);
            }
        }

        // MuseScore's own test files
        int ok = 0, total = 0;
        if (args.length > 0) {
            List<File> files = new ArrayList<File>();
            collect(new File(args[0]), files);
            for (File f : files) {
                total++;
                try {
                    MscxReader.Result res = Mscz.load(Files.readAllBytes(f.toPath()), f.getName());
                    checkVoicesFit(res.score, f.getName());
                    Score again = MscxReader.read(MscxWriter.write(res.score).getBytes("UTF-8")).score;
                    checkVoicesFit(again, f.getName() + " rewrite");
                    Timeline.build(res.score);
                    MusicXmlReader.read(MusicXmlWriter.write(res.score).getBytes("UTF-8"));
                    ok++;
                } catch (Exception e) {
                    System.out.println("load failed " + f + ": " + e);
                }
            }
            System.out.println("MuseScore test files loaded: " + ok + "/" + total);
        }
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    static void collect(File dir, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collect(f, out);
            else if (f.getName().endsWith(".mscx") || f.getName().endsWith(".mscz")) out.add(f);
        }
    }
}
