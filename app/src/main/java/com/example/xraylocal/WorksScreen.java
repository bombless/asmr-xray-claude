package com.example.xraylocal;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Grid of works (thumbnail + title) with endless paging. The data source is pluggable via {@link Loader}. */
final class WorksScreen {
    interface OnOpen { void open(AsmrApi.Work work); }
    /** Loads one page (1-based). Called on a background thread. */
    interface Loader { AsmrApi.Page load(int page) throws Exception; }
    /** Reports the server-side total after a page loaded; -1 while unknown (reloading / failed). */
    interface OnTotal { void total(int total); }

    private final Activity activity;
    private final OnOpen onOpen;
    private final FrameLayout root;
    private final GridView grid;
    private final TextView status;
    private final List<AsmrApi.Work> items = new ArrayList<>();
    private final Adapter adapter = new Adapter();

    private Loader loader;
    private OnTotal onTotal;
    private int nextPage = 1;
    private boolean loading;
    private boolean end;
    private int total;
    private int gen;

    WorksScreen(Activity activity, OnOpen onOpen) {
        this(activity, onOpen, AsmrApi::works);
    }

    WorksScreen(Activity activity, OnOpen onOpen, Loader loader) {
        this.activity = activity;
        this.onOpen = onOpen;
        this.loader = loader;
        int pad = Ui.dp(activity, 8);

        root = new FrameLayout(activity);
        root.setBackgroundColor(Ui.BG);

        grid = new GridView(activity);
        grid.setNumColumns(2);
        grid.setHorizontalSpacing(pad);
        grid.setVerticalSpacing(pad);
        grid.setPadding(pad, pad, pad, pad);
        grid.setClipToPadding(false);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < items.size()) onOpen.open(items.get(pos));
        });
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) { }
            @Override public void onScroll(AbsListView v, int first, int visible, int count) {
                if (count > 0 && first + visible >= count - 4) loadMore();
            }
        });
        root.addView(grid, new FrameLayout.LayoutParams(-1, -1));

        status = new TextView(activity);
        status.setGravity(Gravity.CENTER);
        status.setTextColor(Ui.SUB);
        status.setTextSize(14);
        status.setPadding(pad * 3, pad * 3, pad * 3, pad * 3);
        status.setOnClickListener(v -> { if (items.isEmpty()) reload(); else loadMore(); });
        root.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        status.setVisibility(View.GONE);
    }

    View view() { return root; }

    void setOnTotal(OnTotal l) { onTotal = l; }

    /** Swap the data source (e.g. another filter/sort). Call {@link #reload()} afterwards. */
    void setLoader(Loader l) { loader = l; }

    void reload() {
        gen++;
        items.clear();
        nextPage = 1;
        end = false;
        loading = false;
        total = 0;
        adapter.notifyDataSetChanged();
        reportTotal(-1);
        loadMore();
    }

    private void reportTotal(int n) {
        if (onTotal != null) onTotal.total(n);
    }

    private void loadMore() {
        if (loading || end) return;
        loading = true;
        final int myGen = gen;
        final int page = nextPage;
        final Loader ld = loader;
        if (items.isEmpty()) showStatus("加载中…");
        Thread t = new Thread(() -> {
            try {
                final AsmrApi.Page p = ld.load(page);
                activity.runOnUiThread(() -> {
                    if (myGen != gen) return;
                    items.addAll(p.works);
                    total = p.total;
                    nextPage = page + 1;
                    end = p.works.isEmpty() || (total > 0 && items.size() >= total);
                    loading = false;
                    adapter.notifyDataSetChanged();
                    status.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                    if (items.isEmpty()) status.setText("没有内容");
                    reportTotal(total);
                });
            } catch (final Throwable e) {
                activity.runOnUiThread(() -> {
                    if (myGen != gen) return;
                    loading = false;
                    String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    if (items.isEmpty()) {
                        showStatus("加载失败：" + msg + "\n\n点击重试");
                    } else {
                        android.widget.Toast.makeText(activity, "加载更多失败：" + msg, android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "works-load");
        t.setDaemon(true);
        t.start();
    }

    private void showStatus(String text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return items.get(i).id; }

        @Override public View getView(int pos, View convert, ViewGroup parent) {
            Cell c;
            if (convert == null) {
                c = new Cell();
                convert = c.root;
                convert.setTag(c);
            } else {
                c = (Cell) convert.getTag();
            }
            AsmrApi.Work w = items.get(pos);
            c.title.setText(w.title);
            StringBuilder sub = new StringBuilder(w.sourceId);
            if (w.duration > 0) sub.append(" · ").append(Ui.duration(w.duration));
            if (w.hasSubtitle) sub.append(" · 字幕");
            c.sub.setText(sub);
            if (w.progress.isEmpty()) {
                c.mark.setVisibility(View.GONE);
            } else {
                StringBuilder m = new StringBuilder(AsmrApi.progressLabel(w.progress));
                if (!w.updatedAt.isEmpty()) m.append(" · ").append(w.updatedAt);
                if (w.userRating > 0) m.append(" · ★").append(w.userRating);
                c.mark.setText(m);
                c.mark.setVisibility(View.VISIBLE);
            }
            ImageLoader.load(c.image, w.thumbUrl);
            return convert;
        }
    }

    private final class Cell {
        final LinearLayout root;
        final Ui.RatioImageView image;
        final TextView title;
        final TextView sub;
        final TextView mark;

        Cell() {
            int p = Ui.dp(activity, 8);
            root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackground(Ui.round(activity, Ui.CARD, 10));
            root.setClipToOutline(true);
            image = new Ui.RatioImageView(activity, 1f);
            root.addView(image, new LinearLayout.LayoutParams(-1, -2));

            title = new TextView(activity);
            title.setTextColor(Ui.TEXT);
            title.setTextSize(13);
            title.setMaxLines(2);
            title.setMinLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setPadding(p, p, p, 0);
            root.addView(title, new LinearLayout.LayoutParams(-1, -2));

            sub = new TextView(activity);
            sub.setTextColor(Ui.SUB);
            sub.setTextSize(11);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sub.setPadding(p, Ui.dp(activity, 2), p, p);
            root.addView(sub, new LinearLayout.LayoutParams(-1, -2));

            // Third line, only visible for progress-list items. Pulled up into sub's bottom padding.
            mark = new TextView(activity);
            mark.setTextColor(Ui.ACCENT);
            mark.setTextSize(11);
            mark.setSingleLine(true);
            mark.setEllipsize(android.text.TextUtils.TruncateAt.END);
            mark.setPadding(p, 0, p, p);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
            mp.topMargin = -(p - Ui.dp(activity, 2));
            root.addView(mark, mp);
            mark.setVisibility(View.GONE);
        }
    }
}
