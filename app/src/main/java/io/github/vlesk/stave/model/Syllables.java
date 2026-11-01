package io.github.vlesk.stave.model;

/**
 * Lyric syllables. A chord's lyric is stored as text; a trailing hyphen marks a syllable that
 * continues on the next lyric (MuseScore's syllabic "begin" or "middle"). The hyphen is not
 * drawn as text: the layout draws MuseScore-style dashes between the two syllables instead.
 */
public final class Syllables {
    private Syllables() {
    }

    /** True if the syllable continues on the next lyric (a lone "-" is plain text). */
    public static boolean hyphenated(String lyric) {
        return lyric != null && lyric.length() >= 2 && lyric.endsWith("-");
    }

    /** The text as shown and as written to files: without the hyphen marker. */
    public static String text(String lyric) {
        if (lyric == null) return "";
        return hyphenated(lyric) ? lyric.substring(0, lyric.length() - 1) : lyric;
    }

    /** The previous chord with a lyric in the same staff and voice (across barlines), or null. */
    public static ChordRest previous(Score s, int m, int st, int v, int idx) {
        int i = idx - 1;
        for (int mm = m; mm >= 0; mm--) {
            Voice voice = s.measures.get(mm).staves.get(st).voices[v];
            if (mm != m) i = voice.items.size() - 1;
            for (; i >= 0; i--) {
                ChordRest cr = voice.items.get(i);
                if (cr instanceof Chord && cr.lyric != null && !cr.lyric.isEmpty()) return cr;
            }
        }
        return null;
    }

    /**
     * The next chord with a lyric in the same staff and voice (across barlines), or null. An empty
     * lyric counts: it is the syllable being typed, so the dash can lead to it.
     */
    public static ChordRest next(Score s, int m, int st, int v, int idx) {
        int i = idx + 1;
        for (int mm = m; mm < s.measures.size(); mm++) {
            Voice voice = s.measures.get(mm).staves.get(st).voices[v];
            if (mm != m) i = 0;
            for (; i < voice.items.size(); i++) {
                ChordRest cr = voice.items.get(i);
                if (cr instanceof Chord && cr.lyric != null) return cr;
            }
        }
        return null;
    }

    /** MuseScore/MusicXML syllabic value of the lyric at a position: single, begin, middle or end. */
    public static String syllabic(Score s, int m, int st, int v, int idx) {
        ChordRest cr = s.measures.get(m).staves.get(st).voices[v].items.get(idx);
        boolean continues = hyphenated(cr.lyric);
        ChordRest prev = previous(s, m, st, v, idx);
        boolean continued = prev != null && hyphenated(prev.lyric);
        if (continued) return continues ? "middle" : "end";
        return continues ? "begin" : "single";
    }
}
