package com.eurobuddha.casino;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * Encrypted persistent key-value store for gambling secrets and history — the native replacement
 * for the dapp's MDS.keypair. Keys:
 *   casino_secret_for_<commit>   house secret (so we can auto-reveal)
 *   casino_psecret_for_<commit>  player secret (so we can auto-resolve)
 *   casino_history               JSON array of resolved bets (capped 50)
 *
 * Secrets gate real funds, so they live in EncryptedSharedPreferences (AES via the Android
 * Keystore-backed master key). Falls back to plain prefs only if the keystore is unavailable.
 */
public class SecretStore {

    private static final String FILE = "casino_secrets";
    private final SharedPreferences prefs;

    public SecretStore(Context ctx) {
        prefs = open(ctx);
    }

    private static SharedPreferences open(Context ctx) {
        try {
            MasterKey key = new MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    ctx, FILE, key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            // Keystore unavailable (rare) — degrade to plain prefs rather than crash.
            return ctx.getSharedPreferences(FILE + "_plain", Context.MODE_PRIVATE);
        }
    }

    /** Non-critical async write (used for history). */
    public void put(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    /**
     * FUND-CRITICAL durable write. A commit preimage MUST be provably persisted BEFORE the on-chain send/take that
     * commits to it — a lost preimage strands the pot to the counterparty's 1500-block timeout claim. {@code apply()}
     * is asynchronous and swallows write failures, so we use {@code commit()} (synchronous, returns success) and then
     * READ THE VALUE BACK from durable storage. Returns true only when the secret is confirmed retrievable.
     */
    public boolean putDurable(String key, String value) {
        try {
            boolean ok = prefs.edit().putString(key, value).commit();
            return ok && value.equals(prefs.getString(key, null));
        } catch (Exception e) {
            return false;
        }
    }

    public String get(String key) {
        return prefs.getString(key, null);
    }

    public boolean has(String key) {
        return prefs.contains(key);
    }

    // ---- typed helpers mirroring the dapp key names (secrets use the durable path; callers MUST check the result) ----
    public boolean putHouseSecret(String commit, String secret) { return putDurable("casino_secret_for_" + commit, secret); }
    public String houseSecret(String commit) { return get("casino_secret_for_" + commit); }

    public boolean putPlayerSecret(String commit, String secret) { return putDurable("casino_psecret_for_" + commit, secret); }
    public String playerSecret(String commit) { return get("casino_psecret_for_" + commit); }

    public String history() { return get("casino_history"); }
    public void putHistory(String json) { put("casino_history", json); }
}
