package com.eurobuddha.casino;

import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** HISTORY tab — resolved bets with P&L. */
public class HistoryView extends BaseView {

    private final LinearLayout container;
    private final SimpleDateFormat fmt = new SimpleDateFormat("dd MMM HH:mm", Locale.getDefault());

    public HistoryView(MainActivity a) {
        super(a, R.layout.view_container);
        container = find(R.id.container);
    }

    @Override public void onShown() { refresh(); }

    @Override public void refresh() {
        container.removeAllViews();
        container.addView(Ui.text(act, "HISTORY", Theme.gold(), 16, true));

        // Sub-header line: the "last N resolved bets" caption on the left, and — on the SAME line,
        // right-aligned — the net P&L across the stored history (the running total of the same +/-
        // profit figures each row below shows). Won profits add, lost profits subtract.
        LinearLayout subRow = Ui.row(act);
        subRow.setPadding(0, Ui.dp(act, 2), 0, Ui.dp(act, 12));
        subRow.addView(Ui.text(act, "Your last " + SecretStore.HISTORY_CAP + " resolved bets.", Theme.dim(), 11, false), Ui.lpRow(act, 1));
        if (!act.history().isEmpty()) {
            BigDecimal net = BigDecimal.ZERO;
            for (ResolvedBet r : act.history()) {
                BigDecimal p = Util.dec(r.profit);
                net = r.won ? net.add(p) : net.subtract(p);
            }
            int sign = net.signum();
            String netTxt = "Net " + (sign < 0 ? "-" : "+") + Util.displayAmount(net.abs());
            int netColor = sign > 0 ? Theme.green() : sign < 0 ? Theme.red() : Theme.dim();
            subRow.addView(Ui.text(act, netTxt, netColor, 11, true));
        }
        container.addView(subRow);

        if (act.history().isEmpty()) {
            TextView empty = Ui.text(act, "No completed bets yet.", Theme.dim(), 12, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(act, 40), 0, 0);
            container.addView(empty);
            return;
        }
        for (ResolvedBet r : act.history()) container.addView(row(r));
    }

    private LinearLayout row(ResolvedBet r) {
        LinearLayout card = Ui.card(act);
        LinearLayout top = Ui.row(act);
        top.addView(Ui.text(act, r.game + "  ·  " + r.role, Theme.text(), 13, true), Ui.lpRow(act, 1));
        TextView pl = Ui.text(act, (r.won ? "+" : "-") + r.profit, r.won ? Theme.green() : Theme.red(), 13, true);
        top.addView(pl);
        card.addView(top);

        TextView detail = Ui.text(act,
                "Picked " + r.pickLabel + " · rolled " + r.resultLabel + "  ·  " + fmt.format(new Date(r.time)),
                Theme.dim(), 10, false);
        detail.setPadding(0, Ui.dp(act, 5), 0, 0);
        card.addView(detail);
        return card;
    }
}
