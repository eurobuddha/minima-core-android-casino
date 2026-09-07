package com.eurobuddha.casino;

/**
 * The currency the casino is currently betting in. Two currencies share the SAME token-agnostic
 * covenant and the SAME {@link CasinoContract#SCRIPT_ADDR}; only the coin's token differs:
 *
 *   - MINIMA — the native token (tokenid {@code 0x00}), the original proven casino.
 *   - USD    — MxUSD, the Minima dollar-pegged coloured token (shown to players as "USD").
 *
 * The active currency is a persisted UI flag on {@link Theme} (mirrors the sound/light toggles).
 * Reuse note: the tokenid + pattern come from the AtomiX app (apks/atomix, MinimaHtlc/TradingContext),
 * which already transacts MxUSD — we do not scale by decimals, the node speaks human token units.
 */
public final class Currency {

    /** MxUSD — verified across the family (tools/dexHistory, AtomiX, PandaPools). Never truncate. */
    public static final String USD_TOKENID =
            "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";

    /** The dollar-mode accent green (the same green AtomiX uses for its MxUSD context). */
    public static final int ACCENT_GREEN = 0xFF26A17B;

    private Currency() {}

    public static boolean isDollar() { return Theme.dollar(); }

    /** Active token for NEWLY created bets. Operations on an existing bet must use the bet's own
     *  coin token ({@link Bet#tokenid()}), never this — the coin dictates the token. */
    public static String tokenId() { return isDollar() ? USD_TOKENID : Util.MINIMA_TOKENID; }

    /** Short name for the active currency ("MINIMA" / "USD"). */
    public static String label() { return isDollar() ? "USD" : "MINIMA"; }

    /** Amount suffix for the active currency (" USD"; native Minima carries none, as today). */
    public static String suffix() { return isDollar() ? " USD" : ""; }

    /** Suffix for a specific token (so a bet renders in ITS token, not just the active one). */
    public static String suffixFor(String tokenid) {
        return Util.isMinima(tokenid) ? "" : " USD";
    }

    /** Short name for a specific token ("Minima" / "USD"), for prose messages. */
    public static String nameFor(String tokenid) {
        return Util.isMinima(tokenid) ? "Minima" : "USD";
    }

    /**
     * Display an amount at the right resolution for the active currency.
     * Minima keeps the 5-dp display cap (its native 45-dp precision overflows the header);
     * a token (MxUSD) is shown at its FULL real resolution — the whole point of a coloured
     * token is its own decimals, and capping to 5-dp would hide real value.
     */
    public static String display(String amt) {
        return isDollar() ? Util.miniNum(Util.dec(amt)) : Util.displayAmount(amt);
    }

    /** Display for a specific token (used by History/My Bets rows that may mix currencies). */
    public static String displayFor(String amt, String tokenid) {
        return Util.isMinima(tokenid) ? Util.displayAmount(amt) : Util.miniNum(Util.dec(amt));
    }

    /** Amount + currency suffix in the ACTIVE currency, e.g. "2 USD" (Minima: just "2"). */
    public static String show(String amt) {
        return display(amt) + suffix();
    }

    /** Amount + currency suffix for a SPECIFIC token (a bet renders in its own currency). */
    public static String show(String amt, String tokenid) {
        return displayFor(amt, tokenid) + suffixFor(tokenid);
    }
}
