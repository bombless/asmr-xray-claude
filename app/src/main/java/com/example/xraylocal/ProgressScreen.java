package com.example.xraylocal;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The site's "我的进度" page: the logged-in user's works filtered by listening state
 * (想听 / 在听 / 听过 / 重听 / 搁置) with a sort field + direction. The grid itself is a {@link WorksScreen}
 * fed by {@link AsmrApi#progress}; browsing only, marks are not edited here.
 */
final class ProgressScreen {
    /** Sort fields the review API accepts: {api value, label}. First one is the site's default (marked time). */
    private static final String[][] SORTS = {
            {"updated_at", "标记时间"},
            {"userRating", "我的评价"},
            {"release", "发售日"},
            {"rate_average_2dp", "平均评分"},
            {"dl_count", "销量"},
            {"price", "价格"},
    };

    private final Activity activity;
    private final LinearLayout root;
    private final WorksScreen works;
    private final TextView[] chips = new TextView[AsmrApi.PROGRESS_FILTERS.length];
    private final TextView sortButton;
    private final TextView dirButton;
    private final TextView totalView;

    private int filterIdx;   // index into AsmrApi.PROGRESS_FILTERS
    private int sortIdx;     // index into SORTS
    private boolean desc = true;

    ProgressScreen(Activity activity, WorksScreen.OnOpen onOpen) {
        this.activity = activity;
        int pad = Ui.dp(activity, 8);

        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        // ---- status filter chips (horizontally scrollable)
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(activity);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(pad, pad, pad, 0);
        for (int i = 0; i < chips.length; i++) {
            final int idx = i;
            TextView c = new TextView(activity);
            c.setText(AsmrApi.PROGRESS_FILTERS[i][1]);
            c.setTextSize(14);
            c.setGravity(Gravity.CENTER);
            c.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 7), Ui.dp(activity, 16), Ui.dp(activity, 7));
            c.setOnClickListener(v -> selectFilter(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = pad;
            chipRow.addView(c, lp);
            chips[i] = c;
        }
        scroll.addView(chipRow);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        // ---- sort field / direction / total
        LinearLayout tools = new LinearLayout(activity);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(pad, pad, pad, 0);

        sortButton = tool("");
        sortButton.setOnClickListener(v -> pickSort());
        tools.addView(sortButton, new LinearLayout.LayoutParams(-2, -2));

        dirButton = tool("");
        dirButton.setOnClickListener(v -> { desc = !desc; apply(); });
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-2, -2);
        dp.leftMargin = pad;
        tools.addView(dirButton, dp);

        totalView = new TextView(activity);
        totalView.setTextSize(12);
        totalView.setTextColor(Ui.SUB);
        totalView.setGravity(Gravity.END);
        tools.addView(totalView, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(tools, new LinearLayout.LayoutParams(-1, -2));

        // ---- grid
        works = new WorksScreen(activity, onOpen, loader());
        works.setOnTotal(n -> totalView.setText(n < 0 ? "" : "共 " + n + " 部"));
        root.addView(works.view(), new LinearLayout.LayoutParams(-1, 0, 1f));

        refreshControls();
    }

    View view() { return root; }

    /** Reloads the current filter/sort (also used after login changes). */
    void reload() { apply(); }

    private TextView tool(String text) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(Ui.ACCENT);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.round(activity, Ui.CARD, 14));
        t.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 6), Ui.dp(activity, 12), Ui.dp(activity, 6));
        return t;
    }

    private void selectFilter(int idx) {
        if (idx == filterIdx) return;
        filterIdx = idx;
        apply();
    }

    private void pickSort() {
        String[] labels = new String[SORTS.length];
        for (int i = 0; i < SORTS.length; i++) labels[i] = SORTS[i][1];
        new AlertDialog.Builder(activity)
                .setTitle("排序")
                .setSingleChoiceItems(labels, sortIdx, (d, which) -> {
                    d.dismiss();
                    if (which != sortIdx) {
                        sortIdx = which;
                        apply();
                    }
                })
                .show();
    }

    private void apply() {
        works.setLoader(loader());
        refreshControls();
        works.reload();
    }

    /** Loader bound to a snapshot of the current filter/sort, so in-flight pages of an old selection are ignored. */
    private WorksScreen.Loader loader() {
        final String filter = AsmrApi.PROGRESS_FILTERS[filterIdx][0];
        final String order = SORTS[sortIdx][0];
        final boolean d = desc;
        return page -> AsmrApi.progress(filter, order, d, page);
    }

    private void refreshControls() {
        for (int i = 0; i < chips.length; i++) {
            boolean on = i == filterIdx;
            chips[i].setBackground(Ui.round(activity, on ? Ui.ACCENT : Ui.CARD, 18));
            chips[i].setTextColor(on ? 0xFFFFFFFF : Ui.SUB);
        }
        sortButton.setText(SORTS[sortIdx][1] + " ▾");
        dirButton.setText(desc ? "↓ 降序" : "↑ 升序");
    }
}
