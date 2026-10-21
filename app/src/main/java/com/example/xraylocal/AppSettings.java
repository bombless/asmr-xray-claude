package com.example.xraylocal;

import android.content.Context;
import android.content.SharedPreferences;

/** User-facing settings. Every write bumps {@link #rev()} so screens can detect changes. */
final class AppSettings {
    static final String DEFAULT_API_BASE = "https://api.asmr-200.com";
    private static final String PREFS = "asmr_settings";
    private static volatile int rev = 0;

    private AppSettings() {}

    private static SharedPreferences sp() {
        return App.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int rev() { return rev; }

    /** When true every request (API, covers, audio) is dialed through the in-process Xray core. */
    static boolean useXray() { return sp().getBoolean("use_xray", true); }

    static void setUseXray(boolean v) {
        sp().edit().putBoolean("use_xray", v).apply();
        rev++;
        Net.clearPool();
    }

    static String apiBase() {
        String v = sp().getString("api_base", DEFAULT_API_BASE);
        return normalizeBase(v);
    }

    static void setApiBase(String v) {
        sp().edit().putString("api_base", normalizeBase(v)).apply();
        rev++;
    }

    /** Prefer the low-bitrate m4a stream (when the server offers one) instead of the original WAV. */
    static boolean lowQuality() { return sp().getBoolean("low_quality", false); }

    static void setLowQuality(boolean v) {
        sp().edit().putBoolean("low_quality", v).apply();
        rev++;
    }

    static String normalizeBase(String v) {
        if (v == null) return DEFAULT_API_BASE;
        v = v.trim();
        if (v.isEmpty()) return DEFAULT_API_BASE;
        if (!v.contains("://")) v = "https://" + v;
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }
}
