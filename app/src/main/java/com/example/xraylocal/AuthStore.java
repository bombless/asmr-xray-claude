package com.example.xraylocal;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Persisted login session (JWT + user name). Static like {@link AppSettings}.
 *
 * <p>Only the token is stored, never the password. The token must only ever be sent to the API host
 * ({@link AppSettings#apiBase()}): covers and audio live on other hosts and must not receive it.
 */
final class AuthStore {
    private static final String PREFS = "asmr_auth";
    // JWTs are base64url + dots. Anything else (e.g. CR/LF) must never reach a request header.
    private static final Pattern TOKEN_OK = Pattern.compile("[A-Za-z0-9._~+/=-]{10,4096}");

    private AuthStore() {}

    private static SharedPreferences sp() {
        return App.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized String token() { return sp().getString("token", ""); }

    static synchronized String name() { return sp().getString("name", ""); }

    static boolean loggedIn() { return !token().isEmpty(); }

    /** @throws IllegalArgumentException if the token does not look like a JWT */
    static synchronized void save(String token, String name) {
        if (token == null || !TOKEN_OK.matcher(token).matches()) {
            throw new IllegalArgumentException("服务器返回的登录凭证格式异常");
        }
        sp().edit().putString("token", token).putString("name", name == null ? "" : name).apply();
        AppSettings.bumpRev();
    }

    static synchronized void clear() {
        boolean had = !token().isEmpty();
        sp().edit().clear().apply();
        if (had) AppSettings.bumpRev();
    }

    /**
     * Drops the session only if {@code sentToken} is still the current one. A late 401 for a request that
     * was sent with an old token must not wipe a login the user has just made.
     */
    static synchronized void clearIfCurrent(String sentToken) {
        if (sentToken != null && !sentToken.isEmpty() && sentToken.equals(token())) clear();
    }

    /** Extra request headers for calls to the API host: {@code Authorization: Bearer ...} when logged in. */
    static Map<String, String> headers() { return headers(token()); }

    static Map<String, String> headers(String token) {
        if (token == null || token.isEmpty()) return Collections.emptyMap();
        Map<String, String> h = new HashMap<>(2);
        h.put("Authorization", "Bearer " + token);
        return h;
    }
}
