package com.eurobuddha.casino;

import android.content.Context;
import android.graphics.Typeface;

/**
 * Retro 8-bit pixel-arcade design tokens, ported from the Zero Edge Casino dapp CSS
 * (index.html). Single dark theme with a light toggle, mirroring the dapp's only real
 * customisation (Dark <-> Light). Every view reads colours from here.
 */
public final class Theme {

    private static final String PREFS = "casino_theme";
    private static final String KEY_LIGHT = "light";
    private static final String KEY_SOUND = "sound";
    private static final String KEY_DOLLAR = "dollar";

    private static boolean light = false;   // dapp default is dark
    private static boolean sound = true;
    private static boolean dollar = false;  // false = native Minima; true = MxUSD ("USD") mode

    private Theme() {}

    public static void load(Context c) {
        var p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        light = p.getBoolean(KEY_LIGHT, false);
        sound = p.getBoolean(KEY_SOUND, true);
        dollar = p.getBoolean(KEY_DOLLAR, false);
    }

    public static boolean isLight() { return light; }

    public static void setLight(Context c, boolean v) {
        light = v;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LIGHT, v).apply();
    }

    public static boolean sound() { return sound; }

    public static void setSound(Context c, boolean v) {
        sound = v;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SOUND, v).apply();
    }

    public static boolean dollar() { return dollar; }

    public static void setDollar(Context c, boolean v) {
        dollar = v;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DOLLAR, v).apply();
    }

    private static int pick(int dark, int lightCol) { return light ? lightCol : dark; }

    // ---- palette (verbatim from the dapp CSS variables) ----
    public static int bg()        { return pick(0xFF0A0E1A, 0xFFF0F0F5); } // --bg #0a0e1a
    public static int panel()     { return pick(0xFF121829, 0xFFFFFFFF); } // card surface
    public static int panel2()    { return pick(0xFF1A2238, 0xFFE9E9F0); } // raised surface
    public static int border()    { return pick(0xFF2A3550, 0xFFC9C9D6); }
    // The primary accent. Dollar (MxUSD) mode swaps the gold accent for dollar-green so the whole
    // app reads "dollars"; Minima mode keeps the original gold, byte-for-byte. Every view funnels
    // its accent through gold(), so this one line recolours all four tabs + overlays centrally.
    public static int gold()      { return dollar ? Currency.ACCENT_GREEN : 0xFFFFD700; }
    public static int pink()      { return 0xFFFF2D78; } // --pink
    public static int cyan()      { return 0xFF00E5FF; } // --cyan
    public static int green()     { return 0xFF35E07A; } // win green
    public static int red()       { return 0xFFFF3B5C; } // lose red
    public static int amber()     { return 0xFFE6A23C; } // timeout warning
    public static int text()      { return pick(0xFFEAEAF2, 0xFF14141C); }
    public static int dim()       { return pick(0xFF8A93AD, 0xFF6A6A78); }
    public static int onAccent()  { return 0xFF0A0E1A; }

    // ---- type ----
    /** Pixel/title face — falls back to monospace (no bundled Press Start 2P). */
    public static Typeface pixel()     { return Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); }
    public static Typeface body()      { return Typeface.SANS_SERIF; }
    public static Typeface mono()      { return Typeface.MONOSPACE; }
}
