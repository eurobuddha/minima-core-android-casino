package com.eurobuddha.casino;

import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.math.BigDecimal;

import static com.eurobuddha.casino.CasinoContract.Game;

/** MY BETS tab — bets where I'm house or player, across phases. Auto-processing handles reveal /
 *  resolve; this view also offers manual actions (cancel, resolve, claim timeout). */
public class MyBetsView extends BaseView {

    private final LinearLayout container;

    public MyBetsView(MainActivity a) {
        super(a, R.layout.view_container);
        container = find(R.id.container);
    }

    private String lastSig = null;
    // coinid -> the card's status/timeout TextView, so the countdown can tick each block WITHOUT
    // rebuilding the card (which would restart the spinning animation).
    private final java.util.Map<String, TextView> statusViews = new java.util.HashMap<>();

    @Override public void onShown() { lastSig = null; refresh(); }

    @Override public void refresh() {
        // Only rebuild when the set of my bets actually changes (new bet / phase change / resolved /
        // timeout). Otherwise keep the existing cards so their continuous animations don't restart
        // every block — but still tick the per-card timeout counters in place.
        String sig = signature();
        if (sig.equals(lastSig)) { updateLiveCounters(); return; }
        lastSig = sig;

        statusViews.clear();
        container.removeAllViews();
        container.addView(Ui.text(act, "MY BETS", Theme.gold(), 16, true));
        TextView sub = Ui.text(act, "Auto-reveal & resolve. Keep Minima Core running and don't "
                + "force-stop the casino, or a bet may stall (and time out) until you reopen.",
                Theme.amber(), 11, false);
        sub.setPadding(0, Ui.dp(act, 2), 0, Ui.dp(act, 12));
        container.addView(sub);

        int shown = 0;
        for (Bet b : act.bets()) {
            if (!b.isMine(act.myKeys())) continue;
            container.addView(betCard(b));
            shown++;
        }
        if (shown == 0) {
            TextView empty = Ui.text(act, "No active bets.\nTake a bet on PLAY or open one on HOUSE.", Theme.dim(), 12, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(act, 40), 0, 0);
            container.addView(empty);
        }
    }

    private String signature() {
        StringBuilder sb = new StringBuilder();
        for (Bet b : act.bets()) {
            if (!b.isMine(act.myKeys())) continue;
            sb.append(b.coinid()).append(':').append(b.phase)
              .append(b.timedOut(act.chainBlock()) ? "T" : "").append('|');
        }
        return sb.toString();
    }

    private LinearLayout betCard(Bet b) {
        Game g = b.game();
        boolean isHouse = b.iAmHouse(act.myKeys());
        LinearLayout card = Ui.card(act);

        LinearLayout header = Ui.row(act);
        header.addView(Ui.text(act, g.icon + "  " + g.name + " · " + Currency.nameFor(b.tokenid()),
                Theme.gold(), 18, true), Ui.lpRow(act, 1));
        header.addView(Ui.badge(act, phaseLabel(b.phase), Theme.bg(), phaseColor(b.phase)));
        card.addView(header);

        TextView role = Ui.text(act, isHouse ? "YOU ARE THE HOUSE" : "YOU ARE THE PLAYER",
                isHouse ? Theme.gold() : Theme.cyan(), 14, true);
        role.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 2));
        card.addView(role);

        // The pick — big and clear (your own pick as player; the player's pick as house).
        String pickStr = b.pick >= 0
                ? (isHouse ? "Player picked  " : "Your pick  ") + g.pickLabel(b.pick)
                : "Waiting for a player to pick…";
        card.addView(Ui.text(act, pickStr, Theme.text(), 15, true));

        card.addView(Ui.text(act, "Stake " + Currency.show(b.betAmount, b.tokenid())
                + "  ·  pot " + Currency.show(b.totalAmount, b.tokenid())
                + "  ·  win " + Currency.show(Util.miniNum(Util.dec(b.betAmount).multiply(java.math.BigDecimal.valueOf(g.payout))), b.tokenid()),
                Theme.dim(), 12, false));

        // Big live game visual — spins continuously while the bet is in play.
        View visual = gameVisual(b);
        if (visual != null) {
            int sz = Ui.dp(act, 150);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(sz, sz);
            vlp.topMargin = Ui.dp(act, 14);
            vlp.bottomMargin = Ui.dp(act, 4);
            vlp.gravity = Gravity.CENTER_HORIZONTAL;
            visual.setLayoutParams(vlp);
            card.addView(visual);
        }

        card.addView(statusLine(b, isHouse));
        addActions(card, b, isHouse);
        return card;
    }

    /**
     * The game visual. STILL while the bet is open and untaken (phase 0) — it only starts spinning
     * once the bet has been TAKEN and is in play (phase >= 1). That spin is the "your game is being
     * played, wait for the result" signal; spinning before anyone has taken it is meaningless.
     */
    private View gameVisual(Bet b) {
        Game g = b.game();
        boolean inPlay = b.phase >= 1;
        int face = Math.max(0, b.pick);
        switch (g) {
            case FLIP: {
                CoinFlipView v = new CoinFlipView(act);
                if (inPlay) v.startFlipping(); else v.setIdleFace(face);
                return v;
            }
            case DICE: {
                DiceRollView v = new DiceRollView(act);
                if (inPlay) v.startTumbling(); else v.setIdleFace(face);
                return v;
            }
            case ROULETTE: {
                RouletteView v = new RouletteView(act);
                v.setRange(g.range);
                if (inPlay) v.startSpinning();   // else: draws a still wheel
                return v;
            }
            default: return null;
        }
    }

    private TextView statusLine(Bet b, boolean isHouse) {
        TextView t = Ui.text(act, "", Theme.dim(), 11, false);
        t.setPadding(0, Ui.dp(act, 10), 0, 0);
        statusViews.put(b.coinid(), t);
        applyStatus(t, b, isHouse);
        return t;
    }

    /** Phase line + a live timeout countdown (blocks until the bet can be reclaimed if abandoned). */
    private String statusText(Bet b, boolean isHouse) {
        String msg;
        if (b.phase == 0) msg = "Open — waiting for a player to take it.";
        else if (b.phase == 1) msg = isHouse ? "Revealing house secret…" : "Waiting for house to reveal…";
        else msg = isHouse ? "Waiting for player to resolve…" : "Resolving — paying out…";
        if (b.phase >= 1 && b.coin.created >= 0) {
            long age = Math.max(0L, act.chainBlock() - b.coin.created);
            long left = b.timeout + 1L - age;  // covenant requires age > timeout, not >=
            if (left > 0) msg += "\n⏳ Timeout in " + left + " block" + (left == 1 ? "" : "s") + " (" + age + " elapsed)";
            else msg += b.canClaimTimeout(act.myKeys(), act.chainBlock())
                    ? "\nTimed out — you can claim the pot."
                    : "\nTimed out — the counterparty can claim the pot.";
        }
        return msg;
    }

    private void applyStatus(TextView t, Bet b, boolean isHouse) {
        t.setText(statusText(b, isHouse));
        t.setTextColor(b.timedOut(act.chainBlock()) ? Theme.amber() : Theme.dim());
    }

    /** Tick the per-card timeout counters without rebuilding (keeps the spin smooth). */
    private void updateLiveCounters() {
        for (Bet b : act.bets()) {
            if (!b.isMine(act.myKeys())) continue;
            TextView t = statusViews.get(b.coinid());
            if (t != null) applyStatus(t, b, b.iAmHouse(act.myKeys()));
        }
    }

    private void addActions(LinearLayout card, Bet b, boolean isHouse) {
        LinearLayout actions = Ui.row(act);
        Ui.marginTop(actions, Ui.dp(act, 10));
        TextView status = Ui.text(act, "", Theme.dim(), 11, false);

        boolean any = false;
        if (b.phase == 0 && isHouse) {
            Button cancel = Ui.button(act, "Cancel", Theme.panel2(), Theme.text());
            cancel.setOnClickListener(v -> { cancel.setEnabled(false); status.setText("Cancelling…");
                act.txn().cancel(b, simple(status, "Bet cancelled. Funds returned.")); });
            actions.addView(cancel);
            any = true;
        }
        // Already resolved locally (result recorded + modal shown) but the payout coin hasn't mined
        // out yet: the outcome is decided, so show a settled status instead of a live Resolve button
        // — keeps MY BETS in step with HISTORY and the celebration while confirmation completes.
        ResolvedBet settled = b.phase == 2 && b.iAmPlayer(act.myKeys()) ? act.resultFor(b.coinid()) : null;
        if (settled != null) {
            status.setTextColor(settled.won ? Theme.green() : Theme.red());
            status.setText((settled.won ? "WON +" : "LOST -") + settled.profit + " · payout confirming…");
        } else if (b.phase == 2 && b.iAmPlayer(act.myKeys())) {
            boolean inFlight = act.auto() != null && act.auto().inFlight(b.coinid(), act.chainBlock());
            Button resolve = Ui.button(act, inFlight ? "Resolving…" : "Resolve", Theme.gold(), Theme.onAccent());
            resolve.setOnClickListener(v -> {
                // Auto-resolve already handles this; only fire manually if nothing is in flight,
                // and register it so the auto-resolver won't also post a competing tx.
                if (act.auto() != null && act.auto().inFlight(b.coinid(), act.chainBlock())) {
                    status.setText("Resolving automatically — please wait…");
                    return;
                }
                if (act.auto() != null) act.auto().markPosted(b.coinid(), act.chainBlock());
                resolve.setEnabled(false); status.setText("Resolving…"); onResolve(b, status);
            });
            actions.addView(resolve);
            any = true;
        }
        if (b.canClaimTimeout(act.myKeys(), act.chainBlock())) {
            Button claim = Ui.button(act, "Claim Timeout", Theme.pink(), Theme.text());
            if (actions.getChildCount() > 0) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.leftMargin = Ui.dp(act, 8);
                claim.setLayoutParams(lp);
            }
            claim.setOnClickListener(v -> { claim.setEnabled(false); status.setText("Claiming…");
                act.txn().claimTimeout(b, simple(status, "Timeout claimed. Funds returned.")); });
            actions.addView(claim);
            any = true;
        }
        if (any) card.addView(actions);
        status.setPadding(0, Ui.dp(act, 8), 0, 0);
        card.addView(status);
    }

    private void onResolve(Bet b, TextView status) {
        act.txn().resolve(b, new CasinoTxn.ResolveResult() {
            @Override public void onResolved(boolean playerWins, int result, String txpowid) {
                boolean isHouse = b.iAmHouse(act.myKeys());
                boolean iWon = (playerWins && !isHouse) || (!playerWins && isHouse);
                BigDecimal profit = profit(b, iWon, isHouse);
                act.log((iWon ? "WON +" : "LOST -") + Currency.show(Util.miniNum(profit), b.tokenid()) + " · " + b.gameName()
                        + " (rolled " + b.game().pickLabel(result) + ")", iWon ? MainActivity.LOG_OK : MainActivity.LOG_ERR);
                status.setTextColor(iWon ? Theme.green() : Theme.red());
                status.setText((iWon ? "WON +" : "LOST -") + Currency.show(Util.miniNum(profit), b.tokenid()));
                act.recordResult(b, iWon, profit, result, isHouse);   // records + celebrates (modal/anim/sound)
                act.requestReload();
            }
            @Override public void onFailed(String message) {
                status.setTextColor(Theme.red());
                status.setText(message);
            }
        });
    }

    private CasinoTxn.Result simple(TextView status, String okMsg) {
        return new CasinoTxn.Result() {
            @Override public void onPosted(String txpowid) {
                status.setTextColor(Theme.green()); status.setText(okMsg);
                act.log(okMsg, MainActivity.LOG_OK); act.requestReload();
            }
            @Override public void onFailed(String message) {
                status.setTextColor(Theme.red()); status.setText(message);
                act.log(message, MainActivity.LOG_ERR);
            }
        };
    }

    private static BigDecimal profit(Bet bet, boolean iWon, boolean isHouse) {
        BigDecimal betAmt = Util.dec(bet.betAmount);
        BigDecimal winnings = betAmt.multiply(BigDecimal.valueOf(bet.payout));
        if (iWon) return isHouse ? betAmt : winnings.subtract(betAmt);
        return isHouse ? betAmt.multiply(BigDecimal.valueOf(bet.payout - 1L)) : betAmt;
    }

    private String phaseLabel(int phase) {
        switch (phase) { case 0: return "Open"; case 1: return "Taken"; default: return "Revealed"; }
    }

    private int phaseColor(int phase) {
        switch (phase) { case 0: return Theme.cyan(); case 1: return Theme.gold(); default: return Theme.green(); }
    }
}
