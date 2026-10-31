package io.github.vlesk.stave.model;

/** A tuplet ratio shared by the chords/rests that belong to it (e.g. 3 in the time of 2). */
public final class Tuplet {
    public int actual;
    public int normal;
    /** Duration type of the tuplet's base unit (used for the file format and bracket number). */
    public int baseType;

    public Tuplet(int actual, int normal, int baseType) {
        this.actual = actual;
        this.normal = normal;
        this.baseType = baseType;
    }

    /** Total length in ticks of the complete tuplet. */
    public int spanTicks() {
        return Duration.baseTicks(baseType) * normal;
    }

    public int scale(int rawTicks) {
        return rawTicks * normal / actual;
    }

    public Tuplet copy() {
        return new Tuplet(actual, normal, baseType);
    }
}
