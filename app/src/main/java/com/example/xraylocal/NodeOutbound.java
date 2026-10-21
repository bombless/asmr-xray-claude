package com.example.xraylocal;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Turns a {@link SubscriptionNode} into the Xray outbound object tagged "proxy". */
final class NodeOutbound {
    private NodeOutbound() {}

    /** @throws IllegalArgumentException if the node uses a protocol/transport this app cannot build. */
    static JSONObject build(SubscriptionNode n) throws JSONException {
        JSONObject out = new JSONObject();
        out.put("tag", "proxy");
        out.put("protocol", n.protocol);
        switch (n.protocol) {
            case "vmess": {
                JSONObject user = new JSONObject();
                user.put("id", n.uuid);
                user.put("alterId", n.alterId);
                user.put("security", "auto");
                out.put("settings", vnext(n, user));
                out.put("streamSettings", stream(n, "tls".equals(n.tls) ? "tls" : "none"));
                break;
            }
            case "vless": {
                JSONObject user = new JSONObject();
                user.put("id", n.uuid);
                user.put("encryption", "none");
                if (!n.flow.isEmpty()) user.put("flow", n.flow);
                out.put("settings", vnext(n, user));
                out.put("streamSettings", stream(n, n.security));
                break;
            }
            case "trojan": {
                JSONObject server = new JSONObject();
                server.put("address", n.address);
                server.put("port", n.port);
                server.put("password", n.password);
                out.put("settings", new JSONObject().put("servers", new JSONArray().put(server)));
                out.put("streamSettings", stream(n, n.security.isEmpty() ? "tls" : n.security));
                break;
            }
            default:
                throw new IllegalArgumentException("不支持的协议：" + n.protocol);
        }
        return out;
    }

    private static JSONObject vnext(SubscriptionNode n, JSONObject user) throws JSONException {
        JSONObject server = new JSONObject();
        server.put("address", n.address);
        server.put("port", n.port);
        server.put("users", new JSONArray().put(user));
        return new JSONObject().put("vnext", new JSONArray().put(server));
    }

    private static JSONObject stream(SubscriptionNode n, String security) throws JSONException {
        if (!"tcp".equals(n.network) && !"ws".equals(n.network)) {
            throw new IllegalArgumentException("暂不支持的传输方式：" + n.network + "（仅支持 tcp / ws）");
        }
        JSONObject s = new JSONObject();
        s.put("network", n.network);
        s.put("security", security);
        if ("tls".equals(security)) {
            JSONObject tls = new JSONObject();
            // Like most clients: without an explicit SNI, fall back to the ws Host header.
            String sni = !n.sni.isEmpty() ? n.sni : n.host;
            if (!sni.isEmpty()) tls.put("serverName", sni);
            if (!n.fingerprint.isEmpty()) tls.put("fingerprint", n.fingerprint);
            s.put("tlsSettings", tls);
        } else if ("reality".equals(security)) {
            JSONObject r = new JSONObject();
            r.put("serverName", n.sni);
            r.put("fingerprint", n.fingerprint.isEmpty() ? "chrome" : n.fingerprint);
            r.put("publicKey", n.publicKey);
            r.put("shortId", n.shortId);
            s.put("realitySettings", r);
        }
        if ("ws".equals(n.network)) {
            JSONObject ws = new JSONObject();
            ws.put("path", n.path.isEmpty() ? "/" : n.path);
            if (!n.host.isEmpty()) ws.put("headers", new JSONObject().put("Host", n.host));
            s.put("wsSettings", ws);
        }
        return s;
    }
}
