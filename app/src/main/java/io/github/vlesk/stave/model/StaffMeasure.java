package io.github.vlesk.stave.model;

/** The content of one measure on one staff: up to four voices and an optional clef change. */
public final class StaffMeasure {
    public static final int VOICES = 4;
    public final Voice[] voices = new Voice[VOICES];
    /** Clef change at the start of this measure, or -1. */
    public int clef = -1;

    public StaffMeasure() {
        for (int i = 0; i < VOICES; i++) voices[i] = new Voice();
    }

    public StaffMeasure copy() {
        StaffMeasure s = new StaffMeasure();
        for (int i = 0; i < VOICES; i++) s.voices[i] = voices[i].copy();
        s.clef = clef;
        return s;
    }
}
