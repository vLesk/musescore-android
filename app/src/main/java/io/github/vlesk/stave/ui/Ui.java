package io.github.vlesk.stave.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small factory for Material 3 styled framework widgets (no AndroidX dependency). */
public final class Ui {
    private Ui() {}

    /** Minimum touch target recommended by Material / Android accessibility guidelines. */
    public static final int TOUCH_DP = 48;

    public static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    public static GradientDrawable roundStroke(int fill, int stroke, int strokePx, float radiusPx) {
        GradientDrawable g = round(fill, radiusPx);
        g.setStroke(strokePx, stroke);
        return g;
    }

    public static Drawable ripple(Theme t, Drawable content, boolean bounded) {
        ColorStateList rc = ColorStateList.valueOf(Theme.alpha(t.onSurface, 0.14f));
        Drawable mask = bounded ? round(0xFFFFFFFF, content instanceof GradientDrawable ? t.dp(12) : 0) : null;
        return new RippleDrawable(rc, content, mask);
    }

    public static Drawable rippleRound(Theme t, int fill, float radiusDp) {
        GradientDrawable bg = round(fill, t.dp(radiusDp));
        GradientDrawable mask = round(0xFFFFFFFF, t.dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(Theme.alpha(t.onSurface, 0.14f)), bg, mask);
    }

    public static ImageButton icon(Context c, Theme t, int iconRes, String desc, View.OnClickListener l) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(iconRes);
        b.setImageTintList(ColorStateList.valueOf(t.onSurfaceVariant));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(Theme.alpha(t.onSurface, 0.14f)), null, null));
        b.setContentDescription(desc);
        if (Build.VERSION.SDK_INT >= 26) b.setTooltipText(desc);
        b.setOnClickListener(l);
        int s = t.dp(TOUCH_DP);
        b.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        b.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        return b;
    }

    /** A 48dp square toggle button showing a music glyph (Leland) or text. */
    /** TextView that centres its (music) glyph on the glyph's ink bounds rather than the font baseline. */
    static final class GlyphView extends TextView {
        private final android.graphics.Rect b = new android.graphics.Rect();

        GlyphView(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            android.graphics.Paint p = getPaint();
            p.setColor(getCurrentTextColor());
            p.setTextAlign(android.graphics.Paint.Align.CENTER);
            String s = getText().toString();
            p.getTextBounds(s, 0, s.length(), b);
            canvas.drawText(s, getWidth() / 2f, getHeight() / 2f - (b.top + b.bottom) / 2f, p);
        }
    }

    public static TextView glyphButton(Context c, Theme t, String glyph, boolean music, float textSp, String desc, View.OnClickListener l) {
        TextView v = music ? new GlyphView(c) : new TextView(c);
        v.setText(glyph);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(music ? Theme.music(c) : Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        v.setTextColor(t.onSurfaceVariant);
        v.setIncludeFontPadding(false);
        v.setContentDescription(desc);
        if (Build.VERSION.SDK_INT >= 26) v.setTooltipText(desc);
        v.setOnClickListener(l);
        v.setClickable(true);
        v.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(t.dp(TOUCH_DP), t.dp(TOUCH_DP));
        v.setLayoutParams(lp);
        setToggled(t, v, false);
        return v;
    }

    public static void setToggled(Theme t, TextView v, boolean on) {
        int fill = on ? t.primaryContainer : 0x00000000;
        v.setBackground(rippleRound(t, fill, 12));
        v.setTextColor(on ? t.onPrimaryContainer : t.onSurfaceVariant);
        v.setSelected(on);
        if (Build.VERSION.SDK_INT >= 30) v.setStateDescription(on ? "on" : "off");
    }

    public static void setToggled(Theme t, ImageButton b, boolean on) {
        b.setBackground(on ? rippleRound(t, t.primaryContainer, 12)
                : new RippleDrawable(ColorStateList.valueOf(Theme.alpha(t.onSurface, 0.14f)), null, null));
        b.setImageTintList(ColorStateList.valueOf(on ? t.onPrimaryContainer : t.onSurfaceVariant));
        b.setSelected(on);
    }

    public static TextView text(Context c, String s, float sp, int color, boolean medium) {
        TextView v = new TextView(c);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        if (medium) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }

    /** M3 filled / tonal / text button. kind: 0 filled, 1 tonal, 2 text. */
    public static TextView button(Context c, Theme t, String label, int kind, View.OnClickListener l) {
        TextView b = text(c, label, 14, kind == 0 ? t.onPrimary : (kind == 1 ? t.onPrimaryContainer : t.primary), true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(t.dp(40));
        b.setMinWidth(t.dp(64));
        b.setPadding(t.dp(kind == 2 ? 12 : 24), 0, t.dp(kind == 2 ? 12 : 24), 0);
        int fill = kind == 0 ? t.primary : (kind == 1 ? t.primaryContainer : 0x00000000);
        b.setBackground(rippleRound(t, fill, 20));
        b.setOnClickListener(l);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    public static TextView title(Context c, Theme t, String s) {
        TextView v = text(c, s, 22, t.onSurface, false);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        return v;
    }

    public static EditText input(Context c, Theme t, String hint, String value) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextColor(t.onSurface);
        e.setHintTextColor(t.onSurfaceVariant);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        return e;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static View spacer(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }

    public static View divider(Context c, Theme t) {
        View v = new View(c);
        v.setBackgroundColor(t.outlineVariant);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, t.dp(1) / 2 + 0)));
        return v;
    }

    public static View vDivider(Context c, Theme t) {
        View v = new View(c);
        v.setBackgroundColor(t.outlineVariant);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.max(1, t.dp(1)), t.dp(28));
        lp.leftMargin = t.dp(4);
        lp.rightMargin = t.dp(4);
        v.setLayoutParams(lp);
        return v;
    }

    /**
     * Edge-to-edge: draws behind the system bars (enforced from Android 15 for apps targeting
     * API 35) and pads the given views by the bar insets instead.
     */
    @SuppressWarnings("deprecation")
    public static void edgeToEdge(Activity a, Theme t, final View topPadded, final View bottomPadded, final View sidesPadded) {
        Window w = a.getWindow();
        View decor = w.getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!t.dark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
        }
        final int topBase = topPadded != null ? topPadded.getPaddingTop() : 0;
        final int bottomBase = bottomPadded != null ? bottomPadded.getPaddingBottom() : 0;
        decor.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                int top = in.getSystemWindowInsetTop();
                int bottom = in.getSystemWindowInsetBottom();
                int left = in.getSystemWindowInsetLeft();
                int right = in.getSystemWindowInsetRight();
                if (Build.VERSION.SDK_INT >= 28 && in.getDisplayCutout() != null) {
                    left = Math.max(left, in.getDisplayCutout().getSafeInsetLeft());
                    right = Math.max(right, in.getDisplayCutout().getSafeInsetRight());
                }
                if (topPadded != null) topPadded.setPadding(topPadded.getPaddingLeft(), topBase + top, topPadded.getPaddingRight(), topPadded.getPaddingBottom());
                if (bottomPadded != null) bottomPadded.setPadding(bottomPadded.getPaddingLeft(), bottomPadded.getPaddingTop(), bottomPadded.getPaddingRight(), bottomBase + bottom);
                if (sidesPadded != null) sidesPadded.setPadding(left, sidesPadded.getPaddingTop(), right, sidesPadded.getPaddingBottom());
                return v.onApplyWindowInsets(in);
            }
        });
    }

    public static void haptic(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    public static FrameLayout.LayoutParams frame(int w, int h, int gravity) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = gravity;
        return lp;
    }

    public static LinearLayout.LayoutParams lin(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lin(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }
}
