package com.example.xraylocal;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persisted subscription: URL, parsed nodes and the selected node. Static like {@link AppSettings}.
 *
 * <p>The subscription itself is always fetched over the system network (never through Xray): the core
 * may not be running yet, or the current node may be dead, and the list must still be refreshable.
 */
final class SubscriptionStore {
    private static final String PREFS = "asmr_subscription";
    private static final int MAX_BODY = 8 << 20;
    // Some panels only return the plain v2ray share-link list for v2ray-style clients.
    private static final String UA = "v2rayA/debug WebRequestHelper";

    private static boolean loaded;
    private static String url = "";
    private static String selectedKey = "";
    private static List<SubscriptionNode> nodes = Collections.emptyList();

    private SubscriptionStore() {}

    private static SharedPreferences sp() {
        return App.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        SharedPreferences p = sp();
        url = p.getString("url", "");
        selectedKey = p.getString("selected_key", "");
        try {
            JSONArray a = new JSONArray(p.getString("nodes", "[]"));
            List<SubscriptionNode> list = new ArrayList<>(a.length());
            for (int i = 0; i < a.length(); i++) list.add(SubscriptionNode.fromJson(a.getJSONObject(i)));
            nodes = Collections.unmodifiableList(list);
        } catch (Exception e) {
            nodes = Collections.emptyList();
        }
    }

    static synchronized String url() {
        ensureLoaded();
        return url;
    }

    static synchronized List<SubscriptionNode> nodes() {
        ensureLoaded();
        return nodes;
    }

    /** The node Xray should use, or null to fall back to the outbound bundled in assets/config.json. */
    static synchronized SubscriptionNode selectedNode() {
        ensureLoaded();
        for (SubscriptionNode n : nodes) if (n.key().equals(selectedKey)) return n;
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    static synchronized void select(SubscriptionNode node) {
        ensureLoaded();
        selectedKey = node.key();
        sp().edit().putString("selected_key", selectedKey).apply();
    }

    /** Forgets the subscription so the bundled default outbound is used again. */
    static synchronized void clear() {
        ensureLoaded();
        url = "";
        selectedKey = "";
        nodes = Collections.emptyList();
        sp().edit().clear().apply();
    }

    /**
     * Downloads, parses and stores the subscription. Blocking: call from a background thread.
     * Existing data is left untouched if anything fails.
     *
     * @return number of nodes found
     */
    static int update(String subscriptionUrl) throws IOException {
        String u = subscriptionUrl == null ? "" : subscriptionUrl.trim();
        if (u.isEmpty()) throw new IOException("请输入订阅地址");
        List<SubscriptionNode> parsed = SubscriptionParser.parse(download(u));
        if (parsed.isEmpty()) throw new IOException("未找到支持的节点（VLESS / VMess / Trojan）");

        synchronized (SubscriptionStore.class) {
            ensureLoaded();
            String keep = selectedKey;
            boolean stillThere = false;
            for (SubscriptionNode n : parsed) if (n.key().equals(keep)) { stillThere = true; break; }
            if (!stillThere) keep = parsed.get(0).key();

            url = u;
            selectedKey = keep;
            nodes = Collections.unmodifiableList(parsed);
            try {
                JSONArray a = new JSONArray();
                for (SubscriptionNode n : parsed) a.put(n.toJson());
                sp().edit().putString("url", url).putString("nodes", a.toString())
                        .putString("selected_key", selectedKey).apply();
            } catch (Exception e) {
                throw new IOException("保存订阅失败：" + e.getMessage(), e);
            }
        }
        return parsed.size();
    }

    private static String download(String subscriptionUrl) throws IOException {
        URL u;
        try {
            u = new URL(subscriptionUrl);
        } catch (MalformedURLException e) {
            throw new IOException("订阅地址格式不正确");
        }
        if (!"https".equalsIgnoreCase(u.getProtocol())) throw new IOException("仅支持 https 订阅地址");

        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        try {
            c.setConnectTimeout(15_000);
            c.setReadTimeout(20_000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            // No manual Accept-Encoding: HttpURLConnection then transparently un-gzips the body.
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("订阅请求失败：HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BODY) throw new IOException("订阅内容过大");
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
