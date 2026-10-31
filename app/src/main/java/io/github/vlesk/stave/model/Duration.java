package io.github.vlesk.stave.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Note value helpers. Time is measured in ticks with {@link #DIVISION} ticks per quarter note,
 * the same resolution MuseScore uses in its file format.
 *
 * Duration types are indexed by their power of two: 0 = whole, 1 = half, 2 = quarter,
 * 3 = eighth, 4 = 16th, 5 = 32nd, 6 = 64th.
 */
public final class Duration {
    private Duration() {}

    public static final int DIVISION = 480;
    public static final int WHOLE = 0, HALF = 1, QUARTER = 2, EIGHTH = 3, N16 = 4, N32 = 5, N64 = 6;
    public static final int MAX_TYPE = N64;

    private static final String[] XML_NAMES = {"whole", "half", "quarter", "eighth", "16th", "32nd", "64th"};
    private static final String[] MUSICXML_NAMES = {"whole", "half", "quarter", "eighth", "16th", "32nd", "64th"};
    private static final String[] LABELS = {"Whole", "Half", "Quarter", "Eighth", "16th", "32nd", "64th"};

    public static int baseTicks(int type) {
        return (DIVISION * 4) >> type;
    }

    public static int ticks(int type, int dots) {
        int b = baseTicks(type);
        int t = b;
        for (int i = 0; i < dots; i++) {
            b /= 2;
            t += b;
        }
        return t;
    }

    public static String xmlName(int type) {
        return XML_NAMES[type];
    }

    public static String musicXmlName(int type) {
        return MUSICXML_NAMES[type];
    }

    public static String label(int type, int dots) {
        String s = LABELS[type];
        if (dots == 1) return "Dotted " + s.toLowerCase();
        if (dots == 2) return "Double-dotted " + s.toLowerCase();
        return s;
    }

    public static int typeFromXml(String name) {
        if (name == null) return -1;
        for (int i = 0; i < XML_NAMES.length; i++) if (XML_NAMES[i].equals(name)) return i;
        if (name.equals("128th")) return N64; // not supported; clamp
        if (name.equals("breve")) return WHOLE;
        return -1;
    }

    /**
     * Greedy decomposition of a tick length into note values (largest first, single dots allowed).
     * Returns a list of {type, dots}. Any remainder smaller than a 64th is dropped.
     */
    public static List<int[]> decompose(int ticks) {
        List<int[]> out = new ArrayList<int[]>();
        int rem = ticks;
        while (rem > 0) {
            int[] best = null;
            for (int type = 0; type <= MAX_TYPE && best == null; type++) {
                for (int dots = 1; dots >= 0; dots--) {
                    if (type == MAX_TYPE && dots > 0) continue;
                    int t = ticks(type, dots);
                    if (t <= rem) {
                        best = new int[]{type, dots};
                        break;
                    }
                }
            }
            if (best == null) break;
            out.add(best);
            rem -= ticks(best[0], best[1]);
        }
        return out;
    }

    /** Decomposition of a single duration if the tick length is exactly representable, else null. */
    public static int[] exact(int ticks) {
        for (int type = 0; type <= MAX_TYPE; type++) {
            for (int dots = 0; dots <= 2; dots++) {
                if (ticks(type, dots) == ticks) return new int[]{type, dots};
            }
        }
        return null;
    }

    /**
     * Decomposes a gap into rests that respect beat positions, e.g. a 3-beat gap starting on beat 2
     * of a 4/4 bar becomes quarter + half instead of half + quarter.
     */
    public static List<int[]> decomposeRests(int start, int len) {
        List<int[]> out = new ArrayList<int[]>();
        int pos = start;
        int end = start + len;
        while (pos < end) {
            int rem = end - pos;
            int[] best = null;
            for (int type = 0; type <= MAX_TYPE && best == null; type++) {
                int t = baseTicks(type);
                if (t <= rem && pos % t == 0) best = new int[]{type, 0};
            }
            if (best == null) {
                // fall back to largest value that fits regardless of alignment
                for (int type = 0; type <= MAX_TYPE && best == null; type++) {
                    if (baseTicks(type) <= rem) best = new int[]{type, 0};
                }
            }
            if (best == null) break;
            out.add(best);
            pos += ticks(best[0], best[1]);
        }
        return out;
    }
}
