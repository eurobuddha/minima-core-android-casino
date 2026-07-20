package com.eurobuddha.casino;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.eurobuddha.casino.CasinoContract.*;

/**
 * Builds, signs and posts the casino's commit-reveal transactions over {@link NodeApi}. Every
 * path mirrors the dapp's command sequence (index.html) command-for-command so on-chain behaviour
 * (and the resulting script address) is identical.
 */
public class CasinoTxn {

    public interface Result {
        void onPosted(String txpowid);
        void onFailed(String message);
    }

    private static final BigDecimal DUST = new BigDecimal("0.000001");

    private final NodeApi node;
    private final SecretStore secrets;
    private final String myPubkey;
    private final String myHexAddr;
    // Funding coins reserved by a take that's been posted but not yet confirmed/spent. Prevents a
    // second take (a different bet) from selecting the same coin and double-spending → competing txns.
    private final Set<String> inflightCoins = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public CasinoTxn(NodeApi node, SecretStore secrets, String myPubkey, String myHexAddr) {
        this.node = node;
        this.secrets = secrets;
        this.myPubkey = myPubkey;
        this.myHexAddr = myHexAddr;
    }

    // ===================================================================== create (phase 0)
    public void create(Game game, BigDecimal bet, Result cb) {
        BigDecimal stake = bet.multiply(BigDecimal.valueOf(game.payout - 1L));
        if (stake.compareTo(BigDecimal.ZERO) <= 0) stake = bet;
        final String stakeStr = Util.miniNum(stake);
        final String betStr = Util.miniNum(bet);

        node.cmd("random", new NodeApi.Cb() {
            @Override public void onResult(JSONObject r) {
                String secret = resp(r);
                if (secret.isEmpty()) { cb.onFailed("Random failed"); return; }
                node.cmd("hash data:" + secret, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject h) {
                        String commit = resp(h);
                        if (commit.isEmpty()) { cb.onFailed("Hash failed"); return; }
                        // FUND-SAFETY: the house secret must be durable BEFORE we lock funds — a lost preimage means
                        // we can never reveal and the player timeout-claims the whole pot. Abort if it didn't persist.
                        if (!secrets.putHouseSecret(commit, secret)) { cb.onFailed("Could not save the bet secret — aborting to protect your funds"); return; }
                        String state = "{\"0\":\"" + myPubkey + "\",\"1\":\"" + myHexAddr
                                + "\",\"2\":\"" + commit + "\",\"3\":\"" + game.range
                                + "\",\"4\":\"" + game.payout + "\",\"5\":\"" + betStr
                                + "\",\"6\":\"0\",\"7\":\"" + TIMEOUT_BLOCKS + "\"}";
                        node.cmd("send amount:" + stakeStr + " address:" + SCRIPT_ADDR
                                + " state:" + state, new NodeApi.Cb() {
                            @Override public void onResult(JSONObject s) {
                                if (s.optBoolean("status", false)) cb.onPosted(Util.extractTxpowid(s, ""));
                                else cb.onFailed("Send failed" + err(s));
                            }
                            @Override public void onError(String m) { cb.onFailed(m); }
                        });
                    }
                    @Override public void onError(String m) { cb.onFailed(m); }
                });
            }
            @Override public void onError(String m) { cb.onFailed(m); }
        });
    }

    // ===================================================================== take (phase 0 -> 1)
    public void take(Bet bet, int pick, Result cb) {
        final BigDecimal betAmt = Util.dec(bet.betAmount);
        final BigDecimal total = Util.dec(bet.totalAmount).add(betAmt);
        node.cmd("random", new NodeApi.Cb() {
            @Override public void onResult(JSONObject r) {
                String secret = resp(r);
                if (secret.isEmpty()) { cb.onFailed("Random failed"); return; }
                node.cmd("hash data:" + secret, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject h) {
                        String commit = resp(h);
                        if (commit.isEmpty()) { cb.onFailed("Hash failed"); return; }
                        // FUND-SAFETY: the player secret must be durable BEFORE we commit the take — a lost preimage
                        // means we can never resolve and the house timeout-claims the pot. Abort if it didn't persist.
                        if (!secrets.putPlayerSecret(commit, secret)) { cb.onFailed("Could not save the bet secret — aborting to protect your funds"); return; }
                        findCoins(betAmt, bet.coinid(), new CoinsCb() {
                            @Override public void onCoins(List<Coin> funds, BigDecimal sum) {
                                buildTake(bet, pick, commit, betAmt, total, funds, sum, cb);
                            }
                            @Override public void onNone() {
                                cb.onFailed("Insufficient Minima (need " + Util.miniNum(betAmt) + ")");
                            }
                        });
                    }
                    @Override public void onError(String m) { cb.onFailed(m); }
                });
            }
            @Override public void onError(String m) { cb.onFailed(m); }
        });
    }

    private void buildTake(Bet bet, int pick, String commit, BigDecimal betAmt, BigDecimal total,
                           List<Coin> funds, BigDecimal sum, Result cb) {
        String txid = "take_" + tag();
        Coin c = bet.coin;
        List<String> cmds = new ArrayList<>();
        cmds.add("txncreate id:" + txid);
        cmds.add("txninput id:" + txid + " coinid:" + bet.coinid());
        for (Coin f : funds) cmds.add("txninput id:" + txid + " coinid:" + f.coinid);
        cmds.add("txnoutput id:" + txid + " amount:" + Util.miniNum(total)
                + " address:" + SCRIPT_ADDR + " storestate:true");
        BigDecimal change = sum.subtract(betAmt);
        if (change.compareTo(DUST) > 0) {
            cmds.add("txnoutput id:" + txid + " amount:" + Util.miniNum(change)
                    + " address:" + myHexAddr + " storestate:false");
        }
        // states 0..11: house data copied, phase->1, player identity + pick
        addState(cmds, txid, P_HOUSE_PK, c.stateAt(P_HOUSE_PK));
        addState(cmds, txid, P_HOUSE_ADDR, c.stateAt(P_HOUSE_ADDR));
        addState(cmds, txid, P_HOUSE_COMMIT, c.stateAt(P_HOUSE_COMMIT));
        addState(cmds, txid, P_RANGE, c.stateAt(P_RANGE));
        addState(cmds, txid, P_PAYOUT, c.stateAt(P_PAYOUT));
        addState(cmds, txid, P_BET, c.stateAt(P_BET));
        addState(cmds, txid, P_PHASE, "1");
        addState(cmds, txid, P_TIMEOUT, c.stateAt(P_TIMEOUT));
        addState(cmds, txid, P_PLAYER_PK, myPubkey);
        addState(cmds, txid, P_PLAYER_ADDR, myHexAddr);
        addState(cmds, txid, P_PLAYER_COMMIT, commit);
        addState(cmds, txid, P_PICK, String.valueOf(pick));
        cmds.add("txnsign id:" + txid + " publickey:auto");
        cmds.add("txnbasics id:" + txid);
        cmds.add("txnpost id:" + txid);
        // Reserve the funding coins until this take confirms; release them if it fails.
        for (Coin f : funds) inflightCoins.add(f.coinid);
        post(cmds, txid, new Result() {
            @Override public void onPosted(String txpowid) { cb.onPosted(txpowid); }
            @Override public void onFailed(String message) {
                for (Coin f : funds) inflightCoins.remove(f.coinid);
                cb.onFailed(message);
            }
        });
    }

    // ===================================================================== reveal (phase 1 -> 2)
    public void reveal(Bet bet, Result cb) {
        String houseSecret = secrets.houseSecret(bet.houseCommit);
        if (houseSecret == null || houseSecret.isEmpty()) { cb.onFailed("House secret not found"); return; }
        String txid = "autoreveal_" + tag();
        Coin c = bet.coin;
        List<String> cmds = new ArrayList<>();
        cmds.add("txncreate id:" + txid);
        cmds.add("txninput id:" + txid + " coinid:" + bet.coinid());
        cmds.add("txnoutput id:" + txid + " amount:" + c.amount
                + " address:" + SCRIPT_ADDR + " storestate:true");
        for (int p = P_HOUSE_PK; p <= P_PICK; p++) {
            addState(cmds, txid, p, p == P_PHASE ? "2" : c.stateAt(p));
        }
        addState(cmds, txid, P_HOUSE_SECRET, houseSecret);
        cmds.add("txnsign id:" + txid + " publickey:" + bet.housePk);
        cmds.add("txnbasics id:" + txid);
        cmds.add("txnpost id:" + txid);
        post(cmds, txid, cb);
    }

    // ===================================================================== resolve (phase 2 -> payout)
    public void resolve(Bet bet, ResolveResult cb) {
        String playerSecret = secrets.playerSecret(bet.playerCommit);
        if (playerSecret == null || playerSecret.isEmpty()) { cb.onFailed("Player secret not found"); return; }
        final String houseSecret = bet.houseSecret;
        // combined = house secret (with 0x) + player secret (without 0x), per the dapp.
        String combined = houseSecret + (playerSecret.startsWith("0x") ? playerSecret.substring(2) : playerSecret);
        node.cmd("hash data:" + combined, new NodeApi.Cb() {
            @Override public void onResult(JSONObject h) {
                String hash = resp(h);
                if (hash.isEmpty()) { cb.onFailed("Hash failed"); return; }
                long num;
                try { num = Long.parseLong(hash.substring(2, 10), 16); }
                catch (Exception e) { cb.onFailed("Bad hash"); return; }
                int result = (int) (num % bet.range);
                boolean playerWins = (result == bet.pick);
                buildResolve(bet, playerSecret, result, playerWins, cb);
            }
            @Override public void onError(String m) { cb.onFailed(m); }
        });
    }

    private void buildResolve(Bet bet, String playerSecret, int result, boolean playerWins, ResolveResult cb) {
        BigDecimal betAmt = Util.dec(bet.betAmount);
        BigDecimal total = Util.dec(bet.totalAmount);
        BigDecimal winnings = betAmt.multiply(BigDecimal.valueOf(bet.payout));
        String txid = "resolve_" + tag();
        List<String> cmds = new ArrayList<>();
        cmds.add("txncreate id:" + txid);
        cmds.add("txninput id:" + txid + " coinid:" + bet.coinid());
        if (playerWins) {
            cmds.add("txnoutput id:" + txid + " amount:" + Util.miniNum(winnings)
                    + " address:" + bet.playerAddr + " storestate:false");
            BigDecimal remainder = total.subtract(winnings);
            if (remainder.compareTo(DUST) > 0) {
                cmds.add("txnoutput id:" + txid + " amount:" + Util.miniNum(remainder)
                        + " address:" + bet.houseAddr + " storestate:false");
            }
        } else {
            cmds.add("txnoutput id:" + txid + " amount:" + Util.miniNum(total)
                    + " address:" + bet.houseAddr + " storestate:false");
        }
        addState(cmds, txid, P_PLAYER_SECRET, playerSecret);
        cmds.add("txnsign id:" + txid + " publickey:" + bet.playerPk);
        cmds.add("txnbasics id:" + txid);
        cmds.add("txnpost id:" + txid);
        CmdChain.run(node, cmds, "txndelete id:" + txid, new CmdChain.Done() {
            // ok() only fires once every command (incl. txnpost) returned status:true.
            @Override public void ok(JSONObject last) { cb.onResolved(playerWins, result, Util.extractTxpowid(last, txid)); }
            @Override public void fail(String message) { cb.onFailed(message); }
        });
    }

    // ===================================================================== cancel (phase 0 house)
    public void cancel(Bet bet, Result cb) {
        String txid = "cancel_" + tag();
        List<String> cmds = new ArrayList<>();
        cmds.add("txncreate id:" + txid);
        cmds.add("txninput id:" + txid + " coinid:" + bet.coinid());
        cmds.add("txnoutput id:" + txid + " amount:" + bet.coin.amount
                + " address:" + myHexAddr + " storestate:false");
        cmds.add("txnsign id:" + txid + " publickey:" + bet.housePk);
        cmds.add("txnbasics id:" + txid);
        cmds.add("txnpost id:" + txid);
        post(cmds, txid, cb);
    }

    // ===================================================================== claim timeout (E1/E2)
    public void claimTimeout(Bet bet, Result cb) {
        String signKey = bet.phase == 1 ? bet.playerPk : bet.housePk;
        String txid = "timeout_" + tag();
        List<String> cmds = new ArrayList<>();
        cmds.add("txncreate id:" + txid);
        cmds.add("txninput id:" + txid + " coinid:" + bet.coinid());
        cmds.add("txnoutput id:" + txid + " amount:" + bet.coin.amount
                + " address:" + myHexAddr + " storestate:false");
        cmds.add("txnsign id:" + txid + " publickey:" + signKey);
        cmds.add("txnbasics id:" + txid);
        cmds.add("txnpost id:" + txid);
        post(cmds, txid, cb);
    }

    // ----------------------------------------------------------------- helpers

    public interface ResolveResult {
        void onResolved(boolean playerWins, int result, String txpowid);
        void onFailed(String message);
    }

    private void post(List<String> cmds, String txid, Result cb) {
        // ok() only fires once every command (incl. txnpost) returned status:true — i.e. the node
        // accepted & queued the tx. We intentionally never gate on response.istransaction: txnpost
        // mines asynchronously and that flag stays false until PoW completes a few blocks later;
        // the real outcome is reconciled from chain state.
        CmdChain.run(node, cmds, "txndelete id:" + txid, new CmdChain.Done() {
            @Override public void ok(JSONObject last) { cb.onPosted(Util.extractTxpowid(last, txid)); }
            @Override public void fail(String message) { cb.onFailed(message); }
        });
    }

    private static void addState(List<String> cmds, String txid, int port, String value) {
        cmds.add("txnstate id:" + txid + " port:" + port + " value:" + value);
    }

    /** Extract a value from random/hash responses (response.random / .hash / plain). */
    private static String resp(JSONObject r) {
        if (r == null || !r.optBoolean("status", false)) return "";
        JSONObject resp = r.optJSONObject("response");
        if (resp == null) return "";
        String v = resp.optString("random", "");
        if (v.isEmpty()) v = resp.optString("hash", "");
        return v;
    }

    private static String err(JSONObject j) {
        String e = j.optString("error", "");
        return e.isEmpty() ? "" : " : " + e;
    }

    private static String tag() {
        return System.currentTimeMillis() + "_" + Integer.toHexString((int) (System.nanoTime() & 0xffffff));
    }

    // ---- coin selection (port of the dapp's findCoins) ----
    private interface CoinsCb { void onCoins(List<Coin> coins, BigDecimal sum); void onNone(); }

    private void findCoins(BigDecimal need, String excludeCoinid, CoinsCb cb) {
        node.cmd("coins relevant:true sendable:true tokenid:0x00", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONArray arr = json.optJSONArray("response");
                if (arr == null || arr.length() == 0) { cb.onNone(); return; }
                java.util.Set<String> present = new java.util.HashSet<>();
                List<Coin> avail = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject jc = arr.optJSONObject(i);
                    if (jc == null) continue;
                    Coin c = Coin.from(jc);
                    present.add(c.coinid);
                    if (c.hasState()) continue;                       // only plain (no-state) coins
                    if (c.coinid.equals(excludeCoinid)) continue;
                    if (inflightCoins.contains(c.coinid)) continue;   // reserved by a pending take
                    avail.add(c);
                }
                inflightCoins.retainAll(present);                     // drop reservations for coins now spent
                if (avail.isEmpty()) { cb.onNone(); return; }
                avail.sort((a, b) -> Util.dec(b.amount).compareTo(Util.dec(a.amount)));
                List<Coin> sel = new ArrayList<>();
                BigDecimal sum = BigDecimal.ZERO;
                for (Coin c : avail) {
                    sel.add(c);
                    sum = sum.add(Util.dec(c.amount));
                    if (sum.compareTo(need) >= 0) { cb.onCoins(sel, sum); return; }
                }
                cb.onNone();
            }
            @Override public void onError(String message) { cb.onNone(); }
        });
    }
}
