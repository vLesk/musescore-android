package io.github.vlesk.stave;

import io.github.vlesk.stave.audio.MidiWriter;
import io.github.vlesk.stave.audio.Timeline;
import io.github.vlesk.stave.io.MscxReader;
import io.github.vlesk.stave.io.MscxWriter;
import io.github.vlesk.stave.io.Mscz;
import io.github.vlesk.stave.io.MusicXmlReader;
import io.github.vlesk.stave.io.MusicXmlWriter;
import io.github.vlesk.stave.model.*;

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
