package org.minimarex.casino;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Tiny programmatic-UI helpers so the tab views stay readable. All colors come from {@link Theme}. */
public final class Ui {

    private Ui() {}

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable rounded(int fill, int stroke, float radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(dp(c, 1.5f), stroke);
        return g;
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** A panel card with border + rounded corners. */
    public static LinearLayout card(Context c) {
        LinearLayout l = col(c);
        l.setBackground(rounded(Theme.panel(), Theme.border(), 8, c));
        int p = dp(c, 14);
        l.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        l.setLayoutParams(lp);
        return l;
    }

    public static TextView text(Context c, String s, int color, float sp, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTypeface(bold ? Theme.pixel() : Theme.body(), bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        return t;
    }

    public static TextView label(Context c, String s) {
        TextView t = text(c, s.toUpperCase(), Theme.dim(), 10, false);
        t.setLetterSpacing(0.1f);
        return t;
    }

    public static Button button(Context c, String s, int bg, int fg) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(true);
        b.setTextColor(fg);
        b.setTextSize(12);
        b.setTypeface(Theme.pixel(), android.graphics.Typeface.BOLD);
        b.setBackground(rounded(bg, 0, 6, c));
        b.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    /** A small rounded badge (e.g. phase / odds). */
    public static TextView badge(Context c, String s, int fg, int bg) {
        TextView t = text(c, s.toUpperCase(), fg, 9, true);
        t.setLetterSpacing(0.08f);
        t.setBackground(rounded(bg, 0, 4, c));
        t.setPadding(dp(c, 7), dp(c, 3), dp(c, 7), dp(c, 3));
        return t;
    }

    public static LinearLayout.LayoutParams lpRow(Context c, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        return lp;
    }

    public static void marginTop(View v, int dp) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) ((LinearLayout.LayoutParams) lp).topMargin = dp;
        else v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{ topMargin = dp; }});
    }
}
