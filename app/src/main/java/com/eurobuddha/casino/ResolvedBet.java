package com.eurobuddha.casino;

import org.json.JSONException;
import org.json.JSONObject;

/** A completed bet for the History tab (persisted as JSON in SecretStore). */
public class ResolvedBet {
    public String role;        // "House" / "Player"
    public String game;        // "Coin Flip" ...
    public int range;
    public String pickLabel;
    public String resultLabel;
    public int pickIdx = -1;   // pick / result as indices, to drive the result animation
    public int resultIdx = -1;
    public boolean won;        // from my perspective
    public String profit;      // magnitude string
    public String tokenid = Util.MINIMA_TOKENID;   // currency this bet ran in (0x00 = native Minima)
    public String coinid;
    public long time;
    /** Has the win/lose modal+animation+sound been shown for this result yet? */
    public boolean celebrated = false;

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("role", role); o.put("game", game); o.put("range", range);
            o.put("pickLabel", pickLabel); o.put("resultLabel", resultLabel);
            o.put("pickIdx", pickIdx); o.put("resultIdx", resultIdx);
            o.put("won", won); o.put("profit", profit); o.put("tokenid", tokenid);
            o.put("coinid", coinid); o.put("time", time);
            o.put("celebrated", celebrated);
        } catch (JSONException ignored) {}
        return o;
    }

    public static ResolvedBet from(JSONObject o) {
        ResolvedBet r = new ResolvedBet();
        r.role = o.optString("role"); r.game = o.optString("game"); r.range = o.optInt("range");
        r.pickLabel = o.optString("pickLabel"); r.resultLabel = o.optString("resultLabel");
        r.pickIdx = o.optInt("pickIdx", -1); r.resultIdx = o.optInt("resultIdx", -1);
        r.won = o.optBoolean("won"); r.profit = o.optString("profit");
        r.tokenid = o.optString("tokenid", Util.MINIMA_TOKENID);   // pre-USD entries = native Minima
        r.coinid = o.optString("coinid"); r.time = o.optLong("time");
        // Entries loaded from storage are treated as already celebrated (default true) so we never
        // re-show old results; only freshly-recorded ones (celebrated:false) get celebrated.
        r.celebrated = o.optBoolean("celebrated", true);
        return r;
    }
}
