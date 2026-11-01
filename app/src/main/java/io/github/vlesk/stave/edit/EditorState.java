package io.github.vlesk.stave.edit;

import io.github.vlesk.stave.model.Chord;
import io.github.vlesk.stave.model.ChordRest;
import io.github.vlesk.stave.model.Clef;
import io.github.vlesk.stave.model.Duration;
import io.github.vlesk.stave.model.Measure;
import io.github.vlesk.stave.model.Note;
import io.github.vlesk.stave.model.Pitch;
import io.github.vlesk.stave.model.Pos;
import io.github.vlesk.stave.model.Rest;
import io.github.vlesk.stave.model.Score;
import io.github.vlesk.stave.model.ScoreEdit;
import io.github.vlesk.stave.model.StaffMeasure;
import io.github.vlesk.stave.model.Voice;

import java.util.ArrayList;
import java.util.List;

/**
 * Editing session state: the score, undo history, selection and note-input state.
 * This is the touch-friendly port of MuseScore's note input and selection model; it has no
 * Android dependencies so it can be unit-tested on the JVM.
 */
public final class EditorState {
    public static final int SEL_NONE = 0, SEL_CHORDREST = 1, SEL_NOTE = 2, SEL_RANGE = 3;
    private static final int MAX_UNDO = 80;

    public Score score;
    private final List<Score> undo = new ArrayList<Score>();
    private final List<Score> redo = new ArrayList<Score>();
    /** Increments on every content change (used for autosave and re-layout). */
    public int revision;

    // selection
    public int selKind = SEL_NONE;
    public Pos selPos;
    public int selNote = -1;
    public int rangeM1, rangeM2, rangeS1, rangeS2;

    // note input
    public boolean inputMode;
    public Pos cursor;
    public int durType = Duration.QUARTER;
    public int dots;
    public boolean triplet;
    public int voice;
    /** When on, piano keys add notes to the chord just entered instead of advancing. */
    public boolean chordMode;
    private Pos lastEntered;

    private ScoreEdit.Clip clipboard;

    public EditorState(Score s) {
        score = s;
    }

    // ------------------------------------------------------------------ history

    /** Call before every modification. */
    public void checkpoint() {
        undo.add(score.copy());
        if (undo.size() > MAX_UNDO) undo.remove(0);
        redo.clear();
    }

    private void changed() {
        revision++;
        validateSelection();
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public void undo() {
        endLyric();
        if (undo.isEmpty()) return;
        redo.add(score);
        score = undo.remove(undo.size() - 1);
        changed();
    }

    public void redo() {
        endLyric();
        if (redo.isEmpty()) return;
        undo.add(score);
        score = redo.remove(redo.size() - 1);
        changed();
    }

    // ------------------------------------------------------------------ selection

    public void clearSelection() {
        selKind = SEL_NONE;
        selPos = null;
        selNote = -1;
    }

    public void selectNote(Pos p, int noteIdx) {
        selKind = noteIdx >= 0 ? SEL_NOTE : SEL_CHORDREST;
        selPos = p.copy();
        selNote = noteIdx;
        if (inputMode) {
            cursor = p.copy();
            voice = p.voice;
        }
    }

    public void selectMeasure(int m, int staff) {
        selKind = SEL_RANGE;
        rangeM1 = rangeM2 = m;
        rangeS1 = rangeS2 = staff;
        selPos = new Pos(m, staff, 0, 0);
    }

    /** Extends the current selection to a range ending at the measure/staff (long-press). */
    public void extendTo(int m, int staff) {
        int am, as;
        if (selKind == SEL_RANGE) {
            am = rangeM1;
            as = rangeS1;
            if (m < rangeM1) am = rangeM2;
            if (staff < rangeS1) as = rangeS2;
        } else if (selPos != null) {
            am = selPos.measure;
            as = selPos.staff;
        } else {
            am = m;
            as = staff;
        }
        selKind = SEL_RANGE;
        rangeM1 = Math.min(am, m);
        rangeM2 = Math.max(am, m);
        rangeS1 = Math.min(as, staff);
        rangeS2 = Math.max(as, staff);
        selPos = new Pos(rangeM1, rangeS1, 0, 0);
    }

    public void selectAll() {
        selKind = SEL_RANGE;
        rangeM1 = 0;
        rangeM2 = score.measures.size() - 1;
        rangeS1 = 0;
        rangeS2 = score.staffCount() - 1;
        selPos = new Pos(0, 0, 0, 0);
    }

    public ChordRest selectedChordRest() {
        if ((selKind == SEL_CHORDREST || selKind == SEL_NOTE) && selPos != null) return ScoreEdit.elementAt(score, selPos);
        return null;
    }

    public Note selectedNote() {
        ChordRest cr = selectedChordRest();
        if (cr instanceof Chord) {
            Chord c = (Chord) cr;
            if (selNote >= 0 && selNote < c.notes.size()) return c.notes.get(selNote);
            return c.top();
        }
        return null;
    }

    public boolean hasSelection() {
        return selKind != SEL_NONE;
    }

    private void validateSelection() {
        if (selKind == SEL_RANGE) {
            int mc = score.measures.size() - 1, sc = score.staffCount() - 1;
            rangeM1 = Math.min(rangeM1, mc);
            rangeM2 = Math.min(rangeM2, mc);
            rangeS1 = Math.min(rangeS1, sc);
            rangeS2 = Math.min(rangeS2, sc);
            return;
        }
        if (selPos == null) return;
        if (selPos.measure >= score.measures.size() || selPos.staff >= score.staffCount()) {
            clearSelection();
            return;
        }
        ChordRest cr = ScoreEdit.elementAt(score, selPos);
        if (cr == null) {
            Voice v = score.measures.get(selPos.measure).staves.get(selPos.staff).voices[selPos.voice];
            if (v.isEmpty()) {
                clearSelection();
                return;
            }
            selPos = ScoreEdit.snapToElement(score, selPos);
            cr = ScoreEdit.elementAt(score, selPos);
        }
        if (cr instanceof Chord) {
            if (selNote >= ((Chord) cr).notes.size()) selNote = ((Chord) cr).notes.size() - 1;
        } else if (selKind == SEL_NOTE) {
            selKind = SEL_CHORDREST;
            selNote = -1;
        }
    }

    // ------------------------------------------------------------------ note input

    public void startInput() {
        inputMode = true;
        if (selPos != null && selKind != SEL_RANGE) {
            cursor = selPos.copy();
            voice = selPos.voice;
        } else if (selKind == SEL_RANGE) {
            cursor = new Pos(rangeM1, rangeS1, voice, 0);
        } else if (cursor == null) {
            cursor = new Pos(0, 0, voice, 0);
        }
        cursor.voice = voice;
        clampCursor();
        lastEntered = null;
    }

    public void stopInput() {
        inputMode = false;
        chordMode = false;
    }

    private void clampCursor() {
        if (cursor.measure >= score.measures.size()) cursor.measure = score.measures.size() - 1;
        if (cursor.staff >= score.staffCount()) cursor.staff = score.staffCount() - 1;
        if (cursor.tick >= score.measures.get(cursor.measure).ticks()) cursor.tick = 0;
    }

    public void setVoice(int v) {
        voice = v;
        if (cursor != null) {
            cursor.voice = v;
            // voices 2-4 may be empty: keep the cursor tick
        }
    }

    public int keyAtCursor() {
        return score.keyAt(cursor != null ? cursor.measure : 0);
    }

    /** Clef at the input cursor (for choosing the octave of new notes). */
    public int clefAtCursor() {
        return cursor == null ? Clef.TREBLE : score.clefAt(cursor.staff, cursor.measure);
    }

    /** Enters a pitch at the cursor (or adds it to the current chord in chord mode). Returns the pitch. */
    public int enterPitch(int pitch, int forcedAlter) {
        if (cursor == null) startInput();
        pitch = Math.max(0, Math.min(127, pitch));
        int key = score.keyAt(cursor.measure);
        int tpc = Pitch.spell(pitch, key);
        if (forcedAlter != Integer.MIN_VALUE) {
            int t = Pitch.spellWithAlter(pitch, forcedAlter);
            if (Pitch.validTpc(t)) tpc = t;
        }
        checkpoint();
        if (chordMode && lastEntered != null && ScoreEdit.elementAt(score, lastEntered) instanceof Chord) {
            ScoreEdit.addToChord(score, lastEntered, pitch, tpc);
            selectEnteredNote(lastEntered, pitch);
            changed();
            return pitch;
        }
        Chord c = new Chord();
        c.addNote(new Note(pitch, tpc));
        Pos at = cursor.copy();
        Pos after = ScoreEdit.enter(score, at, c, durType, triplet ? 0 : dots, triplet);
        lastEntered = at;
        cursor = after;
        cursor.voice = voice;
        selectEnteredNote(at, pitch);
        changed();
        return pitch;
    }

    private void selectEnteredNote(Pos at, int pitch) {
        ChordRest cr = ScoreEdit.elementAt(score, at);
        int idx = -1;
        if (cr instanceof Chord) {
            List<Note> ns = ((Chord) cr).notes;
            for (int i = 0; i < ns.size(); i++) if (ns.get(i).pitch == pitch) idx = i;
        }
        selKind = idx >= 0 ? SEL_NOTE : SEL_CHORDREST;
        selPos = at.copy();
        selNote = idx;
    }

    /** Adds a pitch to the chord at the selection (MuseScore: Shift+letter / "add to chord"). */
    public void addPitchToSelectedChord(int pitch) {
        Pos p = selPos != null ? selPos : lastEntered;
        if (p == null) return;
        int key = score.keyAt(p.measure);
        checkpoint();
        ScoreEdit.addToChord(score, p, pitch, Pitch.spell(pitch, key));
        selectEnteredNote(p, pitch);
        changed();
    }

    public void enterRest() {
        if (cursor == null) startInput();
        checkpoint();
        Pos at = cursor.copy();
        cursor = ScoreEdit.enter(score, at, null, durType, triplet ? 0 : dots, triplet);
        cursor.voice = voice;
        lastEntered = null;
        selKind = SEL_CHORDREST;
        selPos = at;
        selNote = -1;
        changed();
    }

    /** Enters a tied copy of the previous chord at the cursor (MuseScore "+" in note input). */
    public boolean enterTie() {
        if (cursor == null) return false;
        Pos prev = lastEntered;
        if (prev == null) prev = ScoreEdit.prev(score, cursor);
        ChordRest pcr = prev != null ? ScoreEdit.elementAt(score, prev) : null;
        if (!(pcr instanceof Chord)) return false;
        checkpoint();
        Chord tmpl = ((Chord) pcr).copy();
        tmpl.clearMarkings();
        tmpl.tuplet = null;
        for (Note n : tmpl.notes) n.tieForward = false;
        for (Note n : ((Chord) pcr).notes) n.tieForward = true;
        Pos at = cursor.copy();
        cursor = ScoreEdit.enter(score, at, tmpl, durType, triplet ? 0 : dots, triplet);
        cursor.voice = voice;
        lastEntered = at;
        selKind = SEL_CHORDREST;
        selPos = at;
        selNote = -1;
        changed();
        return true;
    }

    /** Letter-name entry from a hardware keyboard: picks the octave closest to the previous note. */
    public int enterLetter(int step, boolean addToChord) {
        int key = score.keyAt(cursor != null ? cursor.measure : 0);
        int ref = referencePitch();
        int alter = Pitch.keyAlter(key, step);
        int best = -1, bestDist = Integer.MAX_VALUE;
        for (int oct = 0; oct <= 9; oct++) {
            int p = Pitch.pitchFromDiatonic(oct * 7 + step, alter);
            if (p < 0 || p > 127) continue;
            int d = Math.abs(p - ref);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        if (best < 0) return -1;
        if (addToChord) {
            addPitchToSelectedChord(best);
            return best;
        }
        return enterPitch(best, Integer.MIN_VALUE);
    }

    private int referencePitch() {
        Note n = selectedNote();
        if (n != null) return n.pitch;
        if (cursor != null) {
            Chord c = ScoreEdit.prevChordBefore(score, cursor);
            if (c != null) return c.top().pitch;
            return Clef.middlePitch(score.clefAt(cursor.staff, cursor.measure));
        }
        return 71;
    }

    public void moveCursor(int dir) {
        if (inputMode && cursor != null) {
            Pos snap = ScoreEdit.snapToElement(score, cursor);
            Voice v = score.measures.get(snap.measure).staves.get(snap.staff).voices[snap.voice];
            Pos np;
            if (v.isEmpty()) {
                np = new Pos(Math.max(0, Math.min(score.measures.size() - 1, snap.measure + dir)), snap.staff, snap.voice, 0);
            } else {
                np = dir > 0 ? ScoreEdit.next(score, snap) : ScoreEdit.prev(score, snap);
                if (dir < 0 && snap.tick < cursor.tick) np = snap;
            }
            if (np != null) cursor = np;
            lastEntered = null;
            ChordRest cr = ScoreEdit.elementAt(score, cursor);
            if (cr != null) {
                selKind = cr instanceof Chord ? SEL_NOTE : SEL_CHORDREST;
                selPos = cursor.copy();
                selNote = cr instanceof Chord ? ((Chord) cr).notes.size() - 1 : -1;
            }
            return;
        }
        if (selPos == null) return;
        Pos np = dir > 0 ? ScoreEdit.next(score, selPos) : ScoreEdit.prev(score, selPos);
        if (np == null) return;
        ChordRest cr = ScoreEdit.elementAt(score, np);
        selKind = cr instanceof Chord ? SEL_NOTE : SEL_CHORDREST;
        selPos = np;
        selNote = cr instanceof Chord ? ((Chord) cr).notes.size() - 1 : -1;
    }

    /** Moves the input cursor to a tapped position. */
    public void setCursor(Pos p) {
        cursor = p.copy();
        voice = p.voice;
        lastEntered = null;
    }

    // ------------------------------------------------------------------ duration

    public void setDuration(int type) {
        durType = type;
        if (type == Duration.MAX_TYPE) dots = 0;
        if (!inputMode) applyDurationToSelection();
    }

    public void toggleDot() {
        dots = dots == 1 ? 0 : 1;
        if (durType == Duration.MAX_TYPE) dots = 0;
        if (!inputMode) applyDurationToSelection();
    }

    private void applyDurationToSelection() {
        if (selKind != SEL_CHORDREST && selKind != SEL_NOTE) return;
        ChordRest cr = selectedChordRest();
        if (cr == null) return;
        checkpoint();
        ScoreEdit.changeDuration(score, selPos, durType, dots);
        changed();
    }

    // ------------------------------------------------------------------ modifications on selection

    public void deleteSelection() {
        if (selKind == SEL_RANGE) {
            checkpoint();
            ScoreEdit.clearRange(score, rangeM1, rangeM2, rangeS1, rangeS2);
            changed();
            return;
        }
        if (selPos == null) return;
        ChordRest cr = selectedChordRest();
        if (cr == null) return;
        checkpoint();
        if (selKind == SEL_NOTE && cr instanceof Chord && ((Chord) cr).notes.size() > 1) {
            ScoreEdit.deleteNote(score, selPos, selNote);
            selNote = Math.max(0, selNote - 1);
        } else {
            ScoreEdit.deleteChordRest(score, selPos);
            if (selPos.voice > 0 && score.measures.get(selPos.measure).staves.get(selPos.staff).voices[selPos.voice].isEmpty()) {
                clearSelection();
            }
        }
        if (inputMode && selPos != null) {
            cursor = selPos.copy();
            lastEntered = null;
        }
        changed();
    }

    /** Semitone (or octave with 12) transposition of the selected note(s). */
    public void transpose(int semis) {
        if (selKind == SEL_RANGE) {
            checkpoint();
            ScoreEdit.transposeRange(score, rangeM1, rangeM2, rangeS1, rangeS2, semis);
            changed();
            return;
        }
        ChordRest cr = selectedChordRest();
        if (!(cr instanceof Chord)) return;
        checkpoint();
        Chord c = (Chord) cr;
        if (selKind == SEL_NOTE) {
            Note n = selectedNote();
            int target = n.pitch + semis;
            for (Note o : c.notes) if (o != n && o.pitch == target) return;
            ScoreEdit.transposeNote(score, selPos.measure, n, semis);
            c.sortNotes();
            selNote = c.notes.indexOf(n);
        } else {
            for (Note n : c.notes) ScoreEdit.transposeNote(score, selPos.measure, n, semis);
            c.sortNotes();
        }
        changed();
    }

    public void applyAccidental(int alter) {
        Note n = selectedNote();
        if (selKind == SEL_RANGE || n == null) return;
        checkpoint();
        ScoreEdit.setAccidental(n, alter);
        ((Chord) selectedChordRest()).sortNotes();
        changed();
    }

    public boolean toggleTie() {
        if (selPos == null) return false;
        checkpoint();
        boolean ok = ScoreEdit.toggleTie(score, selPos);
        if (!ok) {
            undo.remove(undo.size() - 1);
            return false;
        }
        changed();
        return true;
    }

    public void toggleArticulation(String id) {
        List<ChordRest> targets = targets();
        if (targets.isEmpty()) return;
        checkpoint();
        boolean allHave = true;
        for (ChordRest cr : targets) if (!cr.articulations.contains(id)) allHave = false;
        for (ChordRest cr : targets) {
            if (cr instanceof Rest && !id.equals(io.github.vlesk.stave.model.Articulation.FERMATA)) continue;
            if (allHave) cr.articulations.remove(id);
            else if (!cr.articulations.contains(id)) cr.articulations.add(id);
        }
        changed();
    }

    public void setDynamic(String d) {
        ChordRest cr = firstTarget();
        if (cr == null) return;
        checkpoint();
        cr.dynamic = d != null && d.equals(cr.dynamic) ? null : d;
        changed();
    }

    public void setLyric(String text) {
        ChordRest cr = selectedChordRest();
        if (!(cr instanceof Chord)) return;
        checkpoint();
        cr.lyric = text == null || text.trim().isEmpty() ? null : text.trim();
        changed();
    }

    public void setChordSymbol(String text) {
        ChordRest cr = firstTarget();
        if (cr == null) return;
        checkpoint();
        cr.chordSymbol = text == null || text.trim().isEmpty() ? null : text.trim();
        changed();
    }

    public void setStaffText(String text) {
        ChordRest cr = firstTarget();
        if (cr == null) return;
        checkpoint();
        cr.staffText = text == null || text.trim().isEmpty() ? null : text.trim();
        changed();
    }

    // ------------------------------------------------------------------ inline lyric entry

    /**
     * True while lyrics are typed on the score. The text being typed is the selected chord's own
     * lyric, so layout and drawing need no separate draft.
     */
    public boolean lyricEdit;
    /** Trailing characters of the lyric that an input method is still composing. */
    private int lyricComposing;
    /** The next change to a lyric first takes an undo step (one step per syllable). */
    private boolean lyricUndoPending;

    private Chord lyricChord() {
        ChordRest cr = selectedChordRest();
        return cr instanceof Chord ? (Chord) cr : null;
    }

    private void lyricBeforeChange() {
        if (lyricUndoPending) {
            checkpoint();
            lyricUndoPending = false;
        }
    }

    /** Drops an empty syllable, so no empty lyric is kept. */
    private void lyricFinish(Chord c) {
        lyricComposing = 0;
        if (c == null || c.lyric == null) return;
        String t = c.lyric.trim();
        c.lyric = t.isEmpty() ? null : t;
    }

    /** Removes the text an input method is still composing. */
    private void lyricDropComposing() {
        Chord c = lyricChord();
        if (c != null && c.lyric != null && lyricComposing > 0) {
            c.lyric = c.lyric.substring(0, Math.max(0, c.lyric.length() - lyricComposing));
        }
        lyricComposing = 0;
    }

    /**
     * Where lyric entry starts: the selected chord, else the first chord of the selected range or
     * staff, else the first chord of the score. Null if the score has no chord at all.
     */
    public Pos lyricStartPos() {
        ChordRest cr = selectedChordRest();
        if (cr instanceof Chord) return selPos.copy();
        int m = 0, staff = 0;
        if (selKind == SEL_RANGE) {
            m = rangeM1;
            staff = rangeS1;
        } else if (selPos != null) {
            m = selPos.measure;
            staff = selPos.staff;
        }
        Pos p = new Pos(m, staff, 0, 0);
        while (p != null) {
            if (ScoreEdit.elementAt(score, p) instanceof Chord) return p;
            p = ScoreEdit.next(score, p);
        }
        return null;
    }

    /** Starts typing the lyric of the chord at p. An existing lyric is continued at its end. */
    public boolean beginLyric(Pos p) {
        if (p == null) return false;
        ChordRest cr = ScoreEdit.elementAt(score, p);
        if (!(cr instanceof Chord)) return false;
        if (lyricEdit) lyricFinish(lyricChord());
        selKind = SEL_CHORDREST;
        selPos = p.copy();
        selNote = -1;
        if (cr.lyric == null) cr.lyric = "";
        lyricEdit = true;
        lyricComposing = 0;
        lyricUndoPending = true;
        changed();
        return true;
    }

    /** Ends the current syllable and moves to the next chord. Ends lyric entry after the last chord. */
    public boolean lyricNext() {
        return lyricMove(true);
    }

    private boolean lyricMove(boolean forward) {
        if (!lyricEdit) return false;
        lyricDropComposing();
        lyricFinish(lyricChord());
        Pos p = selPos;
        while (p != null) {
            p = forward ? ScoreEdit.next(score, p) : ScoreEdit.prev(score, p);
            if (p == null) break;
            ChordRest cr = ScoreEdit.elementAt(score, p);
            if (cr instanceof Chord) {
                selKind = SEL_CHORDREST;
                selPos = p;
                selNote = -1;
                if (cr.lyric == null) cr.lyric = "";
                lyricUndoPending = true;
                changed();
                return true;
            }
        }
        // no further chord: stop typing and keep the last syllable selected
        lyricEdit = false;
        lyricUndoPending = false;
        changed();
        return false;
    }

    /** Types text into the lyric. A space or newline ends the syllable, as in MuseScore. */
    public void lyricType(CharSequence text) {
        if (!lyricEdit || text == null) return;
        lyricDropComposing();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ' ' || ch == '\n') {
                lyricMove(true);
                if (!lyricEdit) return;
            } else if (!Character.isISOControl(ch)) {
                Chord c = lyricChord();
                if (c == null) return;
                lyricBeforeChange();
                c.lyric = (c.lyric == null ? "" : c.lyric) + ch;
                changed();
            }
        }
    }

    /** Shows the text an input method is composing (replaces the previous composing text). */
    public void lyricCompose(CharSequence text) {
        Chord c = lyricChord();
        if (!lyricEdit || c == null) return;
        String t = text == null ? "" : text.toString();
        if (t.indexOf(' ') >= 0 || t.indexOf('\n') >= 0) {
            lyricType(t);
            return;
        }
        lyricBeforeChange();
        lyricDropComposing();
        c.lyric = (c.lyric == null ? "" : c.lyric) + t;
        lyricComposing = t.length();
        changed();
    }

    /** The input method has finished its composing text; it stays in the lyric. */
    public void lyricFinishComposing() {
        lyricComposing = 0;
    }

    /** Deletes the last character of the syllable. At the start of a syllable, moves back to the previous one. */
    public void lyricBackspace() {
        Chord c = lyricChord();
        if (!lyricEdit || c == null) return;
        if (lyricComposing > 0) {
            lyricBeforeChange();
            lyricDropComposing();
            changed();
            return;
        }
        String base = c.lyric == null ? "" : c.lyric;
        if (!base.isEmpty()) {
            lyricBeforeChange();
            c.lyric = base.substring(0, base.length() - 1);
            changed();
        } else {
            lyricMove(false);
        }
    }

    /** Ends lyric entry, keeping what was typed. */
    public void endLyric() {
        if (!lyricEdit) return;
        lyricDropComposing();
        lyricFinish(lyricChord());
        lyricEdit = false;
        lyricUndoPending = false;
        changed();
    }

    /** Moves the selection to the next chord (used to type lyrics syllable by syllable). */
    public boolean selectNextChord() {
        if (selPos == null) return false;
        Pos p = ScoreEdit.next(score, selPos);
        while (p != null) {
            ChordRest cr = ScoreEdit.elementAt(score, p);
            if (cr instanceof Chord) {
                selKind = SEL_CHORDREST;
                selPos = p;
                selNote = -1;
                return true;
            }
            p = ScoreEdit.next(score, p);
        }
        return false;
    }

    private ChordRest firstTarget() {
        if (selKind == SEL_RANGE) {
            Voice v = score.measures.get(rangeM1).staves.get(rangeS1).voices[0];
            return v.items.isEmpty() ? null : v.items.get(0);
        }
        return selectedChordRest();
    }

    private List<ChordRest> targets() {
        List<ChordRest> out = new ArrayList<ChordRest>();
        if (selKind == SEL_RANGE) {
            for (int m = rangeM1; m <= rangeM2; m++) {
                for (int st = rangeS1; st <= rangeS2; st++) {
                    for (Voice v : score.measures.get(m).staves.get(st).voices) {
                        for (ChordRest cr : v.items) if (cr instanceof Chord) out.add(cr);
                    }
                }
            }
        } else {
            ChordRest cr = selectedChordRest();
            if (cr != null) out.add(cr);
        }
        return out;
    }

    /** Measure index the selection (or cursor) starts at; 0 if none. */
    public int selectionMeasure() {
        if (selKind == SEL_RANGE) return rangeM1;
        if (selPos != null) return selPos.measure;
        if (cursor != null) return cursor.measure;
        return 0;
    }

    public int selectionStaff() {
        if (selKind == SEL_RANGE) return rangeS1;
        if (selPos != null) return selPos.staff;
        if (cursor != null) return cursor.staff;
        return 0;
    }

    // ------------------------------------------------------------------ measure-level

    public void setClef(int clef) {
        int m = selectionMeasure();
        int s1 = selectionStaff(), s2 = selKind == SEL_RANGE ? rangeS2 : s1;
        checkpoint();
        for (int st = s1; st <= s2; st++) {
            if (m == 0) {
                score.staff(st).defaultClef = clef;
                score.measures.get(0).staves.get(st).clef = -1;
            } else {
                score.measures.get(m).staves.get(st).clef = clef == score.clefAt(st, m - 1) ? -1 : clef;
            }
        }
        changed();
    }

    public void setKeySig(int fifths) {
        checkpoint();
        score.setKeySig(selectionMeasure(), fifths);
        changed();
    }

    public void setTimeSig(int n, int d, boolean symbol) {
        checkpoint();
        score.setTimeSig(selectionMeasure(), n, d, symbol);
        changed();
    }

    public void setBarline(int type) {
        int m = selKind == SEL_RANGE ? rangeM2 : selectionMeasure();
        checkpoint();
        Measure mm = score.measures.get(m);
        mm.endBarline = mm.endBarline == type ? Measure.BAR_NORMAL : type;
        changed();
    }

    public void toggleStartRepeat() {
        int m = selectionMeasure();
        checkpoint();
        score.measures.get(m).startRepeat = !score.measures.get(m).startRepeat;
        changed();
    }

    public void setTempo(int m, int bpm, String text) {
        checkpoint();
        Measure mm = score.measures.get(m);
        mm.tempo = Math.max(0, Math.min(400, bpm));
        mm.tempoText = text == null || text.trim().isEmpty() ? null : text.trim();
        changed();
    }

    public void setRehearsal(String text) {
        int m = selectionMeasure();
        checkpoint();
        score.measures.get(m).rehearsal = text == null || text.trim().isEmpty() ? null : text.trim();
        changed();
    }

    public void insertMeasureBefore() {
        int m = selectionMeasure();
        checkpoint();
        score.insertMeasure(m);
        changed();
    }

    public void appendMeasures(int n) {
        checkpoint();
        score.appendMeasures(n);
        changed();
    }

    public void deleteSelectedMeasures() {
        int m1 = selKind == SEL_RANGE ? rangeM1 : selectionMeasure();
        int m2 = selKind == SEL_RANGE ? rangeM2 : m1;
        if (score.measures.size() <= 1) return;
        checkpoint();
        score.deleteMeasures(m1, m2);
        clearSelection();
        if (cursor != null) clampCursor();
        changed();
    }

    // ------------------------------------------------------------------ clipboard

    public boolean copy() {
        if (selKind == SEL_RANGE) {
            clipboard = ScoreEdit.copyRange(score, rangeM1, rangeM2, rangeS1, rangeS2);
            return true;
        }
        if (selPos != null) {
            clipboard = ScoreEdit.copyRange(score, selPos.measure, selPos.measure, selPos.staff, selPos.staff);
            return true;
        }
        return false;
    }

    public boolean hasClipboard() {
        return clipboard != null;
    }

    public boolean paste() {
        if (clipboard == null) return false;
        int m = selectionMeasure(), st = selectionStaff();
        checkpoint();
        int n = ScoreEdit.paste(score, clipboard, m, st);
        selKind = SEL_RANGE;
        rangeM1 = m;
        rangeM2 = Math.min(score.measures.size() - 1, m + clipboard.measures.size() - 1);
        rangeS1 = st;
        rangeS2 = Math.min(score.staffCount() - 1, st + clipboard.staffCount() - 1);
        changed();
        return n > 0;
    }

    /** Applies a structural change made directly to the score (instruments, properties). */
    public void externalChange() {
        changed();
    }

    /** Fills empty voice selection helper: ensures selection points to an existing element. */
    public static boolean voiceHasContent(StaffMeasure sm, int v) {
        return !sm.voices[v].isEmpty();
    }
}
