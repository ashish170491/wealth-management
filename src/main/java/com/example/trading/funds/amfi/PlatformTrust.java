package com.example.trading.funds.amfi;

import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A trust manager that accepts anything either the JDK <b>or</b> the operating system trusts.
 *
 * <p><b>Why this exists.</b> AMFI's certificate chains to {@code Sectigo Public Server
 * Authentication Root R46}, a legitimate public root that is in the Microsoft and Mozilla root
 * programs but <em>not</em> in this JDK's bundled {@code cacerts} — JDK 21.0.2 ships a January-2024
 * trust list that predates it. The result is a {@code PKIX path building failed} on every fetch,
 * while {@code curl} on the same machine succeeds because it uses a different trust store.
 *
 * <p><b>Why delegate to the OS rather than ship the certificate.</b> Pinning a copy of the root in
 * the repository would also work, and is worse: a root that is later distrusted gets removed from
 * the OS store by an update, whereas a PEM committed here would keep being trusted until somebody
 * noticed. Deferring to the platform's root programme means revocation keeps working for free.
 *
 * <p><b>What this does not do.</b> It does not disable verification, accept expired or self-signed
 * certificates, or skip hostname checks. It <em>widens the set of trusted roots</em> to the union of
 * two curated stores and nothing more — every other part of the handshake is unchanged. It is also
 * scoped to the single {@link AmfiNavClient} rather than installed globally, so no other call in the
 * app has its trust decisions altered.
 *
 * <p>Falls back to JDK-only on a platform with no OS store (Linux, macOS), which is correct: those
 * JDK distributions are typically kept current by the same package manager that updates the system.
 */
@Slf4j
public final class PlatformTrust {

    private PlatformTrust() {}

    /** Keystore types worth trying, in order. Absent types are skipped without complaint. */
    private static final List<String> OS_KEYSTORE_TYPES = List.of("Windows-ROOT", "KeychainStore");

    /**
     * @return a trust manager accepting the union of the JDK default roots and the OS root store,
     *         or the JDK defaults alone when no OS store is available
     */
    public static X509TrustManager unionOfJdkAndOs() {
        List<X509TrustManager> delegates = new ArrayList<>();

        X509TrustManager jdk = fromKeyStore(null, "JDK default");
        if (jdk != null) {
            delegates.add(jdk);
        }
        for (String type : OS_KEYSTORE_TYPES) {
            X509TrustManager os = fromOsStore(type);
            if (os != null) {
                delegates.add(os);
            }
        }

        if (delegates.isEmpty()) {
            // Never silently fall through to trusting everything. If no store could be built at all
            // the honest outcome is a handshake failure, not an unverified connection.
            throw new IllegalStateException("No trust store could be initialised - refusing to "
                    + "continue rather than disabling certificate verification");
        }
        if (delegates.size() == 1) {
            log.info("AMFI client: using the JDK trust store only (no OS root store on this platform)");
        }
        return new UnionTrustManager(delegates);
    }

    private static X509TrustManager fromOsStore(String type) {
        try {
            KeyStore ks = KeyStore.getInstance(type);
            ks.load(null, null);
            X509TrustManager tm = fromKeyStore(ks, type);
            if (tm != null) {
                log.info("AMFI client: OS root store {} loaded ({} roots) - this is what supplies the "
                        + "Sectigo R46 root that this JDK's bundled cacerts predates",
                        type, tm.getAcceptedIssuers().length);
            }
            return tm;
        } catch (Exception e) {
            // Absent on this platform. Not an error - the JDK store alone is a valid configuration.
            log.debug("OS root store {} unavailable: {}", type, e.getMessage());
            return null;
        }
    }

    private static X509TrustManager fromKeyStore(KeyStore ks, String label) {
        try {
            TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager x) {
                    return x;
                }
            }
        } catch (Exception e) {
            log.warn("Could not initialise the {} trust store: {}", label, e.getMessage());
        }
        return null;
    }

    /**
     * Accepts if any delegate accepts; rethrows the first failure when all of them reject.
     *
     * <p>The rejection path matters as much as the acceptance path: a chain no store trusts must
     * still fail, with the original reason, rather than being swallowed.
     */
    private record UnionTrustManager(List<X509TrustManager> delegates) implements X509TrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            check(tm -> tm.checkClientTrusted(chain, authType));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            check(tm -> tm.checkServerTrusted(chain, authType));
        }

        private void check(Check c) throws CertificateException {
            CertificateException first = null;
            for (X509TrustManager tm : delegates) {
                try {
                    c.run(tm);
                    return;
                } catch (CertificateException e) {
                    if (first == null) {
                        first = e;
                    }
                }
            }
            throw first != null ? first
                    : new CertificateException("No trust manager accepted the certificate chain");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            Set<X509Certificate> all = new LinkedHashSet<>();
            for (X509TrustManager tm : delegates) {
                all.addAll(Arrays.asList(tm.getAcceptedIssuers()));
            }
            return all.toArray(new X509Certificate[0]);
        }

        @FunctionalInterface
        private interface Check {
            void run(X509TrustManager tm) throws CertificateException;
        }
    }
}
