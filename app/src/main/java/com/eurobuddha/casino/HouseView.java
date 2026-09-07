package com.eurobuddha.casino;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.math.BigDecimal;

import static com.eurobuddha.casino.CasinoContract.Game;

/** HOUSE tab — create a new bet (phase 0). Port of the dapp's create form. */
public class HouseView extends BaseView {

    private final LinearLayout container;
    private Game selected = Game.FLIP;
    private final LinearLayout presetRow;
    private EditText amount;
    private TextView summary, status;

    public HouseView(MainActivity a) {
        super(a, R.layout.view_container);
        container = find(R.id.container);

        container.addView(Ui.text(act, "BE THE HOUSE", Theme.gold(), 16, true));
        TextView sub = Ui.text(act, "Lock funds and open a bet. Fair odds, zero edge.", Theme.dim(), 11, false);
        sub.setPadding(0, Ui.dp(act, 2), 0, Ui.dp(act, 12));
        container.addView(sub);

        LinearLayout card = Ui.card(act);

        card.addView(Ui.label(act, "Game"));
        presetRow = Ui.row(act);
        presetRow.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 12));
        for (Game g : Game.values()) presetRow.addView(presetButton(g));
        card.addView(presetRow);

        card.addView(Ui.label(act, "Bet amount (" + Currency.label() + ")"));
        amount = new EditText(act);
        amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amount.setText("1");
        amount.setTextColor(Theme.text());
        amount.setBackground(Ui.rounded(Theme.panel2(), Theme.border(), 6, act));
        amount.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10));
        amount.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {}
            public void afterTextChanged(Editable s) { updateSummary(); }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Ui.dp(act, 6);
        amount.setLayoutParams(alp);
        card.addView(amount);

        summary = Ui.text(act, "", Theme.cyan(), 12, false);
        summary.setPadding(0, Ui.dp(act, 12), 0, Ui.dp(act, 12));
        card.addView(summary);

        android.widget.Button create = Ui.button(act, "Create Bet", Theme.gold(), Theme.onAccent());
        create.setOnClickListener(v -> onCreate());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        create.setLayoutParams(clp);
        card.addView(create);

        status = Ui.text(act, "", Theme.dim(), 11, false);
        status.setPadding(0, Ui.dp(act, 8), 0, 0);
        card.addView(status);

        container.addView(card);
        updatePresetStyles();
        updateSummary();
    }

    private TextView presetButton(Game g) {
        TextView t = Ui.text(act, g.icon + "\n" + g.key.toUpperCase() + "\n" + g.oddsAgainst() + ":1",
                Theme.text(), 12, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(act, 10), 0, Ui.dp(act, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Ui.dp(act, 3), 0, Ui.dp(act, 3), 0);
        t.setLayoutParams(lp);
        t.setTag(g);
        t.setOnClickListener(v -> { selected = g; updatePresetStyles(); updateSummary(); Sfx.click(); });
        return t;
    }

    private void updatePresetStyles() {
        for (int i = 0; i < presetRow.getChildCount(); i++) {
            View c = presetRow.getChildAt(i);
            boolean sel = c.getTag() == selected;
            c.setBackground(Ui.rounded(sel ? Theme.panel2() : Theme.panel(),
                    sel ? Theme.gold() : Theme.border(), 6, act));
            ((TextView) c).setTextColor(sel ? Theme.gold() : Theme.text());
        }
    }

    private void updateSummary() {
        BigDecimal bet = Util.dec(amount.getText().toString());
        BigDecimal stake = bet.multiply(BigDecimal.valueOf(selected.payout - 1L));
        if (stake.compareTo(BigDecimal.ZERO) <= 0) stake = bet;
        BigDecimal pot = stake.add(bet);
        BigDecimal win = bet.multiply(BigDecimal.valueOf(selected.payout));
        summary.setText("You lock " + Currency.show(Util.miniNum(stake)) + " · player stakes " + Currency.show(Util.miniNum(bet))
                + "\nPot " + Currency.show(Util.miniNum(pot)) + " · player wins " + Currency.show(Util.miniNum(win))
                + " at " + selected.oddsAgainst() + ":1 (1/" + selected.range + " chance)");
    }

    private void onCreate() {
        if (!act.identityReady()) { status.setTextColor(Theme.red()); status.setText("Identity not loaded yet"); return; }
        BigDecimal bet = Util.dec(amount.getText().toString());
        if (bet.compareTo(BigDecimal.ZERO) <= 0) { status.setTextColor(Theme.red()); status.setText("Enter a valid bet amount"); return; }
        status.setTextColor(Theme.cyan());
        status.setText("Generating secret & sending…");
        Sfx.chipStack();
        act.txn().create(selected, bet, new CasinoTxn.Result() {
            @Override public void onPosted(String txpowid) {
                status.setTextColor(Theme.amber());
                status.setText("Bet sent — confirming over the next few blocks…");
                act.markPending(act.pendingCreate(), "Created " + selected.name + " bet ("
                        + Currency.show(Util.miniNum(bet)) + " · " + selected.oddsAgainst() + ":1)");
                act.requestReload();
            }
            @Override public void onFailed(String message) {
                status.setTextColor(Theme.red());
                status.setText(message);
                act.log("Create failed: " + message, MainActivity.LOG_ERR);
            }
        });
    }

    @Override public void refresh() {}
}
