package com.example.xraylocal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Arrays;

import libv2ray.CoreController;
import libv2ray.XrayConnection;

/**
 * A {@link Socket} backed by an in-process Xray connection (no local listener, no TUN).
 * TLS is layered on top of it by the caller via SSLSocketFactory.createSocket(Socket, ...).
 */
final class XraySocket extends Socket {
    private final XrayConnection connection;
    private final String host;
    private final int port;
    private volatile boolean closed;
    private int soTimeout;
    private InputStream in;
    private OutputStream out;

    XraySocket(CoreController controller, String host, int port) throws Exception {
        this.connection = controller.dial(host + ":" + port);
        this.host = host;
        this.port = port;
    }

    /** gomobile surfaces Go's io.EOF as an Exception whose message is "EOF". */
    private static boolean isEof(Exception e) {
        String m = e.getMessage();
        return m != null && (m.equals("EOF") || m.endsWith(": EOF") || m.contains("closed pipe"));
    }

    @Override public synchronized InputStream getInputStream() throws IOException {
        if (in == null) {
            in = new InputStream() {
                @Override public int read() throws IOException {
                    byte[] one = new byte[1];
                    int n;
                    do { n = read(one, 0, 1); } while (n == 0);
                    return n < 0 ? -1 : (one[0] & 0xff);
                }

                @Override public int read(byte[] b, int off, int len) throws IOException {
                    if (len == 0) return 0;
                    if (closed) throw new IOException("Xray socket closed");
                    byte[] tmp = (off == 0 && len == b.length) ? b : new byte[len];
                    try {
                        long n = connection.read(tmp);
                        if (n < 0) return -1;
                        if (n > len) throw new IOException("Xray read too large: " + n);
                        int count = (int) n;
                        if (tmp != b && count > 0) System.arraycopy(tmp, 0, b, off, count);
                        return count;
                    } catch (IOException e) {
                        throw e;
                    } catch (Exception e) {
                        if (isEof(e)) return -1;
                        throw new IOException(e);
                    }
                }

                @Override public void close() throws IOException { XraySocket.this.close(); }
            };
        }
        return in;
    }

    @Override public synchronized OutputStream getOutputStream() throws IOException {
        if (out == null) {
            out = new OutputStream() {
                @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }

                @Override public void write(byte[] b, int off, int len) throws IOException {
                    if (len == 0) return;
                    if (closed) throw new IOException("Xray socket closed");
                    byte[] tmp = (off == 0 && len == b.length) ? b : Arrays.copyOfRange(b, off, off + len);
                    try {
                        long written = connection.write(tmp);
                        if (written != len) throw new IOException("partial Xray write: " + written + "/" + len);
                    } catch (IOException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IOException(e);
                    }
                }

                @Override public void close() throws IOException { XraySocket.this.close(); }
            };
        }
        return out;
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { connection.close(); } catch (Exception e) { throw new IOException(e); }
    }

    @Override public void setSoTimeout(int timeout) { soTimeout = timeout; }
    @Override public int getSoTimeout() { return soTimeout; }
    @Override public boolean isConnected() { return true; }
    @Override public boolean isClosed() { return closed; }
    @Override public SocketAddress getRemoteSocketAddress() { return InetSocketAddress.createUnresolved(host, port); }
    @Override public void shutdownInput() throws IOException { }
    @Override public void shutdownOutput() throws IOException { }
    @Override public boolean isInputShutdown() { return closed; }
    @Override public boolean isOutputShutdown() { return closed; }
    @Override public void setTcpNoDelay(boolean on) { }
    @Override public boolean getTcpNoDelay() { return true; }
    @Override public void setKeepAlive(boolean on) { }
    @Override public boolean getKeepAlive() { return false; }
}
