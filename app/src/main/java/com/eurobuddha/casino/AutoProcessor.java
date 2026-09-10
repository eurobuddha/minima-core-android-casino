package com.eurobuddha.casino;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The background brain (port of the dapp's autoProcess / service.js): given the current bets and
 * my wallet keys, renews untaken house offers, auto-reveals bets where I'm the house (phase 1) and auto-resolves bets where I'm
 * the player (phase 2). A busy-set prevents posting the same transition twice while it confirms.
 *
 * Shared by {@link MainActivity} (foreground) and {@link CasinoService} (background).
 */
public class AutoProcessor {

    public interface Listener {
        default void onOfferMaintained(Bet bet, boolean cancelled) {}
        void onRevealed(Bet bet);
        /** iWon/profit are from MY perspective; result is the rolled number. */
        void onResolved(Bet bet, boolean iWon, BigDecimal profit, int result);
        void onError(String message);
    }

    // A reveal/resolve takes a few blocks to mine. Once posted, don't re-post the SAME transition
    // for this coin until it confirms (the coin then advances to a new coinid) — otherwise we spam a
    // new competing tx every block, which races and stalls confirmation (the old 5-10 min lag).
    private static final int REPOST_AFTER = 4;

    private final CasinoTxn txn;
    private final Set<String> busy = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, Integer> postedAt = new ConcurrentHashMap<>();
    private int renewalBlock = -1, renewalsThisBlock = 0;

    public AutoProcessor(CasinoTxn txn) {
        this.txn = txn;
    }

    /** True if a transition for this coin was posted recently and is probably still confirming. */
    public boolean inFlight(String coinid, int chainBlock) {
        if (busy.contains(coinid)) return true;
        Integer pa = postedAt.get(coinid);
        return pa != null && chainBlock - pa < REPOST_AFTER;
    }

    /** Record that something (auto or manual) just posted a transition for this coin. */
    public void markPosted(String coinid, int chainBlock) { postedAt.put(coinid, chainBlock); }

    public void process(List<Bet> bets, Set<String> myKeys, int chainBlock, Listener l) {
        if (renewalBlock != chainBlock) { renewalBlock = chainBlock; renewalsThisBlock = 0; }
        // Prune cooldown entries for coins that have advanced/been spent (bounded memory).
        Set<String> live = new java.util.HashSet<>();
        for (Bet b : bets) live.add(b.coinid());
        postedAt.keySet().retainAll(live);
        busy.retainAll(live);

        for (Bet bet : bets) {
            String id = bet.coinid();
            if (inFlight(id, chainBlock)) continue;

            if (bet.phase == 0 && bet.iAmHouse(myKeys)
                    && (OfferKeepAlive.due(bet, chainBlock) || txn.cancelRequested(bet))) {
                if (renewalsThisBlock >= 2) continue; // same per-block bound as the MDS service
                renewalsThisBlock++;
                busy.add(id);
                markPosted(id, chainBlock);
                boolean cancelled = txn.cancelRequested(bet);
                txn.maintainOpen(bet, new CasinoTxn.Result() {
                    @Override public void onPosted(String txpowid) {
                        busy.remove(id);
                        if (l != null) l.onOfferMaintained(bet, cancelled);
                    }
                    @Override public void onFailed(String message) {
                        // Keep the cooldown on failure too: locked keys/transport failures must not
                        // hammer the node on every UI refresh. Retry on a later block.
                        busy.remove(id);
                        if (l != null) l.onError("Offer keepalive: " + message);
                    }
                });
            } else if (bet.phase == 1 && bet.iAmHouse(myKeys)) {
                busy.add(id);
                markPosted(id, chainBlock);
                txn.reveal(bet, new CasinoTxn.Result() {
                    @Override public void onPosted(String txpowid) { busy.remove(id); if (l != null) l.onRevealed(bet); }
                    @Override public void onFailed(String message) { busy.remove(id); postedAt.remove(id); if (l != null) l.onError(message); }
                });
            } else if (bet.phase == 2 && bet.iAmPlayer(myKeys)) {
                busy.add(id);
                markPosted(id, chainBlock);
                final boolean isHouse = bet.iAmHouse(myKeys);   // self-play edge case
                txn.resolve(bet, new CasinoTxn.ResolveResult() {
                    @Override public void onResolved(boolean playerWins, int result, String txpowid) {
                        busy.remove(id);
                        boolean iWon = (playerWins && !isHouse) || (!playerWins && isHouse);
                        if (l != null) l.onResolved(bet, iWon, profit(bet, iWon, isHouse), result);
                    }
                    @Override public void onFailed(String message) { busy.remove(id); postedAt.remove(id); if (l != null) l.onError(message); }
                });
            }
        }
    }

    /** Magnitude of P&L from my perspective, matching the dapp. */
    private static BigDecimal profit(Bet bet, boolean iWon, boolean isHouse) {
        BigDecimal betAmt = Util.dec(bet.betAmount);
        BigDecimal winnings = betAmt.multiply(BigDecimal.valueOf(bet.payout));
        if (iWon) {
            return isHouse ? betAmt : winnings.subtract(betAmt);
        } else {
            return isHouse ? betAmt.multiply(BigDecimal.valueOf(bet.payout - 1L)) : betAmt;
        }
    }
}
