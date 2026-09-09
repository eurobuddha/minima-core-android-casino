package com.eurobuddha.casino;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class TimeoutClaimsTest {
    private static final Set<String> HOUSE = Collections.singleton("house");
    private static final Set<String> PLAYER = Collections.singleton("player");

    private Bet bet(String id, String token, int phase, long created) {
        Coin coin = new Coin();
        coin.coinid = id;
        coin.tokenid = token;
        coin.amount = "2";
        coin.created = created;
        coin.state.put(CasinoContract.P_HOUSE_PK, "house");
        coin.state.put(CasinoContract.P_HOUSE_COMMIT, "commit");
        coin.state.put(CasinoContract.P_PLAYER_PK, "player");
        coin.state.put(CasinoContract.P_PHASE, Integer.toString(phase));
        coin.state.put(CasinoContract.P_TIMEOUT, "10");
        return new Bet(coin);
    }

    @Test public void deadlineRequiresStrictlyGreaterCoinAgeInBothCurrencies() {
        for (String token : Arrays.asList(Util.MINIMA_TOKENID, Currency.USD_TOKENID)) {
            Bet bet = bet("coin", token, 1, 100);
            assertFalse(bet.canClaimTimeout(PLAYER, 109));
            assertFalse(bet.canClaimTimeout(PLAYER, 110));
            assertTrue(bet.canClaimTimeout(PLAYER, 111));
            assertFalse(bet.canClaimTimeout(PLAYER, 99)); // tip rollback / future coin
        }
    }

    @Test public void onlyTheCovenantBeneficiaryGetsAClaim() {
        for (String token : Arrays.asList(Util.MINIMA_TOKENID, Currency.USD_TOKENID)) {
            Bet taken = bet("taken", token, 1, 100);
            Bet revealed = bet("revealed", token, 2, 100);
            assertTrue(taken.canClaimTimeout(PLAYER, 111));
            assertFalse(taken.canClaimTimeout(HOUSE, 111));
            assertTrue(revealed.canClaimTimeout(HOUSE, 111));
            assertFalse(revealed.canClaimTimeout(PLAYER, 111));
            assertFalse(taken.canClaimTimeout(Collections.singleton("stranger"), 111));
            assertFalse(revealed.canClaimTimeout(Collections.emptySet(), 111));
        }
    }

    @Test public void selfPlayRecognisesBothKeys() {
        Set<String> both = new HashSet<>(Arrays.asList("house", "player"));
        assertTrue(bet("one", Util.MINIMA_TOKENID, 1, 100).canClaimTimeout(both, 111));
        assertTrue(bet("two", Currency.USD_TOKENID, 2, 100).canClaimTimeout(both, 111));
    }

    @Test public void openUnknownPhaseAndUnknownCreationNeverAlert() {
        Set<String> both = new HashSet<>(Arrays.asList("house", "player"));
        for (int phase : Arrays.asList(0, 3, -1)) {
            assertFalse(bet("coin", Util.MINIMA_TOKENID, phase, 100).canClaimTimeout(both, 111));
        }
        assertFalse(bet("coin", Currency.USD_TOKENID, 1, -1).canClaimTimeout(both, 10000));
    }

    @Test public void snapshotIncludesBothCurrenciesAndExcludesNonClaims() {
        Bet minima = bet("minima", Util.MINIMA_TOKENID, 1, 100);
        Bet usd = bet("usd", Currency.USD_TOKENID, 1, 100);
        TimeoutClaims claims = new TimeoutClaims(Arrays.asList(minima, usd, usd,
                bet("open", Currency.USD_TOKENID, 0, 100),
                bet("house-only", Util.MINIMA_TOKENID, 2, 100),
                bet("not-expired", Currency.USD_TOKENID, 1, 101)), PLAYER, 111);
        assertEquals(new HashSet<>(Arrays.asList("minima", "usd")), claims.coinids);
        assertEquals("2 timeout claims available (1 Minima · 1 USD)", claims.summary());
    }

    @Test public void unchangedReorderedRemovedAndNewClaimsHaveDistinctAlertBehaviour() {
        Bet minima = bet("minima", Util.MINIMA_TOKENID, 1, 100);
        Bet usd = bet("usd", Currency.USD_TOKENID, 1, 100);
        TimeoutClaims first = new TimeoutClaims(Arrays.asList(minima, usd), PLAYER, 111);
        TimeoutClaims reordered = new TimeoutClaims(Arrays.asList(usd, minima), PLAYER, 112);
        assertEquals(first.coinids, reordered.coinids);
        assertFalse(reordered.hasNewClaims(first.coinids));
        TimeoutClaims remaining = new TimeoutClaims(Collections.singletonList(usd), PLAYER, 112);
        assertFalse(remaining.hasNewClaims(first.coinids));
        assertEquals("1 timeout claim available (1 USD)", remaining.summary());
        assertTrue(first.hasNewClaims(remaining.coinids));
        TimeoutClaims spent = new TimeoutClaims(Collections.emptyList(), PLAYER, 112);
        assertTrue(spent.coinids.isEmpty());
        assertFalse(spent.hasNewClaims(first.coinids));
        assertTrue(first.hasNewClaims(spent.coinids)); // reappeared after reorg
    }
}
