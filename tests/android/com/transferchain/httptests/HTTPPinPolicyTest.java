package com.transferchain.httptests;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.transferchain.http.PinnedTrustManager;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises the real Android cleaned-chain trust manager with a test-only CA. */
@RunWith(AndroidJUnit4.class)
public final class HTTPPinPolicyTest {
    @Test
    public void nativeTransportAcceptsPublicHTTPSAndRejectsMismatchedPins() throws Exception {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        org.apache.cordova.CordovaInterface cordova = (org.apache.cordova.CordovaInterface) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{org.apache.cordova.CordovaInterface.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getThreadPool")) return executor;
                    if (method.getName().equals("getContext")) return InstrumentationRegistry.getInstrumentation().getTargetContext();
                    return null;
                });
        com.transferchain.http.UltraHTTP plugin = new com.transferchain.http.UltraHTTP();

        plugin.privateInitialize("http", cordova, null, new org.apache.cordova.CordovaPreferences());
        try {
            for (boolean pinned : new boolean[]{false, true}) {
                org.json.JSONObject options = new org.json.JSONObject()
                        .put("id", pinned ? "pinned" : "plain")
                        .put("url", "https://example.org/").put("method", "GET")
                        .put("headers", new org.json.JSONObject()).put("hasBody", false)
                        .put("fingerprints", pinned ? new org.json.JSONArray().put("certificate:" + "00".repeat(32)) : new org.json.JSONArray());
                java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.atomic.AtomicReference<org.apache.cordova.PluginResult> captured = new java.util.concurrent.atomic.AtomicReference<>();
                java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
                org.apache.cordova.CallbackContext callback = new org.apache.cordova.CallbackContext("test", null) {
                    @Override public void sendPluginResult(org.apache.cordova.PluginResult result) {
                        captured.set(result);
                        count.incrementAndGet();
                        done.countDown();
                    }
                };

                assertTrue(plugin.execute("request", new org.apache.cordova.CordovaArgs(new org.json.JSONArray().put(options).put("")), callback));
                assertTrue("Native UltraHTTP timed out", done.await(40, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(1, count.get());
                org.apache.cordova.PluginResult result = captured.get();

                if (pinned) {
                    assertEquals(org.apache.cordova.PluginResult.Status.ERROR.ordinal(), result.getStatus());
                    assertEquals(88, new org.json.JSONObject(result.getMessage()).getInt("code"));
                } else {
                    assertEquals(org.apache.cordova.PluginResult.Status.OK.ordinal(), result.getStatus());
                    assertEquals(2, result.getMultipartMessagesSize());
                    assertEquals(200, new org.json.JSONObject(result.getMultipartMessage(0).getMessage()).getInt("status"));
                }
            }
        } finally {
            plugin.onReset();
            executor.shutdown();
            assertTrue(executor.awaitTermination(40, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    private X509Certificate read(String name) throws Exception {
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name + ".der")) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    private X509TrustManager validator(X509Certificate anchor) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());

        store.load(null, null);
        store.setCertificateEntry("fixture", anchor);
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());

        factory.init(store);
        return (X509TrustManager) factory.getTrustManagers()[0];
    }

    private String digest(byte[] data) throws Exception {
        StringBuilder result = new StringBuilder();

        for (byte value : MessageDigest.getInstance("SHA-256").digest(data)) {
            result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        }

        return result.toString();
    }

    @Test
    public void pinsNeverOverrideTrustOrHostname() throws Exception {
        X509Certificate leaf = read("leaf");
        X509Certificate ca = read("ca");
        X509TrustManager trusted = validator(ca);
        X509Certificate[] chain = {leaf};
        String certificate = "certificate:" + digest(leaf.getEncoded());
        String spki = "spki:" + digest(leaf.getPublicKey().getEncoded());
        String wrong = "certificate:" + digest(read("other").getEncoded());

        for (List<String> pins : List.of(Collections.<String>emptyList(), List.of(certificate), List.of(spki), List.of(wrong, certificate))) {
            new PinnedTrustManager(trusted, "localhost", pins).checkServerTrusted(chain, "RSA");
        }

        assertThrows(CertificateException.class, () -> new PinnedTrustManager(trusted, "localhost", List.of(wrong)).checkServerTrusted(chain, "RSA"));
        // Android's trust extension constructs the CA chain; hostname matching is
        // HttpsURLConnection's separate verifier, which the transport never overrides.
        javax.net.ssl.SSLSession session = (javax.net.ssl.SSLSession) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{javax.net.ssl.SSLSession.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getPeerCertificates")) return chain;
                    if (method.getName().equals("getPeerHost")) return "localhost";
                    return null;
                });

        assertTrue(javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify("localhost", session));
        assertFalse(javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify("wrong.example", session));
        X509TrustManager untrusted = validator(read("other"));

        assertThrows(CertificateException.class, () -> new PinnedTrustManager(untrusted, "localhost", List.of(certificate)).checkServerTrusted(chain, "RSA"));
        assertThrows(CertificateException.class, () -> new PinnedTrustManager(untrusted, "localhost", List.of()).checkServerTrusted(chain, "RSA"));
        // Appending an unrelated raw peer cert must not satisfy a pin: the actual
        // manager matches Android's verified chain instead of this untrusted array.
        X509Certificate[] injected = {leaf, ca, read("other")};

        assertThrows(CertificateException.class, () -> new PinnedTrustManager(trusted, "localhost", List.of(wrong)).checkServerTrusted(injected, "RSA"));
    }
}
