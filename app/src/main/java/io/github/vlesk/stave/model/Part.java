package io.github.vlesk.stave.model;

import java.util.ArrayList;
import java.util.List;

/** An instrument with one or more staves. */
public final class Part {
    public String name;
    public String shortName;
    /** MuseScore instrument id, e.g. "piano". */
    public String instrumentId;
    public int program;
    /** True if this part plays on the MIDI percussion channel. */
    public boolean drums;
    public int volume = 100;
    public int pan = 64;
    public boolean muted;
    public final List<Staff> staves = new ArrayList<Staff>();

    public Part(String name, String shortName, String instrumentId, int program) {
        this.name = name;
        this.shortName = shortName;
        this.instrumentId = instrumentId;
        this.program = program;
    }

    public Part copy() {
        Part p = new Part(name, shortName, instrumentId, program);
        p.drums = drums;
        p.volume = volume;
        p.pan = pan;
        p.muted = muted;
        for (Staff s : staves) p.staves.add(s.copy());
        return p;
    }
}
