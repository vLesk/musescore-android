package io.github.vlesk.stave.model;

import java.util.ArrayList;
import java.util.List;

/** The complete score: metadata, instruments and the sequence of measures. */
public final class Score {
    public String title = "Untitled score";
    public String subtitle = "";
    public String composer = "";
    public String lyricist = "";
    public String copyright = "";
    public final List<Part> parts = new ArrayList<Part>();
    public final List<Measure> measures = new ArrayList<Measure>();

    // ---------------------------------------------------------------- staves

    public int staffCount() {
        int n = 0;
        for (Part p : parts) n += p.staves.size();
        return n;
    }

    public Staff staff(int idx) {
        for (Part p : parts) {
            if (idx < p.staves.size()) return p.staves.get(idx);
            idx -= p.staves.size();
        }
        throw new IndexOutOfBoundsException();
    }

    public Part partOfStaff(int idx) {
        for (Part p : parts) {
            if (idx < p.staves.size()) return p;
            idx -= p.staves.size();
        }
        throw new IndexOutOfBoundsException();
    }

    public int firstStaffOf(Part part) {
        int n = 0;
        for (Part p : parts) {
            if (p == part) return n;
            n += p.staves.size();
        }
        return -1;
    }

    /** Effective clef of a staff at the start of a measure (after any clef change there). */
    public int clefAt(int staff, int measure) {
        for (int m = Math.min(measure, measures.size() - 1); m >= 0; m--) {
            int c = measures.get(m).staves.get(staff).clef;
            if (c >= 0) return c;
        }
        return staff(staff).defaultClef;
    }

    public int keyAt(int measure) {
        if (measures.isEmpty()) return 0;
        return measures.get(Math.min(measure, measures.size() - 1)).keyFifths;
    }

    public int tempoAt(int measure) {
        for (int m = Math.min(measure, measures.size() - 1); m >= 0; m--) {
            if (measures.get(m).tempo > 0) return measures.get(m).tempo;
        }
        return 120;
    }

    /** Displayed measure number (pickup bars are not counted). */
    public int measureNumber(int measure) {
        int n = 0;
        for (int i = 0; i <= measure && i < measures.size(); i++) if (!measures.get(i).irregular) n++;
        return Math.max(n, 1);
    }

    public int measureStartTick(int measure) {
        int t = 0;
        for (int i = 0; i < measure && i < measures.size(); i++) t += measures.get(i).ticks();
        return t;
    }

    public int totalTicks() {
        return measureStartTick(measures.size());
    }

    // ---------------------------------------------------------------- structure

    /** Creates an empty measure using the attributes of the given template (or defaults). */
    public Measure newMeasureLike(Measure template) {
        Measure m = new Measure();
        if (template != null) {
            m.timeN = template.timeN;
            m.timeD = template.timeD;
            m.timeSymbol = template.timeSymbol;
            m.keyFifths = template.keyFifths;
        }
        int n = staffCount();
        for (int i = 0; i < n; i++) {
            StaffMeasure sm = new StaffMeasure();
            sm.voices[0].items.add(Rest.measureRest(m.ticks()));
            m.staves.add(sm);
        }
        return m;
    }

    public void appendMeasures(int count) {
        for (int i = 0; i < count; i++) {
            Measure last = measures.isEmpty() ? null : measures.get(measures.size() - 1);
            Measure m = newMeasureLike(last);
            if (last != null && last.endBarline == Measure.BAR_FINAL) {
                last.endBarline = Measure.BAR_NORMAL;
                m.endBarline = Measure.BAR_FINAL;
            }
            measures.add(m);
        }
    }

    public void insertMeasure(int index) {
        Measure ref = measures.isEmpty() ? null : measures.get(Math.min(index, measures.size() - 1));
        Measure m = newMeasureLike(ref);
        measures.add(index, m);
    }

    public void deleteMeasures(int from, int to) {
        if (measures.size() <= 1) return;
        to = Math.min(to, measures.size() - 1);
        int count = to - from + 1;
        if (count >= measures.size()) {
            from = 1;
            to = measures.size() - 1;
            // keep first measure but clear it
            Measure first = measures.get(0);
            for (StaffMeasure sm : first.staves) {
                for (int v = 0; v < StaffMeasure.VOICES; v++) sm.voices[v].items.clear();
                sm.voices[0].items.add(Rest.measureRest(first.ticks()));
            }
            if (from > to) return;
        }
        Measure deletedFirst = measures.get(from);
        for (int i = to; i >= from; i--) measures.remove(i);
        // preserve signature changes / tempo that started in the deleted range
        if (from < measures.size()) {
            Measure next = measures.get(from);
            if (deletedFirst.tempo > 0 && next.tempo == 0) next.tempo = deletedFirst.tempo;
        }
    }

    /** Adds a part to the end of the score, filling existing measures with rests. */
    public void addPart(Part p) {
        int insertAt = staffCount();
        parts.add(p);
        for (Measure m : measures) {
            for (int s = 0; s < p.staves.size(); s++) {
                StaffMeasure sm = new StaffMeasure();
                sm.voices[0].items.add(Rest.measureRest(m.ticks()));
                m.staves.add(insertAt + s, sm);
            }
        }
    }

    public void removePart(Part p) {
        int first = firstStaffOf(p);
        if (first < 0 || parts.size() <= 1) return;
        for (Measure m : measures) {
            for (int s = 0; s < p.staves.size(); s++) m.staves.remove(first);
        }
        parts.remove(p);
    }

    public void movePart(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= parts.size() || to >= parts.size()) return;
        Part p = parts.get(from);
        int firstStaff = firstStaffOf(p);
        int n = p.staves.size();
        List<List<StaffMeasure>> moved = new ArrayList<List<StaffMeasure>>();
        for (Measure m : measures) {
            List<StaffMeasure> block = new ArrayList<StaffMeasure>();
            for (int s = 0; s < n; s++) block.add(m.staves.remove(firstStaff));
            moved.add(block);
        }
        parts.remove(from);
        parts.add(to, p);
        int target = firstStaffOf(p);
        for (int i = 0; i < measures.size(); i++) {
            measures.get(i).staves.addAll(target, moved.get(i));
        }
    }

    /** Sets the time signature from a measure until the next explicit change. */
    public void setTimeSig(int fromMeasure, int n, int d, boolean symbol) {
        Measure start = measures.get(fromMeasure);
        int oldN = start.timeN, oldD = start.timeD;
        for (int i = fromMeasure; i < measures.size(); i++) {
            Measure m = measures.get(i);
            if (i > fromMeasure && (m.timeN != oldN || m.timeD != oldD)) break;
            int oldTicks = m.ticks();
            m.actualTicks = 0;
            m.timeN = n;
            m.timeD = d;
            m.timeSymbol = symbol;
            if (m.ticks() != oldTicks) ScoreEdit.refitMeasure(m, oldTicks);
        }
    }

    public void setKeySig(int fromMeasure, int fifths) {
        int old = measures.get(fromMeasure).keyFifths;
        for (int i = fromMeasure; i < measures.size(); i++) {
            Measure m = measures.get(i);
            if (i > fromMeasure && m.keyFifths != old) break;
            m.keyFifths = fifths;
        }
    }

    public Score copy() {
        Score s = new Score();
        s.title = title;
        s.subtitle = subtitle;
        s.composer = composer;
        s.lyricist = lyricist;
        s.copyright = copyright;
        for (Part p : parts) s.parts.add(p.copy());
        for (Measure m : measures) s.measures.add(m.copy());
        return s;
    }

    /** Builds a new score with the given instruments and settings. */
    public static Score create(String title, String composer, List<Part> parts, int timeN, int timeD, int keyFifths,
                               int tempo, int measureCount) {
        return create(title, composer, parts, timeN, timeD, keyFifths, tempo, measureCount, 0);
    }

    /** As above; pickupTicks > 0 adds an anacrusis bar of that length before the regular measures. */
    public static Score create(String title, String composer, List<Part> parts, int timeN, int timeD, int keyFifths,
                               int tempo, int measureCount, int pickupTicks) {
        Score s = new Score();
        s.title = title;
        s.composer = composer;
        s.parts.addAll(parts);
        Measure tmpl = new Measure();
        tmpl.timeN = timeN;
        tmpl.timeD = timeD;
        tmpl.keyFifths = keyFifths;
        if (pickupTicks > 0 && pickupTicks < tmpl.ticks()) {
            Measure pickup = new Measure();
            pickup.timeN = timeN;
            pickup.timeD = timeD;
            pickup.keyFifths = keyFifths;
            pickup.actualTicks = pickupTicks;
            pickup.irregular = true;
            Measure pm = s.newMeasureLike(pickup);
            pm.actualTicks = pickupTicks;
            pm.irregular = true;
            for (StaffMeasure sm : pm.staves) ((Rest) sm.voices[0].items.get(0)).measureTicks = pickupTicks;
            s.measures.add(pm);
        }
        for (int i = 0; i < Math.max(1, measureCount); i++) s.measures.add(s.newMeasureLike(tmpl));
        s.measures.get(0).tempo = tempo;
        s.measures.get(s.measures.size() - 1).endBarline = Measure.BAR_FINAL;
        return s;
    }
}
