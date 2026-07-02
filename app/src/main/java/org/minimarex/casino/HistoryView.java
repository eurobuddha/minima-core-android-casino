package org.minimarex.casino;

import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

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
        TextView sub = Ui.text(act, "Your last 50 resolved bets.", Theme.dim(), 11, false);
        sub.setPadding(0, Ui.dp(act, 2), 0, Ui.dp(act, 12));
        container.addView(sub);

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
