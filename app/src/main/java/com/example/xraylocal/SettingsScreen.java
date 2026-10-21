package com.example.xraylocal;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Settings page: Xray on/off, API base, low-quality stream, connectivity test. */
final class SettingsScreen {
    private final Activity activity;
    private final ScrollView root;
    private final TextView xrayStatus;
    private final TextView testResult;
    private final Button testButton;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refreshStatus();
            ui.postDelayed(this, 1000);
        }
    };

    SettingsScreen(Activity activity) {
        this.activity = activity;
        int pad = Ui.dp(activity, 16);

        root = new ScrollView(activity);
        root.setBackgroundColor(Ui.BG);
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);
        root.addView(col, new ScrollView.LayoutParams(-1, -2));

        // --- Xray toggle -------------------------------------------------
        LinearLayout card1 = card(col);
        Switch sw = new Switch(activity);
        sw.setText("通过 Xray 访问");
        sw.setTextSize(16);
        sw.setTextColor(Ui.TEXT);
        sw.setChecked(AppSettings.useXray());
        card1.addView(sw, new LinearLayout.LayoutParams(-1, -2));
        card1.addView(note("开启：作品列表、封面、音频全部经由应用内置的 Xray 核心拨号（进程内，无 VPN / 本地代理端口）。\n"
                + "关闭：直接使用系统网络连接 asmr.one 的服务器。"));
        xrayStatus = note("");
        card1.addView(xrayStatus);
        sw.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
            AppSettings.setUseXray(on);
            if (on) XrayCore.ensureStarted();
            refreshStatus();
        });

        // --- API base ----------------------------------------------------
        LinearLayout card2 = card(col);
        TextView l2 = new TextView(activity);
        l2.setText("API 地址");
        l2.setTextSize(16);
        l2.setTextColor(Ui.TEXT);
        card2.addView(l2);
        card2.addView(note("站点的 API 域名偶尔会更换，可在这里修改（默认 " + AppSettings.DEFAULT_API_BASE + "）。"));
        final EditText et = new EditText(activity);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setText(AppSettings.apiBase());
        card2.addView(et, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button save = new Button(activity);
        save.setText("保存");
        save.setAllCaps(false);
        save.setOnClickListener(v -> {
            AppSettings.setApiBase(et.getText().toString());
            et.setText(AppSettings.apiBase());
            Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show();
        });
        Button reset = new Button(activity);
        reset.setText("恢复默认");
        reset.setAllCaps(false);
        reset.setOnClickListener(v -> {
            AppSettings.setApiBase(AppSettings.DEFAULT_API_BASE);
            et.setText(AppSettings.apiBase());
        });
        row.addView(save);
        row.addView(reset);
        card2.addView(row);

        // --- audio quality -----------------------------------------------
        LinearLayout card3 = card(col);
        Switch low = new Switch(activity);
        low.setText("优先使用低码率流（m4a）");
        low.setTextSize(16);
        low.setTextColor(Ui.TEXT);
        low.setChecked(AppSettings.lowQuality());
        card3.addView(low, new LinearLayout.LayoutParams(-1, -2));
        card3.addView(note("WAV 文件很大（几十 MB 一首）。开启后若服务器提供低码率版本则改播它，经代理时更省流量、更不易卡顿。"));
        low.setOnCheckedChangeListener((b, on) -> AppSettings.setLowQuality(on));

        // --- connectivity test ---------------------------------------------
        LinearLayout card4 = card(col);
        TextView l4 = new TextView(activity);
        l4.setText("连通性测试");
        l4.setTextSize(16);
        l4.setTextColor(Ui.TEXT);
        card4.addView(l4);
        testButton = new Button(activity);
        testButton.setText("测试 API 连接");
        testButton.setAllCaps(false);
        testButton.setOnClickListener(v -> runTest());
        card4.addView(testButton);
        testResult = note("");
        card4.addView(testResult);

        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { ui.post(tick); }
            @Override public void onViewDetachedFromWindow(View v) { ui.removeCallbacks(tick); }
        });
        refreshStatus();
    }

    View view() { return root; }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(activity);
        c.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(activity, 14);
        c.setPadding(p, p, p, p);
        c.setBackground(Ui.round(activity, Ui.CARD, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(activity, 12);
        parent.addView(c, lp);
        return c;
    }

    private TextView note(String text) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(Ui.SUB);
        t.setPadding(0, Ui.dp(activity, 6), 0, 0);
        return t;
    }

    private void refreshStatus() {
        if (AppSettings.useXray()) {
            xrayStatus.setText("Xray 核心：" + XrayCore.status());
        } else {
            xrayStatus.setText("当前为直连模式（Xray 未使用）");
        }
    }

    private void runTest() {
        testButton.setEnabled(false);
        final boolean xray = AppSettings.useXray();
        testResult.setText("测试中…（" + (xray ? "经 Xray" : "直连") + "）");
        Thread t = new Thread(() -> {
            final long t0 = System.currentTimeMillis();
            String msg;
            try {
                String body = Net.getText(AppSettings.apiBase() + "/api/health", null);
                long ms = System.currentTimeMillis() - t0;
                msg = "✔ 成功 " + ms + " ms（" + (xray ? "经 Xray" : "直连") + "）\n"
                        + (body.length() > 80 ? body.substring(0, 80) + "…" : body);
            } catch (Throwable e) {
                msg = "✘ 失败（" + (xray ? "经 Xray" : "直连") + "）：" + e.getMessage();
            }
            final String out = msg;
            activity.runOnUiThread(() -> {
                testResult.setText(out);
                testButton.setEnabled(true);
            });
        }, "net-test");
        t.setDaemon(true);
        t.start();
    }
}
