package org.minimarex.casino;

import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.minimarex.casino.CasinoContract.Game;

/** PLAY tab — open phase-0 bets created by others, available to take. */
public class PlayView extends BaseView {

    private final LinearLayout container;
    private final Map<String, Integer> picks = new HashMap<>();
    private final Map<String, TextView> statuses = new HashMap<>();
    // Bets we've already fired a Take for — persists across refreshes so the button can't be tapped
    // again while the take is still confirming (which would post competing txns and stall the chain).
    private final Set<String> taking = new HashSet<>();

    public PlayView(MainActivity a) {
        super(a, R.layout.view_container);
        container = find(R.id.container);
    }

    @Override public void onShown() { refresh(); }

    @Override public void refresh() {
        container.removeAllViews();
        container.addView(Ui.text(act, "OPEN BETS", Theme.gold(), 16, true));
        TextView sub = Ui.text(act, "Pick a side and take a bet to play.", Theme.dim(), 11, false);
        sub.setPadding(0, Ui.dp(act, 2), 0, Ui.dp(act, 12));
        container.addView(sub);

        // Prune the take-guard: drop bets that have left phase 0 (taken / cancelled / gone).
        Set<String> openIds = new HashSet<>();
        for (Bet b : act.bets()) if (b.phase == 0) openIds.add(b.coinid());
        taking.retainAll(openIds);

        int shown = 0;
        for (Bet b : act.bets()) {
            if (b.phase != 0) continue;
            if (b.iAmHouse(act.myKeys())) continue;     // can't take your own bet
            container.addView(betCard(b));
            shown++;
        }
        if (shown == 0) {
            TextView empty = Ui.text(act, "No open bets right now.\nCreate one on the HOUSE tab.", Theme.dim(), 12, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(act, 40), 0, 0);
            container.addView(empty);
        }
    }

    private LinearLayout betCard(Bet b) {
        Game g = b.game();
        LinearLayout card = Ui.card(act);

        LinearLayout header = Ui.row(act);
        TextView title = Ui.text(act, g.icon + "  " + g.name, Theme.text(), 14, true);
        header.addView(title, Ui.lpRow(act, 1));
        header.addView(Ui.badge(act, g.oddsAgainst() + ":1", Theme.bg(), Theme.gold()));
        card.addView(header);

        TextView info = Ui.text(act,
                "Stake " + Util.miniNum(Util.dec(b.betAmount)) + " · pot "
                        + Util.miniNum(Util.dec(b.totalAmount)) + " · win "
                        + Util.miniNum(Util.dec(b.betAmount).multiply(java.math.BigDecimal.valueOf(g.payout))),
                Theme.dim(), 11, false);
        info.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 10));
        card.addView(info);

        boolean isTaking = taking.contains(b.coinid());
        if (!isTaking) {
            card.addView(Ui.label(act, "Your pick"));
            card.addView(pickChips(b, g));
        }

        Button take = Ui.button(act, isTaking ? "Taking…" : "Take Bet",
                isTaking ? Theme.panel2() : Theme.pink(), isTaking ? Theme.dim() : Theme.text());
        take.setEnabled(!isTaking);
        take.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        Ui.marginTop(take, Ui.dp(act, 10));
        TextView status = Ui.text(act, isTaking ? "Taking this bet — confirming on-chain…" : "",
                isTaking ? Theme.amber() : Theme.dim(), 11, false);
        statuses.put(b.coinid(), status);
        if (!isTaking) take.setOnClickListener(v -> onTake(b, take, status));
        card.addView(take);
        status.setPadding(0, Ui.dp(act, 8), 0, 0);
        card.addView(status);
        return card;
    }

    /** Pick selector as a wrapping GRID so every number is visible at once (the dapp shows the
     *  36 roulette numbers in a grid, not a scroll line). Columns scale with the game range. */
    private LinearLayout pickChips(Bet b, Game g) {
        int cur = picks.containsKey(b.coinid()) ? picks.get(b.coinid()) : 0;
        int cols = g.range <= 2 ? 2 : (g.range <= 6 ? g.range : 6);   // flip:2, dice:6, roulette:6x6
        LinearLayout grid = Ui.col(act);
        grid.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 4));
        LinearLayout rowL = null;
        for (int i = 0; i < g.range; i++) {
            if (i % cols == 0) {
                rowL = Ui.row(act);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = Ui.dp(act, 6);
                rowL.setLayoutParams(rlp);
                grid.addView(rowL);
            }
            final int pick = i;
            TextView chip = Ui.text(act, g.pickLabel(i), pick == cur ? Theme.bg() : Theme.text(), 13, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(0, Ui.dp(act, 9), 0, Ui.dp(act, 9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(Ui.dp(act, 3), 0, Ui.dp(act, 3), 0);
            chip.setLayoutParams(lp);
            chip.setBackground(Ui.rounded(pick == cur ? Theme.gold() : Theme.panel2(),
                    pick == cur ? Theme.gold() : Theme.border(), 6, act));
            chip.setOnClickListener(v -> { picks.put(b.coinid(), pick); Sfx.click(); refresh(); });
            rowL.addView(chip);
        }
        // pad the final row so trailing cells keep equal width
        if (rowL != null) {
            int rem = g.range % cols;
            if (rem != 0) for (int k = rem; k < cols; k++) {
                View spacer = new View(act);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(Ui.dp(act, 3), 0, Ui.dp(act, 3), 0);
                spacer.setLayoutParams(lp);
                rowL.addView(spacer);
            }
        }
        return grid;
    }

    private void onTake(Bet b, Button take, TextView status) {
        if (!act.identityReady()) { status.setTextColor(Theme.red()); status.setText("Identity not loaded yet"); return; }
        if (taking.contains(b.coinid())) return;          // already taking this bet — ignore re-taps
        int pick = picks.containsKey(b.coinid()) ? picks.get(b.coinid()) : 0;
        taking.add(b.coinid());                           // guard: survives refresh until it leaves phase 0
        take.setEnabled(false);
        status.setTextColor(Theme.cyan());
        status.setText("Taking bet… (generating secret, building tx)");
        Sfx.cardDeal();
        act.txn().take(b, pick, new CasinoTxn.Result() {
            @Override public void onPosted(String txpowid) {
                status.setTextColor(Theme.amber());
                status.setText("Bet taken — confirming, then waiting for house reveal…");
                act.markPending(act.pendingTake(), "Took " + b.gameName() + " bet · picked "
                        + b.game().pickLabel(pick));
                act.switchTab(MainActivity.TAB_MYBETS);
                act.requestReload();
            }
            @Override public void onFailed(String message) {
                taking.remove(b.coinid());                // failed — allow a retry
                take.setEnabled(true);
                status.setTextColor(Theme.red());
                status.setText(message);
                act.log("Take failed: " + message, MainActivity.LOG_ERR);
            }
        });
    }
}
