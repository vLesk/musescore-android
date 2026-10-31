package io.github.vlesk.stave.engrave;

/** Measures text for the layout engine (implemented with Android Paint or Java2D). */
public interface TextMetrics {
    int FONT_TEXT = 0, FONT_ITALIC = 1, FONT_BOLD = 2, FONT_MUSIC = 3;

    /** Width of the string in staff spaces when rendered with an em size of sizeSp staff spaces. */
    float width(String text, int font, float sizeSp);
}
