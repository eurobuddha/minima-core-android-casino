package com.eurobuddha.casino;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure decision logic for keeping the node's tracked-script set hygienic: playing once must not make
 * this wallet count every future casino bet on the network as its own locked balance. The casino is
 * ONE shared script address for everyone ({@link CasinoContract#SCRIPT_ADDR}), so unlike PandaPools
 * there is no per-owner row classification — the fix is: register/demote the single row with
 * {@code trackall:false}, then drop relevance from bet coins this wallet is NOT a party to.
 *
 * Why this is safe (verified against core + all three casino surfaces, 2026-08-22):
 * - Bet discovery is {@code coins address:} (relevance-free), settlement is {@code txninput coinid:}
 *   + {@code txnbasics} ScriptProof (reads the script table regardless of the track flag).
 * - A coin whose HEX state contains one of this wallet's keys/addresses is relevant WITHOUT tracking
 *   (core {@code checkRelevant} matches state vars) — bet coins carry house pk/addr in ports 0/1 and
 *   player pk/addr in ports 8/9, so YOUR bets keep their locked status all by themselves.
 * Core gotchas encoded here: command failures arrive as {@code {"status":false}} via the SUCCESS
 * callback; {@code newscript} REPLACES an existing row — which makes the every-launch
 * {@code register()} (now {@code trackall:false}) double as the demote of a polluted row; a demoted
 * address stays in core's in-memory relevance cache until node restart, so the coin sweep re-runs
 * every launch.
 */
public final class CasinoHygiene {

    private CasinoHygiene() {}

    /** True if the node returned this flag as boolean true, integer 1, or string "true"/"1". */
    static boolean truthy(JSONObject o, String key) {
        Object v = (o == null) ? null : o.opt(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() == 1;
        if (v instanceof String) { String s = ((String) v).trim(); return s.equals("1") || s.equalsIgnoreCase("true"); }
        return false;
    }

    /**
     * Coinids of relevant casino coins this wallet is NOT a party to. A coin is spared when ANY of
     * state ports 0 (house pk), 1 (house addr), 8 (player pk), 9 (player addr) matches this wallet's
     * keys/addresses (case-insensitive). This filter is the ONLY protection: cointrack on an
     * EXISTING coin is permanent — core re-checks relevance only when a block first processes a
     * coin, so a wrongly-untracked live own bet loses its timeout claim at cascade. Callers must
     * therefore abort the sweep entirely when keys or the scripts table can't be read. An EMPTY
     * {@code mineLower} returns nothing: ownership unprovable → zero writes.
     */
    static List<String> coinsToUntrack(JSONArray coinsRelevantReply, Set<String> mineLower) {
        List<String> out = new ArrayList<>();
        if (coinsRelevantReply == null || mineLower == null || mineLower.isEmpty()) return out;
        for (int i = 0; i < coinsRelevantReply.length(); i++) {
            JSONObject c = coinsRelevantReply.optJSONObject(i);
            if (c == null) continue;
            Coin coin = Coin.from(c);
            if (coin.coinid.isEmpty()) continue;
            if (isParty(coin, mineLower)) continue;   // my bet — spare it
            out.add(coin.coinid);
        }
        return out;
    }

    /** True if any identity port (0/1/8/9) of this bet coin matches the wallet (case-insensitive). */
    static boolean isParty(Coin coin, Set<String> mineLower) {
        return mineLower.contains(coin.stateAt(CasinoContract.P_HOUSE_PK).toLowerCase())
            || mineLower.contains(coin.stateAt(CasinoContract.P_HOUSE_ADDR).toLowerCase())
            || mineLower.contains(coin.stateAt(CasinoContract.P_PLAYER_PK).toLowerCase())
            || mineLower.contains(coin.stateAt(CasinoContract.P_PLAYER_ADDR).toLowerCase());
    }

    /** Lowercased union of wallet keys + simple wallet addresses from a full {@code scripts} reply. */
    static Set<String> collectWalletAddressesLower(JSONArray scriptsReply, Set<String> into) {
        if (scriptsReply == null) return into;
        for (int i = 0; i < scriptsReply.length(); i++) {
            JSONObject r = scriptsReply.optJSONObject(i);
            if (r == null || !truthy(r, "simple")) continue;   // simple rows = this wallet's own addresses
            String a = r.optString("address", "");
            if (!a.isEmpty()) into.add(a.toLowerCase());
        }
        return into;
    }
}
