package io.github.vlesk.stave.model;

import java.util.ArrayList;
import java.util.List;

/** A sequence of chords and rests that fills a measure (voice 1) or is empty (voices 2-4). */
public final class Voice {
    public final List<ChordRest> items = new ArrayList<ChordRest>();

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** Start tick (relative to the measure) of every element, plus the end tick as last entry. */
    public int[] starts() {
        int[] s = new int[items.size() + 1];
        int t = 0;
        for (int i = 0; i < items.size(); i++) {
            s[i] = t;
            t += items.get(i).ticks();
        }
        s[items.size()] = t;
        return s;
    }

    public int totalTicks() {
        int t = 0;
        for (ChordRest cr : items) t += cr.ticks();
        return t;
    }

    /** Index of the element starting exactly at tick, or -1. */
    public int indexAt(int tick) {
        int t = 0;
        for (int i = 0; i < items.size(); i++) {
            if (t == tick) return i;
            if (t > tick) return -1;
            t += items.get(i).ticks();
        }
        return -1;
    }

    /** Index of the element covering tick, or -1. */
    public int indexCovering(int tick) {
        int t = 0;
        for (int i = 0; i < items.size(); i++) {
            int e = t + items.get(i).ticks();
            if (tick >= t && tick < e) return i;
            t = e;
        }
        return -1;
    }

    public boolean onlyRests() {
        for (ChordRest cr : items) if (!cr.isRest() || cr.hasMarkings()) return false;
        return true;
    }

    public Voice copy() {
        Voice v = new Voice();
        // tuplets are shared objects within a voice; copy them preserving identity
        java.util.IdentityHashMap<Tuplet, Tuplet> map = new java.util.IdentityHashMap<Tuplet, Tuplet>();
        for (ChordRest cr : items) {
            ChordRest c = cr.copy();
            if (cr.tuplet != null) {
                Tuplet t = map.get(cr.tuplet);
                if (t == null) {
                    t = cr.tuplet.copy();
                    map.put(cr.tuplet, t);
                }
                c.tuplet = t;
            }
            v.items.add(c);
        }
        return v;
    }
}
