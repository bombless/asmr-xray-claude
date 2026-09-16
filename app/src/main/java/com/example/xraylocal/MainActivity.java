package com.example.xraylocal;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.graphics.Color;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.widget.EditText;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.ViewGroup;
import android.view.Gravity;

import org.json.JSONObject;
import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;
import libv2ray.XrayConnection;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

public class MainActivity extends Activity {
    private static final String TAG = "XrayLocalChat";
    private static final String PREFS = "chat_runtime";
    private static final String SECURE_VALUE = "config_secure_v1";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "chat_runtime_config_key";
    private static final int TIMEOUT_MS = 15000;

    private WebView web;
    private TextView statusView;
    private Button googleButton;
    private SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private CoreController controller;
    private volatile boolean coreStarted = false;
    private PrivateKey transferPrivateKey;
    private String transferToken;
    private AlertDialog pairingDialog;
    private final ArrayDeque<String> transferDebugLog = new ArrayDeque<>();

    private final CoreCallbackHandler callback = new CoreCallbackHandler() {
        @Override public long startup() { coreStarted = true; return 0L; }
        @Override public long shutdown() { coreStarted = false; return 0L; }
        @Override public long onEmitStatus(long code, String message) {
            Log.d(TAG, "Xray[" + code + "] " + message);
            return 0L;
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));

        googleButton = new Button(this);
        googleButton.setText("Access Google");
        googleButton.setAllCaps(false);
        googleButton.setOnClickListener(v -> runGoogleTest());
        root.addView(googleButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setText("Starting Xray Core...");
        statusView.setGravity(Gravity.START);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(16);
        root.addView(statusView, statusParams);

        setContentView(root);
        io.execute(this::startXray);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void runGoogleTest() {
        googleButton.setEnabled(false);
        statusView.setText("Dialing Google through in-process Xray...");
        io.execute(() -> {
            XraySSLSocketFactory factory = null;
            SSLSocket ssl = null;
            try {
                waitForXray();
                final String host = "www.google.com";
                factory = new XraySSLSocketFactory(controller);
                Socket raw = new XraySocket(controller, host, 443);
                ssl = (SSLSocket) factory.createSocket(raw, host, 443, true);
                ssl.setSoTimeout(30000);
                ssl.startHandshake();
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.getSession())) {
                    throw new java.io.IOException("TLS hostname verification failed");
                }
                OutputStream out = ssl.getOutputStream();
                out.write(("GET / HTTP/1.1\r\nHost: " + host + "\r\nUser-Agent: XrayLocal/1.0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                BufferedReader reader = new BufferedReader(new InputStreamReader(ssl.getInputStream(), StandardCharsets.ISO_8859_1));
                String statusLine = reader.readLine();
                if (statusLine == null) throw new java.io.IOException("empty HTTPS response");
                int code = -1;
                String[] parts = statusLine.split(" ", 3);
                if (parts.length >= 2) code = Integer.parseInt(parts[1]);
                final int responseCode = code;
                runOnUiThread(() -> {
                    statusView.setText("Google HTTPS " + responseCode + " (in-process Xray Core)");
                    googleButton.setEnabled(true);
                });
            } catch (Throwable t) {
                Log.e(TAG, "Google in-process HTTPS failed", t);
                final String message = t.getClass().getSimpleName() + ": " +
                        (t.getMessage() == null ? "unknown error" : t.getMessage());
                runOnUiThread(() -> {
                    statusView.setText("HTTPS failed: " + message);
                    googleButton.setEnabled(true);
                });
            } finally {
                if (ssl != null) {
                    try { ssl.close(); } catch (Exception ignored) { }
                }
            }
        });
    }
    private void startXray() {
        try {
            Libv2ray.initCoreEnv(getFilesDir().getAbsolutePath(), "");
            controller = Libv2ray.newCoreController(callback);
            JSONObject root = new JSONObject(readAsset("config.json"));
            JSONObject log = root.optJSONObject("log");
            if (log == null) { log = new JSONObject(); root.put("log", log); }
            log.put("loglevel", log.optString("loglevel", "warning"));
            log.put("access", new java.io.File(getFilesDir(), "access.log").getAbsolutePath());
            log.put("error", new java.io.File(getFilesDir(), "error.log").getAbsolutePath());
            controller.startLoop(root.toString(), 0);
            transferLog("I", "Xray started in-process; no inbound listener/TUN is created");
            runOnUiThread(() -> statusView.setText("Xray Core started (in-process; no TUN/local proxy)"));
        } catch (Throwable t) {
            coreStarted = false;
            Log.e(TAG, "Failed to start Xray", t);
            transferLog("E", "Xray startup failed: " + t.getMessage());
            runOnUiThread(() -> statusView.setText("Xray startup failed: " +
                    (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage())));
        }
    }

    private String readAsset(String path) throws Exception {
        try (InputStream in = getAssets().open(path); BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            String line; while ((line = br.readLine()) != null) out.append(line).append('\n');
            return out.toString();
        }
    }

    private void waitForXray() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!coreStarted && System.nanoTime() < deadline) Thread.sleep(100);
        if (!coreStarted) throw new IllegalStateException("Xray core did not start");
    }

    /**
     * Model transport using a real Xray-provided socket. No proxy listener,
     * localhost hop, or system-level tunnel is involved.
     */
    private String requestModel(String method, String urlText, String body, String headersJson) throws Exception {
        waitForXray();
        URL url = new URL(urlText);
        if (!(url.getProtocol().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Only HTTPS model URLs are supported by the in-process transport");
        }
        HttpsURLConnection c = (HttpsURLConnection) url.openConnection();
        c.setSSLSocketFactory(new XraySSLSocketFactory(controller));
        c.setRequestMethod(method == null ? "GET" : method.toUpperCase());
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(120000);
        c.setDoInput(true);
        c.setUseCaches(false);
        try {
            if (headersJson != null && !headersJson.isEmpty()) {
                JSONObject headers = new JSONObject(headersJson);
                java.util.Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    c.setRequestProperty(k, headers.optString(k, ""));
                }
            }
            if (body != null && !body.isEmpty() && !"GET".equalsIgnoreCase(method)) {
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line; while ((line = br.readLine()) != null) sb.append(line).append('\n');
                }
            }
            JSONObject out = new JSONObject();
            out.put("status", code);
            out.put("ok", code >= 200 && code < 300);
            out.put("body", sb.toString());
            out.put("contentType", c.getContentType() == null ? "" : c.getContentType());
            return out.toString();
        } finally { c.disconnect(); }
    }

    private static final class XraySocket extends Socket {
        private final XrayConnection connection;
        private final String host;
        private final int port;
        private volatile boolean closed;
        private int soTimeout;

        XraySocket(CoreController controller, String host, int port) throws Exception {
            this.connection = controller.dial(host + ":" + port);
            this.host = host;
            this.port = port;
        }

        @Override public InputStream getInputStream() throws java.io.IOException {
            return new InputStream() {
                @Override public int read() throws java.io.IOException {
                    byte[] one = new byte[1];
                    int n = read(one, 0, 1);
                    return n < 0 ? -1 : (one[0] & 0xff);
                }
                @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
                    if (len == 0) return 0;
                    byte[] tmp = (off == 0 && len == b.length) ? b : new byte[len];
                    try {
                        long n = connection.read(tmp);
                        if (n > Integer.MAX_VALUE) throw new java.io.IOException("Xray read too large: " + n);
                        int count = (int) n;
                        if (tmp != b && count > 0) System.arraycopy(tmp, 0, b, off, count);
                        return count;
                    } catch (Exception e) { throw new java.io.IOException(e); }
                }
                @Override public void close() throws java.io.IOException { XraySocket.this.close(); }
            };
        }

        @Override public OutputStream getOutputStream() throws java.io.IOException {
            return new OutputStream() {
                @Override public void write(int b) throws java.io.IOException { write(new byte[]{(byte) b}, 0, 1); }
                @Override public void write(byte[] b, int off, int len) throws java.io.IOException {
                    if (len == 0) return;
                    byte[] tmp = (off == 0 && len == b.length) ? b : java.util.Arrays.copyOfRange(b, off, off + len);
                    try {
                        long written = connection.write(tmp);
                        if (written != len) throw new java.io.IOException("partial Xray write: " + written + "/" + len);
                    } catch (Exception e) { throw new java.io.IOException(e); }
                }
                @Override public void close() throws java.io.IOException { XraySocket.this.close(); }
            };
        }

        @Override public synchronized void close() throws java.io.IOException {
            if (closed) return;
            closed = true;
            try { connection.close(); } catch (Exception e) { throw new java.io.IOException(e); }
        }

        @Override public void setSoTimeout(int timeout) { soTimeout = timeout; }
        @Override public int getSoTimeout() { return soTimeout; }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isClosed() { return closed; }
        @Override public SocketAddress getRemoteSocketAddress() { return InetSocketAddress.createUnresolved(host, port); }
        @Override public void shutdownInput() throws java.io.IOException { close(); }
        @Override public void shutdownOutput() throws java.io.IOException { close(); }
        @Override public boolean isInputShutdown() { return closed; }
        @Override public boolean isOutputShutdown() { return closed; }
        @Override public void setTcpNoDelay(boolean on) { }
        @Override public boolean getTcpNoDelay() { return true; }
        @Override public void setKeepAlive(boolean on) { }
        @Override public boolean getKeepAlive() { return false; }
    }

    private static final class XraySSLSocketFactory extends SSLSocketFactory {
        private final CoreController controller;
        private final SSLSocketFactory delegate;

        XraySSLSocketFactory(CoreController controller) throws Exception {
            this.controller = controller;
            this.delegate = SSLContext.getDefault().getSocketFactory();
        }

        private Socket layer(String host, int port) throws java.io.IOException {
            try {
                return delegate.createSocket(new XraySocket(controller, host, port), host, port, true);
            } catch (Exception e) {
                throw new java.io.IOException("Xray dial failed for " + host + ":" + port, e);
            }
        }

        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket(String host, int port) throws java.io.IOException { return layer(host, port); }
        @Override public Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort) throws java.io.IOException { return layer(host, port); }
        @Override public Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException { return layer(host.getHostName(), port); }
        @Override public Socket createSocket(java.net.InetAddress address, int port, java.net.InetAddress localAddress, int localPort) throws java.io.IOException { return layer(address.getHostName(), port); }
        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws java.io.IOException {
            return delegate.createSocket(s, host, port, autoClose);
        }
    }

    public class RuntimeBridge {
        @JavascriptInterface public String getConfig() {
            JSONObject out = readSecureConfig();
            return out == null ? "{}" : out.toString();
        }
        @JavascriptInterface public void setConfig(String json) {
            try { writeSecureConfig(normalizeConfig(new JSONObject(json == null ? "{}" : json))); }
            catch (Exception e) { transferLog("E", "setConfig failed: " + e.getMessage()); }
        }
        @JavascriptInterface public String requestModel(String method, String url, String body, String headersJson) {
            try { return requestModel(method, url, body, headersJson); }
            catch (Throwable t) {
                Log.e(TAG, "model request failed", t);
                JSONObject out = new JSONObject();
                try { out.put("status", 599).put("ok", false).put("body", t.getClass().getSimpleName() + ": " + (t.getMessage() == null ? "unknown error" : t.getMessage())); } catch (Exception ignored) {}
                return out.toString();
            }
        }
        @JavascriptInterface public void importConfig() {
            runOnUiThread(() -> {
                final EditText input = new EditText(MainActivity.this);
                input.setSingleLine(true);
                input.setHint("粘贴电脑端配置迁移地址");
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("导入电脑配置")
                        .setMessage("把电脑端“导出配置”生成的 http(s) 地址粘贴到这里。")
                        .setView(input)
                        .setNegativeButton("取消", null)
                        .setPositiveButton("连接", (dialog, which) -> onScannedContents(input.getText().toString()))
                        .show();
            });
        }
        @JavascriptInterface public String getTransferDebugLog() { return MainActivity.this.getTransferDebugLog(); }
        @JavascriptInterface public void clearTransferDebugLog() { MainActivity.this.clearTransferDebugLog(); }
        @JavascriptInterface public void appendTransferDebugLog(String message) { transferLog("I", message); }
        @JavascriptInterface public void clearConfig() {
            prefs.edit().clear().apply();
            try { KeyStore ks = KeyStore.getInstance(KEYSTORE); ks.load(null); if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS); } catch (Exception ignored) {}
        }
    }

    private JSONObject normalizeConfig(JSONObject in) throws Exception {
        JSONObject out = new JSONObject();
        out.put("url", in.optString("url", ""));
        out.put("modelsUrl", in.optString("modelsUrl", ""));
        out.put("apiKey", in.optString("apiKey", ""));
        out.put("model", in.optString("model", ""));
        return out;
    }
    private SecretKey getLocalKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE); ks.load(null);
        if (!ks.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance("AES", KEYSTORE);
            generator.init(new android.security.keystore.KeyGenParameterSpec.Builder(KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
    }
    private void writeSecureConfig(JSONObject config) throws Exception {
        SecretKey key = getLocalKey(); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key);
        prefs.edit().putString(SECURE_VALUE, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(config.toString().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP)).apply();
    }
    private JSONObject readSecureConfig() {
        try {
            String value = prefs.getString(SECURE_VALUE, ""); if (value.isEmpty()) return null;
            String[] parts = value.split(":", 2); SecretKey key = getLocalKey(); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.decode(parts[0], Base64.DEFAULT)));
            return new JSONObject(new String(cipher.doFinal(Base64.decode(parts[1], Base64.DEFAULT)), StandardCharsets.UTF_8));
        } catch (Exception e) { return null; }
    }

    private void transferLog(String level, String message) {
        String line = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()) + " " + level + " " + message;
        synchronized (transferDebugLog) { while (transferDebugLog.size() >= 100) transferDebugLog.removeFirst(); transferDebugLog.addLast(line); }
        if ("E".equals(level)) Log.e(TAG, message); else if ("W".equals(level)) Log.w(TAG, message); else Log.i(TAG, message);
    }
    private String getTransferDebugLog() {
        StringBuilder out = new StringBuilder(); synchronized (transferDebugLog) { for (String x : transferDebugLog) out.append(x).append('\n'); }
        JSONObject c = readSecureConfig(); out.append("保存的配置：").append(c == null ? "无" : "已保存");
        if (c != null) out.append("；URL=").append(c.optString("url", "")).append("；Models URL=").append(c.optString("modelsUrl", "")).append("；Model=").append(c.optString("model", "")).append("；API Key=").append(!c.optString("apiKey", "").isEmpty());
        out.append('\n'); return out.toString();
    }
    private void clearTransferDebugLog() { synchronized (transferDebugLog) { transferDebugLog.clear(); } transferLog("I", "诊断日志已清空"); }

    private static class HttpResult { int code; JSONObject json; HttpResult(int c, JSONObject j){ code=c; json=j; } }
    private HttpResult requestDirect(String method, String urlText, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlText).openConnection(); c.setRequestMethod(method); c.setConnectTimeout(TIMEOUT_MS); c.setReadTimeout(TIMEOUT_MS); c.setDoInput(true);
        if (body != null) { c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json"); try (OutputStream out=c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); } }
        int code=c.getResponseCode(); InputStream in=code>=400?c.getErrorStream():c.getInputStream(); StringBuilder sb=new StringBuilder(); if(in!=null){try(BufferedReader br=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String line;while((line=br.readLine())!=null)sb.append(line);}} c.disconnect(); return new HttpResult(code,new JSONObject(sb.length()==0?"{}":sb.toString()));
    }
    private void onScannedContents(String contents) {
        String scanned = contents == null ? "" : contents.trim(); Uri uri=Uri.parse(scanned); String scheme=uri.getScheme(), host=uri.getHost(); int port=uri.getPort(); String token=uri.getQueryParameter("t");
        if (!("http".equalsIgnoreCase(scheme)||"https".equalsIgnoreCase(scheme)) || host==null || host.isEmpty() || token==null || token.length()<32) { Toast.makeText(this,"二维码不是有效的配置迁移地址",Toast.LENGTH_SHORT).show(); return; }
        transferToken=token; try { KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA"); gen.initialize(2048); KeyPair pair=gen.generateKeyPair(); transferPrivateKey=pair.getPrivate(); String publicKey=Base64.encodeToString(pair.getPublic().getEncoded(),Base64.NO_WRAP); String base=scheme+"://"+host+(port==-1?"":":"+port); joinTransfer(base,token,publicKey); } catch(Exception e){ showError("无法建立安全连接："+e.getMessage()); }
    }
    private void joinTransfer(String base,String token,String publicKey){ io.execute(()->{try{JSONObject body=new JSONObject();body.put("token",token);body.put("publicKey",publicKey);HttpResult r=requestDirect("POST",base+"/api/config-transfer/join",body.toString());if(r.code/100!=2)throw new Exception(r.json.optString("error","连接失败"));String code=r.json.optString("code","");runOnUiThread(()->showPairingCode(code));pollForConfig(base,token);}catch(Exception e){transferLog("E","Join failed: "+e);showError("连接失败："+e.getMessage());}});}
    private void showPairingCode(String code){ if(pairingDialog!=null)pairingDialog.dismiss(); pairingDialog=new AlertDialog.Builder(this).setTitle("检测到电脑配置迁移").setMessage("连接验证码\n\n"+code+"\n\n请在电脑端输入这个验证码。").setNegativeButton("取消",(d,w)->{transferToken=null;transferPrivateKey=null;}).create(); pairingDialog.show(); }
    private void pollForConfig(String base,String token){for(int i=0;i<300&&token.equals(transferToken);i++){try{Thread.sleep(1000);HttpResult r=requestDirect("GET",base+"/api/config-transfer/config?t="+Uri.encode(token),null);if(r.code==200){JSONObject config=decryptTransfer(r.json);writeSecureConfig(normalizeConfig(config));runOnUiThread(()->{if(pairingDialog!=null){pairingDialog.dismiss();pairingDialog=null;}transferToken=null;transferPrivateKey=null;Toast.makeText(this,"配置导入成功",Toast.LENGTH_SHORT).show();web.reload();});return;}if(r.code==410||r.code==404){showError("迁移会话已过期，请重新扫码");return;}}catch(Exception e){showError("配置导入失败："+e.getMessage());return;}}}
    private JSONObject decryptTransfer(JSONObject encrypted)throws Exception{byte[] encryptedKey=Base64.decode(encrypted.getString("encryptedKey"),Base64.DEFAULT);OAEPParameterSpec oaep=new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT);Cipher rsa=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");rsa.init(Cipher.DECRYPT_MODE,transferPrivateKey,oaep);byte[] aesKey=rsa.doFinal(encryptedKey);Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");byte[] iv=Base64.decode(encrypted.getString("iv"),Base64.DEFAULT);byte[] tag=Base64.decode(encrypted.getString("tag"),Base64.DEFAULT);byte[] ciphertext=Base64.decode(encrypted.getString("ciphertext"),Base64.DEFAULT);byte[] combined=new byte[ciphertext.length+tag.length];System.arraycopy(ciphertext,0,combined,0,ciphertext.length);System.arraycopy(tag,0,combined,ciphertext.length,tag.length);aes.init(Cipher.DECRYPT_MODE,new javax.crypto.spec.SecretKeySpec(aesKey,"AES"),new GCMParameterSpec(128,iv));return new JSONObject(new String(aes.doFinal(combined),StandardCharsets.UTF_8));}
    private void showError(String message){runOnUiThread(()->Toast.makeText(this,message,Toast.LENGTH_LONG).show());}
    @Override protected void onDestroy(){io.shutdownNow();transferPrivateKey=null;transferToken=null;if(pairingDialog!=null)pairingDialog.dismiss();if(web!=null)web.destroy();if(controller!=null){try{controller.stopLoop();}catch(Throwable ignored){}}controller=null;coreStarted=false;super.onDestroy();}
    @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
}
