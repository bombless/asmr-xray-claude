package com.example.xraylocal;

import android.media.MediaDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Random-access audio source for MediaPlayer built on HTTP Range requests through {@link Net}
 * (so it honours the Xray toggle). Xray has no local listener, hence MediaPlayer cannot be given
 * a URL; this keeps everything in-process. Blocks are cached and the next ones are prefetched.
 */
final class HttpMediaDataSource extends MediaDataSource {
    private static final int BLOCK = 512 * 1024;
    private static final int MAX_CACHED_BLOCKS = 12;
    private static final int PREFETCH_AHEAD = 2;

    private final String url;
    private final long size;
    private volatile boolean closed;

    private final LinkedHashMap<Integer, byte[]> cache =
            new LinkedHashMap<Integer, byte[]>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> e) {
                    return size() > MAX_CACHED_BLOCKS;
                }
            };
    private final HashMap<Integer, Future<byte[]>> inflight = new HashMap<>();
    private final Set<Net.Response> active = new HashSet<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "media-fetch");
        t.setDaemon(true);
        return t;
    });

    HttpMediaDataSource(String url, long size) {
        this.url = url;
        this.size = size;
    }

    /** Asks the server for byte 0 only and reads the total length from Content-Range. */
    static long probeSize(String url) throws IOException {
        Map<String, String> h = new HashMap<>();
        h.put("Range", "bytes=0-0");
        try (Net.Response r = Net.get(url, h, false)) {
            r.armTimeout(20_000);
            if (r.status == 206) {
                String cr = r.header("content-range"); // bytes 0-0/12345
                if (cr != null && cr.contains("/")) {
                    try { return Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim()); }
                    catch (NumberFormatException ignored) { }
                }
            } else if (r.status == 200) {
                String cl = r.header("content-length");
                if (cl != null) {
                    try { return Long.parseLong(cl.trim()); } catch (NumberFormatException ignored) { }
                }
            }
            throw new IOException("无法确定音频大小 (HTTP " + r.status + ")");
        }
    }

    @Override public long getSize() { return size; }

    @Override public int readAt(long position, byte[] buffer, int offset, int length) throws IOException {
        if (closed) throw new IOException("closed");
        if (position < 0 || position >= size) return -1;
        if (length == 0) return 0;
        long t0 = System.currentTimeMillis();
        int r = readAt0(position, buffer, offset, length);
        android.util.Log.d("AsmrMedia", "readAt pos=" + position + " len=" + length + " -> " + r
                + " in " + (System.currentTimeMillis() - t0) + "ms");
        return r;
    }

    private int readAt0(long position, byte[] buffer, int offset, int length) throws IOException {
        int total = 0;
        int lastBlock = (int) (position / BLOCK);
        while (total < length && position < size) {
            int bi = (int) (position / BLOCK);
            lastBlock = bi;
            byte[] blk = block(bi);
            int inBlock = (int) (position - (long) bi * BLOCK);
            int n = Math.min(length - total, blk.length - inBlock);
            if (n <= 0) break;
            System.arraycopy(blk, inBlock, buffer, offset + total, n);
            total += n;
            position += n;
        }
        for (int k = 1; k <= PREFETCH_AHEAD; k++) prefetch(lastBlock + k);
        return total > 0 ? total : -1;
    }

    private byte[] block(int bi) throws IOException {
        Future<byte[]> f;
        synchronized (this) {
            byte[] hit = cache.get(bi);
            if (hit != null) return hit;
            f = inflight.get(bi);
            if (f == null) {
                if (closed) throw new IOException("closed");
                try { f = submit(bi); }
                catch (java.util.concurrent.RejectedExecutionException e) { throw new IOException("closed"); }
            }
        }
        try {
            return f.get();
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            throw (c instanceof IOException) ? (IOException) c : new IOException(c);
        } catch (CancellationException e) {
            throw new IOException("cancelled");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    private void prefetch(int bi) {
        if ((long) bi * BLOCK >= size) return;
        synchronized (this) {
            if (closed || cache.containsKey(bi) || inflight.containsKey(bi)) return;
            submit(bi);
        }
    }

    /** Caller holds the monitor. */
    private Future<byte[]> submit(final int bi) {
        Future<byte[]> f = pool.submit(() -> {
            try {
                byte[] data = fetch(bi);
                synchronized (HttpMediaDataSource.this) { cache.put(bi, data); }
                return data;
            } finally {
                synchronized (HttpMediaDataSource.this) { inflight.remove(bi); }
            }
        });
        inflight.put(bi, f);
        return f;
    }

    private byte[] fetch(int bi) throws IOException {
        long start = (long) bi * BLOCK;
        long end = Math.min(size - 1, start + BLOCK - 1);
        int expected = (int) (end - start + 1);
        IOException last = null;
        for (int attempt = 1; attempt <= 3 && !closed; attempt++) {
            Net.Response r = null;
            long tf = System.currentTimeMillis();
            try {
                Map<String, String> h = new HashMap<>();
                h.put("Range", "bytes=" + start + "-" + end);
                r = Net.get(url, h, false);
                long ttfb = System.currentTimeMillis() - tf;
                synchronized (this) {
                    if (closed) { r.abort(); throw new IOException("closed"); }
                    active.add(r);
                }
                r.armTimeout(30_000);
                if (r.status == 206 || (r.status == 200 && start == 0)) {
                    byte[] out = new byte[expected];
                    readFully(r.body, out);
                    android.util.Log.d("AsmrMedia", "block " + bi + " " + expected + "B ttfb=" + ttfb
                            + "ms total=" + (System.currentTimeMillis() - tf) + "ms");
                    return out;
                }
                throw new IOException("HTTP " + r.status + (r.status == 200 ? " (服务器忽略了 Range)" : ""));
            } catch (IOException e) {
                last = e;
                if (closed) break;
                try { Thread.sleep(300L * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } finally {
                if (r != null) {
                    synchronized (this) { active.remove(r); }
                    r.close();
                }
            }
        }
        throw last != null ? last : new IOException("closed");
    }

    private static void readFully(InputStream in, byte[] out) throws IOException {
        int off = 0;
        while (off < out.length) {
            int n = in.read(out, off, out.length - off);
            if (n < 0) throw new IOException("unexpected end of stream at " + off + "/" + out.length);
            off += n;
        }
    }

    @Override public void close() {
        Set<Net.Response> toAbort;
        synchronized (this) {
            if (closed) return;
            closed = true;
            for (Future<byte[]> f : inflight.values()) f.cancel(true);
            toAbort = new HashSet<>(active);
            active.clear();
            cache.clear();
        }
        for (Net.Response r : toAbort) r.abort(); // unblocks reads stuck on the socket
        pool.shutdownNow();
    }
}
