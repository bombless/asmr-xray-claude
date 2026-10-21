package com.example.xraylocal;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny async image loader: memory LRU + 3 worker threads, fetching through {@link Net}. */
final class ImageLoader {
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
                @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
            };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "img-loader");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ColorDrawable PLACEHOLDER = new ColorDrawable(0xFFE3E5E8);

    private ImageLoader() {}

    static void load(final ImageView view, final String url) {
        view.setTag(url);
        Bitmap hit = CACHE.get(url);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        view.setImageDrawable(PLACEHOLDER);
        POOL.execute(() -> {
            if (!url.equals(view.getTag())) return; // the cell was recycled for another item
            try {
                byte[] data = Net.getBytes(url, 8 << 20);
                Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bm == null) return;
                CACHE.put(url, bm);
                MAIN.post(() -> { if (url.equals(view.getTag())) view.setImageBitmap(bm); });
            } catch (Throwable ignored) {
                // leave the placeholder; scrolling back re-triggers a load
            }
        });
    }
}
