package com.eurobuddha.casino;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.eurobuddha.casino.CasinoContract.*;

/** Refresh an untaken offer before the 1024-block family cascade drops it on other nodes. */
final class OfferKeepAlive {
    static final int RENEW_AT = 500; // Same early renewal window as Limit's GTC processor.

    static boolean due(Bet bet, long block) {
        return valid(bet) && bet.coin.created >= 0 && block - bet.coin.created >= RENEW_AT;
    }

    // Only canonical offers made by this app are renewed. Never interpolate arbitrary on-chain
    // text into commands; preserve all eight original fields without rounding money or exposing secrets.
    static boolean valid(Bet b) {
        if (b.phase != 0 || b.coin.state.size() != 8 || !hex(b.coinid()) || !hex(b.tokenid())) return false;
        if (!Util.isMinima(b.tokenid()) && !Currency.USD_TOKENID.equalsIgnoreCase(b.tokenid())) return false;
        for (int p = 0; p <= 2; p++) if (!hex(b.coin.stateAt(p))) return false;
        for (int p : new int[]{3, 4, 6, 7}) if (!b.coin.stateAt(p).matches("[0-9]+")) return false;
        if (!b.betAmount.matches("[0-9]+(?:\\.[0-9]+)?") || !b.totalAmount.matches("[0-9]+(?:\\.[0-9]+)?")) return false;
        if (!(b.range == 2 || b.range == 6 || b.range == 36) || b.payout != b.range || b.timeout < 0) return false;
        BigDecimal stake = new BigDecimal(b.betAmount);
        return stake.signum() > 0 && new BigDecimal(b.totalAmount).compareTo(stake.multiply(BigDecimal.valueOf(b.payout - 1L))) == 0;
    }

    private static boolean hex(String s) { return s != null && s.matches("0x(?:[0-9a-fA-F]{2})+"); }

    /** Same owner-signed, state-preserving input/output sequence used by CasinoTxn.reveal. */
    static List<String> commands(Bet b, String id) {
        if (!valid(b) || !id.matches("[a-zA-Z0-9_]+")) throw new IllegalArgumentException("Invalid open offer");
        List<String> cmds = new ArrayList<>();
        cmds.add("txncreate id:" + id);
        cmds.add("txninput id:" + id + " coinid:" + b.coinid());
        cmds.add("txnoutput id:" + id + " amount:" + b.totalAmount + " address:" + SCRIPT_ADDR + " tokenid:" + b.tokenid() + " storestate:true");
        for (int p = 0; p <= 7; p++) cmds.add("txnstate id:" + id + " port:" + p + " value:" + b.coin.stateAt(p));
        cmds.add("txnsign id:" + id + " publickey:" + b.housePk);
        cmds.add("txnbasics id:" + id);
        return cmds; // Caller checks durable cancellation intent again immediately before txnpost.
    }
}
