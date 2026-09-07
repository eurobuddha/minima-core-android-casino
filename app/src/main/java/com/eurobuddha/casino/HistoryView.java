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

    /** How many rows to actually draw. The container is a plain ScrollView with no view recycling,
     *  so drawing all {@link SecretStore#HISTORY_CAP} rows would inflate thousands of views per
     *  refresh and jank on older phones. The Net line above still sums the FULL stored history —
     *  only the drawn rows are capped; a footer notes how many older bets are counted but not shown. */
    private static final int RENDER_LIMIT = 250;

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

        // Only the ACTIVE currency's resolved bets — History mirrors the currency toggle, and a Net
        // that mixed Minima and USD would be meaningless. Amounts render at each row's own token
        // resolution (full precision for USD, 5-dp cap for Minima) with the currency suffix.
        String tok = Currency.tokenId();
        java.util.List<ResolvedBet> mine = new java.util.ArrayList<>();
        for (ResolvedBet r : act.history()) if (sameToken(r.tokenid, tok)) mine.add(r);

        // Sub-header line: the "N resolved bets" caption on the left, and — on the SAME line,
        // right-aligned — the net P&L across this currency's stored history. Won adds, lost subtracts.
        LinearLayout subRow = Ui.row(act);
        subRow.setPadding(0, Ui.dp(act, 2), 0, Ui.dp(act, 12));
        int n = mine.size();
        String caption = "Your " + n + " " + Currency.label() + " bet" + (n == 1 ? "" : "s") + ".";
        subRow.addView(Ui.text(act, caption, Theme.dim(), 11, false), Ui.lpRow(act, 1));
        if (!mine.isEmpty()) {
            BigDecimal net = BigDecimal.ZERO;
            for (ResolvedBet r : mine) {
                BigDecimal p = Util.dec(r.profit);
                net = r.won ? net.add(p) : net.subtract(p);
            }
            int sign = net.signum();
            String netTxt = "Net " + (sign < 0 ? "-" : "+") + Currency.show(Util.miniNum(net.abs()), tok);
            int netColor = sign > 0 ? Theme.green() : sign < 0 ? Theme.red() : Theme.dim();
            subRow.addView(Ui.text(act, netTxt, netColor, 11, true));
        }
        container.addView(subRow);

        if (mine.isEmpty()) {
            TextView empty = Ui.text(act, "No completed " + Currency.label() + " bets yet.", Theme.dim(), 12, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(act, 40), 0, 0);
            container.addView(empty);
            return;
        }
        int shown = Math.min(mine.size(), RENDER_LIMIT);
        for (int i = 0; i < shown; i++) container.addView(row(mine.get(i)));
        if (mine.size() > shown) {
            TextView more = Ui.text(act,
                    "+ " + (mine.size() - shown) + " older bets — counted in Net above, not shown here.",
                    Theme.dim(), 10, false);
            more.setGravity(Gravity.CENTER);
            more.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 4));
            container.addView(more);
        }
    }

    /** Token equality treating null / "" / "0x00" all as native Minima. */
    private static boolean sameToken(String a, String b) {
        boolean am = Util.isMinima(a), bm = Util.isMinima(b);
        return am ? bm : a.equalsIgnoreCase(b);
    }

    private LinearLayout row(ResolvedBet r) {
        LinearLayout card = Ui.card(act);
        LinearLayout top = Ui.row(act);
        top.addView(Ui.text(act, r.game + "  ·  " + r.role, Theme.text(), 13, true), Ui.lpRow(act, 1));
        TextView pl = Ui.text(act, (r.won ? "+" : "-") + Currency.show(r.profit, r.tokenid), r.won ? Theme.green() : Theme.red(), 13, true);
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
