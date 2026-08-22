package com.eurobuddha.casino;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link CasinoHygiene} decides which relevant coins at the shared casino address get their
 * relevance dropped by the launch sweep. Wrong in one direction it strips a player's own live stake
 * out of their confirmed balance; wrong in the other it leaves the every-stranger's-bet pollution
 * in place. These tests pin the party-matching (ports 0/1/8/9, case-insensitive), the fail-safe on
 * unprovable ownership, and reply-shape tolerance.
 */
public class CasinoHygieneTest {

    private static final String MY_PK    = "0xAAAA00000000000000000000000000000000000000000000000000000000AAAA";
    private static final String MY_ADDR  = "0xDDDD00000000000000000000000000000000000000000000000000000000DDDD";
    private static final String OTHER_PK = "0xBBBB00000000000000000000000000000000000000000000000000000000BBBB";
    private static final String OTHER_AD = "0xEEEE00000000000000000000000000000000000000000000000000000000EEEE";

    private static JSONObject betCoin(String coinid, String housePk, String houseAddr,
                                      String playerPk, String playerAddr) throws JSONException {
        JSONArray state = new JSONArray();
        state.put(new JSONObject().put("port", 0).put("data", housePk));
        state.put(new JSONObject().put("port", 1).put("data", houseAddr));
        state.put(new JSONObject().put("port", 6).put("data", "1"));
        if (playerPk != null) state.put(new JSONObject().put("port", 8).put("data", playerPk));
        if (playerAddr != null) state.put(new JSONObject().put("port", 9).put("data", playerAddr));
        return new JSONObject().put("coinid", coinid).put("address", CasinoContract.SCRIPT_ADDR)
                .put("amount", "10").put("state", state);
    }

    private static Set<String> mine(String... v) {
        Set<String> s = new HashSet<>();
        for (String x : v) s.add(x.toLowerCase());
        return s;
    }

    @Test public void strangersBetsAreUntrackedMineAreSpared() throws JSONException {
        JSONArray coins = new JSONArray()
                .put(betCoin("0xC001", OTHER_PK, OTHER_AD, null, null))              // stranger, open
                .put(betCoin("0xC002", MY_PK, MY_ADDR, OTHER_PK, OTHER_AD))          // I am house → spare
                .put(betCoin("0xC003", OTHER_PK, OTHER_AD, MY_PK, MY_ADDR))          // I am player → spare
                .put(betCoin("0xC004", OTHER_PK, OTHER_AD, OTHER_PK, OTHER_AD));     // stranger, taken
        List<String> ids = CasinoHygiene.coinsToUntrack(coins, mine(MY_PK, MY_ADDR));
        assertEquals(2, ids.size());
        assertEquals("0xC001", ids.get(0));
        assertEquals("0xC004", ids.get(1));
    }

    @Test public void addressOnlyMatchSparesTheCoin() throws JSONException {
        // ports 1/9 can carry ANY of the wallet's 64 addresses even when the pk isn't in `keys` yet
        JSONArray coins = new JSONArray()
                .put(betCoin("0xC005", OTHER_PK, MY_ADDR, null, null))               // my addr as house payout
                .put(betCoin("0xC006", OTHER_PK, OTHER_AD, OTHER_PK, MY_ADDR));      // my addr as player payout
        assertTrue(CasinoHygiene.coinsToUntrack(coins, mine(MY_ADDR)).isEmpty());
    }

    @Test public void matchingIsCaseInsensitive() throws JSONException {
        JSONArray coins = new JSONArray()
                .put(betCoin("0xC007", MY_PK.toLowerCase(), OTHER_AD, null, null));
        assertTrue("state lowercased vs keys uppercased must still match",
                CasinoHygiene.coinsToUntrack(coins, mine(MY_PK)).isEmpty());
    }

    @Test public void emptyOwnershipMeansZeroWrites() throws JSONException {
        JSONArray coins = new JSONArray().put(betCoin("0xC008", OTHER_PK, OTHER_AD, null, null));
        assertTrue("unprovable ownership → fail-safe empty",
                CasinoHygiene.coinsToUntrack(coins, Collections.<String>emptySet()).isEmpty());
        assertTrue(CasinoHygiene.coinsToUntrack(coins, null).isEmpty());
        assertTrue("null reply tolerated", CasinoHygiene.coinsToUntrack(null, mine(MY_PK)).isEmpty());
    }

    @Test public void coinsWithoutIdOrStateAreSkippedNotUntracked() throws JSONException {
        JSONArray coins = new JSONArray()
                .put(new JSONObject().put("address", CasinoContract.SCRIPT_ADDR))            // no coinid
                .put(new JSONObject().put("coinid", "0xC009").put("address", CasinoContract.SCRIPT_ADDR));  // no state at all
        List<String> ids = CasinoHygiene.coinsToUntrack(coins, mine(MY_PK));
        // a stateless coin at the casino address is not ours by any port → untracked
        assertEquals(1, ids.size());
        assertEquals("0xC009", ids.get(0));
    }

    @Test public void collectsOnlySimpleWalletAddresses() throws JSONException {
        JSONArray scripts = new JSONArray()
                .put(new JSONObject().put("address", "0x1111").put("simple", true))
                .put(new JSONObject().put("address", "0x2222").put("simple", "true"))    // string shape
                .put(new JSONObject().put("address", CasinoContract.SCRIPT_ADDR).put("simple", false))
                .put(new JSONObject().put("address", "0x3333"));                          // no flag → not simple
        Set<String> into = new HashSet<>();
        CasinoHygiene.collectWalletAddressesLower(scripts, into);
        assertEquals(2, into.size());
        assertTrue(into.contains("0x1111"));
        assertTrue(into.contains("0x2222"));
        assertFalse(into.contains(CasinoContract.SCRIPT_ADDR.toLowerCase()));
        CasinoHygiene.collectWalletAddressesLower(null, into);   // tolerated
        assertEquals(2, into.size());
    }

    @Test public void truthyToleratesNodeReplyShapes() throws JSONException {
        assertTrue(CasinoHygiene.truthy(new JSONObject().put("t", true), "t"));
        assertTrue(CasinoHygiene.truthy(new JSONObject().put("t", "true"), "t"));
        assertTrue(CasinoHygiene.truthy(new JSONObject().put("t", 1), "t"));
        assertFalse(CasinoHygiene.truthy(new JSONObject().put("t", "false"), "t"));
        assertFalse(CasinoHygiene.truthy(new JSONObject(), "t"));
        assertFalse(CasinoHygiene.truthy(null, "t"));
    }
}
