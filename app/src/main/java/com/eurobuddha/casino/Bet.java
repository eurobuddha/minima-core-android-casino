package com.eurobuddha.casino;

import java.util.Set;

import static com.eurobuddha.casino.CasinoContract.*;

/**
 * A casino bet decoded from a contract coin's state. Mirrors the dapp's getState() reads.
 */
public class Bet {

    public final Coin coin;

    public final String housePk, houseAddr, houseCommit;
    public final int range, payout, phase, timeout;
    public final String betAmount;            // state[5], the player stake
    public final String totalAmount;          // coin.amount (pot held at the contract)

    public final String playerPk, playerAddr, playerCommit;
    public final int pick;                    // -1 if not set (phase 0)
    public final String houseSecret;          // phase 2+
    public final String playerSecret;         // resolve only

    public Bet(Coin c) {
        this.coin = c;
        housePk     = c.stateAt(P_HOUSE_PK);
        houseAddr   = c.stateAt(P_HOUSE_ADDR);
        houseCommit = c.stateAt(P_HOUSE_COMMIT);
        range       = parseInt(c.stateAt(P_RANGE), 2);
        payout      = parseInt(c.stateAt(P_PAYOUT), 2);
        betAmount   = c.stateAt(P_BET);
        phase       = parseInt(c.stateAt(P_PHASE), 0);
        timeout     = parseInt(c.stateAt(P_TIMEOUT), TIMEOUT_BLOCKS);
        playerPk    = c.stateAt(P_PLAYER_PK);
        playerAddr  = c.stateAt(P_PLAYER_ADDR);
        playerCommit= c.stateAt(P_PLAYER_COMMIT);
        pick        = parseInt(c.stateAt(P_PICK), -1);
        houseSecret = c.stateAt(P_HOUSE_SECRET);
        playerSecret= c.stateAt(P_PLAYER_SECRET);
        totalAmount = c.amount;
    }

    public String coinid() { return coin.coinid; }
    /** The token this bet's pot is held in (0x00 for native Minima). Every settlement output
     *  must carry this exact token — the covenant pins each output to the spent coin's @TOKENID. */
    public String tokenid() { return coin.tokenid; }
    public Game game() { return Game.byRange(range); }
    public String gameName() { return game().name; }
    public String pickLabel() { return game().pickLabel(pick); }

    /** A valid casino coin has at least the house fields and a numeric phase. */
    public boolean isValid() {
        return !housePk.isEmpty() && !houseCommit.isEmpty() && phase >= 0;
    }

    public boolean iAmHouse(Set<String> myKeys) { return myKeys.contains(housePk); }
    public boolean iAmPlayer(Set<String> myKeys) { return !playerPk.isEmpty() && myKeys.contains(playerPk); }
    public boolean isMine(Set<String> myKeys) { return iAmHouse(myKeys) || iAmPlayer(myKeys); }

    /** Block height at which this coin's timeout elapses (coin age > timeout). */
    public long timeoutAtBlock() {
        return coin.created < 0 ? Long.MAX_VALUE : coin.created + timeout;
    }

    public boolean timedOut(long tipBlock) {
        return coin.created >= 0 && (tipBlock - coin.created) > timeout;
    }

    /** Same E1/E2 eligibility as the covenant and the MDS casino's manual claim buttons. */
    public boolean canClaimTimeout(Set<String> myKeys, long tipBlock) {
        return timedOut(tipBlock)
                && ((phase == 1 && iAmPlayer(myKeys)) || (phase == 2 && iAmHouse(myKeys)));
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }
}
