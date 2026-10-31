package io.github.vlesk.stave.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Curated instrument catalog. Ids, names, MIDI programs and clefs follow MuseScore's
 * share/instruments/instruments.xml. Scores are shown at concert pitch; octave-transposing
 * instruments use octave clefs so they read as usual.
 */
public final class Instruments {
    private Instruments() {}

    public static final class Def {
        public final String id, name, shortName, group, musicXmlId;
        public final int program;
        public final int[] clefs;
        public final boolean drums;

        Def(String group, String id, String name, String shortName, String musicXmlId, int program, boolean drums, int... clefs) {
            this.group = group;
            this.id = id;
            this.name = name;
            this.shortName = shortName;
            this.musicXmlId = musicXmlId;
            this.program = program;
            this.drums = drums;
            this.clefs = clefs;
        }

        public Part create() {
            Part p = new Part(name, shortName, id, program);
            p.drums = drums;
            for (int c : clefs) p.staves.add(new Staff(c));
            return p;
        }
    }

    private static final int G = Clef.TREBLE, F = Clef.BASS, C3 = Clef.ALTO, G8VB = Clef.TREBLE_8VB, G8VA = Clef.TREBLE_8VA,
            F8VB = Clef.BASS_8VB, PERC = Clef.PERC;

    public static final Def[] ALL = {
            new Def("Keyboards", "piano", "Piano", "Pno.", "keyboard.piano", 0, false, G, F),
            new Def("Keyboards", "electric-piano", "Electric Piano", "El. Pno.", "keyboard.piano.electric", 4, false, G, F),
            new Def("Keyboards", "harpsichord", "Harpsichord", "Hch.", "keyboard.harpsichord", 6, false, G, F),
            new Def("Keyboards", "organ", "Organ", "Org.", "keyboard.organ", 19, false, G, F),
            new Def("Keyboards", "accordion", "Accordion", "Acc.", "keyboard.accordion", 21, false, G, F),
            new Def("Keyboards", "celesta", "Celesta", "Cel.", "keyboard.celesta", 8, false, G8VA, G),
            new Def("Voices", "voice", "Voice", "Vo.", "voice.vocals", 52, false, G),
            new Def("Voices", "soprano", "Soprano", "S.", "voice.soprano", 52, false, G),
            new Def("Voices", "alto", "Alto", "A.", "voice.alto", 52, false, G),
            new Def("Voices", "tenor", "Tenor", "T.", "voice.tenor", 52, false, G8VB),
            new Def("Voices", "baritone", "Baritone", "Bar.", "voice.baritone", 52, false, F),
            new Def("Voices", "bass", "Bass", "B.", "voice.bass", 52, false, F),
            new Def("Woodwinds", "piccolo", "Piccolo", "Picc.", "wind.flutes.flute.piccolo", 72, false, G8VA),
            new Def("Woodwinds", "flute", "Flute", "Fl.", "wind.flutes.flute", 73, false, G),
            new Def("Woodwinds", "soprano-recorder", "Soprano Recorder", "S. Rec.", "wind.flutes.recorder.soprano", 74, false, G8VA),
            new Def("Woodwinds", "alto-recorder", "Alto Recorder", "A. Rec.", "wind.flutes.recorder.alto", 74, false, G),
            new Def("Woodwinds", "oboe", "Oboe", "Ob.", "wind.reed.oboe", 68, false, G),
            new Def("Woodwinds", "clarinet", "Clarinet", "Cl.", "wind.reed.clarinet", 71, false, G),
            new Def("Woodwinds", "bassoon", "Bassoon", "Bsn.", "wind.reed.bassoon", 70, false, F),
            new Def("Woodwinds", "soprano-saxophone", "Soprano Saxophone", "S. Sax.", "wind.reed.saxophone.soprano", 64, false, G),
            new Def("Woodwinds", "alto-saxophone", "Alto Saxophone", "A. Sax.", "wind.reed.saxophone.alto", 65, false, G),
            new Def("Woodwinds", "tenor-saxophone", "Tenor Saxophone", "T. Sax.", "wind.reed.saxophone.tenor", 66, false, G8VB),
            new Def("Woodwinds", "baritone-saxophone", "Baritone Saxophone", "Bar. Sax.", "wind.reed.saxophone.baritone", 67, false, F),
            new Def("Woodwinds", "harmonica", "Harmonica", "Hmc.", "wind.reed.harmonica", 22, false, G),
            new Def("Brass", "trumpet", "Trumpet", "Tpt.", "brass.trumpet", 56, false, G),
            new Def("Brass", "horn", "Horn", "Hn.", "brass.french-horn", 60, false, G),
            new Def("Brass", "trombone", "Trombone", "Tbn.", "brass.trombone", 57, false, F),
            new Def("Brass", "tuba", "Tuba", "Tba.", "brass.tuba", 58, false, F),
            new Def("Plucked strings", "guitar-nylon", "Classical Guitar", "Guit.", "pluck.guitar.nylon-string", 24, false, G8VB),
            new Def("Plucked strings", "guitar-steel", "Acoustic Guitar", "Guit.", "pluck.guitar.acoustic", 25, false, G8VB),
            new Def("Plucked strings", "electric-guitar", "Electric Guitar", "El. Guit.", "pluck.guitar.electric", 27, false, G8VB),
            new Def("Plucked strings", "bass-guitar", "Bass Guitar", "B. Guit.", "pluck.bass", 34, false, F8VB),
            new Def("Plucked strings", "ukulele", "Ukulele", "Uk.", "pluck.ukulele", 24, false, G),
            new Def("Plucked strings", "harp", "Harp", "Hrp.", "pluck.harp", 46, false, G, F),
            new Def("Strings", "violin", "Violin", "Vln.", "strings.violin", 40, false, G),
            new Def("Strings", "viola", "Viola", "Vla.", "strings.viola", 41, false, C3),
            new Def("Strings", "violoncello", "Violoncello", "Vc.", "strings.cello", 42, false, F),
            new Def("Strings", "contrabass", "Contrabass", "Cb.", "strings.contrabass", 43, false, F8VB),
            new Def("Percussion", "timpani", "Timpani", "Timp.", "drum.timpani", 47, false, F),
            new Def("Percussion", "glockenspiel", "Glockenspiel", "Glock.", "pitched-percussion.glockenspiel", 9, false, G8VA),
            new Def("Percussion", "xylophone", "Xylophone", "Xyl.", "pitched-percussion.xylophone", 13, false, G8VA),
            new Def("Percussion", "vibraphone", "Vibraphone", "Vib.", "pitched-percussion.vibraphone", 11, false, G),
            new Def("Percussion", "marimba", "Marimba", "Mrm.", "pitched-percussion.marimba", 12, false, G, F),
            new Def("Percussion", "drumset", "Drum Kit", "D. Kit", "drum.group.set", 0, true, PERC),
    };

    public static Def byId(String id) {
        if (id == null) return null;
        for (Def d : ALL) if (d.id.equals(id)) return d;
        return null;
    }

    public static Def byName(String name) {
        if (name == null) return null;
        for (Def d : ALL) if (d.name.equalsIgnoreCase(name.trim())) return d;
        return null;
    }

    public static List<String> groups() {
        List<String> g = new ArrayList<String>();
        for (Def d : ALL) if (!g.contains(d.group)) g.add(d.group);
        return g;
    }

    /** Ready-made score templates shown in the "New score" screen. */
    public static final String[][] TEMPLATES = {
            {"Piano", "piano"},
            {"Lead sheet / voice", "voice"},
            {"Voice + piano", "voice", "piano"},
            {"SATB choir", "soprano", "alto", "tenor", "bass"},
            {"String quartet", "violin", "violin", "viola", "violoncello"},
            {"Guitar", "guitar-nylon"},
            {"Flute", "flute"},
            {"Violin + piano", "violin", "piano"},
            {"Jazz combo", "alto-saxophone", "trumpet", "piano", "bass-guitar", "drumset"},
    };
}
