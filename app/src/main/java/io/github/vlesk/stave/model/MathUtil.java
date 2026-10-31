package io.github.vlesk.stave.model;

/** Integer helpers (the platform API level used for compilation predates java.lang.MathUtil.floorDiv). */
public final class MathUtil {
    private MathUtil() {}

    public static int floorDiv(int a, int b) {
        int q = a / b;
        if ((a % b != 0) && ((a < 0) != (b < 0))) q--;
        return q;
    }

    public static int floorMod(int a, int b) {
        return a - floorDiv(a, b) * b;
    }

    public static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
