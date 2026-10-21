package com.example.xraylocal;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

/** Process-wide owner of the in-process Xray core. Started lazily, only when something needs it. */
final class XrayCore {
    private static final String TAG = "XrayCore";

    private static volatile CoreController controller;
    private static volatile boolean ready;
    private static volatile boolean starting;
    private static volatile String status = "未启动";

    private static final CoreCallbackHandler CALLBACK = new CoreCallbackHandler() {
        @Override public long startup() { ready = true; return 0L; }
        @Override public long shutdown() { ready = false; return 0L; }
        @Override public long onEmitStatus(long code, String message) {
            Log.d(TAG, "Xray[" + code + "] " + message);
            return 0L;
        }
    };

    private XrayCore() {}

    static String status() { return status; }

    static boolean isReady() { return ready && controller != null; }

    /** Idempotent. Returns immediately; use {@link #dial} (which waits) for actual traffic. */
    static synchronized void ensureStarted() {
        if (controller != null || starting) return;
        starting = true;
        status = "启动中…";
        Thread t = new Thread(XrayCore::doStart, "xray-start");
        t.setDaemon(true);
        t.start();
    }

    private static void doStart() {
        try {
            Context c = App.instance;
            Libv2ray.initCoreEnv(c.getFilesDir().getAbsolutePath(), "");
            CoreController ctl = Libv2ray.newCoreController(CALLBACK);
            controller = ctl; // assigned before startLoop: startup() may fire before it returns
            JSONObject root = new JSONObject(readAsset(c, "config.json"));
            JSONObject log = root.optJSONObject("log");
            if (log == null) { log = new JSONObject(); root.put("log", log); }
            // "debug" would log every connection of every audio stream; keep it quiet.
            log.put("loglevel", "warning");
            log.put("access", new File(c.getFilesDir(), "access.log").getAbsolutePath());
            log.put("error", new File(c.getFilesDir(), "error.log").getAbsolutePath());
            ctl.startLoop(root.toString(), 0);
            status = "已启动（进程内，无 TUN / 本地代理端口）";
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start Xray", t);
            status = "启动失败：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            controller = null;
            ready = false;
            synchronized (XrayCore.class) { starting = false; }
        }
    }

    private static String readAsset(Context c, String path) throws IOException {
        try (InputStream in = c.getAssets().open(path);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) out.append(line).append('\n');
            return out.toString();
        }
    }

    private static void awaitReady(long timeoutMs) throws IOException {
        ensureStarted();
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (!isReady() && System.nanoTime() < deadline) {
            if (controller == null && !starting) break; // startup failed
            try { Thread.sleep(50); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        }
        if (!isReady()) throw new IOException("Xray 核心未就绪：" + status);
    }

    /** Opens a TCP stream to host:port through the Xray router/outbound. DNS is resolved remotely. */
    static XraySocket dial(String host, int port) throws IOException {
        awaitReady(15_000);
        try {
            return new XraySocket(controller, host, port);
        } catch (Exception e) {
            throw new IOException("Xray dial " + host + ":" + port + " 失败：" + e.getMessage(), e);
        }
    }
}
