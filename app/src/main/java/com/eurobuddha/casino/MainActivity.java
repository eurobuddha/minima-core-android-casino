package com.eurobuddha.casino;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.tabs.TabLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.minimarex.minimaapi.MinimaAPI;
import org.minimarex.minimaapi.MinimaAPIMessages;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Native Zero Edge Casino. Tabs: PLAY / HOUSE / MY BETS / HISTORY. Talks to the local Minima Core
 * node over the broadcast-Intent IPC ({@link NodeApi}) and runs the same commit-reveal contract as
 * the dapp, so it's interoperable with it (identical script address).
 */
public class MainActivity extends AppCompatActivity {

    public static final int TAB_PLAY = 0, TAB_HOUSE = 1, TAB_MYBETS = 2, TAB_HISTORY = 3;

    /** True while the Activity is in the foreground. The background CasinoService checks this and
     *  skips auto-processing when we're foreground, so the two never post competing reveal/resolve
     *  transactions for the same coin. */
    public static volatile boolean FOREGROUND = false;

    private NodeApi node;
    private SecretStore secrets;
    private CasinoTxn txn;
    private AutoProcessor auto;

    private BaseView[] views;
    private ViewPager pager;
    private TextView balanceTv, blockTv, tickerTv;
    private View pairingBanner, liveDot;
    private Button soundBtn;
    private BroadcastReceiver notifyReceiver;

    // ----- activity log -----
    public static final int LOG_INFO = 0, LOG_OK = 1, LOG_WARN = 2, LOG_ERR = 3;
    private final java.util.ArrayDeque<String> logLines = new java.util.ArrayDeque<>();
    // pending-confirmation tracking (so the slow 3-4 block confirm shows progress)
    private static final int PEND_NONE = 0, PEND_CREATE = 1, PEND_TAKE = 2;
    private int pendingType = PEND_NONE;
    private String pendingDesc = "";
    private int pendingSinceBlock = 0;
    private final Set<String> pendingBaselineIds = new HashSet<>();   // my bet coinids when the create/take was posted

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable reloadTask = this::reload;

    // identity
    private String myPubkey = "", myHexAddr = "";
    private final Set<String> myKeys = new HashSet<>();
    private boolean identityReady = false;
    private boolean cleanedTracking = false;   // balance hygiene sweep runs once per session

    // chain / bet state
    private int chainBlock = 0;
    private String balance = "0";
    private final List<Bet> bets = new ArrayList<>();
    private final List<ResolvedBet> history = new ArrayList<>();
    private boolean serviceStarted = false;
    // last reload's bets that are mine (coinid -> bet), to detect ones the counterparty resolved
    private final Map<String, Bet> prevMineBets = new HashMap<>();
    private final Set<String> reconciled = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Theme.load(this);
        setContentView(R.layout.activity_main);

        View root = findViewById(R.id.main);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars =
                    insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        balanceTv = findViewById(R.id.balance);
        blockTv = findViewById(R.id.blockNo);
        tickerTv = findViewById(R.id.ticker);
        liveDot = findViewById(R.id.liveDot);
        pairingBanner = findViewById(R.id.pairingBanner);
        soundBtn = findViewById(R.id.btnSound);
        tickerTv.setOnClickListener(v -> showLogDialog());

        secrets = new SecretStore(this);
        loadHistory();

        node = new NodeApi(this, enabled -> setPaired(enabled));
        CasinoContract.register(node);

        // Tabs
        views = new BaseView[]{ new PlayView(this), new HouseView(this), new MyBetsView(this), new HistoryView(this) };
        pager = findViewById(R.id.pager);
        pager.setAdapter(new MainPager(views, new String[]{"PLAY", "HOUSE", "MY BETS", "HISTORY"}));
        pager.setOffscreenPageLimit(3);
        TabLayout tabs = findViewById(R.id.tabs);
        tabs.setupWithViewPager(pager);
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override public void onPageSelected(int position) { views[position].onShown(); }
        });

        soundBtn.setOnClickListener(v -> {
            boolean on = !Theme.sound();
            Theme.setSound(this, on);
            updateSoundBtn();
            if (on) Sfx.chime();     // audible confirmation that sound is working
        });
        updateSoundBtn();

        // Live updates from the node.
        notifyReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (!MinimaAPI.checkMinimaID(MainActivity.this, intent)) return;
                String data = intent.getStringExtra(MinimaAPIMessages.MINIMA_API_NOTIFY_DATA);
                if (data == null) return;
                try {
                    String event = new JSONObject(data).optString("event", "");
                    if ("NEWBLOCK".equals(event) || "NEWBALANCE".equals(event)) requestReload();
                } catch (Exception ignored) {}
            }
        };
        ContextCompat.registerReceiver(this, notifyReceiver,
                new IntentFilter(MinimaAPIMessages.MINIMA_API_NOTIFY), ContextCompat.RECEIVER_EXPORTED);

        requestNotifPermission();
        loadIdentity();
    }

    @Override protected void onResume() {
        super.onResume();
        FOREGROUND = true;          // we own auto-processing while visible; the service stands down
        loadHistory();              // pick up any results the background service recorded while away
        requestReload();
    }

    @Override protected void onPause() {
        super.onPause();
        FOREGROUND = false;         // hand auto-processing back to the background service
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(reloadTask);
        if (node != null) node.onDestroy();
        if (notifyReceiver != null) { try { unregisterReceiver(notifyReceiver); } catch (Exception ignored) {} }
    }

    // ===== identity =====
    private void loadIdentity() {
        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                setPaired(true);
                JSONObject r = json.optJSONObject("response");
                if (r != null) {
                    myPubkey = r.optString("publickey", "");
                    myHexAddr = r.optString("address", "");
                }
                loadKeys();
            }
            @Override public void onError(String message) { handleErr(message); }
        });
    }

    private void loadKeys() {
        node.cmd("keys", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                myKeys.clear();
                Object resp = json.opt("response");
                JSONArray arr = null;
                if (resp instanceof JSONArray) arr = (JSONArray) resp;
                else if (resp instanceof JSONObject) arr = ((JSONObject) resp).optJSONArray("keys");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject k = arr.optJSONObject(i);
                        if (k != null) {
                            String pk = k.optString("publickey", "");
                            if (!pk.isEmpty()) myKeys.add(pk);
                        }
                    }
                }
                if (!myPubkey.isEmpty()) myKeys.add(myPubkey);
                identityReady = !myPubkey.isEmpty() && !myHexAddr.isEmpty();
                txn = new CasinoTxn(node, secrets, myPubkey, myHexAddr);
                auto = new AutoProcessor(txn);
                if (identityReady) log("Connected · " + Util.shorten(myHexAddr), LOG_OK);
                cleanCasinoTracking();
                reload();
            }
            @Override public void onError(String message) { handleErr(message); }
        });
    }

    /**
     * Wallet-balance hygiene sweep (once per session, after keys load): drop relevance from casino
     * bet coins this wallet is NOT a party to. Old builds registered the shared casino script with
     * {@code trackall:true}, so one play made the node adopt EVERY bet on the network into its
     * confirmed balance forever. {@link CasinoContract#register} (now {@code trackall:false};
     * {@code newscript} REPLACES the row) demotes the script each launch; this sweep clears the
     * already-relevant stranger coins with one address-scoped {@code coins relevant:true} query
     * (deliberately depth-unbounded: old pollution concentrates in the tree root). Coins carrying
     * this wallet's keys/addresses in state ports 0/1/8/9 are spared: those are YOUR live bets.
     * WARNING — the spare-filter is the ONLY protection: {@code cointrack enable:false} on an
     * EXISTING coin is permanent (core re-checks relevance only when a block first processes a
     * coin), and a wrongly-untracked live own bet would drop from the tree at cascade, making its
     * timeout claim impossible. So the sweep ABORTS (and retries next launch) whenever {@code keys}
     * or the {@code scripts} table can't be read — never proceed on partial ownership knowledge.
     * Re-runs each launch because core's in-memory relevance cache only clears on node restart.
     * Per-coin failures ({@code {"status":false}} via the SUCCESS callback) are skipped, never fatal.
     */
    private void cleanCasinoTracking() {
        if (cleanedTracking || node == null || myKeys.isEmpty()) return;
        cleanedTracking = true;
        final Set<String> mine = new HashSet<>();
        for (String k : myKeys) mine.add(k.toLowerCase());
        if (!myHexAddr.isEmpty()) mine.add(myHexAddr.toLowerCase());
        // ports 1/9 may carry ANY of the wallet's addresses (getaddress cycles the 64 defaults across
        // sessions) — collect them all from the scripts table's simple rows before filtering coins.
        node.cmd("scripts", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (!CasinoHygiene.truthy(j, "status") || j.optJSONArray("response") == null) {
                    cleanedTracking = false;   // ports 1/9 net unavailable → abort, retry next launch
                    return;
                }
                CasinoHygiene.collectWalletAddressesLower(j.optJSONArray("response"), mine);
                untrackForeignBets(mine);
            }
            @Override public void onError(String m) { cleanedTracking = false; }   // abort — never sweep on partial ownership knowledge
        });
    }

    private void untrackForeignBets(final Set<String> mineLower) {
        node.cmd("coins relevant:true address:" + CasinoContract.SCRIPT_ADDR, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                untrackNextCoin(CasinoHygiene.coinsToUntrack(j.optJSONArray("response"), mineLower), 0);
            }
            @Override public void onError(String m) {}
        });
    }

    private void untrackNextCoin(final List<String> coinids, final int i) {
        if (i >= coinids.size()) return;
        // {"status":false} (already spent / denied) arrives via the SUCCESS callback — keep going
        node.cmd("cointrack enable:false coinid:" + coinids.get(i), new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { untrackNextCoin(coinids, i + 1); }
            @Override public void onError(String m) { untrackNextCoin(coinids, i + 1); }
        });
    }

    // ===== loading =====
    public void reload() {
        node.cmd("balance", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONArray arr = json.optJSONArray("response");
                if (arr != null && arr.length() > 0) {
                    JSONObject b0 = arr.optJSONObject(0);
                    if (b0 != null) { balance = b0.optString("sendable", "0"); balanceTv.setText(Util.displayAmount(balance)); }
                }
            }
            @Override public void onError(String message) {}
        });

        // Fetch the block tip FIRST so the bets pass (cooldown, pending, reconcile) runs against the
        // current height — not a stale one racing a separate command.
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                setPaired(true);
                JSONObject r = json.optJSONObject("response");
                if (r != null) {
                    String b = r.optString("block", "");
                    if (b.isEmpty()) { JSONObject h = r.optJSONObject("header"); if (h != null) b = h.optString("block", ""); }
                    int prev = chainBlock;
                    try { chainBlock = Integer.parseInt(b); } catch (Exception ignored) {}
                    blockTv.setText("#" + chainBlock);
                    if (chainBlock != prev) pulseDot();
                }
                fetchBetsAndProcess();
            }
            @Override public void onError(String message) { handleErr(message); fetchBetsAndProcess(); }
        });
    }

    /** Pull the casino coins and run the reconcile / auto-process / celebrate pass (block tip known). */
    private void fetchBetsAndProcess() {
        // All casino coins at the contract address. Refreshed only on new block (memory: heavy
        // IPC responses can crash the node — never poll this in a tight loop). depth:4096 is a
        // pathological-growth cap ONLY: it sits above every tree length (family fork cascades at
        // 1024, STOCK Minima at 2048, oscillating ~2148), so the walk always reaches the root and
        // old claimable coins carried there stay visible. NEVER lower below the stock cascade — a
        // sub-2048 cap silently hides >cap-old timeout claims on stock nodes (stranded pots).
        node.cmd("coins address:" + CasinoContract.SCRIPT_ADDR + " depth:4096", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                setPaired(true);
                bets.clear();
                JSONArray arr = json.optJSONArray("response");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject c = arr.optJSONObject(i);
                        if (c == null) continue;
                        Bet bet = new Bet(Coin.from(c));
                        if (bet.isValid()) bets.add(bet);
                    }
                }
                if (identityReady) reconcileDisappeared();   // surface results the counterparty settled
                snapshotMine();
                updatePending();
                refreshAll();
                if (auto != null && identityReady) {
                    auto.process(new ArrayList<>(bets), new HashSet<>(myKeys), chainBlock, autoListener);
                }
                celebratePending();   // show the modal for any recorded result whose coin has now left chain
                maybeStartService();
            }
            @Override public void onError(String message) { handleErr(message); }
        });
    }

    private final AutoProcessor.Listener autoListener = new AutoProcessor.Listener() {
        @Override public void onRevealed(Bet bet) {
            log(bet.gameName() + " — house secret revealed, awaiting player resolve", LOG_OK);
            requestReload();
        }
        @Override public void onResolved(Bet bet, boolean iWon, BigDecimal profit, int result) {
            log((iWon ? "WON +" : "LOST -") + Util.miniNum(profit) + " · " + bet.gameName()
                    + " (rolled " + bet.game().pickLabel(result) + ")", iWon ? LOG_OK : LOG_ERR);
            recordResult(bet, iWon, profit, result, bet.iAmHouse(myKeys));   // triggers the celebration
            requestReload();
        }
        @Override public void onError(String message) { /* transient; will retry next block */ }
    };

    // ===== result + history =====
    /**
     * Record a settled bet. History is the single source of truth for the win/lose celebration:
     * a freshly-recorded result has celebrated=false, and {@link #celebratePending()} shows the
     * animated modal+sound for it (whether it was resolved here, by the background service, or
     * detected on reopen via reconciliation). De-dupes by coinid.
     */
    public void recordResult(Bet bet, boolean iWon, BigDecimal profit, int result, boolean isHouse) {
        if (hasHistory(bet.coinid())) { celebratePending(); return; }   // already recorded (in-memory authoritative)
        ResolvedBet rb = new ResolvedBet();
        rb.role = isHouse ? "House" : "Player";
        rb.game = bet.gameName();
        rb.range = bet.range;
        rb.pickIdx = bet.pick;
        rb.resultIdx = result;
        rb.pickLabel = bet.game().pickLabel(bet.pick);
        rb.resultLabel = result >= 0 ? bet.game().pickLabel(result) : "—";
        rb.won = iWon;
        rb.profit = Util.miniNum(profit);
        rb.coinid = bet.coinid();
        rb.time = System.currentTimeMillis();
        rb.celebrated = false;
        history.add(0, rb);
        while (history.size() > 50) history.remove(history.size() - 1);
        saveHistory();
        views[TAB_HISTORY].refresh();
        celebratePending();
    }

    /**
     * Show the animated win/lose modal for the newest result whose bet has actually LEFT the chain
     * (its coin is gone), so the celebration coincides with the card disappearing from MY BETS —
     * never while the bet still looks live. Results whose coin is still on-chain are left for a
     * later reload.
     */
    public void celebratePending() {
        Set<String> live = new HashSet<>();
        for (Bet b : bets) live.add(b.coinid());
        ResolvedBet target = null;
        for (ResolvedBet r : history) {
            if (r.celebrated) continue;
            if (r.coinid != null && live.contains(r.coinid)) continue;   // bet still live — wait
            if (target == null) target = r;
            r.celebrated = true;
        }
        if (target == null) return;
        saveHistory();
        CasinoContract.Game game = CasinoContract.Game.byRange(target.range);
        int pick = target.pickIdx >= 0 ? target.pickIdx : 0;
        int result = target.resultIdx >= 0 ? target.resultIdx : (target.won ? pick : (pick + 1) % game.range);
        ResultOverlay.show(this, game, pick, result, target.won, target.profit);
    }

    private void loadHistory() {
        history.clear();
        String json = secrets.history();
        if (json == null) return;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) history.add(ResolvedBet.from(o));
            }
        } catch (Exception ignored) {}
    }

    private void saveHistory() {
        JSONArray arr = new JSONArray();
        for (ResolvedBet r : history) arr.put(r.toJson());
        secrets.putHistory(arr.toString());
    }

    private boolean hasHistory(String coinid) {
        for (ResolvedBet r : history) if (coinid.equals(r.coinid)) return true;
        return false;
    }

    // ===== result reconciliation (the counterparty resolved the bet) =====
    /** Snapshot the bets that are mine this reload so we can spot ones that vanish next reload. */
    private void snapshotMine() {
        prevMineBets.clear();
        for (Bet b : bets) if (b.isMine(myKeys)) prevMineBets.put(b.coinid(), b);
    }

    /** A phase-2 bet of mine that disappeared was resolved by the other side — surface the result. */
    private void reconcileDisappeared() {
        if (reconciled.size() > 300) reconciled.clear();   // bounded; hasHistory() still guards re-celebration
        Set<String> current = new HashSet<>();
        for (Bet b : bets) current.add(b.coinid());
        for (Bet prev : new ArrayList<>(prevMineBets.values())) {
            if (current.contains(prev.coinid())) continue;   // still on-chain (or just advanced phase)
            if (prev.phase != 2) continue;                   // only a resolved phase-2 coin yields a result
            reconcileBet(prev);
        }
    }

    /**
     * PRIMARY — mechanism B: read the taker's RESOLVE txn for the EXACT rolled result the instant they resolve.
     * The resolve txn is self-contained: player_secret is in txn.state[13] and the spent phase-2 coin (inputs[0])
     * still carries FULL state incl. house_secret[12]. So either side computes the exact roll, matched by the
     * house commit (state[2], which survives every phase). Falls back to pot-detection when the resolve txn isn't
     * readable (older node without `txpow address:`, or not in the txpowdb yet) — win/lose stays correct there,
     * only the exact rolled number is approximated.
     */
    private void reconcileBet(final Bet prev) {
        final String id = prev.coinid();
        if (reconciled.contains(id) || hasHistory(id)) { reconciled.add(id); return; }
        reconciled.add(id);
        final boolean isHouse = prev.iAmHouse(myKeys);
        resolveExact(prev, isHouse, id);
    }

    private void resolveExact(final Bet prev, final boolean isHouse, final String id) {
        final String want = norm(prev.houseCommit);
        if (want.isEmpty()) { reconcileByPot(prev, isHouse, id); return; }
        node.cmd("txpow address:" + CasinoContract.SCRIPT_ADDR, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject input = null; String ps = null;
                JSONArray list = json.optJSONArray("response");
                if (list != null) for (int i = 0; i < list.length(); i++) {
                    JSONObject tp = list.optJSONObject(i); if (tp == null) continue;
                    JSONObject txn = txnOf(tp); if (txn == null) continue;
                    String p13 = stateOf(txn.optJSONArray("state"), CasinoContract.P_PLAYER_SECRET);
                    if (p13.isEmpty()) continue;                              // only resolve txns carry state[13]
                    JSONArray ins = txn.optJSONArray("inputs"); if (ins == null || ins.length() == 0) continue;
                    JSONObject in0 = ins.optJSONObject(0); if (in0 == null) continue;
                    if (!norm(in0.optString("address")).equals(norm(CasinoContract.SCRIPT_ADDR))) continue;
                    if (!norm(stateOf(in0.optJSONArray("state"), CasinoContract.P_HOUSE_COMMIT)).equals(want)) continue;
                    input = in0; ps = p13; break;
                }
                if (input == null) { reconcileByPot(prev, isHouse, id); return; }   // resolve txn not readable yet
                final String hs = stateOf(input.optJSONArray("state"), CasinoContract.P_HOUSE_SECRET);
                final int range = parseIntOr(stateOf(input.optJSONArray("state"), CasinoContract.P_RANGE), prev.range);
                final int pick  = parseIntOr(stateOf(input.optJSONArray("state"), CasinoContract.P_PICK), prev.pick);
                if (hs.isEmpty() || range <= 0) { reconcileByPot(prev, isHouse, id); return; }
                final String combined = hs + (ps.startsWith("0x") ? ps.substring(2) : ps);   // hs keeps 0x, ps drops it
                node.cmd("hash data:" + combined, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject h) {
                        String hash = resp(h);
                        int result;
                        try { result = (int) (Long.parseLong(hash.substring(2, 10), 16) % range); }
                        catch (Exception e) { reconcileByPot(prev, isHouse, id); return; }
                        boolean playerWins = (result == pick);
                        boolean iWon = isHouse ? !playerWins : playerWins;
                        BigDecimal profit = pnl(prev, iWon, isHouse);
                        log((iWon ? "WON +" : "LOST -") + Util.miniNum(profit) + " · " + prev.gameName()
                                + " (result " + prev.game().pickLabel(result) + ")", iWon ? LOG_OK : LOG_ERR);
                        recordResult(prev, iWon, profit, result, isHouse);   // EXACT result → triggers celebration
                        requestReload();
                    }
                    @Override public void onError(String m) { reconcileByPot(prev, isHouse, id); }
                });
            }
            @Override public void onError(String message) { reconcileByPot(prev, isHouse, id); }
        });
    }

    /**
     * Fallback — decide win/lose by whether the pot landed back at MY address (the winner receives the whole
     * pot; the loser receives nothing). Works for house or player without the counterparty's secret. The exact
     * rolled number is only knowable when I won as player or lost as house (rolled == pick); otherwise we show a
     * representative non-pick value.
     */
    private void reconcileByPot(final Bet prev, final boolean isHouse, final String id) {
        final String myAddr = isHouse ? prev.houseAddr : prev.playerAddr;
        final BigDecimal total = Util.dec(prev.totalAmount);
        if (myAddr == null || myAddr.isEmpty()) return;
        node.cmd("coins address:" + myAddr, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                boolean iGotPot = false;
                JSONArray arr = json.optJSONArray("response");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c == null) continue;
                    Coin coin = Coin.from(c);
                    if (coin.hasState()) continue;                       // payout coins carry no state
                    if (coin.created >= 0 && coin.created < prev.coin.created) continue;  // pre-existing
                    if (Util.dec(coin.amount).compareTo(total) == 0) { iGotPot = true; break; }
                }
                boolean iWon = iGotPot;
                boolean playerWon = isHouse ? !iWon : iWon;
                int result = playerWon ? prev.pick : nonPick(prev);   // rolled == pick iff player won
                BigDecimal profit = pnl(prev, iWon, isHouse);
                log((iWon ? "WON +" : "LOST -") + Util.miniNum(profit) + " · " + prev.gameName()
                        + " (settled by " + (isHouse ? "player" : "house") + ")", iWon ? LOG_OK : LOG_ERR);
                recordResult(prev, iWon, profit, result, isHouse);   // triggers the celebration
                requestReload();
            }
            @Override public void onError(String message) { reconciled.remove(id); }  // allow a retry
        });
    }

    private static String norm(String v) {
        if (v == null) return "";
        String s = v.toUpperCase();
        return s.startsWith("0X") ? s.substring(2) : s;
    }
    private static int parseIntOr(String v, int def) {
        if (v == null || v.isEmpty()) return def;
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return def; }
    }
    private static JSONObject txnOf(JSONObject tp) {
        JSONObject body = tp.optJSONObject("body");
        if (body != null) { JSONObject t = body.optJSONObject("txn"); if (t != null) return t; }
        return tp.optJSONObject("txn");
    }
    private static String stateOf(JSONArray st, int port) {
        if (st == null) return "";
        for (int i = 0; i < st.length(); i++) {
            JSONObject e = st.optJSONObject(i);
            if (e == null) continue;
            if (e.optInt("port", -1) == port) return e.optString("data", "");
        }
        return "";
    }
    private static String resp(JSONObject r) {
        if (r == null || !r.optBoolean("status", false)) return "";
        JSONObject resp = r.optJSONObject("response");
        if (resp == null) return "";
        String v = resp.optString("random", "");
        if (v.isEmpty()) v = resp.optString("hash", "");
        return v;
    }

    private static int nonPick(Bet b) {
        int p = Math.max(0, b.pick);
        return b.range <= 1 ? 0 : (p + 1) % b.range;
    }

    private static BigDecimal pnl(Bet bet, boolean iWon, boolean isHouse) {
        BigDecimal betAmt = Util.dec(bet.betAmount);
        BigDecimal winnings = betAmt.multiply(BigDecimal.valueOf(bet.payout));
        if (iWon) return isHouse ? betAmt : winnings.subtract(betAmt);
        return isHouse ? betAmt.multiply(BigDecimal.valueOf(bet.payout - 1L)) : betAmt;
    }

    // ===== service =====
    private void maybeStartService() {
        if (serviceStarted || !identityReady) return;
        serviceStarted = true;
        try { ContextCompat.startForegroundService(this, new Intent(this, CasinoService.class)); }
        catch (Exception ignored) {}
        try { AutoProcessWorker.schedule(this); } catch (Exception ignored) {}
    }

    // ===== ui helpers =====
    private void refreshAll() { for (BaseView v : views) v.refresh(); }

    private void updateSoundBtn() { soundBtn.setText(Theme.sound() ? "SND" : "MUTE"); }

    private void setPaired(boolean paired) {
        pairingBanner.setVisibility(paired ? View.GONE : View.VISIBLE);
    }

    private void handleErr(String message) {
        if (NodeApi.ERR_NOT_ENABLED.equals(message)) {
            setPaired(false);
            log("Enable Zero Edge Casino in Minima Core → Apps", LOG_WARN);
        }
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    /** Coalesce bursts of NEWBLOCK/NEWBALANCE into a single reload. */
    public void requestReload() {
        ui.removeCallbacks(reloadTask);
        ui.postDelayed(reloadTask, 400);
    }

    public void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }

    // ===== activity log =====
    /** Append a timestamped, colour-coded line to the activity log and the always-visible ticker. */
    public void log(String msg, int type) {
        String stamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date());
        String line = stamp + "  " + msg;
        logLines.addFirst(line);
        while (logLines.size() > 60) logLines.removeLast();
        if (tickerTv != null) {
            tickerTv.setTextColor(logColor(type));
            tickerTv.setText((type == LOG_WARN ? "⏳ " : type == LOG_ERR ? "✕ " : type == LOG_OK ? "✓ " : "› ") + msg);
        }
    }

    private int logColor(int type) {
        switch (type) {
            case LOG_OK: return Theme.green();
            case LOG_WARN: return Theme.amber();
            case LOG_ERR: return Theme.red();
            default: return Theme.cyan();
        }
    }

    private void showLogDialog() {
        StringBuilder sb = new StringBuilder();
        for (String l : logLines) sb.append(l).append('\n');
        if (sb.length() == 0) sb.append("No activity yet.");
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Activity log")
                .setMessage(sb.toString())
                .setPositiveButton("Close", null)
                .show();
    }

    private void pulseDot() {
        if (liveDot == null) return;
        liveDot.animate().cancel();
        liveDot.setAlpha(1f);
        liveDot.animate().alpha(0.25f).setDuration(900).start();
    }

    // ===== pending-confirmation feedback =====
    /** Called by the create/take flows once a tx is posted, so the wait shows live progress. */
    public void markPending(int type, String desc) {
        pendingType = type;
        pendingDesc = desc;
        pendingSinceBlock = chainBlock;
        pendingBaselineIds.clear();
        for (Bet b : bets) if (b.isMine(myKeys)) pendingBaselineIds.add(b.coinid());
        log(desc + " — waiting for confirmation…", LOG_WARN);
    }

    /** Run each reload: detect when a pending create/take has confirmed, else show elapsed blocks. */
    private void updatePending() {
        if (pendingType == PEND_NONE) return;
        boolean confirmed = false;   // a NEW bet of mine appeared since we posted
        for (Bet b : bets) if (b.isMine(myKeys) && !pendingBaselineIds.contains(b.coinid())) { confirmed = true; break; }
        if (confirmed) {
            log(pendingDesc + " confirmed on-chain!", LOG_OK);
            pendingType = PEND_NONE;
            return;
        }
        int elapsed = Math.max(0, chainBlock - pendingSinceBlock);
        if (elapsed >= 10) {           // give up nagging after ~10 blocks
            log(pendingDesc + " — still not seen after " + elapsed + " blocks. Check Pending/stuck txns.", LOG_ERR);
            pendingType = PEND_NONE;
            return;
        }
        log(pendingDesc + " — confirming, " + elapsed + " block" + (elapsed == 1 ? "" : "s")
                + " elapsed (#" + chainBlock + ")", LOG_WARN);
    }

    public int pendingCreate() { return PEND_CREATE; }
    public int pendingTake() { return PEND_TAKE; }

    public void switchTab(int tab) { if (pager != null) pager.setCurrentItem(tab, true); }

    // ===== accessors for the tab views =====
    public NodeApi node() { return node; }
    public SecretStore secrets() { return secrets; }
    public CasinoTxn txn() { return txn; }
    public AutoProcessor auto() { return auto; }
    public List<Bet> bets() { return bets; }
    public List<ResolvedBet> history() { return history; }
    public Set<String> myKeys() { return myKeys; }
    public int chainBlock() { return chainBlock; }
    public String myPubkey() { return myPubkey; }
    public String myHexAddr() { return myHexAddr; }
    public boolean identityReady() { return identityReady; }
    public String balance() { return balance; }
}
