"""
Experimental RFC 5440 / RFC 8281 PCEP session and message codec.
Encodes OPEN, KEEPALIVE, and PCInitiate frames; PCRpt and PCUpd processing are not implemented.
"""

import ipaddress
import math
import socket
import struct
import threading
import time
from typing import Dict, Any, List, Optional, Tuple

PCEP_VERSION = 1  # Standard RFC 5440 PCEP version 1 header value

# PCEP Message Types (RFC 5440 / RFC 8231 / RFC 8281)
MSG_TYPE_OPEN = 1
MSG_TYPE_KEEPALIVE = 2
MSG_TYPE_PCRPT = 10
MSG_TYPE_PCUPD = 11
MSG_TYPE_PCINITIATE = 12

PCEP_COMMON_HEADER_LENGTH = 4
PCEP_MAX_MESSAGE_LENGTH = 0xFFFF
STATEFUL_PCE_CAPABILITY_TLV = 16
# RFC 8232 SPEAKER-ENTITY-ID. A speaker's identity persists across restarts and
# reconnections, which is precisely what a transport tuple does not.
SPEAKER_ENTITY_ID_TLV = 24
STATEFUL_CAPABILITY_UPDATE = 0x001
STATEFUL_CAPABILITY_SYNC = 0x002
STATEFUL_CAPABILITY_INSTANTIATION = 0x004

# RFC 8231/8281 LSP Object flags. IANA numbers bit 0 from the MSB of
# the 12-bit flag field, so RFC 8281 Create bit 4 is wire mask 0x080.
LSP_FLAG_CREATE = 0x080
LSP_OPERATIONAL_MASK = 0x070
LSP_FLAG_ADMINISTRATIVE = 0x008
LSP_FLAG_REMOVE = 0x004
LSP_FLAG_SYNC = 0x002
LSP_FLAG_DELEGATE = 0x001


def encode_pcep_header(msg_type: int, payload_len: int) -> bytes:
    """
    Encodes 4-byte RFC 5440 PCEP Common Header:
    - Version: 3 bits (value 1 -> 0x20)
    - Flags: 5 bits (0)
    - Message-Type: 8 bits
    - Message-Length: 16 bits (including 4-byte header)
    """
    if not 0 <= msg_type <= 0xFF:
        raise ValueError("PCEP message type must fit in one octet")
    if payload_len < 0 or payload_len > PCEP_MAX_MESSAGE_LENGTH - PCEP_COMMON_HEADER_LENGTH:
        raise ValueError("PCEP payload exceeds the 16-bit message-length field")
    version_flags = (PCEP_VERSION & 0x07) << 5
    total_len = PCEP_COMMON_HEADER_LENGTH + payload_len
    return struct.pack(">BBH", version_flags, msg_type, total_len)


def decode_pcep_header(header_bytes: bytes) -> Optional[Dict[str, int]]:
    """Decodes 4-byte RFC 5440 PCEP Common Header."""
    if len(header_bytes) != PCEP_COMMON_HEADER_LENGTH:
        return None
    ver_flags, msg_type, msg_len = struct.unpack(">BBH", header_bytes)
    version = (ver_flags >> 5) & 0x07
    if msg_len < PCEP_COMMON_HEADER_LENGTH:
        return None
    return {
        "version": version,
        "message_type": msg_type,
        "message_length": msg_len
    }


class PCEPMessage:
    """IETF RFC 8281 PCInitiate Protocol Object Builder with PCEP CoS Extensions."""

    @staticmethod
    def encode_binary_open(
        keepalive_sec: int = 30,
        deadtimer_sec: int = 120,
        sid: int = 1,
        speaker_entity_id: Optional[str] = None,
    ) -> bytes:
        """Encode an OPEN that advertises RFC 8281 LSP instantiation support.

        When ``speaker_entity_id`` is supplied it is advertised as an RFC 8232
        SPEAKER-ENTITY-ID TLV so a peer can recognise this speaker across
        reconnections.
        """
        if not 1 <= keepalive_sec <= 0xFF:
            raise ValueError("keepalive_sec must be in [1, 255]")
        if not keepalive_sec < deadtimer_sec <= 0xFF:
            raise ValueError("deadtimer_sec must be greater than keepalive_sec and at most 255")
        if not 0 <= sid <= 0xFF:
            raise ValueError("sid must be in [0, 255]")
        ot_flags = (1 & 0x0F) << 4
        stateful_flags = STATEFUL_CAPABILITY_UPDATE | STATEFUL_CAPABILITY_SYNC | STATEFUL_CAPABILITY_INSTANTIATION
        capability_tlv = struct.pack(
            ">HHI", STATEFUL_PCE_CAPABILITY_TLV, 4, stateful_flags
        )
        if speaker_entity_id:
            identity = speaker_entity_id.encode("utf-8")
            if not identity or len(identity) > 0xFFFF:
                raise ValueError("speaker_entity_id must be a non-empty string")
            padded = (len(identity) + 3) & ~3
            capability_tlv += (
                struct.pack(">HH", SPEAKER_ENTITY_ID_TLV, len(identity))
                + identity
                + (b"\x00" * (padded - len(identity)))
            )
        object_length = 8 + len(capability_tlv)
        open_obj = (
            struct.pack(">BBHBBBB", 1, ot_flags, object_length,
                        (PCEP_VERSION & 0x07) << 5, keepalive_sec, deadtimer_sec, sid)
            + capability_tlv
        )
        return encode_pcep_header(MSG_TYPE_OPEN, len(open_obj)) + open_obj

    @staticmethod
    def encode_binary_keepalive() -> bytes:
        """Generates binary RFC 5440 KEEPALIVE packet (4 bytes)."""
        return encode_pcep_header(MSG_TYPE_KEEPALIVE, 0)

    @staticmethod
    def encode_binary_pcinitiate(
        srp_id: int,
        lsp_name: str,
        ingress_ip: str,
        egress_ip: str,
        rate_bps: float,
        ero_path: Optional[List[str]] = None
    ) -> bytes:
        """Encode an RFC 8281 PCInitiate create request for an IPv4 path."""
        if not 1 <= srp_id <= 0xFFFFFFFF:
            raise ValueError("srp_id must be a non-zero unsigned 32-bit integer")
        if not lsp_name or not lsp_name.isascii() or not lsp_name.isprintable():
            raise ValueError("lsp_name must be a non-empty printable ASCII string")
        if not math.isfinite(rate_bps) or rate_bps <= 0:
            raise ValueError("rate_bps must be finite and positive")

        ingress = ipaddress.IPv4Address(ingress_ip)
        egress = ipaddress.IPv4Address(egress_ip)
        hops = [ingress, egress] if ero_path is None else [ipaddress.IPv4Address(hop) for hop in ero_path]
        if not hops:
            raise ValueError("ero_path must contain at least one IPv4 hop")

        # 1. SRP Object: Class=33 (0x21), OT=1 (0x10), Length=12, Flags=0, SRP-ID
        srp_obj = struct.pack(">BBHI", 33, (1 & 0x0F) << 4, 12, 0) + struct.pack(">I", srp_id)

        # 2. LSP Object: PLSP-ID=0 and SRP R=0 request creation. C and D are
        # reported by the PCC in the resulting PCRpt, not asserted by the PCE.
        lsp_name_bytes = lsp_name.encode("ascii")
        tlv_len = len(lsp_name_bytes)
        # Pad TLV to 4-byte alignment
        padded_tlv_len = (tlv_len + 3) & ~3
        tlv_bytes = struct.pack(">HH", 17, tlv_len) + lsp_name_bytes + (b"\x00" * (padded_tlv_len - tlv_len))
        lsp_hdr_len = 8 + len(tlv_bytes)
        if lsp_hdr_len > 0xFFFF:
            raise ValueError("lsp_name is too long for the LSP object")
        lsp_obj = struct.pack(">BBHI", 32, (1 & 0x0F) << 4, lsp_hdr_len, LSP_FLAG_ADMINISTRATIVE) + tlv_bytes

        # 3. END-POINTS Object: Class=4 (0x04), OT=1 (0x10), Length=12, IPv4 Src, IPv4 Dst
        src_bytes = ingress.packed
        dst_bytes = egress.packed
        endpoints_obj = struct.pack(">BBH", 4, (1 & 0x0F) << 4, 12) + src_bytes + dst_bytes

        # 4. ERO Object: Class=7 (0x07), OT=1 (0x10), IPv4 Subobjects (Type 1, Length 8)
        ero_bytes = b""
        for hop in hops:
            # IPv4 Subobject: Type=1, Length=8, IPv4 Addr, Prefix=32, Res=0
            ero_bytes += struct.pack(">BB", 1, 8) + hop.packed + struct.pack(">BB", 32, 0)
        ero_obj = struct.pack(">BBH", 7, (1 & 0x0F) << 4, 4 + len(ero_bytes)) + ero_bytes

        # 5. BANDWIDTH Object: Class=5 (0x05), OT=1 (0x10), Length=8, Float Rate in Bytes/sec (RFC 5440)
        bandwidth_bytes_per_sec = rate_bps / 8.0
        if bandwidth_bytes_per_sec > 3.4028235e38:
            raise ValueError("rate_bps exceeds the RFC 5440 32-bit float range")
        bandwidth_obj = struct.pack(">BBHf", 5, (1 & 0x0F) << 4, 8, float(bandwidth_bytes_per_sec))

        payload = srp_obj + lsp_obj + endpoints_obj + ero_obj + bandwidth_obj
        return encode_pcep_header(MSG_TYPE_PCINITIATE, len(payload)) + payload


    @staticmethod
    def create_pcinitiate(
        srp_id: int,
        lsp_name: str,
        ingress_ip: str,
        egress_ip: str,
        ero_path: List[str],
        rate_bps: float,
        cos_class: str = "HIGH",
        laxity_ms: int = 0,
        penalty_score: float = 0.0
    ) -> Dict[str, Any]:
        cos_code_map = {"HIGH": 1, "MEDIUM": 2, "LOW": 3}
        return {
            "pcep_header": {
                "version": PCEP_VERSION,
                "flags": 0,
                "message_type": MSG_TYPE_PCINITIATE,
                "message_length": 144
            },
            "srp_object": {
                "srp_id": srp_id,
                "remove_flag": False
            },
            "lsp_object": {
                "plsp_id": 0,
                "delegate": False,
                "create_request": True,
                "administrative_state": "UP",
                "lsp_name": lsp_name
            },
            "endpoints_object": {
                "source": ingress_ip,
                "destination": egress_ip
            },
            "ero_object": {
                "explicit_route_hops": ero_path
            },
            "bandwidth_object": {
                "allocated_rate_bps": rate_bps,
                "dfe_pacing_enabled": True
            },
            "cos_attribute_object": {
                "cos_class": cos_class.upper(),
                "cos_code": cos_code_map.get(cos_class.upper(), 1),
                "laxity_window_ms": laxity_ms,
                "penalty_score": penalty_score
            }
        }


class PCEPStatefulServer:
    """
    Stateful PCEP Server listening on TCP 4189 for PCC Session Dissemination.
    Implements RFC 5440 4-State Session FSM (IDLE -> OPENSENT -> OPENWAIT -> ESTABLISHED).
    """
    @staticmethod
    def session_key_for(peer_address: str, speaker_entity_id: Optional[str]) -> str:
        """Durable identity for a PCC session.

        Sessions were previously keyed by ``ip:port``. The ephemeral source port changes on
        every reconnect, so a returning PCC was indistinguishable from a new one and the LSPs
        it had already reported could not be re-associated with it. Any reconciliation of
        controller intent against reported network state depends on that association, so the
        key has to survive a reconnect.

        RFC 8232 SPEAKER-ENTITY-ID is the identifier designed for this and is used when the
        peer advertises it. Otherwise the key degrades to the peer address, which survives a
        reconnect from the same host but cannot distinguish two speakers behind one address.
        """
        if speaker_entity_id:
            return f"speaker:{speaker_entity_id}"
        return f"addr:{peer_address}"

    def __init__(self, host: str = "127.0.0.1", port: int = 4189, keepalive_sec: int = 30,
                 deadtimer_sec: int = 120, speaker_entity_id: Optional[str] = None):
        if not 0 <= port <= 65535:
            raise ValueError("port must be in [0, 65535]")
        if not 1 <= keepalive_sec < deadtimer_sec <= 255:
            raise ValueError("PCEP timers must satisfy 1 <= keepalive < deadtimer <= 255")
        self.host = host
        self.port = port
        self.keepalive_sec = keepalive_sec
        self.deadtimer_sec = deadtimer_sec
        self.speaker_entity_id = speaker_entity_id
        self.running = False
        self.sock = None
        self.active_sessions = {}
        self._session_locks = {}
        self._state_lock = threading.Lock()

    def start(self):
        """Start the PCEP TCP listener on the configured port."""
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind((self.host, self.port))
        self.port = self.sock.getsockname()[1]
        self.sock.listen(5)
        self.running = True

        thread = threading.Thread(target=self._listen_loop, daemon=True)
        thread.start()

        print(f"[PCEP Engine] Stateful PCEP Server listening on TCP port {self.port}...")

    def _listen_loop(self):
        while self.running:
            try:
                client_sock, addr = self.sock.accept()
                # The transport tuple is only a label for logging until the OPEN exchange
                # tells us who the peer actually is. The session is keyed on identity.
                peer = f"{addr[0]}:{addr[1]}"
                print(f"[PCEP Engine] Active PCEP Connection from PCC: {peer}")

                session_thread = threading.Thread(
                    target=self._handle_client_session, args=(client_sock, addr), daemon=True)
                session_thread.start()
            except Exception:
                break

    @staticmethod
    def _recv_exact(client_sock: socket.socket, length: int) -> bytes:
        chunks = bytearray()
        while len(chunks) < length:
            chunk = client_sock.recv(length - len(chunks))
            if not chunk:
                raise ConnectionError("PCEP peer closed the connection")
            chunks.extend(chunk)
        return bytes(chunks)

    @classmethod
    def _recv_frame(cls, client_sock: socket.socket) -> Tuple[Dict[str, int], bytes]:
        raw_header = cls._recv_exact(client_sock, PCEP_COMMON_HEADER_LENGTH)
        header = decode_pcep_header(raw_header)
        if header is None or header["version"] != PCEP_VERSION:
            raise ValueError("invalid PCEP common header")
        payload = cls._recv_exact(client_sock, header["message_length"] - PCEP_COMMON_HEADER_LENGTH)
        return header, payload

    @staticmethod
    def _validate_open_payload(payload: bytes) -> Optional[str]:
        """Validate a peer OPEN and return its SPEAKER-ENTITY-ID when advertised."""
        if len(payload) < 8:
            raise ValueError("OPEN message is missing its OPEN object")
        object_class, object_type_flags, object_length = struct.unpack(">BBH", payload[:4])
        if object_class != 1 or (object_type_flags >> 4) != 1 or object_length < 8:
            raise ValueError("OPEN message contains an invalid OPEN object header")
        if len(payload) != object_length or object_length % 4 != 0:
            raise ValueError("OPEN object is truncated")
        open_version, keepalive, deadtimer, _sid = struct.unpack(">BBBB", payload[4:8])
        if ((open_version >> 5) & 0x07) != PCEP_VERSION:
            raise ValueError("OPEN object has an unsupported version")
        if keepalive == 0 or deadtimer <= keepalive:
            raise ValueError("OPEN object contains invalid keepalive/dead-timer values")

        offset = 8
        supports_instantiation = False
        speaker_entity_id = None
        while offset < object_length:
            if object_length - offset < 4:
                raise ValueError("OPEN object contains a truncated TLV")
            tlv_type, tlv_length = struct.unpack(">HH", payload[offset:offset + 4])
            padded_length = (tlv_length + 3) & ~3
            tlv_end = offset + 4 + padded_length
            if tlv_end > object_length:
                raise ValueError("OPEN object contains an invalid TLV length")
            if tlv_type == STATEFUL_PCE_CAPABILITY_TLV:
                if tlv_length != 4:
                    raise ValueError("STATEFUL-PCE-CAPABILITY TLV must be four octets")
                flags = struct.unpack(">I", payload[offset + 4:offset + 8])[0]
                supports_instantiation = bool(flags & STATEFUL_CAPABILITY_INSTANTIATION)
            elif tlv_type == SPEAKER_ENTITY_ID_TLV:
                if tlv_length == 0:
                    raise ValueError("SPEAKER-ENTITY-ID TLV must not be empty")
                raw = payload[offset + 4:offset + 4 + tlv_length]
                try:
                    speaker_entity_id = raw.decode("utf-8")
                except UnicodeDecodeError:
                    # The RFC does not constrain the encoding, so fall back to a stable
                    # hex rendering rather than rejecting an otherwise valid peer.
                    speaker_entity_id = raw.hex()
                if not speaker_entity_id.strip():
                    raise ValueError("SPEAKER-ENTITY-ID TLV must not be blank")
            offset = tlv_end

        if not supports_instantiation:
            raise ValueError("peer did not advertise LSP instantiation capability")
        return speaker_entity_id

    def send_message(self, session_key: str, message: bytes) -> None:
        """Send one fully encoded PCEP message to an established peer."""
        header = decode_pcep_header(message[:PCEP_COMMON_HEADER_LENGTH])
        if header is None or header["message_length"] != len(message):
            raise ValueError("message is not a complete PCEP frame")
        with self._state_lock:
            client_sock = self.active_sessions.get(session_key)
            session_lock = self._session_locks.get(session_key)
        if client_sock is None or session_lock is None:
            raise KeyError(f"unknown PCEP session: {session_key}")
        with session_lock:
            client_sock.sendall(message)

    def _handle_client_session(self, client_sock: socket.socket, addr):
        peer = f"{addr[0]}:{addr[1]}"
        session_key = None
        try:
            client_sock.settimeout(self.deadtimer_sec)
            client_sock.sendall(PCEPMessage.encode_binary_open(
                self.keepalive_sec, self.deadtimer_sec,
                speaker_entity_id=self.speaker_entity_id))

            header, payload = self._recv_frame(client_sock)
            if header["message_type"] != MSG_TYPE_OPEN:
                raise ValueError("first peer message must be OPEN")
            speaker_entity_id = self._validate_open_payload(payload)
            client_sock.sendall(PCEPMessage.encode_binary_keepalive())

            header, _payload = self._recv_frame(client_sock)
            if header["message_type"] != MSG_TYPE_KEEPALIVE or header["message_length"] != 4:
                raise ValueError("peer must acknowledge OPEN with KEEPALIVE")

            session_key = self.session_key_for(addr[0], speaker_entity_id)
            superseded = None
            with self._state_lock:
                # A speaker reconnecting replaces its own prior session. Keeping both would
                # leave messages going to a dead socket while the live one sat unused.
                superseded = self.active_sessions.get(session_key)
                self.active_sessions[session_key] = client_sock
                self._session_locks[session_key] = threading.Lock()
            if superseded is not None and superseded is not client_sock:
                print(f"[PCEP Engine] Superseding stale session for PCC: {session_key}")
                try:
                    superseded.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                superseded.close()
            print(f"[PCEP Engine] PCEP Session FSM ESTABLISHED with PCC: {session_key} (from {peer})")

            last_receive = time.monotonic()
            client_sock.settimeout(self.keepalive_sec)
            while self.running:
                try:
                    self._recv_frame(client_sock)
                    last_receive = time.monotonic()
                except socket.timeout:
                    if time.monotonic() - last_receive >= self.deadtimer_sec:
                        break
                    client_sock.sendall(PCEPMessage.encode_binary_keepalive())
        except (ConnectionError, OSError, ValueError):
            pass
        finally:
            client_sock.close()
            if session_key is not None:
                with self._state_lock:
                    # Only clear the registry if it still points at this socket: a newer
                    # session for the same speaker must not be evicted by an older one's
                    # teardown.
                    if self.active_sessions.get(session_key) is client_sock:
                        self.active_sessions.pop(session_key, None)
                        self._session_locks.pop(session_key, None)
                print(f"[PCEP Engine] Session closed for PCC: {session_key}")
            else:
                print(f"[PCEP Engine] Session closed before establishment: {peer}")

    def stop(self):
        self.running = False
        if self.sock:
            self.sock.close()
        with self._state_lock:
            sessions = list(self.active_sessions.values())
        for client_sock in sessions:
            try:
                client_sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            client_sock.close()
        print("[PCEP Engine] PCEP Server stopped.")
