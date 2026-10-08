import com.transferchain.http.FingerprintMatcher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.HexFormat;
import javax.net.ssl.*;
import java.net.URL;

/** Host TLS regression. Its private CA exists only in the disposable test harness. */
public final class HTTPPolicyTest {
    private static X509Certificate ca, leaf, other;
    private static int checks;

    public static void main(String[] args) throws Exception {
        ca = read(Path.of(args[0], "ca.der"));
        other = read(Path.of(args[0], "other.der"));
        leaf = read(Path.of(args[0], "leaf.der"));
        String certificate = pin("certificate:", leaf.getEncoded());
        String spki = pin("spki:", leaf.getPublicKey().getEncoded());
        String wrong = "certificate:" + "00".repeat(32);

        request(args[1], List.of(), true, true);
        request(args[1], List.of(certificate), true, true);
        request(args[1], List.of(spki), true, true);
        request(args[1], List.of(wrong, certificate), true, true);
        request(args[1], List.of(wrong), true, false);
        request(args[1], List.of(certificate), false, false);
        request(args[1], List.of(), false, false);
        request(args[2], List.of(certificate), true, false);
        request(args[3], List.of(), true, false);
        request(args[3], List.of(certificate), true, false);
        try {
            // A certificate outside the trusted chain cannot satisfy a pin.
            new FingerprintMatcher(List.of(pin("certificate:", ca.getEncoded()))).verify(List.of(leaf));
            throw new AssertionError("Unrelated certificate was accepted");
        } catch (java.security.cert.CertificateException expected) { checks++; }

        for (String malformed : List.of("spki:00", "certificate:" + "gg".repeat(32), "other:" + "00".repeat(32))) {
            try {
                new FingerprintMatcher(List.of(malformed));
                throw new AssertionError("Malformed pin accepted");
            } catch (IllegalArgumentException expected) { checks++; }
        }

        System.out.println("UltraHTTP native host: " + checks + " TLS/pin security checks passed");
    }

    private static void request(String address, List<String> pins, boolean trusted, boolean expected) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());

        store.load(null, null);
        if (trusted) store.setCertificateEntry("test-ca", ca);
        else store.setCertificateEntry("unrelated-test-ca", other);
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());

        factory.init(store);
        X509TrustManager validator = (X509TrustManager) factory.getTrustManagers()[0];
        FingerprintMatcher matcher = new FingerprintMatcher(pins);
        X509TrustManager policy = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return validator.getAcceptedIssuers(); }
            public void checkClientTrusted(X509Certificate[] chain, String type) throws java.security.cert.CertificateException {
                validator.checkClientTrusted(chain, type);
            }
            public void checkServerTrusted(X509Certificate[] chain, String type) throws java.security.cert.CertificateException {
                // For this fixed fixture the validated chain is exactly leaf + test CA.
                // Production Android uses X509TrustManagerExtensions, not this harness.
                validator.checkServerTrusted(chain, type);
                matcher.verify(List.of(chain[0], ca));
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");

        context.init(null, new TrustManager[]{policy}, null);
        HttpsURLConnection connection = (HttpsURLConnection) new URL(address).openConnection();

        connection.setSSLSocketFactory(context.getSocketFactory());
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setInstanceFollowRedirects(false);
        boolean succeeded = false;

        try {
            succeeded = connection.getResponseCode() == 200;
        } catch (SSLException failure) {
            if (expected) throw failure;
        } finally {
            connection.disconnect();
        }

        if (succeeded != expected) throw new AssertionError("TLS policy outcome mismatch");
        checks++;
    }

    private static X509Certificate read(Path path) throws Exception {
        try (var stream = Files.newInputStream(path)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(stream);
        }
    }

    private static String pin(String prefix, byte[] bytes) throws Exception {
        return prefix + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
