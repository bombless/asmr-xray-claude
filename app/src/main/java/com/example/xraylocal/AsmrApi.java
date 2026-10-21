package com.example.xraylocal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Client for the public asmr.one API (api.asmr-200.com). All traffic goes through {@link Net}. */
final class AsmrApi {
    private AsmrApi() {}

    static final class Work {
        long id;
        String title = "";
        String sourceId = "";
        String circle = "";
        int duration;          // seconds
        boolean hasSubtitle;
        final List<String> vas = new ArrayList<>();
        String thumbUrl;
        String coverUrl;
    }

    static final class Page {
        final List<Work> works = new ArrayList<>();
        int total;
    }

    static final class Node {
        String type = "";      // folder | audio | text | image | other
        String title = "";
        String streamUrl = "";
        String lowUrl = "";
        long size;
        double duration;       // seconds
        final List<Node> children = new ArrayList<>();

        boolean isFolder() { return "folder".equals(type); }
        boolean isAudio() { return "audio".equals(type) && !streamUrl.isEmpty(); }
    }

    /**
     * GET against the API host with the login token attached. If the server rejects the token (401) the
     * dead session is dropped, so the next request falls back to guest access instead of failing forever.
     * Never use this for covers or audio: those hosts must not receive the token.
     */
    private static String apiGet(String url) throws IOException {
        String sent = AuthStore.token();
        try {
            return Net.getText(url, AuthStore.headers(sent));
        } catch (Net.HttpException e) {
            if (e.status == 401 && !sent.isEmpty()) {
                AuthStore.clearIfCurrent(sent);
                throw new IOException("登录已过期，请在设置中重新登录", e);
            }
            throw e;
        }
    }

    /**
     * Logs in via the site's own endpoint (POST /api/auth/me) and stores the token. Blocking: call from a
     * background thread. The thrown message is user-facing (the server's own text when it sends one).
     */
    static void login(String name, String password) throws Exception {
        JSONObject req = new JSONObject();
        req.put("name", name);
        req.put("password", password);
        // No Authorization header here: the site itself sends none (an old token must not interfere).
        Net.Result r = Net.postJson(AppSettings.apiBase() + "/api/auth/me", req.toString(), null);
        JSONObject j = parseObject(r.body);
        if (r.status != 200 || j == null) {
            String err = j == null ? "" : j.optString("error", "");
            throw new Exception(!err.isEmpty() ? err : "登录失败（HTTP " + r.status + "）");
        }
        JSONObject user = j.optJSONObject("user");
        String shownName = user != null ? user.optString("name", name) : name;
        try {
            AuthStore.save(j.optString("token", ""), shownName);
        } catch (IllegalArgumentException e) {
            throw new Exception(e.getMessage());
        }
    }

    private static JSONObject parseObject(String s) {
        try { return new JSONObject(s); } catch (Exception e) { return null; }
    }

    static String cover(long id, String type) {
        return AppSettings.apiBase() + "/api/cover/" + id + ".jpg?type=" + type;
    }

    static Page works(int page) throws Exception {
        String url = AppSettings.apiBase()
                + "/api/works?order=create_date&sort=desc&page=" + page + "&pageSize=20&subtitle=0";
        JSONObject j = new JSONObject(apiGet(url));
        JSONArray arr = j.getJSONArray("works");
        Page p = new Page();
        JSONObject pg = j.optJSONObject("pagination");
        p.total = pg != null ? pg.optInt("totalCount", 0) : 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Work w = new Work();
            w.id = o.getLong("id");
            w.title = o.optString("title", "");
            w.sourceId = o.optString("source_id", "RJ" + w.id);
            JSONObject c = o.optJSONObject("circle");
            w.circle = c != null ? c.optString("name", "") : o.optString("name", "");
            w.duration = o.optInt("duration", 0);
            w.hasSubtitle = o.optBoolean("has_subtitle", false);
            JSONArray vas = o.optJSONArray("vas");
            if (vas != null) {
                for (int k = 0; k < vas.length(); k++) {
                    JSONObject v = vas.optJSONObject(k);
                    if (v != null && !v.optString("name").isEmpty()) w.vas.add(v.optString("name"));
                }
            }
            w.thumbUrl = cover(w.id, "240x240");
            w.coverUrl = cover(w.id, "main");
            p.works.add(w);
        }
        return p;
    }

    static List<Node> tracks(long workId) throws Exception {
        String text = apiGet(AppSettings.apiBase() + "/api/tracks/" + workId + "?v=2").trim();
        if (text.startsWith("{")) {
            JSONObject err = new JSONObject(text);
            throw new Exception(err.optString("error", "无法获取文件列表"));
        }
        return parseNodes(new JSONArray(text));
    }

    private static List<Node> parseNodes(JSONArray arr) {
        List<Node> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Node n = new Node();
            n.type = o.optString("type", "");
            n.title = o.optString("title", "");
            n.streamUrl = o.optString("mediaStreamUrl", "");
            n.lowUrl = o.optString("streamLowQualityUrl", "");
            n.size = o.optLong("size", 0);
            n.duration = o.optDouble("duration", 0);
            JSONArray ch = o.optJSONArray("children");
            if (ch != null) n.children.addAll(parseNodes(ch));
            out.add(n);
        }
        return out;
    }
}
