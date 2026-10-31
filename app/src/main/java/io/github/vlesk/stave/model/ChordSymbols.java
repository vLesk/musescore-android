package io.github.vlesk.stave.model;

/** Parsing of chord symbol text such as "F#m7b5/C" into root, quality and bass. */
public final class ChordSymbols {
    private ChordSymbols() {}

    /** Result: root tpc, quality text, bass tpc (or -100). Null when the text has no root. */
    public static final class Parsed {
        public int rootTpc;
        public String quality;
        public int bassTpc = -100;
    }

    public static Parsed parse(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        int[] r = parseNote(t, 0);
        if (r == null) return null;
        Parsed p = new Parsed();
        p.rootTpc = r[0];
        String rest = t.substring(r[1]);
        int slash = rest.lastIndexOf('/');
        if (slash >= 0) {
            int[] b = parseNote(rest, slash + 1);
            if (b != null && b[1] == rest.length()) {
                p.bassTpc = b[0];
                rest = rest.substring(0, slash);
            }
        }
        p.quality = rest;
        return p;
    }

    /** Returns {tpc, endIndex} for a note name at index i, or null. */
    private static int[] parseNote(String s, int i) {
        if (i >= s.length()) return null;
        char c = Character.toUpperCase(s.charAt(i));
        int step = "CDEFGAB".indexOf(c);
        if (step < 0) return null;
        int j = i + 1;
        int alter = 0;
        while (j < s.length()) {
            char a = s.charAt(j);
            if (a == '#' || a == '♯') alter++;
            else if (a == 'b' || a == '♭') {
                // "b" right after the root is a flat unless it starts a word like "b5"? keep as flat
                alter--;
            } else break;
            j++;
            if (Math.abs(alter) >= 2) break;
        }
        return new int[]{Pitch.tpcFromStepAlter(step, alter), j};
    }

    public static String tpcName(int tpc) {
        String s = Pitch.stepName(Pitch.step(tpc));
        int a = Pitch.alter(tpc);
        if (a > 0) for (int i = 0; i < a; i++) s += "#";
        else for (int i = 0; i < -a; i++) s += "b";
        return s;
    }

    public static String format(int rootTpc, String quality, int bassTpc) {
        StringBuilder sb = new StringBuilder(tpcName(rootTpc));
        if (quality != null) sb.append(quality);
        if (Pitch.validTpc(bassTpc)) sb.append('/').append(tpcName(bassTpc));
        return sb.toString();
    }

    /** Pretty display text: '#' and 'b' accidentals turned into music symbols. */
    public static String display(String text) {
        Parsed p = parse(text);
        if (p == null) return text;
        StringBuilder sb = new StringBuilder();
        sb.append(pretty(p.rootTpc));
        sb.append(p.quality);
        if (Pitch.validTpc(p.bassTpc)) sb.append('/').append(pretty(p.bassTpc));
        return sb.toString();
    }

    private static String pretty(int tpc) {
        String s = Pitch.stepName(Pitch.step(tpc));
        int a = Pitch.alter(tpc);
        if (a > 0) for (int i = 0; i < a; i++) s += "♯";
        else for (int i = 0; i < -a; i++) s += "♭";
        return s;
    }
}
