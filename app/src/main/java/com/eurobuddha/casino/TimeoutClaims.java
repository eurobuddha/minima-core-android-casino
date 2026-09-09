package com.eurobuddha.casino;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** One currency-independent snapshot shared by the banner and background notification. */
final class TimeoutClaims {
    final Set<String> coinids = new TreeSet<>();
    private int minima, usd;

    TimeoutClaims(List<Bet> bets, Set<String> myKeys, long block) {
        for (Bet bet : bets) {
            if (!bet.isValid() || !bet.canClaimTimeout(myKeys, block)
                    || !coinids.add(bet.coinid())) continue;
            if (Util.isMinima(bet.tokenid())) minima++;
            else usd++;
        }
    }

    String summary() {
        String currencies = minima > 0 ? minima + " Minima" : "";
        if (usd > 0) currencies += (currencies.isEmpty() ? "" : " · ") + usd + " USD";
        return coinids.size() + " timeout claim" + (coinids.size() == 1 ? "" : "s")
                + " available (" + currencies + ")";
    }

    boolean hasNewClaims(Set<String> previous) {
        return !previous.containsAll(coinids);
    }
}
