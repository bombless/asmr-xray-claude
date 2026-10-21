package com.example.xraylocal;

import org.json.JSONArray;
import org.json.JSONObject;

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

    static String cover(long id, String type) {
        return AppSettings.apiBase() + "/api/cover/" + id + ".jpg?type=" + type;
    }

    static Page works(int page) throws Exception {
        String url = AppSettings.apiBase()
                + "/api/works?order=create_date&sort=desc&page=" + page + "&pageSize=20&subtitle=0";
        JSONObject j = new JSONObject(Net.getText(url, null));
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
        String text = Net.getText(AppSettings.apiBase() + "/api/tracks/" + workId + "?v=2", null).trim();
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
