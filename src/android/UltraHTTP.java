package com.transferchain.http;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONObject;

/** OS HTTPS transport: each request owns its credentials, connection and pins. */
public final class UltraHTTP extends CordovaPlugin {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final List<String> METHODS = Arrays.asList("GET", "POST", "PUT", "DELETE", "HEAD", "PATCH", "OPTIONS");
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicBoolean destroyed = new AtomicBoolean();
    // Cancellation must not queue behind the blocked I/O it needs to release.
    // Two lazy cleanup workers are bounded and never execute ordinary requests.
    private final java.util.concurrent.ExecutorService cancellations =
            java.util.concurrent.Executors.newFixedThreadPool(2);

    @Override
    public boolean execute(String action, CordovaArgs args, CallbackContext callback) {
        try {
            if (destroyed.get()) {
                fail(callback, 90);
                return true;
            }

            if ("cancel".equals(action)) {
                Job job = jobs.get(args.getString(0));

                if (job != null) job.cancel();
                callback.success();
                return true;
            }

            if (!"request".equals(action)) return false;

            JSONObject options = args.getJSONObject(0);
            String id = options.getString("id");

            if (id.isEmpty() || id.length() > 64) throw new IllegalArgumentException();

            Job job = new Job(id, options, args.getArrayBuffer(1), callback);

            synchronized (jobs) {
                // Pair with destroy's admission barrier: no new work may escape
                // the reset snapshot after the cleanup executor is shut down.
                if (destroyed.get()) {
                    Arrays.fill(job.body, (byte) 0);
                    fail(callback, 90);
                    return true;
                }

                if (jobs.size() >= 32 || jobs.putIfAbsent(id, job) != null) {
                    Arrays.fill(job.body, (byte) 0);
                    fail(callback, 91);
                    return true;
                }
            }

            // Blocking TLS/I/O runs on Cordova's existing pool, never the UI thread.
            try {
                cordova.getThreadPool().execute(job::run);
            } catch (java.util.concurrent.RejectedExecutionException unavailable) {
                jobs.remove(id, job);
                Arrays.fill(job.body, (byte) 0);
                // Reset can already have completed this callback while executor
                // admission was in progress. Rejection must share its terminal CAS.
                if (job.terminal.compareAndSet(false, true)) fail(callback, -1);
            }
            return true;
        } catch (Exception failure) {
            fail(callback, 1);
            return true;
        }
    }

    @Override
    public void onReset() {
        for (Job job : jobs.values()) job.cancel();
    }

    @Override
    public void onDestroy() {
        synchronized (jobs) {
            destroyed.set(true);
        }

        onReset();
        cancellations.shutdown();
    }

    private static void fail(CallbackContext callback, int code) {
        JSONObject error = new JSONObject();

        try {
            error.put("code", code);
            error.put("message", "Native HTTPS request failed");
        } catch (Exception ignored) { }

        callback.error(error);
    }

    private final class Job {
        final String id;
        final JSONObject options;
        final byte[] body;
        final CallbackContext callback;
        final AtomicBoolean terminal = new AtomicBoolean();
        volatile HttpsURLConnection connection;

        Job(String id, JSONObject options, byte[] body, CallbackContext callback) {
            this.id = id;
            this.options = options;
            this.body = body;
            this.callback = callback;
        }

        void cancel() {
            if (!terminal.compareAndSet(false, true)) return;

            fail(callback, 90);

            HttpsURLConnection current = connection;

            if (current != null) {
                // Disconnect can wait for socket I/O. Do not block bridge admission.
                try {
                    cancellations.execute(current::disconnect);
                } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
                    // A running request still disconnects in finally; reset must
                    // not throw into Cordova while its executor is shutting down.
                }
            }
        }

        void run() {
            byte[] response = null;

            try {
                if (terminal.get()) return;

                URI uri = new URI(options.getString("url"));
                String host = uri.getHost();
                String method = options.getString("method");
                boolean hasBody = options.getBoolean("hasBody");

                if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null ||
                        !METHODS.contains(method) || body.length > MAX_BYTES ||
                        (hasBody && (method.equals("GET") || method.equals("HEAD")))) {
                    throw new IllegalArgumentException();
                }

                JSONArray values = options.getJSONArray("fingerprints");
                List<String> pins = new ArrayList<>();

                if (values.length() > 16) throw new IllegalArgumentException();

                for (int i = 0; i < values.length(); i++) pins.add(values.getString(i));
                new FingerprintMatcher(pins);
                TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());

                factory.init((KeyStore) null);
                X509TrustManager system = null;

                for (TrustManager manager : factory.getTrustManagers()) {
                    if (manager instanceof X509TrustManager) system = (X509TrustManager) manager;
                }

                if (system == null) throw new SSLException("TLS trust unavailable");

                connection = (HttpsURLConnection) uri.toURL().openConnection();
                if (!pins.isEmpty()) {
                    SSLContext tls = SSLContext.getInstance("TLS");

                    tls.init(null, new TrustManager[]{new PinnedTrustManager(system, host, pins)}, null);
                    connection.setSSLSocketFactory(tls.getSocketFactory());
                }

                // No automatic redirects: a 3xx cannot forward bearer tokens or bodies.
                // Keep the default hostname verifier and OS CA policy in both modes.
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(30000);
                connection.setRequestMethod(method);
                JSONObject headers = options.getJSONObject("headers");
                int headerBytes = 0;

                if (headers.length() > 64) throw new IllegalArgumentException();

                for (java.util.Iterator<String> names = headers.keys(); names.hasNext();) {
                    String name = names.next();
                    String value = headers.getString(name);

                    headerBytes += name.length() + value.length();
                    if (headerBytes > 32768 || !validHeader(name, value)) throw new IllegalArgumentException();

                    connection.setRequestProperty(name, value);
                }

                if (terminal.get()) return;

                if (hasBody) {
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(body.length);
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(body);
                    }
                }

                int status = connection.getResponseCode();
                JSONObject responseHeaders = new JSONObject();
                int responseHeaderBytes = 0;

                for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
                    if (entry.getKey() == null) continue;

                    String value = String.join(", ", entry.getValue());

                    responseHeaderBytes += entry.getKey().length() + value.length();
                    if (responseHeaderBytes > 32768) throw new Limit();

                    responseHeaders.put(entry.getKey(), value);
                }

                long length = connection.getContentLengthLong();

                if (length > MAX_BYTES) throw new Limit();

                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();

                try (InputStream input = stream; WipingOutput output = new WipingOutput()) {
                    byte[] chunk = new byte[16384];

                    try {
                        int count;

                        while (input != null && (count = input.read(chunk)) != -1) {
                            if (terminal.get()) return;
                            if (count > MAX_BYTES - output.size()) throw new Limit();

                            output.write(chunk, 0, count);
                        }

                        response = output.toByteArray();
                    } finally {
                        Arrays.fill(chunk, (byte) 0);
                    }
                }

                JSONObject metadata = new JSONObject();

                metadata.put("status", status);
                metadata.put("url", uri.toString());
                metadata.put("headers", responseHeaders);
                if (terminal.compareAndSet(false, true)) {
                    // Multipart preserves the binary body through Cordova's own bridge.
                    callback.sendPluginResult(new PluginResult(PluginResult.Status.OK, Arrays.asList(
                            new PluginResult(PluginResult.Status.OK, metadata),
                            new PluginResult(PluginResult.Status.OK, response))));
                }
            } catch (Exception failure) {
                int code = failure instanceof SSLException ? 88 :
                        failure instanceof Limit ? 91 : failure instanceof IllegalArgumentException ? 1 : -1;

                if (terminal.compareAndSet(false, true)) fail(callback, code);
            } finally {
                if (connection != null) connection.disconnect();
                Arrays.fill(body, (byte) 0);
                if (response != null) Arrays.fill(response, (byte) 0);

                jobs.remove(id, this);
            }
        }
    }

    private static boolean validHeader(String name, String value) {
        if (name.isEmpty() || Arrays.asList("host", "content-length", "connection", "transfer-encoding").contains(name.toLowerCase(java.util.Locale.ROOT))) return false;

        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);

            if (!(Character.isLetterOrDigit(c) && c < 128) && "!#$%&'*+-.^_`|~".indexOf(c) < 0) return false;
        }

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            if (c == '\r' || c == '\n' || c == 0) return false;
        }

        return true;
    }

    private static final class Limit extends IOException { }

    private static final class WipingOutput extends ByteArrayOutputStream {
        @Override
        public void close() {
            Arrays.fill(buf, (byte) 0);
            reset();
        }
    }
}
