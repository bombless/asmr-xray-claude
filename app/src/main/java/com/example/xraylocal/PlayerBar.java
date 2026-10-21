package com.example.xraylocal;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Bottom mini-player: title, seek bar, prev / play-pause / next. Hidden until something is chosen. */
final class PlayerBar extends LinearLayout implements PlayerController.Listener {
    private final PlayerController pc = PlayerController.get();
    private final TextView title;
    private final TextView time;
    private final SeekBar seek;
    private final Button prev;
    private final Button play;
    private final Button next;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean dragging;
    private int ticks;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateProgress();
            ui.postDelayed(this, 500);
        }
    };

    PlayerBar(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setBackgroundColor(0xFFFFFFFF);
        setElevation(Ui.dp(c, 8));
        int p = Ui.dp(c, 10);
        setPadding(p, Ui.dp(c, 6), p, Ui.dp(c, 4));

        title = new TextView(c);
        title.setTextSize(13);
        title.setTextColor(Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setSelected(true);
        addView(title, new LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addView(row, new LayoutParams(-1, -2));

        prev = button(c, "|◀");
        play = button(c, "▶");
        next = button(c, "▶|");
        row.addView(prev);
        row.addView(play);
        row.addView(next);

        seek = new SeekBar(c);
        seek.setMax(1000);
        LayoutParams sp = new LayoutParams(0, -2, 1f);
        row.addView(seek, sp);

        time = new TextView(c);
        time.setTextSize(11);
        time.setTextColor(Ui.SUB);
        time.setMinWidth(Ui.dp(c, 84));
        time.setGravity(Gravity.END);
        row.addView(time);

        prev.setOnClickListener(v -> pc.prev());
        next.setOnClickListener(v -> pc.next());
        play.setOnClickListener(v -> pc.toggle());
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) {
                    int d = pc.duration();
                    time.setText(Ui.duration(d / 1000.0 * progress / 1000.0) + " / " + Ui.duration(d / 1000.0));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                pc.seekTo((int) ((long) pc.duration() * s.getProgress() / 1000));
            }
        });
        setVisibility(GONE);
    }

    private Button button(Context c, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setMinWidth(0);
        b.setMinimumWidth(Ui.dp(c, 48));
        b.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0);
        return b;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        pc.addListener(this);
        ui.post(tick);
        onPlayerChanged();
    }

    @Override protected void onDetachedFromWindow() {
        pc.removeListener(this);
        ui.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override public void onPlayerChanged() {
        AsmrApi.Node n = pc.current();
        if (n == null) { setVisibility(GONE); return; }
        setVisibility(VISIBLE);
        String t = n.title;
        if (pc.isLoading()) t = "缓冲中… " + t;
        else if (pc.error() != null) t = "⚠ " + pc.error() + " — " + t;
        title.setText(t);
        play.setText(pc.isPlaying() ? "❚❚" : "▶");
        android.util.Log.d("AsmrPlayer", "bar render title=[" + t + "] playBtn=" + play.getText());
        play.setEnabled(!pc.isLoading());
        prev.setEnabled(pc.hasPrev() || pc.position() > 0);
        next.setEnabled(pc.hasNext());
        updateProgress();
    }

    private void updateProgress() {
        if (getVisibility() != VISIBLE || dragging) return;
        int d = pc.duration();
        int pos = pc.position();
        seek.setProgress(d > 0 ? (int) ((long) pos * 1000 / d) : 0);
        time.setText(Ui.duration(pos / 1000.0) + " / " + Ui.duration(d / 1000.0));
        if ((ticks++ % 10) == 0) android.util.Log.d("AsmrPlayer", "progress " + time.getText());
    }
}
