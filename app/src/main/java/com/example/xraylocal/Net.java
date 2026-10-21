package com.example.xraylocal;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal HTTPS/1.1 client (GET, plus JSON POST for login). The underlying TCP socket is either an in-process Xray stream
 * ({@link AppSettings#useXray()}) or a plain direct socket; TLS and the HTTP code are identical.
 * Supports keep-alive pooling, Range, chunked, gzip and redirects.
 */
final class Net {
    private static final int MAX_IDLE_PER_HOST = 4;
    private static final long IDLE_MS = 15_000;
    private static final String UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private static final HashMap<String, ArrayDeque<Conn>> POOL = new HashMap<>();
    private static final ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "net-watchdog");
                t.setDaemon(true);
                return t;
            });
    private static SSLSocketFactory sslFactory;

    private Net() {}

    /** Non-200 answer. {@link #status} lets callers react to 401 etc.; the message format is unchanged. */
    static final class HttpException extends IOException {
        final int status;

        HttpException(int status, String url) {
            super("HTTP " + status + " " + url);
            this.status = status;
        }
    }

    /** Status + body of an exchange where the caller wants the answer whatever the status is. */
    static final class Result {
        final int status;
        final String body;

        Result(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    // ---------------------------------------------------------------- public API

    static String getText(String url, Map<String, String> headers) throws IOException {
        try (Response r = get(url, headers, true)) {
            r.armTimeout(30_000);
            if (r.status != 200) throw new HttpException(r.status, url);
            return new String(r.readAll(16 << 20), StandardCharsets.UTF_8);
        }
    }

    static byte[] getBytes(String url, int max) throws IOException {
        try (Response r = get(url, null, false)) {
            r.armTimeout(30_000);
            if (r.status != 200) throw new HttpException(r.status, url);
            return r.readAll(max);
        }
    }

    /**
     * POSTs a JSON body and returns the status and body whatever the status is (so the caller can read
     * a server-side {"error": ...}). Redirects are not followed. Blocking: call from a background thread.
     */
    static Result postJson(String url, String json, Map<String, String> headers) throws IOException {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        try (Response r = once("POST", new URL(url), headers, true, data)) {
            r.armTimeout(30_000);
            String text = new String(r.readAll(1 << 20), StandardCharsets.UTF_8);
            return new Result(r.status, text);
        }
    }

    static Response get(String url, Map<String, String> extra, boolean gzip) throws IOException {
        String cur = url;
        for (int hop = 0; hop < 6; hop++) {
            Response r = once("GET", new URL(cur), extra, gzip, null);
            String loc = r.header("location");
            if (loc != null && (r.status == 301 || r.status == 302 || r.status == 303
                    || r.status == 307 || r.status == 308)) {
                r.close();
                URL from = new URL(cur);
                URL to = new URL(from, loc);
                // Never forward credentials to a different host than the one that was asked.
                if (extra != null && extra.containsKey("Authorization")
                        && !from.getHost().equalsIgnoreCase(to.getHost())) {
                    extra = new HashMap<>(extra);
                    extra.remove("Authorization");
                }
                cur = to.toString();
                continue;
            }
            return r;
        }
        throw new IOException("Too many redirects");
    }

    static void clearPool() {
        synchronized (POOL) {
            for (ArrayDeque<Conn> q : POOL.values()) for (Conn c : q) c.closeQuiet();
            POOL.clear();
        }
    }

    // ---------------------------------------------------------------- connection pool

    private static final class Conn {
        final String key;
        Socket raw;
        SSLSocket ssl;
        InputStream in;
        OutputStream out;
        long idleSince;

        Conn(String key) { this.key = key; }

        void closeQuiet() {
            try { if (ssl != null) ssl.close(); } catch (Exception ignored) { }
            try { if (raw != null) raw.close(); } catch (Exception ignored) { }
        }

        boolean usable() { return ssl != null && !ssl.isClosed() && raw != null && !raw.isClosed(); }
    }

    private static Conn take(String key) {
        synchronized (POOL) {
            ArrayDeque<Conn> q = POOL.get(key);
            if (q == null) return null;
            long now = System.currentTimeMillis();
            while (!q.isEmpty()) {
                Conn c = q.pollFirst();
                if (now - c.idleSince <= IDLE_MS && c.usable()) return c;
                c.closeQuiet();
            }
            return null;
        }
    }

    private static void release(Conn c) {
        c.idleSince = System.currentTimeMillis();
        synchronized (POOL) {
            ArrayDeque<Conn> q = POOL.get(c.key);
            if (q == null) { q = new ArrayDeque<>(); POOL.put(c.key, q); }
            q.addFirst(c);
            while (q.size() > MAX_IDLE_PER_HOST) q.pollLast().closeQuiet();
        }
    }

    // ---------------------------------------------------------------- one request

    private static synchronized SSLSocketFactory factory() throws IOException {
        if (sslFactory == null) {
            try { sslFactory = SSLContext.getDefault().getSocketFactory(); }
            catch (Exception e) { throw new IOException(e); }
        }
        return sslFactory;
    }

    private static void connect(Conn c, String host, int port, boolean xray) throws IOException {
        Socket raw;
        if (xray) {
            raw = XrayCore.dial(host, port);
            c.raw = raw;
        } else {
            raw = new Socket();
            c.raw = raw;
            raw.connect(new InetSocketAddress(host, port), 15_000);
            raw.setSoTimeout(30_000);
        }
        SSLSocket ssl = (SSLSocket) factory().createSocket(raw, host, port, true);
        c.ssl = ssl;
        ssl.setSoTimeout(30_000); // enforced for direct sockets; Xray streams rely on the watchdog
        ssl.startHandshake();
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.getSession())) {
            throw new SSLPeerUnverifiedException("Hostname not verified: " + host);
        }
        c.in = new BufferedInputStream(ssl.getInputStream(), 16 * 1024);
        c.out = ssl.getOutputStream();
    }

    private static Response once(String method, URL url, Map<String, String> extra, boolean gzip,
                                 byte[] body) throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("Only https is supported");
        final String host = url.getHost();
        final int port = url.getPort() < 0 ? 443 : url.getPort();
        final boolean xray = AppSettings.useXray();
        final String key = (xray ? "x:" : "d:") + host + ":" + port;

        Conn c = take(key);
        boolean reused = c != null;
        for (;;) {
            Response r = new Response();
            if (c == null) c = new Conn(key);
            r.conn = c;
            r.armTimeout(20_000); // covers dial + TLS + response headers
            try {
                if (c.ssl == null) connect(c, host, port, xray);
                c.out.write(buildRequest(method, url, host, port, extra, gzip,
                        body == null ? -1 : body.length).getBytes(StandardCharsets.ISO_8859_1));
                if (body != null) c.out.write(body);
                c.out.flush();
                readHead(r, c);
                return r;
            } catch (IOException e) {
                r.abort();
                if (reused) { reused = false; c = null; continue; } // stale pooled connection
                throw e;
            }
        }
    }

    private static String buildRequest(String method, URL url, String host, int port,
                                       Map<String, String> extra, boolean gzip, int bodyLen) {
        String path = url.getFile();
        if (path == null || path.isEmpty()) path = "/";
        StringBuilder sb = new StringBuilder(384);
        sb.append(method).append(' ').append(sanitize(path)).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(port == 443 ? host : host + ":" + port).append("\r\n");
        sb.append("User-Agent: ").append(UA).append("\r\n");
        sb.append("Accept: */*\r\n");
        sb.append("Accept-Encoding: ").append(gzip ? "gzip" : "identity").append("\r\n");
        sb.append("Referer: https://asmr.one/\r\n");
        sb.append("Origin: https://asmr.one\r\n");
        sb.append("Connection: keep-alive\r\n");
        if (bodyLen >= 0) {
            sb.append("Content-Type: application/json\r\n");
            sb.append("Content-Length: ").append(bodyLen).append("\r\n");
        }
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        sb.append("\r\n");
        return sb.toString();
    }

    /** Percent-encodes anything that must not appear raw in a request target (keeps existing %XX). */
    private static String sanitize(String p) {
        StringBuilder out = new StringBuilder(p.length() + 16);
        for (byte b : p.getBytes(StandardCharsets.UTF_8)) {
            int ch = b & 0xff;
            boolean ok = ch > 0x20 && ch < 0x7f && ch != '"' && ch != '<' && ch != '>'
                    && ch != '\\' && ch != '^' && ch != '`' && ch != '{' && ch != '|' && ch != '}';
            if (ok) out.append((char) ch);
            else out.append('%').append(String.format(Locale.US, "%02X", ch));
        }
        return out.toString();
    }

    private static void readHead(Response r, Conn c) throws IOException {
        String status = readLine(c.in);
        if (status == null) throw new EOFException("empty HTTP response");
        if (!status.startsWith("HTTP/")) throw new IOException("bad HTTP status line: " + status);
        String[] parts = status.split(" ", 3);
        if (parts.length < 2) throw new IOException("bad HTTP status line: " + status);
        try { r.status = Integer.parseInt(parts[1].trim()); }
        catch (NumberFormatException e) { throw new IOException("bad HTTP status: " + status); }
        boolean http11 = status.startsWith("HTTP/1.1");

        String line;
        while ((line = readLine(c.in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) r.headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                    line.substring(colon + 1).trim());
        }

        boolean noBody = r.status == 204 || r.status == 304 || (r.status >= 100 && r.status < 200);
        String te = r.header("transfer-encoding");
        String cl = r.header("content-length");
        boolean keep = http11 && !"close".equalsIgnoreCase(r.header("connection"));
        Framed framed;
        if (noBody) {
            framed = new Fixed(c.in, 0);
        } else if (te != null && te.toLowerCase(Locale.US).contains("chunked")) {
            framed = new Chunked(c.in);
        } else if (cl != null) {
            try { framed = new Fixed(c.in, Long.parseLong(cl.trim())); }
            catch (NumberFormatException e) { throw new IOException("bad Content-Length: " + cl); }
        } else {
            framed = null; // body runs until EOF: connection cannot be reused
            keep = false;
        }
        r.framed = framed;
        r.keepAlive = keep && framed != null;
        InputStream body = framed != null ? framed : c.in;
        String ce = r.header("content-encoding");
        if (ce != null && ce.toLowerCase(Locale.US).contains("gzip") && !noBody) {
            body = new GZIPInputStream(body);
        }
        r.body = body;
    }

    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        int ch;
        while ((ch = in.read()) != -1) {
            any = true;
            if (ch == '\n') break;
            if (ch != '\r') sb.append((char) ch);
            if (sb.length() > 65536) throw new IOException("header line too long");
        }
        return any ? sb.toString() : null;
    }

    // ---------------------------------------------------------------- body framing

    private abstract static class Framed extends InputStream {
        abstract boolean done();
    }

    private static final class Fixed extends Framed {
        private final InputStream in;
        private long remaining;

        Fixed(InputStream in, long len) { this.in = in; this.remaining = len; }

        @Override boolean done() { return remaining <= 0; }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xff);
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            if (len == 0) return 0;
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n < 0) throw new EOFException("connection closed with " + remaining + " bytes left");
            remaining -= n;
            return n;
        }
    }

    private static final class Chunked extends Framed {
        private final InputStream in;
        private long remaining;
        private boolean started;
        private boolean eof;

        Chunked(InputStream in) { this.in = in; }

        @Override boolean done() { return eof; }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xff);
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (eof) return -1;
            if (len == 0) return 0;
            if (remaining == 0) {
                if (started) readLine(in); // CRLF after the previous chunk's data
                started = true;
                String l = readLine(in);
                if (l == null) throw new EOFException("chunked body truncated");
                int semi = l.indexOf(';');
                if (semi >= 0) l = l.substring(0, semi);
                long size;
                try { size = Long.parseLong(l.trim(), 16); }
                catch (NumberFormatException e) { throw new IOException("bad chunk size: " + l); }
                if (size == 0) {
                    String t;
                    while ((t = readLine(in)) != null && !t.isEmpty()) { /* trailers */ }
                    eof = true;
                    return -1;
                }
                remaining = size;
            }
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n < 0) throw new EOFException("chunk truncated");
            remaining -= n;
            return n;
        }
    }

    // ---------------------------------------------------------------- response

    static final class Response implements Closeable {
        int status;
        final HashMap<String, String> headers = new HashMap<>();
        InputStream body;
        private Conn conn;
        private Framed framed;
        private boolean keepAlive;
        private ScheduledFuture<?> wd;

        String header(String name) { return headers.get(name.toLowerCase(Locale.US)); }

        /** (Re)arms a watchdog that kills the connection if the whole remaining exchange takes too long. */
        synchronized void armTimeout(long ms) {
            cancelWd();
            final Conn c = conn;
            if (c == null) return;
            wd = WATCHDOG.schedule(c::closeQuiet, ms, TimeUnit.MILLISECONDS);
        }

        private synchronized void cancelWd() {
            if (wd != null) { wd.cancel(false); wd = null; }
        }

        byte[] readAll(int max) throws IOException {
            ByteArrayOutputStream o = new ByteArrayOutputStream(16 * 1024);
            byte[] b = new byte[16 * 1024];
            int n;
            while ((n = body.read(b)) >= 0) {
                o.write(b, 0, n);
                if (o.size() > max) throw new IOException("response too large");
            }
            return o.toByteArray();
        }

        /** Drop the connection immediately, never pooling it. Safe to call from any thread. */
        void abort() {
            cancelWd();
            Conn c;
            synchronized (this) { c = conn; conn = null; }
            if (c != null) c.closeQuiet();
        }

        /** Finishes the exchange; a fully-consumed keep-alive connection goes back to the pool. */
        @Override public void close() {
            Conn c;
            Framed f;
            boolean ka;
            synchronized (this) { c = conn; conn = null; f = framed; ka = keepAlive; }
            if (c == null) return;
            cancelWd();
            if (ka && f != null) {
                if (!f.done()) { // drain a small leftover so the connection can be reused
                    ScheduledFuture<?> t = WATCHDOG.schedule(c::closeQuiet, 5, TimeUnit.SECONDS);
                    try {
                        byte[] buf = new byte[8192];
                        int total = 0, n;
                        while (!f.done() && total < 65536 && (n = f.read(buf, 0, buf.length)) >= 0) total += n;
                    } catch (IOException ignored) { }
                    t.cancel(false);
                }
                if (f.done() && c.usable()) { release(c); return; }
            }
            c.closeQuiet();
        }
    }
}
