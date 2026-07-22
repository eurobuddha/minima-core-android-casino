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
 * A spinning roulette wheel. For the zero-edge game (range 36) it draws 36 pockets in the
 * authentic single-zero-REMOVED European pocket order, numbered 1-36 with real red/black
 * colouring — NO green zero (the zero is the house edge; this casino has none). A gold centre
 * hub and a fixed gold pointer at the top (12 o'clock).
 *
 * <p>{@link #spin(int, Runnable)} accelerates then decelerates the wheel so the pointer lands
 * on the pocket for the rolled result (on-chain result R, 0-based, shown as number R+1).
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
    // 36-pocket ZERO-EDGE wheel — authentic European pocket order with the single zero removed (no house edge), 1-36.
    private static final int[] ORDER = {32,15,19,4,21,2,25,17,34,6,27,13,36,11,30,8,23,10,5,24,16,33,1,20,14,31,9,22,18,29,7,28,12,35,3,26};
    private static final java.util.HashSet<Integer> RED = new java.util.HashSet<>(java.util.Arrays.asList(1,3,5,7,9,12,14,16,18,19,21,23,25,27,30,32,34,36));
    private int orderIndexOf(int number) { for (int i = 0; i < ORDER.length; i++) if (ORDER[i] == number) return i; return 0; }

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
        // On the 36-pocket wheel the visible segment for result R is where number R+1 sits in the pocket order.
        int segIdx = (range == 36) ? orderIndexOf(pick + 1) : pick;

        float per = 360f / range;
        // Segment i is centred (in wheel-local space) at angle i*per starting from the
        // top and going clockwise. To bring that segment under the top pointer, the
        // wheel must rotate so that -(segIdx*per + per/2) is at 0deg, plus whole spins.
        float target = -(segIdx * per + per / 2f);
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
            int num = (range == 36) ? ORDER[i] : (i + 1);   // 1-36, NO zero
            boolean isRed = (range == 36) ? RED.contains(num) : ((i & 1) == 1);
            seg.setColor(isRed ? Theme.red() : DARK);
            cv.drawArc(oval, start, per, true, seg);

            // number label, upright-ish along the radius
            double mid = Math.toRadians(start + per / 2f);
            float lr = r * 0.72f;
            float lx = cx + (float) Math.cos(mid) * lr;
            float ly = cy + (float) Math.sin(mid) * lr;
            cv.save();
            cv.rotate((start + per / 2f) + 90f, lx, ly);
            float baseline = ly - (label.descent() + label.ascent()) / 2f;
            cv.drawText(String.valueOf(num), lx, baseline, label);
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
