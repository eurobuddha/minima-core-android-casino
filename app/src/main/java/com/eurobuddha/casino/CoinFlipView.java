package com.eurobuddha.casino;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 2D coin-flip animation. The coin is a gold circle that "flips" about a vertical axis
 * by scaling its horizontal radius with cos(angle) — when |cos| is small the coin is
 * edge-on (a thin gold line), and the visible glyph (H / T) switches as cos crosses zero,
 * faking a 3D rotation cheaply on a flat Canvas.
 *
 * <p>Call {@link #flip(int, Runnable)} with 0 = Heads, 1 = Tails; the coin spins several
 * turns and decelerates so it settles on the requested face.
 */
public class CoinFlipView extends View {

    private final Paint coin = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float angle = 0f;            // current rotation angle (radians)
    private int result = 0;              // 0 = Heads, 1 = Tails (settled face)
    private ValueAnimator anim;

    public CoinFlipView(Context c) { super(c); init(); }
    public CoinFlipView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        coin.setStyle(Paint.Style.FILL);
        coin.setColor(Theme.gold());

        edge.setStyle(Paint.Style.STROKE);
        edge.setColor(Theme.onAccent());
        edge.setStrokeWidth(2f);

        glyph.setColor(Theme.onAccent());
        glyph.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        glyph.setTextAlign(Paint.Align.CENTER);
    }

    /**
     * Spin the coin and settle on {@code resultPick} (0 = Heads, 1 = Tails).
     * Safe to call repeatedly — any in-flight animation is cancelled first.
     */
    public void flip(int resultPick, Runnable onEnd) {
        cancel();
        result = (resultPick == 1) ? 1 : 0;

        // Spin a whole number of half-turns + the half-turn that lands on the result face.
        // cos(angle) > 0 shows the "front" (Heads); we choose a final angle whose cos sign
        // matches the desired face.
        int turns = 5;                                   // full visual flips
        float finalAngle = (float) (turns * 2 * Math.PI);
        if (result == 1) finalAngle += (float) Math.PI;  // extra half-turn -> Tails face up

        final float endAngle = finalAngle;
        anim = ValueAnimator.ofFloat(0f, endAngle);
        anim.setDuration(1100);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(a -> { angle = (float) a.getAnimatedValue(); invalidate(); });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                angle = endAngle;
                invalidate();
                if (onEnd != null) post(onEnd);          // run on UI thread
            }
        });
        if (Theme.sound()) Sfx.coinSpin();
        anim.start();
    }

    /** Flip forever (the card's "game in progress" animation while waiting for resolution). */
    public void startFlipping() {
        cancel();
        anim = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
        anim.setDuration(1000);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new android.view.animation.LinearInterpolator());
        anim.addUpdateListener(a -> { angle = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    /** Cancel any running flip. */
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
        float r = Math.min(w, h) * 0.38f;

        float cos = (float) Math.cos(angle);
        float rx = Math.abs(cos) * r;          // horizontal radius collapses edge-on
        if (rx < 1f) rx = 1f;

        // FIXED faces: front (cos>=0) is always Heads, back (cos<0) is always Tails — so the coin flips
        // H/T/H/T naturally as it spins. The landing angle already encodes the result (an extra half-turn
        // for Tails puts the back face up), so the settled face is correct. (The old `(result==0)==frontUp`
        // inverted the faces for Tails and drew Heads on a Tails result.)
        boolean frontUp = cos >= 0;
        boolean showHeads = frontUp;

        // body
        cv.save();
        cv.scale(rx / r, 1f, cx, cy);          // squash horizontally to fake rotation
        cv.drawCircle(cx, cy, r, coin);
        cv.drawCircle(cx, cy, r, edge);
        // milled inner ring for that retro coin look
        edge.setStrokeWidth(1f);
        cv.drawCircle(cx, cy, r * 0.86f, edge);
        edge.setStrokeWidth(2f);
        cv.restore();

        // glyph fades out near edge-on so it doesn't smear on the thin line
        float vis = Math.abs(cos);
        if (vis > 0.18f) {
            glyph.setTextSize(r * 1.1f * vis + r * 0.2f);
            glyph.setAlpha((int) (255 * Math.min(1f, vis * 1.4f)));
            float baseline = cy - (glyph.descent() + glyph.ascent()) / 2f;
            // scale glyph horizontally with the coin so it sits on the face
            cv.save();
            cv.scale(rx / r, 1f, cx, cy);
            cv.drawText(showHeads ? "H" : "T", cx, baseline, glyph);
            cv.restore();
            glyph.setAlpha(255);
        }
    }

    /** Idle render: set a face without animating (e.g. before first flip). */
    public void setIdleFace(int resultPick) {
        cancel();
        result = (resultPick == 1) ? 1 : 0;
        angle = (result == 1) ? (float) Math.PI : 0f;   // back face up for Tails so the idle glyph is correct too
        invalidate();
    }
}
