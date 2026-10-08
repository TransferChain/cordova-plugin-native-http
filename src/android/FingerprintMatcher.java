package com.transferchain.http;

import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/** Matches SHA-256 digests only after the caller has produced a trusted chain. */
public final class FingerprintMatcher {
    private final List<Pin> pins = new ArrayList<>();

    public FingerprintMatcher(List<String> fingerprints) {
        if (fingerprints == null || fingerprints.size() > 16) {
            throw new IllegalArgumentException("Invalid TLS policy");
        }

        for (String fingerprint : fingerprints) {
            pins.add(new Pin(fingerprint));
        }
    }

    public void verify(List<X509Certificate> verifiedChain) throws CertificateException {
        // An empty policy preserves the caller's normal platform TLS validation.
        if (pins.isEmpty()) {
            return;
        }

        try {
            for (X509Certificate certificate : verifiedChain) {
                for (Pin pin : pins) {
                    byte[] encoded = pin.spki ? certificate.getPublicKey().getEncoded() : certificate.getEncoded();
                    byte[] hash = MessageDigest.getInstance("SHA-256").digest(encoded);

                    if (MessageDigest.isEqual(pin.digest, hash)) {
                        return;
                    }
                }
            }
        } catch (java.security.GeneralSecurityException failure) {
            throw new CertificateException("TLS pin verification failed");
        }

        throw new CertificateException("TLS fingerprint mismatch");
    }

    private static final class Pin {
        final boolean spki;
        final byte[] digest = new byte[32];

        Pin(String value) {
            if (value == null || !(value.startsWith("spki:") || value.startsWith("certificate:"))) {
                throw new IllegalArgumentException("Invalid SHA-256 pin");
            }

            spki = value.startsWith("spki:");
            String hex = value.substring(value.indexOf(':') + 1);

            if (hex.length() != 64) {
                throw new IllegalArgumentException("Invalid SHA-256 pin");
            }

            for (int i = 0; i < digest.length; i++) {
                int high = Character.digit(hex.charAt(i * 2), 16);
                int low = Character.digit(hex.charAt(i * 2 + 1), 16);

                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException("Invalid SHA-256 pin");
                }

                digest[i] = (byte) ((high << 4) | low);
            }
        }
    }
}
