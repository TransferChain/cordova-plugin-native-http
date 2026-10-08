package com.transferchain.http;

import android.net.http.X509TrustManagerExtensions;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.X509TrustManager;

/** Request-owned TLS policy. No global socket factory or trust store is changed. */
public final class PinnedTrustManager implements X509TrustManager {
    private final X509TrustManager system;
    private final X509TrustManagerExtensions verifier;
    private final FingerprintMatcher fingerprints;
    private final String hostname;

    public PinnedTrustManager(X509TrustManager system, String hostname, List<String> pins) {
        this.system = system;
        this.verifier = new X509TrustManagerExtensions(system);
        this.fingerprints = new FingerprintMatcher(pins);
        this.hostname = hostname;
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        // Android builds the trusted chain using the request host's trust policy.
        // Never match pins against unverified extra certificates supplied by a peer.
        List<X509Certificate> verified = verifier.checkServerTrusted(chain, authType, hostname);

        fingerprints.verify(verified);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        system.checkClientTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return system.getAcceptedIssuers();
    }
}
