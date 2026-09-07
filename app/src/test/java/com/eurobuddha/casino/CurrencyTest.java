package com.eurobuddha.casino;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Pins the currency-display contract for the MxUSD ("USD") mode. The load-bearing requirement:
 * a token amount must render at its OWN real resolution, while native Minima keeps its 5-decimal
 * display cap. Getting this wrong hides real MxUSD value from the player.
 */
public class CurrencyTest {

    private static final String USD = Currency.USD_TOKENID;
    private static final String MINIMA = Util.MINIMA_TOKENID;

    @Test public void minimaCapsDisplayAtFiveDecimals() {
        // Native Minima: capped to 5 dp (DOWN) — the header-overflow rule.
        assertEquals("1.12345", Currency.displayFor("1.123456789", MINIMA));
    }

    @Test public void usdShowsFullResolution() {
        // MxUSD: full token resolution, NOT capped to 5 dp — the whole point of the token's decimals.
        assertEquals("1.123456789", Currency.displayFor("1.123456789", USD));
    }

    @Test public void usdKeepsEighthDecimal() {
        // An 8-dp value the Minima cap would have truncated to "0.00000".
        assertEquals("0.00000123", Currency.displayFor("0.00000123", USD));
        assertEquals("0", Currency.displayFor("0.00000123", MINIMA));
    }

    @Test public void suffixAndNameTrackTheToken() {
        assertEquals(" USD", Currency.suffixFor(USD));
        assertEquals("", Currency.suffixFor(MINIMA));
        assertEquals("USD", Currency.nameFor(USD));
        assertEquals("Minima", Currency.nameFor(MINIMA));
        // show() = value + suffix, per token.
        assertEquals("2 USD", Currency.show("2", USD));
        assertEquals("2", Currency.show("2", MINIMA));
    }

    @Test public void betReadsItsCoinToken() throws Exception {
        // A coloured-token bet coin: value lives in tokenamount, token in tokenid.
        JSONObject c = new JSONObject();
        c.put("coinid", "0xCAFE");
        c.put("tokenid", USD);
        c.put("amount", "0.0000000000000000000000000000000000001");   // ~1e-37 native shell — must be ignored
        c.put("tokenamount", "5.25");
        Bet bet = new Bet(Coin.from(c));
        assertEquals(USD, bet.tokenid());
        assertEquals("5.25", bet.totalAmount);   // reads tokenamount, not the 1e-37 native amount
    }

    @Test public void nullAndNativeTokenBothCountAsMinima() {
        assertTrue(Util.isMinima(null));
        assertTrue(Util.isMinima("0x00"));
        assertEquals("", Currency.suffixFor(null));
    }
}
