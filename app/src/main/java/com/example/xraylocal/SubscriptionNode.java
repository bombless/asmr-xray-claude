package com.example.xraylocal;

import org.json.JSONException;
import org.json.JSONObject;

/** One proxy node parsed from a subscription (vmess / vless / trojan). Immutable. */
final class SubscriptionNode {
    final int id;
    final String name;
    final String protocol;   // "vmess" | "vless" | "trojan"
    final String address;
    final int port;
    final String uuid;
    final String password;
    final int alterId;
    final String network;    // "tcp" | "ws" | ...
    final String tls;        // vmess only: "tls" or ""
    final String security;   // vless / trojan: "none" | "tls" | "reality"
    final String type;
    final String sni;
    final String fingerprint;
    final String publicKey;
    final String shortId;
    final String flow;
    final String path;
    final String host;

    private SubscriptionNode(Builder b) {
        id = b.id; name = b.name; protocol = b.protocol; address = b.address; port = b.port;
        uuid = b.uuid; password = b.password; alterId = b.alterId; network = b.network;
        tls = b.tls; security = b.security; type = b.type; sni = b.sni; fingerprint = b.fingerprint;
        publicKey = b.publicKey; shortId = b.shortId; flow = b.flow; path = b.path; host = b.host;
    }

    SubscriptionNode withId(int newId) {
        return toBuilder().id(newId).build();
    }

    /** Identity that stays the same when a refreshed subscription reorders its nodes. */
    String key() {
        return protocol + "|" + address + ":" + port + "|" + name;
    }

    /** Short one-line description for lists, e.g. "vmess · ws · tls". */
    String summary() {
        StringBuilder sb = new StringBuilder(protocol).append(" · ").append(network);
        String sec = "vmess".equals(protocol) ? ("tls".equals(tls) ? "tls" : "") : security;
        if (sec != null && !sec.isEmpty() && !"none".equals(sec)) sb.append(" · ").append(sec);
        return sb.toString();
    }

    // ---------------------------------------------------------------- JSON persistence

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id); o.put("name", name); o.put("protocol", protocol);
        o.put("address", address); o.put("port", port);
        o.put("uuid", uuid); o.put("password", password); o.put("alterId", alterId);
        o.put("network", network); o.put("tls", tls); o.put("security", security);
        o.put("type", type); o.put("sni", sni); o.put("fingerprint", fingerprint);
        o.put("publicKey", publicKey); o.put("shortId", shortId); o.put("flow", flow);
        o.put("path", path); o.put("host", host);
        return o;
    }

    static SubscriptionNode fromJson(JSONObject o) throws JSONException {
        return new Builder()
                .id(o.getInt("id")).name(o.getString("name")).protocol(o.getString("protocol"))
                .address(o.getString("address")).port(o.getInt("port"))
                .uuid(o.optString("uuid")).password(o.optString("password")).alterId(o.optInt("alterId"))
                .network(o.optString("network", "tcp")).tls(o.optString("tls"))
                .security(o.optString("security", "none")).type(o.optString("type", "tcp"))
                .sni(o.optString("sni")).fingerprint(o.optString("fingerprint"))
                .publicKey(o.optString("publicKey")).shortId(o.optString("shortId"))
                .flow(o.optString("flow")).path(o.optString("path")).host(o.optString("host"))
                .build();
    }

    // ---------------------------------------------------------------- builder

    Builder toBuilder() {
        return new Builder().id(id).name(name).protocol(protocol).address(address).port(port)
                .uuid(uuid).password(password).alterId(alterId).network(network).tls(tls)
                .security(security).type(type).sni(sni).fingerprint(fingerprint)
                .publicKey(publicKey).shortId(shortId).flow(flow).path(path).host(host);
    }

    static final class Builder {
        private int id;
        private String name = "Node", protocol = "", address = "";
        private int port;
        private String uuid = "", password = "";
        private int alterId;
        private String network = "tcp", tls = "", security = "none", type = "tcp";
        private String sni = "", fingerprint = "", publicKey = "", shortId = "", flow = "", path = "", host = "";

        Builder id(int v) { id = v; return this; }
        Builder name(String v) { name = v; return this; }
        Builder protocol(String v) { protocol = v; return this; }
        Builder address(String v) { address = v; return this; }
        Builder port(int v) { port = v; return this; }
        Builder uuid(String v) { uuid = v; return this; }
        Builder password(String v) { password = v; return this; }
        Builder alterId(int v) { alterId = v; return this; }
        Builder network(String v) { network = v; return this; }
        Builder tls(String v) { tls = v; return this; }
        Builder security(String v) { security = v; return this; }
        Builder type(String v) { type = v; return this; }
        Builder sni(String v) { sni = v; return this; }
        Builder fingerprint(String v) { fingerprint = v; return this; }
        Builder publicKey(String v) { publicKey = v; return this; }
        Builder shortId(String v) { shortId = v; return this; }
        Builder flow(String v) { flow = v; return this; }
        Builder path(String v) { path = v; return this; }
        Builder host(String v) { host = v; return this; }

        SubscriptionNode build() { return new SubscriptionNode(this); }
    }
}
