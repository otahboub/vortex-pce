package net.dcn.pce.pcep;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.security.auth.x500.X500Principal;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Optional;

/**
 * PCEPS (RFC 8253): TLS with mutual certificate authentication for the southbound listener.
 *
 * <p>Every review of this controller has raised the same finding — a peer asserts its identity and
 * the controller believes it. The source-address allowlist that preceded this narrowed who could
 * assert it, but an address is forgeable by anyone on-path and proves nothing about who is at the
 * other end. Automatic dispatch makes that concrete: the controller programs paths onto "the
 * single established session", so a peer that establishes one is handed real LSPs.
 *
 * <p>A client certificate is different in kind. The peer must possess a private key that the
 * configured trust store vouches for, and the certificate subject becomes an identity the
 * controller did not take on trust. That is what {@code authenticatedPeer} returns, and it is
 * what admission is checked against when TLS is enabled.
 *
 * <p>Off unless configured, because turning it on requires the operator to have issued
 * certificates and every existing deployment predates them. When it is off the listener is plain
 * TCP and says so at startup rather than implying protection it does not have.
 */
public final class PcepTransportSecurity {

    private final SSLContext context;

    private PcepTransportSecurity(SSLContext context) {
        this.context = context;
    }

    /**
     * Builds a mutual-TLS context from PKCS#12 stores.
     *
     * @param keyStorePath   this controller's certificate and private key
     * @param trustStorePath the certificate authority that legitimate PCCs are signed by
     */
    public static PcepTransportSecurity fromKeyStores(
            Path keyStorePath, char[] keyStorePassword,
            Path trustStorePath, char[] trustStorePassword)
            throws IOException, GeneralSecurityException {
        KeyManagerFactory keyManagers =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(load(keyStorePath, keyStorePassword), keyStorePassword);

        TrustManagerFactory trustManagers =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(load(trustStorePath, trustStorePassword));

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
        return new PcepTransportSecurity(context);
    }

    private static KeyStore load(Path path, char[] password)
            throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(path)) {
            store.load(in, password);
        }
        return store;
    }

    /**
     * Wraps the accepted socket in TLS and requires the peer to present a trusted certificate.
     *
     * <p>{@code setNeedClientAuth} rather than {@code setWantClientAuth}: wanting a certificate
     * and continuing without one is indistinguishable from not asking, which is the behaviour
     * this replaces.
     */
    public SSLServerSocket newServerSocket() throws IOException {
        SSLServerSocket socket =
                (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
        socket.setNeedClientAuth(true);
        return socket;
    }

    /**
     * The authenticated identity of a peer, or empty when the socket is not TLS.
     *
     * <p>Reads the subject of the certificate the peer proved possession of during the handshake.
     * Unlike a speaker entity ID or a source address, the peer cannot choose this without the
     * corresponding private key.
     */
    public static Optional<String> authenticatedPeer(Socket socket) {
        if (!(socket instanceof SSLSocket tls)) {
            return Optional.empty();
        }
        try {
            SSLSession session = tls.getSession();
            java.security.cert.Certificate[] chain = session.getPeerCertificates();
            if (chain.length == 0 || !(chain[0] instanceof X509Certificate certificate)) {
                return Optional.empty();
            }
            return Optional.of(certificate.getSubjectX500Principal()
                    .getName(X500Principal.CANONICAL));
        } catch (javax.net.ssl.SSLPeerUnverifiedException e) {
            // Should be unreachable with setNeedClientAuth(true), which fails the handshake
            // rather than establishing an unverified session. Treated as no identity regardless:
            // an unverified peer must never read as an authenticated one.
            return Optional.empty();
        }
    }
}
