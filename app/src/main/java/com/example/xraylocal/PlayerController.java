package com.example.xraylocal;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Singleton wrapping MediaPlayer + a playlist (the audio files of the folder the user tapped in).
 * All public methods must be called from the main thread.
 */
final class PlayerController {
    interface Listener { void onPlayerChanged(); }

    private static PlayerController instance;

    static PlayerController get() {
        if (instance == null) instance = new PlayerController();
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "player-prepare");
        t.setDaemon(true);
        return t;
    });
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private List<AsmrApi.Node> queue = new ArrayList<>();
    private int index = -1;
    private MediaPlayer mp;
    private HttpMediaDataSource source;
    private boolean prepared;
    private boolean loading;
    private String error;
    private int generation;
    private WifiManager.WifiLock wifiLock;

    private PlayerController() {}

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    private void changed() {
        android.util.Log.d("AsmrPlayer", "changed idx=" + index + " loading=" + loading + " prepared=" + prepared
                + " playing=" + isPlaying() + " err=" + error + " listeners=" + listeners.size());
        for (Listener l : listeners) l.onPlayerChanged();
    }

    // ------------------------------------------------------------ state

    boolean hasTrack() { return index >= 0 && index < queue.size(); }
    AsmrApi.Node current() { return hasTrack() ? queue.get(index) : null; }
    boolean isLoading() { return loading; }
    boolean isPlaying() { return mp != null && prepared && mp.isPlaying(); }
    String error() { return error; }
    boolean hasPrev() { return index > 0; }
    boolean hasNext() { return index + 1 < queue.size(); }
    int position() { return mp != null && prepared ? mp.getCurrentPosition() : 0; }
    int duration() {
        if (mp != null && prepared) return Math.max(0, mp.getDuration());
        AsmrApi.Node n = current();
        return n == null ? 0 : (int) (n.duration * 1000);
    }

    // ------------------------------------------------------------ control

    void playQueue(List<AsmrApi.Node> tracks, int startIndex) {
        queue = new ArrayList<>(tracks);
        start(startIndex);
    }

    void toggle() {
        if (mp == null || !prepared) return;
        if (mp.isPlaying()) { mp.pause(); releaseWifi(); }
        else { mp.start(); acquireWifi(); }
        changed();
    }

    void seekTo(int ms) {
        if (mp != null && prepared) mp.seekTo(ms);
    }

    void next() { if (hasNext()) start(index + 1); }

    void prev() {
        if (mp != null && prepared && mp.getCurrentPosition() > 3000) { mp.seekTo(0); return; }
        if (hasPrev()) start(index - 1);
        else if (mp != null && prepared) mp.seekTo(0);
    }

    void stop() {
        generation++;
        releasePlayer();
        queue = new ArrayList<>();
        index = -1;
        loading = false;
        error = null;
        changed();
    }

    // ------------------------------------------------------------ internals

    private void start(int idx) {
        if (idx < 0 || idx >= queue.size()) return;
        generation++;
        final int gen = generation;
        releasePlayer();
        index = idx;
        loading = true;
        error = null;
        changed();

        final AsmrApi.Node node = queue.get(idx);
        final boolean low = AppSettings.lowQuality() && !node.lowUrl.isEmpty();
        io.execute(() -> {
            try {
                String url = low ? node.lowUrl : node.streamUrl;
                long size = (low || node.size <= 0) ? HttpMediaDataSource.probeSize(url) : node.size;
                final HttpMediaDataSource ds = new HttpMediaDataSource(url, size);
                main.post(() -> {
                    if (gen != generation) { ds.close(); return; }
                    attach(ds, gen);
                });
            } catch (final Throwable t) {
                main.post(() -> {
                    if (gen != generation) return;
                    fail(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                });
            }
        });
    }

    private void attach(HttpMediaDataSource ds, final int gen) {
        try {
            MediaPlayer p = new MediaPlayer();
            source = ds;
            mp = p;
            prepared = false;
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            p.setWakeMode(App.instance, PowerManager.PARTIAL_WAKE_LOCK);
            p.setOnPreparedListener(m -> {
                if (gen != generation) return;
                prepared = true;
                loading = false;
                m.start();
                acquireWifi();
                changed();
            });
            p.setOnCompletionListener(m -> {
                if (gen != generation) return;
                if (hasNext()) start(index + 1);
                else { releaseWifi(); changed(); }
            });
            p.setOnErrorListener((m, what, extra) -> {
                if (gen == generation) fail("播放出错 (" + what + "/" + extra + ")");
                return true;
            });
            p.setDataSource(ds);
            p.prepareAsync();
        } catch (Throwable t) {
            fail(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
    }

    private void fail(String msg) {
        releasePlayer();
        loading = false;
        error = msg;
        changed();
    }

    private void releasePlayer() {
        releaseWifi();
        final MediaPlayer p = mp;
        final HttpMediaDataSource ds = source;
        mp = null;
        source = null;
        prepared = false;
        if (ds != null) ds.close(); // first: unblocks any read MediaPlayer is waiting on
        if (p != null) {
            p.setOnPreparedListener(null);
            p.setOnCompletionListener(null);
            p.setOnErrorListener(null);
            Thread t = new Thread(() -> {
                try { p.release(); } catch (Throwable ignored) { }
            }, "player-release");
            t.setDaemon(true);
            t.start();
        }
    }

    private void acquireWifi() {
        try {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) App.instance.getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wm == null) return;
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "asmr-xray");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) wifiLock.acquire();
        } catch (Throwable ignored) { }
    }

    private void releaseWifi() {
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Throwable ignored) { }
    }
}
