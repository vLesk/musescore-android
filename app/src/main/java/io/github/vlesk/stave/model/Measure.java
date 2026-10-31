package io.github.vlesk.stave.model;

import java.util.ArrayList;
import java.util.List;

/** A bar across all staves, holding the effective time and key signature. */
public final class Measure {
    public static final int BAR_NORMAL = 0, BAR_DOUBLE = 1, BAR_FINAL = 2, BAR_END_REPEAT = 3, BAR_DASHED = 4;
    public static final String[] BAR_MSCX = {"normal", "double", "end", "end-repeat", "dashed"};

    public int timeN = 4, timeD = 4;
    /** Display "C" / cut time symbol instead of numbers. */
    public boolean timeSymbol;
    public int keyFifths;
    public boolean startRepeat;
    public int endBarline = BAR_NORMAL;
    /** Tempo marking at the start of this measure in quarter notes per minute (0 = none). */
    public int tempo;
    /** Optional text shown with the tempo marking, e.g. "Allegro". */
    public String tempoText;
    /** Rehearsal mark text (null = none). */
    public String rehearsal;
    /** Actual length in ticks when it differs from the time signature (pickup bars), 0 = nominal. */
    public int actualTicks;
    /** Excluded from measure numbering (pickup bars). */
    public boolean irregular;
    public final List<StaffMeasure> staves = new ArrayList<StaffMeasure>();

    public int nominalTicks() {
        return timeN * Duration.DIVISION * 4 / timeD;
    }

    public int ticks() {
        return actualTicks > 0 ? actualTicks : nominalTicks();
    }

    public Measure copy() {
        Measure m = copyHeader();
        for (StaffMeasure s : staves) m.staves.add(s.copy());
        return m;
    }

    /** Copy of the measure's attributes without content. */
    public Measure copyHeader() {
        Measure m = new Measure();
        m.timeN = timeN;
        m.timeD = timeD;
        m.timeSymbol = timeSymbol;
        m.keyFifths = keyFifths;
        m.startRepeat = startRepeat;
        m.endBarline = endBarline;
        m.tempo = tempo;
        m.tempoText = tempoText;
        m.rehearsal = rehearsal;
        m.actualTicks = actualTicks;
        m.irregular = irregular;
        return m;
    }
}
