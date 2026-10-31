package io.github.vlesk.stave.model;

/** A five-line staff belonging to a part. */
public final class Staff {
    public int defaultClef;
    /** Hidden during layout (e.g. not yet used). Currently always visible. */
    public boolean small;

    public Staff(int clef) {
        defaultClef = clef;
    }

    public Staff copy() {
        Staff s = new Staff(defaultClef);
        s.small = small;
        return s;
    }
}
