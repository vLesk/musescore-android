package io.github.vlesk.stave.model;

/** Articulations and ornaments that can be attached to chords. */
public final class Articulation {
    private Articulation() {}

    public static final String STACCATO = "staccato", ACCENT = "accent", TENUTO = "tenuto", MARCATO = "marcato",
            STACCATISSIMO = "staccatissimo", FERMATA = "fermata", TRILL = "trill", MORDENT = "mordent", TURN = "turn";

    public static final String[] ALL = {STACCATO, ACCENT, TENUTO, MARCATO, STACCATISSIMO, FERMATA, TRILL, MORDENT, TURN};
    public static final String[] LABELS = {"Staccato", "Accent", "Tenuto", "Marcato", "Staccatissimo", "Fermata", "Trill", "Mordent", "Turn"};
    /** SMuFL glyph base names (Above/Below suffix added for articulations). */
    private static final String[] SMUFL = {"articStaccato", "articAccent", "articTenuto", "articMarcato",
            "articStaccatissimo", "fermata", "ornamentTrill", "ornamentMordent", "ornamentTurn"};

    public static int index(String id) {
        for (int i = 0; i < ALL.length; i++) if (ALL[i].equals(id)) return i;
        return -1;
    }

    public static String smufl(String id, boolean above) {
        int i = index(id);
        if (i < 0) return null;
        String s = SMUFL[i];
        if (s.startsWith("artic") || s.equals("fermata")) return s + (above ? "Above" : "Below");
        return s;
    }

    /** Whether the mark always goes above the staff (fermata, ornaments). */
    public static boolean alwaysAbove(String id) {
        return FERMATA.equals(id) || TRILL.equals(id) || MORDENT.equals(id) || TURN.equals(id);
    }

    /** MuseScore .mscx subtype name. */
    public static String mscxSubtype(String id) {
        int i = index(id);
        if (i < 0) return null;
        String s = SMUFL[i];
        if (s.startsWith("artic") || s.equals("fermata")) return s + "Above";
        return s;
    }

    public static String fromMscx(String subtype) {
        if (subtype == null) return null;
        for (int i = 0; i < SMUFL.length; i++) {
            if (subtype.startsWith(SMUFL[i])) return ALL[i];
        }
        if (subtype.equals("sforzato") || subtype.equals("articAccentStaccatoAbove")) return ACCENT;
        if (subtype.equals("staccato")) return STACCATO;
        if (subtype.equals("tenuto")) return TENUTO;
        if (subtype.equals("fermata") || subtype.startsWith("fermata")) return FERMATA;
        return null;
    }
}
