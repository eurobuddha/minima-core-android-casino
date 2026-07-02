package org.minimarex.casino;

import org.json.JSONObject;

/**
 * The Zero Edge Casino on-chain contract and its constants — ported verbatim from the dapp
 * (index.html) so the native app registers the IDENTICAL script and therefore resolves to the
 * same script address. That makes bets created in the dapp visible/takeable here and vice-versa.
 *
 * 3-phase commit-reveal. State layout (ports):
 *   0 house pubkey | 1 house addr | 2 house commit SHA3(secret) | 3 range | 4 payout multiplier
 *   5 bet amount   | 6 phase(0/1/2) | 7 timeout blocks | 8 player pubkey | 9 player addr
 *   10 player commit | 11 player pick | 12 house secret (phase2) | 13 player secret (resolve)
 */
public final class CasinoContract {

    private CasinoContract() {}

    /** Verbatim compiled KISS script (index.html:597). Must hash to SCRIPT_ADDR. */
    public static final String SCRIPT =
        "LET hpk=PREVSTATE(0) LET ha=PREVSTATE(1) LET hc=PREVSTATE(2) LET rng=PREVSTATE(3) " +
        "LET po=PREVSTATE(4) LET bt=PREVSTATE(5) LET ph=PREVSTATE(6) LET to=PREVSTATE(7) " +
        "IF ph EQ 0 AND SIGNEDBY(hpk) THEN RETURN TRUE ENDIF " +
        "IF ph EQ 0 THEN ASSERT SAMESTATE(0 5) ASSERT STATE(6) EQ 1 ASSERT STATE(7) EQ to " +
        "ASSERT STATE(11) GTE 0 AND STATE(11) LT rng ASSERT VERIFYOUT(@INPUT @ADDRESS @AMOUNT+bt @TOKENID TRUE) RETURN TRUE ENDIF " +
        "LET qk=PREVSTATE(8) LET pk=PREVSTATE(11) " +
        "IF ph EQ 1 AND SIGNEDBY(hpk) THEN ASSERT SAMESTATE(0 5) ASSERT STATE(6) EQ 2 ASSERT SAMESTATE(7 11) " +
        "LET hs=STATE(12) ASSERT SHA3(hs) EQ hc ASSERT VERIFYOUT(@INPUT @ADDRESS @AMOUNT @TOKENID TRUE) RETURN TRUE ENDIF " +
        "IF ph EQ 1 AND @COINAGE GT to AND SIGNEDBY(qk) THEN RETURN TRUE ENDIF " +
        "IF ph EQ 2 AND SIGNEDBY(qk) THEN LET ps=STATE(13) ASSERT SHA3(ps) EQ PREVSTATE(10) " +
        "LET hs=PREVSTATE(12) LET h=SHA3(CONCAT(hs ps)) LET r=NUMBER(SUBSET(0 4 h))%rng " +
        "IF r EQ pk THEN LET w=bt*po ASSERT VERIFYOUT(@INPUT PREVSTATE(9) w @TOKENID FALSE) " +
        "IF @AMOUNT GT w THEN ASSERT VERIFYOUT(@INPUT+1 ha @AMOUNT-w @TOKENID FALSE) ENDIF " +
        "ELSE ASSERT VERIFYOUT(@INPUT ha @AMOUNT @TOKENID FALSE) ENDIF RETURN TRUE ENDIF " +
        "IF ph EQ 2 AND @COINAGE GT to AND SIGNEDBY(hpk) THEN RETURN TRUE ENDIF RETURN FALSE";

    public static final String SCRIPT_ADDR =
        "0xD65ADBBB7AB5032D794B02CF5E8814C720BE3C9562CC6C07081DE41CCA665A6F";

    public static final int TIMEOUT_BLOCKS = 1500;

    // state ports
    public static final int P_HOUSE_PK = 0, P_HOUSE_ADDR = 1, P_HOUSE_COMMIT = 2, P_RANGE = 3,
            P_PAYOUT = 4, P_BET = 5, P_PHASE = 6, P_TIMEOUT = 7, P_PLAYER_PK = 8,
            P_PLAYER_ADDR = 9, P_PLAYER_COMMIT = 10, P_PICK = 11, P_HOUSE_SECRET = 12,
            P_PLAYER_SECRET = 13;

    /** Game preset: range/payout and human labels (port of PRESETS / gameType / pickLabel). */
    public enum Game {
        FLIP("Coin Flip", "flip", 2, 2, new String[]{"Heads", "Tails"}, "✦"),
        DICE("Dice", "dice", 6, 6, new String[]{"1", "2", "3", "4", "5", "6"}, "⚀"),
        ROULETTE("Roulette", "roulette", 36, 36, null, "◉");

        public final String name, key, icon;
        public final int range, payout;
        public final String[] labels;

        Game(String name, String key, int range, int payout, String[] labels, String icon) {
            this.name = name; this.key = key; this.range = range; this.payout = payout;
            this.labels = labels; this.icon = icon;
        }

        public int oddsAgainst() { return payout - 1; }   // "X:1"

        public String pickLabel(int pick) {
            if (labels != null && pick >= 0 && pick < labels.length) return labels[pick];
            return String.valueOf(pick + 1);              // roulette: 1-based number
        }

        public static Game byRange(int range) {
            for (Game g : values()) if (g.range == range) return g;
            return FLIP;
        }
    }

    /** Register the script so the node tracks the casino address. Fire-and-forget at startup. */
    public static void register(NodeApi node) {
        node.cmd("newscript script:\"" + SCRIPT + "\" trackall:true", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {}
            @Override public void onError(String message) {}
        });
    }
}
