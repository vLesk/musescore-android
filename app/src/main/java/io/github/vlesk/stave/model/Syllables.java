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

    /** Ticks per whole note (480 per quarter), the unit of MuseScore's ticks_f fractions. */
    public static final int WHOLE = 1920;

    /** Tick of a position counted from the start of the score. */
    public static int globalTick(Score s, int m, int tick) {
        int t = 0;
        for (int i = 0; i < m; i++) t += s.measures.get(i).ticks();
        return t + tick;
    }

    /**
     * The last chord a melisma reaches: {measure, index} for the syllable at (m, idx). Rests are
     * skipped, as in MuseScore, and the line never runs into a chord that has its own lyric.
     */
    public static int[] melismaEnd(Score s, int m, int st, int v, int idx) {
        ChordRest start = s.measures.get(m).staves.get(st).voices[v].items.get(idx);
        int[] end = {m, idx};
        int left = start.melisma - 1;
        int i = idx + 1;
        for (int mm = m; mm < s.measures.size() && left > 0; mm++) {
            Voice voice = s.measures.get(mm).staves.get(st).voices[v];
            if (mm != m) i = 0;
            for (; i < voice.items.size() && left > 0; i++) {
                ChordRest cr = voice.items.get(i);
                if (!(cr instanceof Chord)) continue;
                if (cr.lyric != null && !cr.lyric.isEmpty()) return end;
                end = new int[]{mm, i};
                left--;
            }
        }
        return end;
    }

    /** Length of a melisma as MuseScore stores it (LYRIC_TICKS), in ticks; 1 tick is its one-note melisma. */
    public static int melismaTicks(Score s, int m, int st, int v, int idx) {
        ChordRest cr = s.measures.get(m).staves.get(st).voices[v].items.get(idx);
        if (cr.melisma <= 0) return 0;
        int[] end = melismaEnd(s, m, st, v, idx);
        int t0 = globalTick(s, m, s.measures.get(m).staves.get(st).voices[v].starts()[idx]);
        int t1 = globalTick(s, end[0], s.measures.get(end[0]).staves.get(st).voices[v].starts()[end[1]]);
        return Math.max(1, t1 - t0);
    }

    /** The inverse of {@link #melismaTicks}: how many chords (counting its own) a stored length covers. */
    public static int melismaFromTicks(Score s, int m, int st, int v, int idx, int ticks) {
        if (ticks <= 0) return 0;
        int t0 = globalTick(s, m, s.measures.get(m).staves.get(st).voices[v].starts()[idx]);
        int count = 0;
        int i = idx;
        for (int mm = m; mm < s.measures.size(); mm++) {
            Voice voice = s.measures.get(mm).staves.get(st).voices[v];
            int[] starts = voice.starts();
            if (mm != m) i = 0;
            for (; i < voice.items.size(); i++) {
                if (globalTick(s, mm, starts[i]) > t0 + ticks) return Math.max(1, count);
                if (voice.items.get(i) instanceof Chord) count++;
            }
        }
        return Math.max(1, count);
    }

    /** After reading a MuseScore file: turns stored melisma lengths (ticks) into held-note counts. */
    public static void resolveMelismaTicks(Score s, java.util.Map<ChordRest, Integer> ticks) {
        if (ticks.isEmpty()) return;
        for (int m = 0; m < s.measures.size(); m++) {
            for (int st = 0; st < s.staffCount(); st++) {
                for (int v = 0; v < StaffMeasure.VOICES; v++) {
                    Voice voice = s.measures.get(m).staves.get(st).voices[v];
                    for (int i = 0; i < voice.items.size(); i++) {
                        Integer t = ticks.get(voice.items.get(i));
                        if (t != null) voice.items.get(i).melisma = melismaFromTicks(s, m, st, v, i, t);
                    }
                }
            }
        }
    }

    /**
     * After reading MusicXML: a lyric with &lt;extend/&gt; is held over the following chords up to the
     * next lyric (as MuseScore imports it), or up to the next rest if no lyric follows.
     */
    public static void resolveExtends(Score s, java.util.Set<ChordRest> extended) {
        if (extended.isEmpty()) return;
        for (int m = 0; m < s.measures.size(); m++) {
            for (int st = 0; st < s.staffCount(); st++) {
                for (int v = 0; v < StaffMeasure.VOICES; v++) {
                    Voice voice = s.measures.get(m).staves.get(st).voices[v];
                    for (int i = 0; i < voice.items.size(); i++) {
                        if (extended.contains(voice.items.get(i))) voice.items.get(i).melisma = extendCount(s, m, st, v, i);
                    }
                }
            }
        }
    }

    private static int extendCount(Score s, int m, int st, int v, int idx) {
        boolean lyricAhead = next(s, m, st, v, idx) != null;
        int count = 1;
        int i = idx + 1;
        for (int mm = m; mm < s.measures.size(); mm++) {
            Voice voice = s.measures.get(mm).staves.get(st).voices[v];
            if (mm != m) i = 0;
            for (; i < voice.items.size(); i++) {
                ChordRest cr = voice.items.get(i);
                if (!(cr instanceof Chord)) {
                    if (!lyricAhead) return count;
                    continue;
                }
                if (cr.lyric != null) return count;
                count++;
            }
        }
        return count;
    }

    /** A melisma length as MuseScore writes ticks_f: a reduced fraction of a whole note. */
    public static String fraction(int ticks) {
        int g = gcd(ticks, WHOLE);
        return (ticks / g) + "/" + (WHOLE / g);
    }

    /** Reads a ticks_f fraction ("1/4") as ticks; 0 if malformed. */
    public static int parseFraction(String f) {
        if (f == null) return 0;
        String[] p = f.trim().split("/");
        try {
            if (p.length == 1) return Integer.parseInt(p[0]) * WHOLE;
            return (int) Math.round(Integer.parseInt(p[0]) * (double) WHOLE / Integer.parseInt(p[1]));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int gcd(int a, int b) {
        return b == 0 ? Math.max(1, a) : gcd(b, a % b);
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
