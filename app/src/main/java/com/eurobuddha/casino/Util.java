package com.eurobuddha.casino;

import org.json.JSONObject;

import java.math.BigDecimal;

public final class Util {

    public static final String MINIMA_TOKENID = "0x00";

    private Util() {}

    public static boolean isMinima(String tokenid) {
        return tokenid == null || MINIMA_TOKENID.equals(tokenid);
    }

    /** Shorten a long hex id/address for display: 0x1234…ABCD */
    public static String shorten(String s) {
        if (s == null) return "";
        if (s.length() <= 16) return s;
        return s.substring(0, 8) + "…" + s.substring(s.length() - 6);
    }

    /** Pull a txpowid out of a posted-transaction response, falling back to the given id. */
    public static String extractTxpowid(JSONObject json, String fallback) {
        JSONObject resp = json.optJSONObject("response");
        if (resp != null) {
            String t = resp.optString("txpowid", "");
            if (t.isEmpty()) {
                JSONObject txp = resp.optJSONObject("txpow");
                if (txp != null) t = txp.optString("txpowid", "");
            }
            if (!t.isEmpty()) return t;
        }
        return fallback;
    }

    /** Trim trailing zeros from a decimal amount string for tidy display. */
    public static String tidyAmount(String amt) {
        if (amt == null || amt.isEmpty()) return "0";
        if (!amt.contains(".")) return amt;
        String s = amt.replaceAll("0+$", "");
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? "0" : s;
    }

    /** Format a number to a tidy Minima amount (drops trailing zeros, like the dapp's miniNum). */
    public static String miniNum(BigDecimal v) {
        if (v == null) return "0";
        return tidyAmount(v.stripTrailingZeros().toPlainString());
    }

    /** Max decimals shown for a balance/amount in the UI. Minima's native precision (up to 45
     *  decimals) overflows on-screen fields; cap the DISPLAY only. */
    public static final int DISPLAY_DECIMALS = 5;

    /** Cap a Minima amount to 5 decimals for DISPLAY ONLY. Rounds DOWN so we never overstate a
     *  balance. NEVER use for building transactions — send/txnoutput amounts must keep full
     *  precision; use {@link #miniNum(BigDecimal)} there. */
    public static String displayAmount(BigDecimal v) {
        if (v == null) return "0";
        return tidyAmount(v.setScale(DISPLAY_DECIMALS, java.math.RoundingMode.DOWN).toPlainString());
    }

    /** String overload of {@link #displayAmount(BigDecimal)}. */
    public static String displayAmount(String s) {
        return displayAmount(dec(s));
    }

    /** Parse a possibly-empty amount string to BigDecimal, defaulting to zero. */
    public static BigDecimal dec(String s) {
        try { return (s == null || s.isEmpty()) ? BigDecimal.ZERO : new BigDecimal(s); }
        catch (Exception e) { return BigDecimal.ZERO; }
    }
}
