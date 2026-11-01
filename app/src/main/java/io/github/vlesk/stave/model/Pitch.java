package io.github.vlesk.stave.model;

/**
 * Pitch spelling helpers built on MuseScore's "tonal pitch class" (tpc) representation:
 * a position on the line of fifths where C = 14, G = 15, F = 13, F# = 20, Bb = 12, etc.
 * Valid range is -1 (Fbb) .. 33 (B##).
 */
public final class Pitch {
    private Pitch() {}

    public static final int TPC_MIN = -1, TPC_MAX = 33;
    /** Step index (C=0 .. B=6) for each position of F C G D A E B on the line of fifths. */
    private static final int[] FIFTHS_TO_STEP = {3, 0, 4, 1, 5, 2, 6};
    /** Pitch class of the natural step (C=0..B=6). */
    private static final int[] STEP_PC = {0, 2, 4, 5, 7, 9, 11};
    private static final String[] STEP_NAMES = {"C", "D", "E", "F", "G", "A", "B"};

    public static int step(int tpc) {
        return FIFTHS_TO_STEP[((tpc + 1) % 7 + 7) % 7];
    }

    public static int alter(int tpc) {
        return MathUtil.floorDiv(tpc + 1, 7) - 2;
    }

    public static int tpcFromStepAlter(int step, int alter) {
        // natural tpc for step: F=13 C=14 G=15 D=16 A=17 E=18 B=19
        int[] naturalTpc = {14, 16, 18, 13, 15, 17, 19};
        return naturalTpc[step] + 7 * alter;
    }

    public static int pitchClassOfTpc(int tpc) {
        return MathUtil.floorMod((tpc - 14) * 7, 12);
    }

    /** Octave in scientific notation (middle C = C4), taking the spelling into account. */
    public static int octave(int pitch, int tpc) {
        return MathUtil.floorDiv(pitch - alter(tpc), 12) - 1;
    }

    /** Diatonic number: octave * 7 + step. Middle C = 28. */
    public static int diatonic(int pitch, int tpc) {
        return octave(pitch, tpc) * 7 + step(tpc);
    }

    public static int pitchFromDiatonic(int dia, int alter) {
        int oct = MathUtil.floorDiv(dia, 7);
        int step = MathUtil.floorMod(dia, 7);
        return (oct + 1) * 12 + STEP_PC[step] + alter;
    }

    /** Alteration a key signature applies to a step (C=0..B=6). */
    public static int keyAlter(int keyFifths, int step) {
        // order of sharps: F C G D A E B -> steps 3 0 4 1 5 2 6
        int[] sharpOrder = {3, 0, 4, 1, 5, 2, 6};
        if (keyFifths > 0) {
            for (int i = 0; i < keyFifths && i < 7; i++) if (sharpOrder[i] == step) return 1;
        } else if (keyFifths < 0) {
            for (int i = 0; i < -keyFifths && i < 7; i++) if (sharpOrder[6 - i] == step) return -1;
        }
        return 0;
    }

    /** Chooses the most natural spelling of a MIDI pitch in the given key. */
    public static int spell(int pitch, int keyFifths) {
        int pc = MathUtil.floorMod(pitch, 12);
        int center = 16 + keyFifths;
        int best = 14;
        int bestDist = Integer.MAX_VALUE;
        for (int tpc = TPC_MIN; tpc <= TPC_MAX; tpc++) {
            if (pitchClassOfTpc(tpc) != pc) continue;
            int d = Math.abs(tpc - center);
            // on a tie prefer fewer accidentals (C natural in E major, not B sharp; A natural in
            // D flat major, not B double flat), then sharps in sharp keys and flats in flat keys
            boolean tie = d == bestDist;
            int fewer = Integer.compare(Math.abs(alter(tpc)), Math.abs(alter(best)));
            if (d < bestDist || (tie && fewer < 0) || (tie && fewer == 0 && ((keyFifths >= 0) ? tpc > best : tpc < best))) {
                best = tpc;
                bestDist = d;
            }
        }
        return best;
    }

    /**
     * The next spelling of the same pitch, cycling through the valid ones in line-of-fifths order
     * (B ⇄ C♭ ⇄ A𝄪), as MuseScore's "Respell pitches" (J).
     */
    public static int nextSpelling(int tpc) {
        for (int t = tpc + 12; t <= TPC_MAX; t += 12) if (validTpc(t)) return t;
        int t = tpc;
        while (t - 12 >= TPC_MIN) t -= 12;
        return t;
    }

    /** Spelling with a forced alteration (from an accidental button); falls back to {@link #spell}. */
    public static int spellWithAlter(int pitch, int alter) {
        int pc = MathUtil.floorMod(pitch - alter, 12);
        for (int step = 0; step < 7; step++) {
            if (STEP_PC[step] == pc) return tpcFromStepAlter(step, alter);
        }
        return -100;
    }

    public static boolean validTpc(int tpc) {
        return tpc >= TPC_MIN && tpc <= TPC_MAX;
    }

    public static String name(int pitch, int tpc) {
        StringBuilder sb = new StringBuilder(STEP_NAMES[step(tpc)]);
        int a = alter(tpc);
        if (a == 2) sb.append("♯♯");
        else if (a == 1) sb.append("♯");
        else if (a == -1) sb.append("♭");
        else if (a == -2) sb.append("♭♭");
        sb.append(octave(pitch, tpc));
        return sb.toString();
    }

    public static String stepName(int step) {
        return STEP_NAMES[step];
    }

    public static int stepPitchClass(int step) {
        return STEP_PC[step];
    }

    /** Name of a major key for a fifths value (-7..7). */
    public static String keyName(int fifths) {
        String[] names = {"C♭", "G♭", "D♭", "A♭", "E♭", "B♭", "F", "C",
                "G", "D", "A", "E", "B", "F♯", "C♯"};
        return names[fifths + 7] + " major";
    }

    /** Transposes a spelled pitch by a number of semitones, re-spelling it in the key. */
    public static int[] transpose(int pitch, int tpc, int semitones, int keyFifths) {
        int np = pitch + semitones;
        return new int[]{np, spell(np, keyFifths)};
    }
}
