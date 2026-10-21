package com.example.xraylocal;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** One work: cover + info header, then the track tree. Folders navigate in place; audio plays. */
final class DetailScreen implements PlayerController.Listener {
    private final Activity activity;
    private final AsmrApi.Work work;
    private final FrameLayout root;
    private final ListView list;
    private final TextView status;
    private final TextView crumb;
    private final Adapter adapter = new Adapter();

    private List<AsmrApi.Node> top = new ArrayList<>();
    /** Folder path from the root; empty = at the top level. */
    private final ArrayList<AsmrApi.Node> path = new ArrayList<>();
    private List<AsmrApi.Node> shown = new ArrayList<>();
    private boolean destroyed;

    DetailScreen(Activity activity, AsmrApi.Work work) {
        this.activity = activity;
        this.work = work;
        int pad = Ui.dp(activity, 12);

        root = new FrameLayout(activity);
        root.setBackgroundColor(Ui.BG);

        list = new ListView(activity);
        list.setDivider(new android.graphics.drawable.ColorDrawable(0x14000000));
        list.setDividerHeight(1);
        list.setHeaderDividersEnabled(false);
        list.addHeaderView(buildHeader(pad), null, false);
        crumb = new TextView(activity);
        crumb.setTextColor(Ui.ACCENT);
        crumb.setTextSize(13);
        crumb.setPadding(pad, Ui.dp(activity, 8), pad, Ui.dp(activity, 8));
        crumb.setBackgroundColor(Ui.CARD);
        crumb.setOnClickListener(v -> back());
        list.addHeaderView(crumb, null, false);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, position, id) -> {
            int i = position - list.getHeaderViewsCount();
            if (i >= 0 && i < shown.size()) onClick(shown.get(i));
        });
        root.addView(list, new FrameLayout.LayoutParams(-1, -1));

        status = new TextView(activity);
        status.setGravity(Gravity.CENTER);
        status.setTextColor(Ui.SUB);
        status.setTextSize(14);
        status.setPadding(pad * 2, pad * 6, pad * 2, pad);
        status.setOnClickListener(v -> loadTracks());
        // sits below the header inside the list's footer area
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(status, lp);
        status.setVisibility(View.GONE);

        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                PlayerController.get().addListener(DetailScreen.this);
                adapter.notifyDataSetChanged();
            }
            @Override public void onViewDetachedFromWindow(View v) {
                PlayerController.get().removeListener(DetailScreen.this);
            }
        });

        updateCrumb();
        loadTracks();
    }

    View view() { return root; }

    String title() { return work.sourceId; }

    void destroy() { destroyed = true; }

    /** Goes up one folder. Returns false when already at the top level (caller leaves the screen). */
    boolean back() {
        if (path.isEmpty()) return false;
        path.remove(path.size() - 1);
        showCurrent();
        return true;
    }

    private View buildHeader(int pad) {
        LinearLayout h = new LinearLayout(activity);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setBackgroundColor(Ui.CARD);

        Ui.RatioImageView cover = new Ui.RatioImageView(activity, 0.75f);
        cover.setBackgroundColor(0xFFE3E5E8);
        h.addView(cover, new LinearLayout.LayoutParams(-1, -2));
        ImageLoader.load(cover, work.coverUrl);

        TextView title = new TextView(activity);
        title.setText(work.title);
        title.setTextColor(Ui.TEXT);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextIsSelectable(true);
        title.setPadding(pad, pad, pad, Ui.dp(activity, 4));
        h.addView(title, new LinearLayout.LayoutParams(-1, -2));

        StringBuilder meta = new StringBuilder(work.sourceId);
        if (!work.circle.isEmpty()) meta.append("  ·  ").append(work.circle);
        if (work.duration > 0) meta.append("  ·  ").append(Ui.duration(work.duration));
        if (work.hasSubtitle) meta.append("  ·  字幕");
        TextView m = new TextView(activity);
        m.setText(meta);
        m.setTextColor(Ui.SUB);
        m.setTextSize(12);
        m.setPadding(pad, 0, pad, work.vas.isEmpty() ? pad : Ui.dp(activity, 2));
        h.addView(m, new LinearLayout.LayoutParams(-1, -2));

        if (!work.vas.isEmpty()) {
            TextView v = new TextView(activity);
            v.setText("CV：" + TextUtils.join("、", work.vas));
            v.setTextColor(Ui.SUB);
            v.setTextSize(12);
            v.setPadding(pad, 0, pad, pad);
            h.addView(v, new LinearLayout.LayoutParams(-1, -2));
        }
        return h;
    }

    private void loadTracks() {
        status.setText("加载文件列表…");
        status.setVisibility(View.VISIBLE);
        Thread t = new Thread(() -> {
            try {
                final List<AsmrApi.Node> nodes = AsmrApi.tracks(work.id);
                activity.runOnUiThread(() -> {
                    if (destroyed) return;
                    top = nodes;
                    path.clear();
                    status.setVisibility(View.GONE);
                    showCurrent();
                    if (nodes.isEmpty()) {
                        status.setText("没有文件");
                        status.setVisibility(View.VISIBLE);
                    }
                });
            } catch (final Throwable e) {
                activity.runOnUiThread(() -> {
                    if (destroyed) return;
                    String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    status.setText("加载失败：" + msg + "\n\n点击重试");
                    status.setVisibility(View.VISIBLE);
                });
            }
        }, "tracks-load");
        t.setDaemon(true);
        t.start();
    }

    private List<AsmrApi.Node> currentChildren() {
        return path.isEmpty() ? top : path.get(path.size() - 1).children;
    }

    private void showCurrent() {
        shown = currentChildren();
        updateCrumb();
        adapter.notifyDataSetChanged();
        list.setSelection(0);
    }

    private void updateCrumb() {
        if (path.isEmpty()) {
            crumb.setText("文件");
            crumb.setClickable(false);
        } else {
            StringBuilder sb = new StringBuilder("‹ 返回上一级    ");
            for (AsmrApi.Node n : path) sb.append(" / ").append(n.title);
            crumb.setText(sb);
            crumb.setClickable(true);
        }
    }

    private void onClick(AsmrApi.Node node) {
        if (node.isFolder()) {
            path.add(node);
            showCurrent();
        } else if (node.isAudio()) {
            List<AsmrApi.Node> queue = new ArrayList<>();
            int start = 0;
            for (AsmrApi.Node n : shown) {
                if (n.isAudio()) {
                    if (n == node) start = queue.size();
                    queue.add(n);
                }
            }
            PlaybackService.requestNotificationPermission(activity);
            PlayerController.get().playQueue(queue, start, work.title, work.circle, work.coverUrl);
        } else {
            Toast.makeText(activity, "暂不支持预览此类文件", Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onPlayerChanged() {
        adapter.notifyDataSetChanged();
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View convert, ViewGroup parent) {
            Row r;
            if (convert == null) {
                r = new Row();
                convert = r.root;
                convert.setTag(r);
            } else {
                r = (Row) convert.getTag();
            }
            AsmrApi.Node n = shown.get(pos);
            PlayerController pc = PlayerController.get();
            boolean current = n.isAudio() && pc.current() == n;

            r.icon.setText(n.isFolder() ? "📁" : n.isAudio() ? (current && pc.isPlaying() ? "▶" : "♪") : "📄");
            r.title.setText(n.title);
            r.title.setTypeface(current ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            r.title.setTextColor(current ? Ui.ACCENT : (n.isFolder() || n.isAudio() ? Ui.TEXT : Ui.SUB));

            StringBuilder meta = new StringBuilder();
            if (n.isFolder()) meta.append(n.children.size()).append(" 项");
            else {
                if (n.duration > 0) meta.append(Ui.duration(n.duration));
                String sz = Ui.size(n.size);
                if (!sz.isEmpty()) meta.append(meta.length() > 0 ? " · " : "").append(sz);
            }
            r.meta.setText(meta);
            r.meta.setVisibility(meta.length() == 0 ? View.GONE : View.VISIBLE);
            r.root.setBackgroundColor(current ? 0x143F51B5 : Ui.CARD);
            return convert;
        }
    }

    private final class Row {
        final LinearLayout root;
        final TextView icon;
        final TextView title;
        final TextView meta;

        Row() {
            int pad = Ui.dp(activity, 12);
            root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(pad, Ui.dp(activity, 10), pad, Ui.dp(activity, 10));

            icon = new TextView(activity);
            icon.setTextSize(18);
            icon.setGravity(Gravity.CENTER);
            icon.setTextColor(Ui.ACCENT);
            root.addView(icon, new LinearLayout.LayoutParams(Ui.dp(activity, 32), -2));

            LinearLayout col = new LinearLayout(activity);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1f);
            cp.leftMargin = Ui.dp(activity, 8);
            root.addView(col, cp);

            title = new TextView(activity);
            title.setTextSize(14);
            title.setMaxLines(3);
            title.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(title, new LinearLayout.LayoutParams(-1, -2));

            meta = new TextView(activity);
            meta.setTextSize(11);
            meta.setTextColor(Ui.SUB);
            col.addView(meta, new LinearLayout.LayoutParams(-1, -2));
        }
    }
}
