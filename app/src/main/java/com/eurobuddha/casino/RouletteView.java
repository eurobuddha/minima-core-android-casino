package com.eurobuddha.casino;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * A spinning roulette wheel. {@code range} segments (default 36) alternate red / dark,
 * with segment 0 drawn green as the "zero" accent and a gold centre hub. A fixed gold
 * pointer sits at the top (12 o'clock).
 *
 * <p>{@link #spin(int, Runnable)} accelerates then decelerates the wheel so the pointer
 * lands on segment {@code resultPick}.
 */
public class RouletteView extends View {

    private final Paint seg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointer = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Path tri = new Path();

    private static final int DARK = 0xFF181C2A;  // dark "black" segment shade

    private int range = 36;
    private float rotation = 0f;                  // current wheel rotation (degrees)
    private ValueAnimator anim;

    public RouletteView(Context c) { super(c); init(); }
    public RouletteView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        seg.setStyle(Paint.Style.FILL);
        rim.setStyle(Paint.Style.STROKE);
        rim.setColor(Theme.border());
        rim.setStrokeWidth(4f);
        hub.setStyle(Paint.Style.FILL);
        hub.setColor(Theme.gold());
        label.setColor(Theme.text());
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        pointer.setStyle(Paint.Style.FILL);
        pointer.setColor(Theme.gold());
    }

    /** Set the number of segments (e.g. 36). Re-renders. */
    public void setRange(int r) {
        range = Math.max(2, r);
        invalidate();
    }

    /**
     * Spin so the pointer at the top lands on segment {@code resultPick}.
     * Safe to call repeatedly — cancels any in-flight spin first.
     */
    public void spin(int resultPick, Runnable onEnd) {
        cancel();
        int pick = ((resultPick % range) + range) % range;

        float per = 360f / range;
        // Segment i is centred (in wheel-local space) at angle i*per starting from the
        // top and going clockwise. To bring segment 'pick' under the top pointer, the
        // wheel must rotate so that -(pick*per + per/2) is at 0deg, plus whole spins.
        float target = -(pick * per + per / 2f);
        float spins = 360f * 6;                          // 6 full turns for drama
        float finalRot = spins + target;

        anim = ValueAnimator.ofFloat(0f, finalRot);
        anim.setDuration(2000);
        anim.setInterpolator(new DecelerateInterpolator(2.2f)); // accelerate-feel start, long settle
        anim.addUpdateListener(a -> { rotation = (float) a.getAnimatedValue(); invalidate(); });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                rotation = finalRot % 360f;
                invalidate();
                if (onEnd != null) post(onEnd);
            }
        });
        if (Theme.sound()) Sfx.roulette();
        anim.start();
    }

    /** Spin forever (the card's "game in progress" animation while waiting for resolution). */
    public void startSpinning() {
        cancel();
        anim = ValueAnimator.ofFloat(0f, 360f);
        anim.setDuration(2600);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new android.view.animation.LinearInterpolator());
        anim.addUpdateListener(a -> { rotation = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    public void cancel() {
        if (anim != null) { anim.cancel(); anim = null; }
    }

    @Override protected void onDetachedFromWindow() {
        cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) * 0.44f;
        oval.set(cx - r, cy - r, cx + r, cy + r);

        float per = 360f / range;
        label.setTextSize(Math.max(8f, r * 0.10f));

        cv.save();
        // rotate whole wheel; -90 so segment 0 starts at the top (12 o'clock)
        cv.rotate(rotation - 90f, cx, cy);

        for (int i = 0; i < range; i++) {
            float start = i * per;
            if (i == 0)             seg.setColor(Theme.green());   // zero accent
            else if ((i & 1) == 1)  seg.setColor(Theme.red());
            else                    seg.setColor(DARK);
            cv.drawArc(oval, start, per, true, seg);

            // number label, upright-ish along the radius
            double mid = Math.toRadians(start + per / 2f);
            float lr = r * 0.72f;
            float lx = cx + (float) Math.cos(mid) * lr;
            float ly = cy + (float) Math.sin(mid) * lr;
            cv.save();
            cv.rotate((start + per / 2f) + 90f, lx, ly);
            float baseline = ly - (label.descent() + label.ascent()) / 2f;
            cv.drawText(String.valueOf(i), lx, baseline, label);
            cv.restore();
        }
        cv.drawOval(oval, rim);
        cv.restore();

        // centre gold hub (does not rotate)
        cv.drawCircle(cx, cy, r * 0.18f, hub);
        rim.setColor(Theme.onAccent());
        cv.drawCircle(cx, cy, r * 0.18f, rim);
        rim.setColor(Theme.border());

        // fixed pointer at the very top
        float pw = r * 0.10f;
        float py = cy - r - r * 0.02f;
        tri.reset();
        tri.moveTo(cx - pw, py);
        tri.lineTo(cx + pw, py);
        tri.lineTo(cx, py + pw * 1.8f);
        tri.close();
        cv.drawPath(tri, pointer);
    }
}
