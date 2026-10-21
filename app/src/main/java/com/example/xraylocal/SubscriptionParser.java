package com.example.xraylocal;

import android.util.Base64;

import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parses a subscription body into nodes. Accepts either a plain list of share links or the usual
 * base64-encoded list. Supports vmess:// (base64 JSON), vless:// and trojan:// links; lines it
 * cannot understand are skipped.
 */
final class SubscriptionParser {
    private static final Pattern SCHEME_LINE = Pattern.compile("(?im)^(vless|vmess|trojan)://");

    private SubscriptionParser() {}

    static List<SubscriptionNode> parse(String raw) {
        String text = raw.replace("\uFEFF", "").replace("\r", "").trim();
        if (!SCHEME_LINE.matcher(text).find()) {
            try {
                String decoded = decodeBase64(text).replace("\r", "");
                if (SCHEME_LINE.matcher(decoded).find()) text = decoded;
            } catch (RuntimeException ignored) {
                // not base64 either: fall through, nothing will match below
            }
        }
        List<SubscriptionNode> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            try {
                SubscriptionNode n = parseLine(line);
                if (n != null) out.add(n.withId(out.size() + 1));
            } catch (Exception ignored) {
                // one bad line must not lose the rest of the subscription
            }
        }
        return out;
    }

    private static SubscriptionNode parseLine(String line) throws Exception {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.startsWith("vmess://")) return parseVmess(line.substring("vmess://".length()));
        if (lower.startsWith("vless://")) return parseUri(line.substring("vless://".length()), "vless");
        if (lower.startsWith("trojan://")) return parseUri(line.substring("trojan://".length()), "trojan");
        return null;
    }

    // ---------------------------------------------------------------- vmess

    private static SubscriptionNode parseVmess(String body) throws Exception {
        int hash = body.indexOf('#');
        if (hash >= 0) body = body.substring(0, hash);
        JSONObject j = new JSONObject(decodeBase64(body.trim()));
        String address = j.optString("add").trim();
        int port = j.optInt("port");
        String id = j.optString("id").trim();
        if (address.isEmpty() || port <= 0 || port > 65535 || id.isEmpty()) {
            throw new IllegalArgumentException("incomplete vmess node");
        }
        String ps = j.optString("ps").trim();
        return new SubscriptionNode.Builder()
                .name(ps.isEmpty() ? "Node" : ps).protocol("vmess").address(address).port(port)
                .uuid(id).alterId(j.optInt("aid", 0))
                .network(orDefault(j.optString("net"), "tcp"))
                .tls(j.optString("tls")).type(orDefault(j.optString("type"), "none"))
                .host(j.optString("host")).path(j.optString("path"))
                .sni(j.optString("sni")).fingerprint(j.optString("fp"))
                .build();
    }

    // ---------------------------------------------------------------- vless / trojan

    /** {@code rest} is everything after "scheme://": userinfo@host:port?query#fragment. */
    private static SubscriptionNode parseUri(String rest, String protocol) {
        String fragment = "";
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            fragment = decodeLiteral(rest.substring(hash + 1));
            rest = rest.substring(0, hash);
        }
        String queryString = "";
        int q = rest.indexOf('?');
        if (q >= 0) {
            queryString = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        int slash = rest.indexOf('/');
        if (slash >= 0) rest = rest.substring(0, slash);

        int at = rest.lastIndexOf('@');
        String userInfo = at >= 0 ? decodeLiteral(rest.substring(0, at)) : "";
        String hostPort = at >= 0 ? rest.substring(at + 1) : rest;

        String host;
        int port;
        if (hostPort.startsWith("[")) { // IPv6 literal
            int end = hostPort.indexOf(']');
            if (end < 0) throw new IllegalArgumentException("bad IPv6 host");
            host = hostPort.substring(1, end);
            String tail = hostPort.substring(end + 1);
            if (!tail.startsWith(":")) throw new IllegalArgumentException("missing port");
            port = Integer.parseInt(tail.substring(1).trim());
        } else {
            int colon = hostPort.lastIndexOf(':');
            if (colon < 0) throw new IllegalArgumentException("missing port");
            host = hostPort.substring(0, colon).trim();
            port = Integer.parseInt(hostPort.substring(colon + 1).trim());
        }
        if (host.isEmpty() || port <= 0 || port > 65535) throw new IllegalArgumentException("bad host/port");
        if (userInfo.isEmpty()) throw new IllegalArgumentException("missing credentials");

        Map<String, String> query = parseQuery(queryString);
        String name = fragment.trim().isEmpty() ? "Node" : fragment.trim();
        SubscriptionNode.Builder b = new SubscriptionNode.Builder()
                .name(name).protocol(protocol).address(host).port(port)
                .network(orDefault(query.get("type"), "tcp"))
                .sni(nz(query.get("sni"))).path(nz(query.get("path"))).host(nz(query.get("host")));
        if ("vless".equals(protocol)) {
            b.uuid(userInfo).security(orDefault(query.get("security"), "none"))
                    .fingerprint(nz(query.get("fp"))).publicKey(nz(query.get("pbk")))
                    .shortId(nz(query.get("sid"))).flow(nz(query.get("flow")));
        } else {
            b.password(userInfo).security(orDefault(query.get("security"), "tls"));
        }
        return b.build();
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, String> parseQuery(String s) {
        Map<String, String> m = new HashMap<>();
        for (String pair : s.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            m.put(decodeForm(k), decodeForm(v));
        }
        return m;
    }

    /** application/x-www-form-urlencoded decoding ('+' is a space); raw text if malformed. */
    private static String decodeForm(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return s;
        }
    }

    /** Percent-decoding where '+' stays a literal plus (credentials, node names). */
    private static String decodeLiteral(String s) {
        return decodeForm(s.replace("+", "%2B"));
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String orDefault(String s, String def) {
        return s == null || s.trim().isEmpty() ? def : s.trim();
    }

    /** Tolerates url-safe alphabet, missing padding and embedded whitespace. */
    static String decodeBase64(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c)) continue;
            sb.append(c == '-' ? '+' : c == '_' ? '/' : c);
        }
        while (sb.length() % 4 != 0) sb.append('=');
        return new String(Base64.decode(sb.toString(), Base64.DEFAULT), StandardCharsets.UTF_8);
    }
}
