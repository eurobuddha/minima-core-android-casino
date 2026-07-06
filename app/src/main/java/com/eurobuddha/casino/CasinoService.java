package com.eurobuddha.casino;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.minimarex.minimaapi.MinimaAPI;
import org.minimarex.minimaapi.MinimaAPIMessages;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Foreground service that keeps the casino running when the app is closed: on each new block it
 * auto-reveals bets where the user is house and auto-resolves bets where the user is player, then
 * notifies on reveals and wins/losses. Port of the dapp's service.js.
 */
public class CasinoService extends Service {

    private static final String CH_FG = "casino_fg";
    private static final String CH_ALERT = "casino_alert";
    private static final int FG_ID = 1001;
    private int alertId = 2000;

    private NodeApi node;
    private SecretStore secrets;
    private CasinoTxn txn;
    private AutoProcessor auto;
    private BroadcastReceiver receiver;

    private String myPubkey = "", myHexAddr = "";
    private final Set<String> myKeys = new HashSet<>();
    private boolean ready = false;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        createChannels();
        startForegroundCompat();

        secrets = new SecretStore(this);
        node = new NodeApi(this, enabled -> {});
        CasinoContract.register(node);

        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (!MinimaAPI.checkMinimaID(CasinoService.this, intent)) return;
                String data = intent.getStringExtra(MinimaAPIMessages.MINIMA_API_NOTIFY_DATA);
                if (data == null) return;
                try {
                    String event = new JSONObject(data).optString("event", "");
                    if ("NEWBLOCK".equals(event) || "NEWBALANCE".equals(event)) tick();
                } catch (Exception ignored) {}
            }
        };
        ContextCompat.registerReceiver(this, receiver,
                new IntentFilter(MinimaAPIMessages.MINIMA_API_NOTIFY), ContextCompat.RECEIVER_EXPORTED);

        loadIdentity();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        super.onDestroy();
        if (node != null) node.onDestroy();
        if (receiver != null) { try { unregisterReceiver(receiver); } catch (Exception ignored) {} }
    }

    // ----- identity -----
    private void loadIdentity() {
        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                if (r != null) { myPubkey = r.optString("publickey", ""); myHexAddr = r.optString("address", ""); }
                node.cmd("keys", new NodeApi.Cb() {
                    @Override public void onResult(JSONObject j2) {
                        Object resp = j2.opt("response");
                        JSONArray arr = resp instanceof JSONArray ? (JSONArray) resp
                                : (resp instanceof JSONObject ? ((JSONObject) resp).optJSONArray("keys") : null);
                        if (arr != null) for (int i = 0; i < arr.length(); i++) {
                            JSONObject k = arr.optJSONObject(i);
                            if (k != null) { String pk = k.optString("publickey", ""); if (!pk.isEmpty()) myKeys.add(pk); }
                        }
                        if (!myPubkey.isEmpty()) myKeys.add(myPubkey);
                        ready = !myPubkey.isEmpty() && !myHexAddr.isEmpty();
                        txn = new CasinoTxn(node, secrets, myPubkey, myHexAddr);
                        auto = new AutoProcessor(txn);
                        tick();
                    }
                    @Override public void onError(String m) {}
                });
            }
            @Override public void onError(String m) {}
        });
    }

    // ----- per-block processing -----
    private int lastBlock = 0;

    private void tick() {
        if (!ready || auto == null) return;
        // The foreground Activity owns auto-processing while it's visible — stand down so we don't
        // both post competing reveal/resolve transactions for the same coin.
        if (MainActivity.FOREGROUND) return;
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                if (r != null) {
                    String b = r.optString("block", "");
                    if (b.isEmpty()) { JSONObject h = r.optJSONObject("header"); if (h != null) b = h.optString("block", ""); }
                    try { lastBlock = Integer.parseInt(b); } catch (Exception ignored) {}
                }
                fetchAndProcess();
            }
            @Override public void onError(String m) { fetchAndProcess(); }
        });
    }

    private void fetchAndProcess() {
        node.cmd("coins address:" + CasinoContract.SCRIPT_ADDR, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                List<Bet> bets = new ArrayList<>();
                JSONArray arr = json.optJSONArray("response");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c == null) continue;
                    Bet b = new Bet(Coin.from(c));
                    if (b.isValid()) bets.add(b);
                }
                auto.process(bets, new HashSet<>(myKeys), lastBlock, listener);
            }
            @Override public void onError(String m) {}
        });
    }

    private final AutoProcessor.Listener listener = new AutoProcessor.Listener() {
        @Override public void onRevealed(Bet bet) {
            notifyAlert("Secret revealed", bet.gameName() + " — waiting for player to resolve");
        }
        @Override public void onResolved(Bet bet, boolean iWon, BigDecimal profit, int result) {
            recordHistory(bet, iWon, profit, result);
            notifyAlert(iWon ? "You won +" + Util.miniNum(profit) + " Minima!"
                            : "You lost -" + Util.miniNum(profit) + " Minima",
                    bet.gameName() + " — rolled " + bet.game().pickLabel(result));
        }
        @Override public void onError(String message) {}
    };

    /**
     * Append to the same casino_history store the activity reads, with celebrated=false so the
     * activity shows the modal/anim/sound when it next opens/reloads. De-dupes by coinid.
     */
    private void recordHistory(Bet bet, boolean iWon, BigDecimal profit, int result) {
        boolean isHouse = bet.iAmHouse(myKeys);
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
        try {
            JSONArray arr = secrets.history() == null ? new JSONArray() : new JSONArray(secrets.history());
            for (int i = 0; i < arr.length(); i++) {               // de-dupe: skip if already recorded
                JSONObject e = arr.optJSONObject(i);
                if (e != null && bet.coinid().equals(e.optString("coinid"))) return;
            }
            JSONArray out = new JSONArray();
            out.put(rb.toJson());
            for (int i = 0; i < arr.length() && out.length() < 50; i++) out.put(arr.get(i));
            secrets.putHistory(out.toString());
        } catch (Exception ignored) {}
    }

    // ----- notifications -----
    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CH_FG, "Casino background",
                    NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(CH_ALERT, "Casino results",
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    private void startForegroundCompat() {
        Notification n = new NotificationCompat.Builder(this, CH_FG)
                .setContentTitle("Zero Edge Casino")
                .setContentText("Auto-revealing & resolving your bets")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(FG_ID, n);
        }
    }

    private void notifyAlert(String title, String body) {
        Notification n = new NotificationCompat.Builder(this, CH_ALERT)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setAutoCancel(true)
                .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(alertId++, n);
    }
}
