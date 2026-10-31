package io.github.vlesk.stave.model;

/** A single notehead. Pitch is stored at concert pitch, spelled by its tpc. */
public final class Note {
    public int pitch;
    public int tpc;
    /** True if this note is tied to the same pitch in the next chord of the same voice. */
    public boolean tieForward;
    /** Show the accidental in parentheses / always (courtesy). */
    public boolean forceAccidental;

    public Note(int pitch, int tpc) {
        this.pitch = pitch;
        this.tpc = tpc;
    }

    public Note copy() {
        Note n = new Note(pitch, tpc);
        n.tieForward = tieForward;
        n.forceAccidental = forceAccidental;
        return n;
    }
}
