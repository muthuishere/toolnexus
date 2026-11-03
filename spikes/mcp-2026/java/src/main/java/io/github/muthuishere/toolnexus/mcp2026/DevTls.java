package io.github.muthuishere.toolnexus.mcp2026;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * DEV/TEST ONLY. One self-signed certificate for 127.0.0.1 per JVM (generated with the JDK's keytool,
 * since the JDK has no public API to mint certificates), plus the process-wide default HttpClient,
 * which trusts the system CAs and — once a dev server has started — that certificate. This mirrors Go's
 * httptest TLS server + "default clients in this process trust a DevAuthServer".
 */
final class DevTls {
    private DevTls() {}

    // Throwaway keystore password for an ephemeral, per-process dev certificate. Not a secret.
    private static final char[] PW = "dev-only".toCharArray();
    private static volatile X509Certificate devCert;
    private static SSLContext serverCtx;

    /** Default client: 30 s per request (set on each request), HTTP/1.1, no redirects, trusts dev certs. */
    static final HttpClient SHARED = newClient();

    static HttpClient newClient() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new DevAwareTrust()}, null);
            return HttpClient.newBuilder().sslContext(ctx).version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static synchronized SSLContext serverContext() {
        if (serverCtx != null) return serverCtx;
        try {
            Path dir = Files.createTempDirectory("mcp2026-devtls");
            Path ks = dir.resolve("dev.p12");
            String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
            Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "dev", "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "3650", "-dname", "CN=127.0.0.1", "-ext", "san=ip:127.0.0.1,dns:localhost",
                    "-keystore", ks.toString(), "-storetype", "PKCS12", "-storepass", new String(PW), "-keypass", new String(PW))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) throw new IllegalStateException("keytool failed");
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(ks)) {
                store.load(in, PW);
            }
            Files.deleteIfExists(ks);
            Files.deleteIfExists(dir);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(store, PW);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            devCert = (X509Certificate) store.getCertificate("dev");
            serverCtx = ctx;
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("dev TLS: " + e.getMessage(), e);
        }
    }

    /** System trust, plus the in-process dev certificate (exact match) once one exists. */
    private static final class DevAwareTrust implements X509TrustManager {
        private final X509TrustManager sys;

        DevAwareTrust() throws Exception {
            TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            f.init((KeyStore) null);
            X509TrustManager found = null;
            for (TrustManager t : f.getTrustManagers()) if (t instanceof X509TrustManager x) found = x;
            sys = found;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            X509Certificate d = devCert;
            if (d != null && chain.length > 0 && chain[0].equals(d)) return;
            sys.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            sys.checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() { return sys.getAcceptedIssuers(); }
    }
}
