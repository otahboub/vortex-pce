"""Session identity across reconnects.

Sessions used to be keyed by ``ip:port``. The ephemeral source port changes on every
reconnect, so a returning PCC looked like a new peer and the LSPs it had already reported
could not be re-associated with it. Reconciling controller intent against reported network
state depends on that association surviving a reconnect.
"""
import socket
import struct
import time
import unittest

from crp_pce.pcep import (
    MSG_TYPE_KEEPALIVE,
    MSG_TYPE_OPEN,
    SPEAKER_ENTITY_ID_TLV,
    PCEPMessage,
    PCEPStatefulServer,
)


class SessionKeyTest(unittest.TestCase):
    def test_speaker_identity_is_preferred_over_the_transport_address(self):
        self.assertEqual(
            "speaker:pcc-alpha",
            PCEPStatefulServer.session_key_for("10.0.0.1", "pcc-alpha"),
        )

    def test_the_same_speaker_keys_identically_from_a_different_address(self):
        # A PCC that reconnects from a new source port, or moves address entirely, must
        # still resolve to the session the controller already knows about.
        first = PCEPStatefulServer.session_key_for("10.0.0.1", "pcc-alpha")
        second = PCEPStatefulServer.session_key_for("10.0.0.9", "pcc-alpha")
        self.assertEqual(first, second)

    def test_distinct_speakers_never_collide(self):
        self.assertNotEqual(
            PCEPStatefulServer.session_key_for("10.0.0.1", "pcc-alpha"),
            PCEPStatefulServer.session_key_for("10.0.0.1", "pcc-beta"),
        )

    def test_without_an_identity_the_key_degrades_to_the_address_not_the_port(self):
        # Degraded, but still stable across a reconnect from the same host, which the
        # previous ip:port key was not.
        self.assertEqual(
            PCEPStatefulServer.session_key_for("10.0.0.1", None),
            PCEPStatefulServer.session_key_for("10.0.0.1", None),
        )
        self.assertNotEqual(
            PCEPStatefulServer.session_key_for("10.0.0.1", None),
            PCEPStatefulServer.session_key_for("10.0.0.2", None),
        )


class OpenSpeakerIdentityTest(unittest.TestCase):
    def test_open_advertises_and_round_trips_a_speaker_entity_id(self):
        frame = PCEPMessage.encode_binary_open(30, 120, speaker_entity_id="pcc-alpha")
        payload = frame[4:]

        self.assertEqual("pcc-alpha", PCEPStatefulServer._validate_open_payload(payload))

    def test_an_open_without_the_tlv_reports_no_identity(self):
        payload = PCEPMessage.encode_binary_open(30, 120)[4:]

        self.assertIsNone(PCEPStatefulServer._validate_open_payload(payload))

    def test_a_non_ascii_identity_still_round_trips(self):
        frame = PCEPMessage.encode_binary_open(30, 120, speaker_entity_id="pcc-ünïcode")
        self.assertEqual(
            "pcc-ünïcode",
            PCEPStatefulServer._validate_open_payload(frame[4:]),
        )

    def test_a_blank_identity_is_rejected(self):
        identity = b"   "
        tlv = struct.pack(">HH", SPEAKER_ENTITY_ID_TLV, len(identity)) + identity + b"\x00"
        capability = struct.pack(">HHI", 16, 4, 0x004)
        body = capability + tlv
        open_obj = struct.pack(">BBHBBBB", 1, 1 << 4, 8 + len(body), 1 << 5, 30, 120, 1) + body

        with self.assertRaises(ValueError):
            PCEPStatefulServer._validate_open_payload(open_obj)


class ReconnectSupersedesTest(unittest.TestCase):
    """End to end: a reconnecting speaker replaces its own session rather than adding one."""

    def _handshake(self, port, speaker):
        peer = socket.create_connection(("127.0.0.1", port), timeout=5)
        header, _ = PCEPStatefulServer._recv_frame(peer)
        assert header["message_type"] == MSG_TYPE_OPEN
        peer.sendall(PCEPMessage.encode_binary_open(30, 120, speaker_entity_id=speaker))
        header, _ = PCEPStatefulServer._recv_frame(peer)
        assert header["message_type"] == MSG_TYPE_KEEPALIVE
        peer.sendall(PCEPMessage.encode_binary_keepalive())
        return peer

    def _await_session(self, server, key, timeout=5.0):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if key in server.active_sessions:
                return True
            time.sleep(0.02)
        return False

    def test_a_reconnecting_speaker_replaces_its_session(self):
        server = PCEPStatefulServer(host="127.0.0.1", port=0, speaker_entity_id="vortex-test")
        server.start()
        key = PCEPStatefulServer.session_key_for("127.0.0.1", "pcc-alpha")
        try:
            first = self._handshake(server.port, "pcc-alpha")
            self.assertTrue(self._await_session(server, key), "first session should establish")

            # Reconnect from a new source port, exactly as a restarted PCC would.
            second = self._handshake(server.port, "pcc-alpha")
            self.assertTrue(self._await_session(server, key))

            # One entry, not two: under the old ip:port key this was two unrelated sessions.
            self.assertEqual(
                1,
                sum(1 for k in server.active_sessions if k == key),
                f"expected a single session for the speaker, saw {list(server.active_sessions)}",
            )
            self.assertEqual(1, len(server.active_sessions), list(server.active_sessions))

            first.close()
            second.close()
        finally:
            server.stop()

    def test_two_distinct_speakers_hold_separate_sessions(self):
        server = PCEPStatefulServer(host="127.0.0.1", port=0, speaker_entity_id="vortex-test")
        server.start()
        try:
            alpha = self._handshake(server.port, "pcc-alpha")
            beta = self._handshake(server.port, "pcc-beta")
            self.assertTrue(self._await_session(
                server, PCEPStatefulServer.session_key_for("127.0.0.1", "pcc-alpha")))
            self.assertTrue(self._await_session(
                server, PCEPStatefulServer.session_key_for("127.0.0.1", "pcc-beta")))

            self.assertEqual(2, len(server.active_sessions), list(server.active_sessions))

            alpha.close()
            beta.close()
        finally:
            server.stop()


if __name__ == "__main__":
    unittest.main()
