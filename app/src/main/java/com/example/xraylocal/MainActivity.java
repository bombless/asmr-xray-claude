package com.example.xraylocal;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Single-activity shell: top bar + content stack (works grid → work detail / settings) + mini player.
 * All networking goes through {@link Net}, which honours the Xray toggle in {@link AppSettings}.
 */
public class MainActivity extends Activity {
    private static final class Screen {
        View view;
        String title;
        DetailScreen detail;
        boolean isSettings;
        int settingsRev;
    }

    private final ArrayList<Screen> stack = new ArrayList<>();
    private FrameLayout content;
    private TextView titleView;
    private Button backButton;
    private Button settingsButton;
    private WorksScreen works;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFFFFFFF);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });

        root.addView(buildTopBar(), new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(new PlayerBar(this), new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

        if (AppSettings.useXray()) XrayCore.ensureStarted(); // warm up while the UI appears

        works = new WorksScreen(this, this::openDetail);
        Screen s = new Screen();
        s.view = works.view();
        s.title = "ASMR Online";
        push(s);
        works.reload();
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFFFFFFFF);
        bar.setElevation(Ui.dp(this, 3));

        backButton = flatButton("‹", 24);
        backButton.setOnClickListener(v -> goBack());
        bar.addView(backButton, new LinearLayout.LayoutParams(Ui.dp(this, 48), -1));

        titleView = new TextView(this);
        titleView.setTextSize(17);
        titleView.setTextColor(Ui.TEXT);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.leftMargin = Ui.dp(this, 8);
        bar.addView(titleView, tp);

        settingsButton = flatButton("⚙", 20);
        settingsButton.setOnClickListener(v -> openSettings());
        bar.addView(settingsButton, new LinearLayout.LayoutParams(Ui.dp(this, 52), -1));
        return bar;
    }

    private Button flatButton(String text, float size) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(size);
        b.setAllCaps(false);
        b.setTextColor(Ui.ACCENT);
        b.setBackgroundColor(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    // ------------------------------------------------------------ navigation

    private void openDetail(AsmrApi.Work w) {
        DetailScreen d = new DetailScreen(this, w);
        Screen s = new Screen();
        s.view = d.view();
        s.title = d.title();
        s.detail = d;
        push(s);
    }

    private void openSettings() {
        if (!stack.isEmpty() && stack.get(stack.size() - 1).isSettings) return;
        SettingsScreen st = new SettingsScreen(this);
        Screen s = new Screen();
        s.view = st.view();
        s.title = "设置";
        s.isSettings = true;
        s.settingsRev = AppSettings.rev();
        push(s);
    }

    private void push(Screen s) {
        stack.add(s);
        render();
    }

    private void pop() {
        if (stack.size() <= 1) return;
        Screen s = stack.remove(stack.size() - 1);
        if (s.detail != null) s.detail.destroy();
        // Changing the Xray switch / API base invalidates what is on screen: reload the list.
        if (s.isSettings && AppSettings.rev() != s.settingsRev) works.reload();
        render();
    }

    private boolean goBack() {
        if (stack.isEmpty()) return false;
        Screen top = stack.get(stack.size() - 1);
        if (top.detail != null && top.detail.back()) return true;
        if (stack.size() > 1) { pop(); return true; }
        return false;
    }

    private void render() {
        Screen top = stack.get(stack.size() - 1);
        content.removeAllViews();
        content.addView(top.view, new FrameLayout.LayoutParams(-1, -1));
        titleView.setText(top.title);
        backButton.setVisibility(stack.size() > 1 ? View.VISIBLE : View.INVISIBLE);
        settingsButton.setVisibility(top.isSettings ? View.INVISIBLE : View.VISIBLE);
    }

    @Override public void onBackPressed() {
        if (!goBack()) super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (isFinishing()) PlayerController.get().stop();
        super.onDestroy();
    }
}
