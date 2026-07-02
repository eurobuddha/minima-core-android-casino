package org.minimarex.casino;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * A rolling six-faced die. The die is a rounded square that tumbles (rotates while the
 * shown face flicks rapidly through random values) then decelerates and settles on the
 * requested face.
 *
 * <p>{@link #roll(int, Runnable)} takes resultPick 0..5 and shows resultPick + 1 pips.
 */
public class DiceRollView extends View {

    private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF body = new RectF();

    private int shown = 1;               // currently drawn face (1..6)
    private int result = 1;              // settled face (1..6)
    private float rot = 0f;              // rotation in degrees
    private ValueAnimator anim;
    private long lastFlick = 0;

    public DiceRollView(Context c) { super(c); init(); }
    public DiceRollView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        face.setStyle(Paint.Style.FILL);
        face.setColor(Theme.panel2());
        edge.setStyle(Paint.Style.STROKE);
        edge.setColor(Theme.border());
        edge.setStrokeWidth(3f);
        pip.setStyle(Paint.Style.FILL);
        pip.setColor(Theme.text());
    }

    /**
     * Tumble then settle on face {@code resultPick + 1} (resultPick 0..5).
     * Safe to call repeatedly — cancels any in-flight roll first.
     */
    public void roll(int resultPick, Runnable onEnd) {
        cancel();
        result = clampFace(resultPick + 1);

        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(900);
        anim.setInterpolator(new DecelerateInterpolator(1.8f));
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            rot = 540f * t;                          // 1.5 spins, easing out

            if (t < 0.82f) {
                // flick the shown face fast while tumbling
                long now = System.currentTimeMillis();
                if (now - lastFlick > 55) {
                    shown = 1 + (int) (Math.random() * 6);
                    lastFlick = now;
                    // diceRoll() SFX (fired in roll()) provides the click track
                }
            } else {
                shown = result;                      // lock to the result near the end
            }
            invalidate();
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                shown = result;
                rot = 0f;
                invalidate();
                if (onEnd != null) post(onEnd);
            }
        });
        if (Theme.sound()) Sfx.diceRoll();
        anim.start();
    }

    /** Tumble forever (the card's "game in progress" animation while waiting for resolution). */
    public void startTumbling() {
        cancel();
        anim = ValueAnimator.ofFloat(0f, 360f);
        anim.setDuration(1400);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new android.view.animation.LinearInterpolator());
        anim.addUpdateListener(a -> {
            rot = (float) a.getAnimatedValue();
            long now = System.currentTimeMillis();
            if (now - lastFlick > 140) { shown = 1 + (int) (Math.random() * 6); lastFlick = now; }
            invalidate();
        });
        anim.start();
    }

    public void cancel() {
        if (anim != null) { anim.cancel(); anim = null; }
    }

    /** Idle render of a specific face without animating. */
    public void setIdleFace(int resultPick) {
        cancel();
        shown = result = clampFace(resultPick + 1);
        rot = 0f;
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float side = Math.min(w, h) * 0.62f;
        float half = side / 2f;
        float radius = side * 0.16f;

        cv.save();
        cv.rotate(rot, cx, cy);
        body.set(cx - half, cy - half, cx + half, cy + half);
        cv.drawRoundRect(body, radius, radius, face);
        cv.drawRoundRect(body, radius, radius, edge);
        drawPips(cv, cx, cy, side, shown);
        cv.restore();
    }

    /** Draw standard 1..6 pip layouts within the die face. */
    private void drawPips(Canvas cv, float cx, float cy, float side, int n) {
        float pr = side * 0.085f;                    // pip radius
        float off = side * 0.24f;                    // pip offset from centre

        float L = cx - off, R = cx + off, MX = cx;
        float T = cy - off, B = cy + off, MY = cy;

        switch (n) {
            case 1:
                dot(cv, MX, MY, pr);
                break;
            case 2:
                dot(cv, L, T, pr); dot(cv, R, B, pr);
                break;
            case 3:
                dot(cv, L, T, pr); dot(cv, MX, MY, pr); dot(cv, R, B, pr);
                break;
            case 4:
                dot(cv, L, T, pr); dot(cv, R, T, pr);
                dot(cv, L, B, pr); dot(cv, R, B, pr);
                break;
            case 5:
                dot(cv, L, T, pr); dot(cv, R, T, pr);
                dot(cv, MX, MY, pr);
                dot(cv, L, B, pr); dot(cv, R, B, pr);
                break;
            default: // 6
                dot(cv, L, T, pr); dot(cv, R, T, pr);
                dot(cv, L, MY, pr); dot(cv, R, MY, pr);
                dot(cv, L, B, pr); dot(cv, R, B, pr);
                break;
        }
    }

    private void dot(Canvas cv, float x, float y, float r) {
        cv.drawCircle(x, y, r, pip);
    }

    private static int clampFace(int f) {
        if (f < 1) return 1;
        if (f > 6) return 6;
        return f;
    }
}
