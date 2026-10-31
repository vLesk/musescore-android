package io.github.vlesk.stave.model;

/** A position in the score: measure index, staff index, voice (0-3) and tick relative to the measure. */
public final class Pos {
    public int measure, staff, voice, tick;

    public Pos(int measure, int staff, int voice, int tick) {
        this.measure = measure;
        this.staff = staff;
        this.voice = voice;
        this.tick = tick;
    }

    public Pos copy() {
        return new Pos(measure, staff, voice, tick);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Pos)) return false;
        Pos p = (Pos) o;
        return p.measure == measure && p.staff == staff && p.voice == voice && p.tick == tick;
    }

    @Override
    public int hashCode() {
        return ((measure * 31 + staff) * 31 + voice) * 31 + tick;
    }

    @Override
    public String toString() {
        return "Pos(m" + measure + " s" + staff + " v" + voice + " t" + tick + ")";
    }
}
