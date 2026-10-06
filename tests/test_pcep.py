import socket
import struct
import time
import unittest

from crp_pce.pcep import (
    LSP_FLAG_ADMINISTRATIVE,
    LSP_FLAG_CREATE,
    MSG_TYPE_KEEPALIVE,
    MSG_TYPE_OPEN,
    PCEPMessage,
    PCEPStatefulServer,
    STATEFUL_CAPABILITY_INSTANTIATION,
    STATEFUL_PCE_CAPABILITY_TLV,
    decode_pcep_header,
    encode_pcep_header,
)


def recv_exact(sock, length):
    data = bytearray()
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            raise ConnectionError("socket closed")
        data.extend(chunk)
    return bytes(data)


def recv_frame(sock):
    raw_header = recv_exact(sock, 4)
    header = decode_pcep_header(raw_header)
    if header is None:
        raise ValueError("invalid header")
    payload = recv_exact(sock, header["message_length"] - 4)
    return header, payload


class PCEPMessageTest(unittest.TestCase):
    def test_pcinitiate_has_valid_boundaries_flags_and_bandwidth(self):
        message = PCEPMessage.encode_binary_pcinitiate(
            7,
            "LSP-A",
            "10.0.0.1",
            "10.0.0.2",
            8_000_000,
            ["10.0.0.1", "10.0.0.2"],
        )

        self.assertEqual(len(message), struct.unpack(">H", message[2:4])[0])
        objects = []
        offset = 4
        while offset < len(message):
            object_class, object_type_flags, object_length = struct.unpack(">BBH", message[offset:offset + 4])
            self.assertGreaterEqual(object_length, 4)
            objects.append((offset, object_class, object_type_flags >> 4, object_length))
            offset += object_length

        self.assertEqual(offset, len(message))
        self.assertEqual(
            objects,
            [(4, 33, 1, 12), (16, 32, 1, 20), (36, 4, 1, 12), (48, 7, 1, 20), (68, 5, 1, 8)],
        )
        lsp_flags = struct.unpack(">I", message[20:24])[0] & 0xFFF
        self.assertEqual(lsp_flags, LSP_FLAG_ADMINISTRATIVE)
        self.assertEqual(lsp_flags & LSP_FLAG_CREATE, 0)
        self.assertEqual(struct.unpack(">f", message[-4:])[0], 1_000_000.0)

    def test_pcinitiate_rejects_invalid_inputs(self):
        valid = (1, "LSP-A", "10.0.0.1", "10.0.0.2", 1_000_000)
        invalid_calls = [
            (0, "LSP-A", "10.0.0.1", "10.0.0.2", 1_000_000, None),
            (1, "", "10.0.0.1", "10.0.0.2", 1_000_000, None),
            (1, "LSP-\N{SNOWMAN}", "10.0.0.1", "10.0.0.2", 1_000_000, None),
            (1, "LSP-A", "not-an-ip", "10.0.0.2", 1_000_000, None),
            (1, "LSP-A", "10.0.0.1", "10.0.0.2", 0, None),
            (1, "LSP-A", "10.0.0.1", "10.0.0.2", 1_000_000, []),
            (1, "LSP-A", "10.0.0.1", "10.0.0.2", 1_000_000, ["bad-hop"]),
        ]
        for args in invalid_calls:
            with self.subTest(args=args):
                with self.assertRaises((ValueError, TypeError)):
                    PCEPMessage.encode_binary_pcinitiate(*args)

        self.assertIsInstance(PCEPMessage.encode_binary_pcinitiate(*valid), bytes)

    def test_common_header_rejects_invalid_lengths(self):
        self.assertIsNone(decode_pcep_header(b"\x20\x01\x00\x00"))
        self.assertIsNone(decode_pcep_header(b"\x20\x01\x00"))
        with self.assertRaises(ValueError):
            encode_pcep_header(MSG_TYPE_OPEN, 65_532)


class PCEPListenerDefaultsTest(unittest.TestCase):
    def test_the_default_listener_is_loopback_only(self):
        server = PCEPStatefulServer(port=0)
        self.assertEqual("127.0.0.1", server.host)


class PCEPSessionTest(unittest.TestCase):
    def setUp(self):
        self.server = PCEPStatefulServer(host="127.0.0.1", port=0, keepalive_sec=1, deadtimer_sec=3)
        self.server.start()

    def tearDown(self):
        self.server.stop()

    def test_complete_handshake_and_outbound_message(self):
        with socket.create_connection(("127.0.0.1", self.server.port), timeout=2) as peer:
            peer.settimeout(2)
            server_open, payload = recv_frame(peer)
            self.assertEqual(server_open["message_type"], MSG_TYPE_OPEN)
            self.assertEqual(len(payload), 16)
            self.assertEqual(struct.unpack(">HH", payload[8:12]), (STATEFUL_PCE_CAPABILITY_TLV, 4))
            self.assertEqual(
                struct.unpack(">I", payload[12:16])[0] & STATEFUL_CAPABILITY_INSTANTIATION,
                STATEFUL_CAPABILITY_INSTANTIATION,
            )

            peer_open = PCEPMessage.encode_binary_open(keepalive_sec=1, deadtimer_sec=3, sid=9)
            for octet in peer_open:
                peer.sendall(bytes([octet]))
            keepalive, payload = recv_frame(peer)
            self.assertEqual(keepalive["message_type"], MSG_TYPE_KEEPALIVE)
            self.assertEqual(payload, b"")
            peer.sendall(PCEPMessage.encode_binary_keepalive())

            deadline = time.monotonic() + 2
            while time.monotonic() < deadline and not self.server.active_sessions:
                time.sleep(0.01)
            self.assertEqual(len(self.server.active_sessions), 1)

            session_key = next(iter(self.server.active_sessions))
            outbound = PCEPMessage.encode_binary_pcinitiate(
                10, "LSP-B", "192.0.2.1", "192.0.2.2", 2_000_000
            )
            self.server.send_message(session_key, outbound)
            header, payload = recv_frame(peer)
            self.assertEqual(header["message_length"], len(outbound))
            self.assertEqual(header["message_type"], 12)
            self.assertEqual(len(payload), len(outbound) - 4)

    def test_keepalive_cannot_replace_peer_open(self):
        with socket.create_connection(("127.0.0.1", self.server.port), timeout=2) as peer:
            peer.settimeout(2)
            recv_frame(peer)
            peer.sendall(PCEPMessage.encode_binary_keepalive())
            self.assertEqual(peer.recv(1), b"")
            self.assertFalse(self.server.active_sessions)

    def test_peer_must_advertise_instantiation_capability(self):
        with socket.create_connection(("127.0.0.1", self.server.port), timeout=2) as peer:
            peer.settimeout(2)
            recv_frame(peer)
            peer_open = bytearray(PCEPMessage.encode_binary_open(keepalive_sec=1, deadtimer_sec=3))
            peer_open[-4:] = b"\x00\x00\x00\x00"
            peer.sendall(peer_open)
            self.assertEqual(peer.recv(1), b"")
            self.assertFalse(self.server.active_sessions)


if __name__ == "__main__":
    unittest.main()
