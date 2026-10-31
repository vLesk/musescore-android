package io.github.vlesk.stave.model;

/** A rest. A measure rest fills the whole bar regardless of its nominal duration. */
public final class Rest extends ChordRest {
    public boolean measureRest;
    /** Length in ticks for measure rests. */
    public int measureTicks;

    @Override
    public boolean isRest() {
        return true;
    }

    @Override
    public int ticks() {
        return measureRest ? measureTicks : super.ticks();
    }

    public static Rest measureRest(int ticks) {
        Rest r = new Rest();
        r.measureRest = true;
        r.measureTicks = ticks;
        r.type = Duration.WHOLE;
        return r;
    }

    @Override
    public Rest copy() {
        Rest r = new Rest();
        copyInto(r);
        r.measureRest = measureRest;
        r.measureTicks = measureTicks;
        return r;
    }
}
