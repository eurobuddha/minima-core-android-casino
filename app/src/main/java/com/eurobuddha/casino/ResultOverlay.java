package com.eurobuddha.casino;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import static com.eurobuddha.casino.CasinoContract.Game;

/**
 * Full-screen win/lose reveal — the native equivalent of the dapp's flashResult modal. Plays the
 * game animation (coin flip / dice roll / roulette spin) settling on the rolled result, then
 * reveals the outcome with the win/lose jingle and confetti. Tap anywhere (or wait) to dismiss.
 */
public final class ResultOverlay {

    private ResultOverlay() {}

    public static void show(MainActivity act, Game game, int pick, int result, boolean iWon, String profit) {
        final ViewGroup root = act.findViewById(android.R.id.content);
        if (root == null) return;

        final FrameLayout overlay = new FrameLayout(act);
        overlay.setBackgroundColor(0xE60A0E1A);   // dim the app behind
        overlay.setClickable(true);
        overlay.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout card = Ui.col(act);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        card.setLayoutParams(clp);

        TextView game_t = Ui.text(act, game.icon + "  " + game.name.toUpperCase(), Theme.dim(), 12, true);
        game_t.setGravity(Gravity.CENTER);
        game_t.setPadding(0, 0, 0, Ui.dp(act, 14));
        card.addView(game_t);

        // The animation view
        View visual = visual(act, game);
        int size = Ui.dp(act, 190);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(size, size);
        vlp.gravity = Gravity.CENTER_HORIZONTAL;
        visual.setLayoutParams(vlp);
        card.addView(visual);

        // Outcome text (revealed after the animation settles)
        final TextView title = Ui.text(act, "", iWon ? Theme.green() : Theme.red(), 28, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, Ui.dp(act, 18), 0, 0);
        title.setVisibility(View.INVISIBLE);
        card.addView(title);

        final TextView detail = Ui.text(act, "", Theme.text(), 13, false);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, Ui.dp(act, 6), 0, 0);
        detail.setVisibility(View.INVISIBLE);
        card.addView(detail);

        final TextView hint = Ui.text(act, "tap to continue", Theme.dim(), 11, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dp(act, 22), 0, 0);
        hint.setVisibility(View.INVISIBLE);
        card.addView(hint);

        overlay.addView(card);
        root.addView(overlay);

        final Runnable dismiss = () -> { try { root.removeView(overlay); } catch (Exception ignored) {} };
        overlay.setOnClickListener(v -> dismiss.run());

        // The reveal (text + win/lose jingle + confetti). Guarded so it runs exactly once, whether
        // fired by the animation's onEnd or by the fallback timer below — so even if an animation
        // view misbehaves, the sound/celebration still happens.
        final boolean[] settled = {false};
        final Runnable settle = () -> {
            if (settled[0]) return;
            settled[0] = true;
            title.setText(iWon ? "YOU WIN" : "YOU LOSE");
            detail.setText((iWon ? "+" : "-") + profit + " Minima\nPicked "
                    + game.pickLabel(pick) + "  ·  Rolled "
                    + (result >= 0 ? game.pickLabel(result) : "—"));
            title.setVisibility(View.VISIBLE);
            detail.setVisibility(View.VISIBLE);
            hint.setVisibility(View.VISIBLE);
            if (iWon) { Sfx.win(); Confetti.burst(overlay); }
            else Sfx.lose();
            overlay.postDelayed(dismiss, 6000);
        };

        // Start the matching animation once the view is laid out (so it has a non-zero size). Each
        // animation view self-plays its spin SFX. A fallback timer guarantees `settle` runs even if
        // the animation throws or never calls back.
        startWhenLaidOut(visual, () -> animate(visual, game, result, settle));
        overlay.postDelayed(settle, 2800);
    }

    /** Run {@code action} after the view has a measured size (or immediately if it already does). */
    private static void startWhenLaidOut(View v, Runnable action) {
        if (v.getWidth() > 0 && v.getHeight() > 0) { action.run(); return; }
        v.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (v.getWidth() <= 0 || v.getHeight() <= 0) return;
                v.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                action.run();
            }
        });
    }

    private static View visual(MainActivity act, Game game) {
        switch (game) {
            case DICE: return new DiceRollView(act);
            case ROULETTE: { RouletteView r = new RouletteView(act); r.setRange(game.range); return r; }
            case FLIP:
            default: return new CoinFlipView(act);
        }
    }

    private static void animate(View visual, Game game, int result, Runnable onEnd) {
        try {
            int r = Math.max(0, result);
            switch (game) {
                case DICE: ((DiceRollView) visual).roll(r, onEnd); break;
                case ROULETTE: ((RouletteView) visual).spin(r, onEnd); break;
                case FLIP:
                default: ((CoinFlipView) visual).flip(r, onEnd); break;
            }
        } catch (Throwable t) {
            if (onEnd != null) onEnd.run();   // never let an animation failure swallow the reveal
        }
    }
}
