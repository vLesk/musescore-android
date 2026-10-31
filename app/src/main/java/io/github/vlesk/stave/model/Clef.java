package io.github.vlesk.stave.model;

/** Clef types supported by the editor, with their MuseScore file names and engraving data. */
public final class Clef {
    private Clef() {}

    public static final int TREBLE = 0, BASS = 1, ALTO = 2, TENOR = 3, TREBLE_8VB = 4, TREBLE_8VA = 5, BASS_8VB = 6, PERC = 7;
    public static final int COUNT = 8;

    /** MuseScore clef type names as written in .mscx files. */
    private static final String[] MSCX = {"G", "F", "C3", "C4", "G8vb", "G8va", "F8vb", "PERC"};
    private static final String[] LABELS = {"Treble", "Bass", "Alto", "Tenor", "Treble 8vb", "Treble 8va", "Bass 8vb", "Percussion"};
    /** Diatonic number of the top staff line (middle C = 28). */
    private static final int[] TOP_LINE_DIA = {38, 26, 32, 30, 31, 45, 19, 38};
    /** Staff position (half spaces from top line) of the glyph origin. */
    private static final int[] GLYPH_LINE = {6, 2, 4, 2, 6, 6, 2, 4};
    /** MusicXML sign/line/octave change. */
    private static final String[] MXL_SIGN = {"G", "F", "C", "C", "G", "G", "F", "percussion"};
    private static final int[] MXL_LINE = {2, 4, 3, 4, 2, 2, 4, 0};
    private static final int[] MXL_OCT = {0, 0, 0, 0, -1, 1, -1, 0};

    public static String mscxName(int clef) {
        return MSCX[clef];
    }

    public static int fromMscx(String s) {
        if (s == null) return TREBLE;
        s = s.trim();
        for (int i = 0; i < COUNT; i++) if (MSCX[i].equals(s)) return i;
        if (s.startsWith("PERC")) return PERC;
        if (s.equals("G8va") || s.equals("G15ma")) return TREBLE_8VA;
        if (s.startsWith("G")) return TREBLE;
        if (s.equals("F15mb") || s.equals("F8vb")) return BASS_8VB;
        if (s.startsWith("F")) return BASS;
        if (s.startsWith("C4")) return TENOR;
        if (s.startsWith("C")) return ALTO;
        return TREBLE;
    }

    public static String label(int clef) {
        return LABELS[clef];
    }

    public static int topLineDiatonic(int clef) {
        return TOP_LINE_DIA[clef];
    }

    public static int glyphLine(int clef) {
        return GLYPH_LINE[clef];
    }

    public static String musicXmlSign(int clef) {
        return MXL_SIGN[clef];
    }

    public static int musicXmlLine(int clef) {
        return MXL_LINE[clef];
    }

    public static int musicXmlOctave(int clef) {
        return MXL_OCT[clef];
    }

    public static int fromMusicXml(String sign, int line, int octaveChange) {
        if ("F".equals(sign)) return octaveChange < 0 ? BASS_8VB : BASS;
        if ("C".equals(sign)) return line == 4 ? TENOR : ALTO;
        if ("percussion".equals(sign)) return PERC;
        if (octaveChange < 0) return TREBLE_8VB;
        if (octaveChange > 0) return TREBLE_8VA;
        return TREBLE;
    }

    /** Staff line (half spaces below the top line) of a spelled note in this clef. */
    public static int line(int clef, int pitch, int tpc) {
        return TOP_LINE_DIA[clef] - Pitch.diatonic(pitch, tpc);
    }

    /** Staff lines of key-signature accidentals, in order of appearance. */
    public static int[] keySigLines(int clef, boolean sharps) {
        int[] trebleSharps = {0, 3, -1, 2, 5, 1, 4};
        int[] trebleFlats = {4, 1, 5, 2, 6, 3, 7};
        int[] base = sharps ? trebleSharps : trebleFlats;
        int[] out = new int[7];
        if (clef == TENOR && sharps) return new int[]{6, 2, 5, 1, 4, 0, 3};
        int shift;
        switch (clef) {
            case BASS:
            case BASS_8VB:
                shift = 2;
                break;
            case ALTO:
                shift = 1;
                break;
            case TENOR:
                shift = -1;
                break;
            default:
                shift = 0;
        }
        for (int i = 0; i < 7; i++) out[i] = base[i] + shift;
        return out;
    }

    /** Typical middle pitch used to place new notes when no reference is available. */
    public static int middlePitch(int clef) {
        return Pitch.pitchFromDiatonic(TOP_LINE_DIA[clef] - 4, 0);
    }
}
