package net.dcn.pce.pcep;

import net.dcn.pce.config.OperatorConfiguration;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Only named peers may establish a session.
 *
 * <p>This is not authentication and the tests do not pretend otherwise — the identity is asserted
 * by the peer over plain TCP. It exists because automatic dispatch selects "the single established
 * session", so any peer that established one would be handed this controller's LSPs. Refusing at
 * the handshake keeps an unexpected peer out of {@code establishedSessions()} entirely, which is
 * what makes it ineligible as a dispatch target.
 */
class PermittedPeerTest {

    private static void handshake(Socket peer, String speaker) throws Exception {
        DataInputStream in = new DataInputStream(peer.getInputStream());
        readFrame(in);
        peer.getOutputStream().write(PcepEncoder.open(30, 120, 1, speaker));
        peer.getOutputStream().flush();
        readFrame(in);
        peer.getOutputStream().write(PcepEncoder.keepalive());
        peer.getOutputStream().flush();
    }

    private static void readFrame(DataInputStream in) throws Exception {
        byte[] header = new byte[4];
        in.readFully(header);
        int length = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        in.readFully(new byte[length - 4]);
    }

    private static boolean awaitSession(PcepSessionServer server, String key) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            if (server.establishedSessions().contains(key)) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    /** The server closes an unpermitted connection at accept, so the peer's read hits EOF. */
    private static void handshakeExpectingRefusal(Socket peer, String speaker) throws Exception {
        try {
            handshake(peer, speaker);
        } catch (IOException expected) {
            // The refusal is the point; how far the write got before the close is not.
        }
    }

    @Test
    void aPermittedAddressEstablishes() throws Exception {
        try (PcepSessionServer server = new PcepSessionServer(
                0, "vortex-pce", (session, report) -> { })) {
            server.onlyAccepting(Set.of("127.0.0.1"));
            server.start("127.0.0.1");
            try (Socket peer = new Socket("127.0.0.1", server.getBoundPort())) {
                peer.setSoTimeout(4000);
                handshake(peer, "pcc-alpha");
                assertTrue(awaitSession(server, "speaker:pcc-alpha"));
            }
        }
    }

    @Test
    void anUnpermittedAddressNeverBecomesADispatchTarget() throws Exception {
        // The case automatic dispatch made dangerous: selection is "the single established
        // session", so a peer that got in would receive this controller's LSPs.
        try (PcepSessionServer server = new PcepSessionServer(
                0, "vortex-pce", (session, report) -> { })) {
            server.onlyAccepting(Set.of("10.99.99.99"));
            server.start("127.0.0.1");
            try (Socket peer = new Socket("127.0.0.1", server.getBoundPort())) {
                peer.setSoTimeout(4000);
                handshakeExpectingRefusal(peer, "pcc-alpha");
                assertFalse(awaitSession(server, "speaker:pcc-alpha"));
                assertTrue(server.establishedSessions().isEmpty(),
                        "a refused peer must not appear as an established session");
            }
        }
    }

    @Test
    void claimingAPermittedSpeakerNameDoesNotGrantAccess() throws Exception {
        // Regression for a real defect. The allowlist used to be checked against the durable
        // session key, which is "speaker:" + the identifier the peer sends in its own OPEN. Any
        // host that named a permitted speaker was admitted -- and then superseded the genuine
        // session for that key, because a reconnecting speaker replaces itself. The control was
        // bypassed by typing the right name.
        //
        // Here the connection comes from loopback while only 10.99.99.99 is permitted, and the
        // peer announces "pcc-alpha", exactly the kind of name an operator would have listed.
        // Under the old rule it established; it must now be refused on the address alone.
        try (PcepSessionServer server = new PcepSessionServer(
                0, "vortex-pce", (session, report) -> { })) {
            server.onlyAccepting(Set.of("10.99.99.99"));
            server.start("127.0.0.1");
            try (Socket peer = new Socket("127.0.0.1", server.getBoundPort())) {
                peer.setSoTimeout(4000);
                handshakeExpectingRefusal(peer, "pcc-alpha");
                assertFalse(awaitSession(server, "speaker:pcc-alpha"),
                        "a permitted speaker name must not admit an unpermitted address");
            }
        }
    }

    @Test
    void anEmptySetAcceptsAnyPeerAsBefore() throws Exception {
        // Every deployment that predates the allowlist keeps working.
        try (PcepSessionServer server = new PcepSessionServer(
                0, "vortex-pce", (session, report) -> { })) {
            server.onlyAccepting(Set.of());
            server.start("127.0.0.1");
            try (Socket peer = new Socket("127.0.0.1", server.getBoundPort())) {
                peer.setSoTimeout(4000);
                handshake(peer, "pcc-anyone");
                assertTrue(awaitSession(server, "speaker:pcc-anyone"));
            }
        }
    }

    @Test
    void theConfigurationParsesAndValidatesEntries() {
        OperatorConfiguration parsed = OperatorConfiguration.parse(Map.of(
                OperatorConfiguration.ENV_PCEP_PERMITTED_PEERS, " addr:10.0.0.3 , 10.0.0.4 "));
        assertEquals(Set.of("10.0.0.3", "10.0.0.4"), parsed.getPcepPermittedPeers());

        assertTrue(OperatorConfiguration.parse(Map.of()).getPcepPermittedPeers().isEmpty(),
                "unset means accept any peer");

        // 'speaker:' was the documented form and was exactly what made the allowlist
        // bypassable, so it fails loudly rather than being accepted and ignored.
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> OperatorConfiguration.parse(Map.of(
                        OperatorConfiguration.ENV_PCEP_PERMITTED_PEERS, "speaker:pcc-alpha")));
        assertTrue(refused.getMessage().contains("supplied by the peer"),
                "the refusal should say why a speaker id cannot restrict access");

        // A typo would otherwise lock out the real PCC and read as a network fault.
        assertThrows(IllegalArgumentException.class, () -> OperatorConfiguration.parse(Map.of(
                OperatorConfiguration.ENV_PCEP_PERMITTED_PEERS, "pcc-alpha")));
    }

    @Test
    void tlsCertificateSubjectsUseAnUnambiguousJsonArrayAndCanonicalNames() {
        OperatorConfiguration parsed = OperatorConfiguration.parse(Map.of(
                OperatorConfiguration.ENV_PCEP_TLS_KEYSTORE, "/run/certs/server.p12",
                OperatorConfiguration.ENV_PCEP_TLS_TRUSTSTORE, "/run/certs/trust.p12",
                OperatorConfiguration.ENV_PCEP_PERMITTED_PEERS,
                "[\"CN=pcc-alpha, OU=development-only, O=VortexPCE\","
                        + "\"O=VortexPCE,OU=development-only,CN=pcc-beta\"]"));

        assertEquals(Set.of(
                "cn=pcc-alpha,ou=development-only,o=vortexpce",
                "o=vortexpce,ou=development-only,cn=pcc-beta"),
                parsed.getPcepPermittedPeers());

        IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class,
                () -> OperatorConfiguration.parse(Map.of(
                        OperatorConfiguration.ENV_PCEP_TLS_KEYSTORE, "/run/certs/server.p12",
                        OperatorConfiguration.ENV_PCEP_TLS_TRUSTSTORE, "/run/certs/trust.p12",
                        OperatorConfiguration.ENV_PCEP_PERMITTED_PEERS,
                        "CN=pcc-alpha,CN=pcc-beta")));
        assertTrue(malformed.getMessage().contains("JSON array"));
    }
}
