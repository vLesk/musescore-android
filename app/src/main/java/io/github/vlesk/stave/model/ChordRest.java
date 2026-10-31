package io.github.vlesk.stave.model;

import java.util.ArrayList;
import java.util.List;

/** Common base of chords and rests: a duration plus attached markings. */
public abstract class ChordRest {
    public int type = Duration.QUARTER;
    public int dots;
    public Tuplet tuplet;
    /** Dynamic marking placed at this position, e.g. "mf" (null = none). */
    public String dynamic;
    /** Chord symbol text placed above the staff, e.g. "Cmaj7" (null = none). */
    public String chordSymbol;
    /** Lyric syllable for verse 1 (null = none). */
    public String lyric;
    /** Articulation ids, see {@link Articulation}. */
    public final List<String> articulations = new ArrayList<String>();
    /** Free staff text above this position (expression / technique text). */
    public String staffText;

    public int ticks() {
        int t = Duration.ticks(type, dots);
        return tuplet != null ? tuplet.scale(t) : t;
    }

    public abstract boolean isRest();

    public abstract ChordRest copy();

    protected void copyInto(ChordRest o) {
        o.type = type;
        o.dots = dots;
        o.tuplet = tuplet;
        o.dynamic = dynamic;
        o.chordSymbol = chordSymbol;
        o.lyric = lyric;
        o.articulations.addAll(articulations);
        o.staffText = staffText;
    }

    public boolean hasMarkings() {
        return dynamic != null || chordSymbol != null || lyric != null || !articulations.isEmpty() || staffText != null;
    }

    public void clearMarkings() {
        dynamic = null;
        chordSymbol = null;
        lyric = null;
        staffText = null;
        articulations.clear();
    }
}
