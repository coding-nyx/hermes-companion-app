"""Reachable companion bind. Proxies the loopback dashboard. Never SHUT_WR.

Lab Hermes often listens on 127.0.0.1:9119. The phone cannot use a laptop SSH
tunnel. Run this on the Hermes host so the phone dials Tailscale :9120.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import socket
import struct
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

from pairing import PairingError, PairingStore, default_store_path
from tickets import ALLOWLIST, PROTOCOL, TICKET_PREFIX, TicketError, TicketStore, parse_subprotocols

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

DEFAULT_BIND = os.environ.get("HERMES_COMPANION_BIND", "0.0.0.0:9120")
DEFAULT_UPSTREAM = os.environ.get("HERMES_DASHBOARD", "http://127.0.0.1:9119")


def parse_bind(bind: str) -> tuple[str, int]:
    raw = (bind or DEFAULT_BIND).strip()
    if "://" in raw:
        raw = raw.split("://", 1)[1]
    host, _, port = raw.rpartition(":")
    return (host or "0.0.0.0"), int(port or "9120")


def parse_upstream(url: str) -> tuple[str, int]:
    parsed = urlparse(url if "://" in url else f"http://{url}")
    return parsed.hostname or "127.0.0.1", parsed.port or 80


def _ws_accept(key: str) -> str:
    digest = hashlib.sha1((key + WS_GUID).encode()).digest()
    return base64.b64encode(digest).decode()


def _ws_text(msg: str) -> bytes:
    data = msg.encode()
    n = len(data)
    if n < 126:
        return bytes([0x81, n]) + data
    if n < 65536:
        return bytes([0x81, 126]) + struct.pack("!H", n) + data
    return bytes([0x81, 127]) + struct.pack("!Q", n) + data


def _ws_recv(rfile, wfile=None):
    hdr = rfile.read(2)
    if not hdr or len(hdr) < 2:
        return None
    opcode = hdr[0] & 0x0F
    masked = (hdr[1] & 0x80) != 0
    n = hdr[1] & 0x7F
    if n == 126:
        ext = rfile.read(2)
        if len(ext) < 2:
            return None
        n = struct.unpack("!H", ext)[0]
    elif n == 127:
        ext = rfile.read(8)
        if len(ext) < 8:
            return None
        n = struct.unpack("!Q", ext)[0]
    mask = rfile.read(4) if masked else b""
    data = rfile.read(n) if n else b""
    if masked:
        if len(mask) < 4:
            return None
        data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
    if opcode == 0x8:
        return None
    if opcode == 0x9 and wfile is not None:
        plen = len(data)
        if plen < 126:
            wfile.write(bytes([0x8A, plen]) + data)
        elif plen < 65536:
            wfile.write(bytes([0x8A, 126]) + struct.pack("!H", plen) + data)
        else:
            wfile.write(bytes([0x8A, 127]) + struct.pack("!Q", plen) + data)
        wfile.flush()
        return ""
    if opcode in (0x1, 0x2, 0x0):
        return data.decode("utf-8", errors="replace")
    return ""


def _keepalive(sock: socket.socket) -> None:
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    if hasattr(socket, "TCP_KEEPIDLE"):
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPIDLE, 30)
    if hasattr(socket, "TCP_KEEPINTVL"):
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPINTVL, 10)


def _pipe(src: socket.socket, dst: socket.socket) -> None:
    try:
        while True:
            chunk = src.recv(65536)
            if not chunk:
                break
            dst.sendall(chunk)
    except OSError:
        pass
    # Never SHUT_WR — uvicorn drops the request if the client half-closes.


def proxy_tcp(client: socket.socket, already: bytes, upstream_host: str, upstream_port: int) -> None:
    _keepalive(client)
    up = socket.create_connection((upstream_host, upstream_port), timeout=8)
    _keepalive(up)
    up.settimeout(None)
    try:
        if already:
            up.sendall(already)
        t = threading.Thread(target=_pipe, args=(client, up), daemon=True)
        t.start()
        _pipe(up, client)
        t.join(timeout=1)
    finally:
        try:
            client.close()
        except OSError:
            pass
        try:
            up.close()
        except OSError:
            pass


class RelayState:
    def __init__(self, pairing: PairingStore | None = None, tickets: TicketStore | None = None):
        self.pairing = pairing or PairingStore()
        self.tickets = tickets or TicketStore()
        self.lanes: dict[str, object] = {}
        self.lock = threading.Lock()
        self.seq = 0
        self.pending: dict[str, threading.Event] = {}
        self.results: dict[str, dict] = {}

    def send_command(self, device_id: str, action: str, arguments: dict | None = None) -> str:
        event = threading.Event()
        with self.lock:
            wfile = self.lanes.get(device_id)
            self.seq += 1
            command_id = f"c{self.seq}"
            if wfile is None:
                raise TicketError("lane_down")
            self.pending[command_id] = event
        frame = {
            "type": "mobile.controller.command",
            "command_id": command_id,
            "action": action,
            "arguments": arguments or {},
            "device_id": device_id,
        }
        try:
            wfile.write(_ws_text(json.dumps(frame)))
            wfile.flush()
        except OSError as exc:
            with self.lock:
                self.pending.pop(command_id, None)
            raise TicketError("lane_down") from exc
        return command_id

    def wait_result(self, command_id: str, timeout: float = 20.0) -> dict:
        with self.lock:
            event = self.pending.get(command_id)
        if event is None:
            raise TicketError("unknown_command")
        if not event.wait(timeout):
            with self.lock:
                self.pending.pop(command_id, None)
            raise TicketError("timeout")
        with self.lock:
            self.pending.pop(command_id, None)
            return self.results.pop(command_id, {"ok": False, "error": {"code": "missing_result"}})

    def on_frame(self, raw: str) -> None:
        if not raw:
            return
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            return
        if not isinstance(payload, dict):
            return
        if payload.get("type") != "mobile.controller.result":
            return
        command_id = str(payload.get("command_id") or "")
        if not command_id:
            return
        with self.lock:
            self.results[command_id] = payload
            event = self.pending.get(command_id)
        if event is not None:
            event.set()


class CompanionHandler(BaseHTTPRequestHandler):
    server_version = "hermes-companion-relay/0.1"
    state: RelayState
    upstream: tuple[str, int]

    def log_message(self, format, *args):
        del format, args

    def do_GET(self):
        self._dispatch()

    def do_POST(self):
        self._dispatch()

    def do_PUT(self):
        self._dispatch()

    def do_DELETE(self):
        self._dispatch()

    def do_OPTIONS(self):
        self._dispatch()

    def _dispatch(self):
        parsed = urlparse(self.path)
        path = parsed.path
        if path.startswith("/companion/"):
            self._companion(path)
            return
        self._proxy()

    def _json(self, payload: dict, status: int = 200):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        if not raw:
            return {}
        return json.loads(raw.decode() or "{}")

    def _companion(self, path: str):
        try:
            if path == "/companion/device/pair" and self.command == "POST":
                body = self._read_json()
                code = self.state.pairing.offer(str(body.get("code") or ""), str(body.get("profile") or "default"))
                return self._json({"code": code, "status": "pending"})
            if path.startswith("/companion/device/pair/") and path.endswith("/approve") and self.command == "POST":
                code = path.rstrip("/").rsplit("/", 2)[-2]
                device = self.state.pairing.approve(code)
                return self._json({"device_id": device.device_id, "status": "approved", "profile": device.profile})
            if path.startswith("/companion/device/pair/") and self.command == "GET":
                code = path.rsplit("/", 1)[-1]
                return self._json(self.state.pairing.status(code))
            if path == "/companion/device/register" and self.command == "POST":
                body = self._read_json()
                device_id = str(body.get("device_id") or "")
                credential = str(body.get("credential") or "")
                profile = str(body.get("profile") or "")
                device = self.state.pairing.devices.get(device_id)
                if device is None or device.credential != credential:
                    return self._json({"error": "unauthorized"}, 401)
                caps = [c for c in (body.get("capabilities") or []) if c in ALLOWLIST]
                issued = self.state.tickets.mint(device_id, profile or device.profile, caps)
                return self._json({"ticket": issued.ticket, "device_id": device_id, "ttl_ms": 30_000})
            if path == "/companion/device/revoke" and self.command == "POST":
                body = self._read_json()
                self.state.pairing.revoke(str(body.get("device_id") or ""))
                return self._json({"ok": True})
            if path == "/companion/device/list" and self.command == "GET":
                return self._json({"devices": self.state.pairing.public_list()})
            if path == "/companion/device/lanes" and self.command == "GET":
                with self.state.lock:
                    ids = list(self.state.lanes)
                return self._json({"devices": ids})
            if path == "/companion/device/command" and self.command == "POST":
                body = self._read_json()
                command_id = self.state.send_command(
                    str(body.get("device_id") or ""),
                    str(body.get("action") or "device.noop"),
                    body.get("arguments") if isinstance(body.get("arguments"), dict) else {},
                )
                if body.get("wait") is False:
                    return self._json({"command_id": command_id})
                result = self.state.wait_result(command_id)
                result["command_id"] = command_id
                return self._json(result)
            if path == "/companion/device/ws":
                return self._device_ws()
            return self._json({"error": "not_found"}, 404)
        except PairingError as extra:
            return self._json({"error": str(extra)}, 400)
        except TicketError as extra:
            status = 401
            if extra.code == "timeout":
                status = 504
            elif extra.code in ("lane_down", "unknown_command"):
                status = 503
            return self._json({"error": extra.code}, status)
        except Exception:
            return self._json({"error": "relay_failed"}, 500)

    def _device_ws(self):
        if self.headers.get("Upgrade", "").lower() != "websocket":
            return self._json({"error": "upgrade_required"}, 426)
        header = self.headers.get("Sec-WebSocket-Protocol") or ""
        has_v1, ticket = parse_subprotocols(header)
        if not has_v1 or not ticket:
            return self._json({"error": "bad_protocol"}, 401)
        issued = self.state.tickets.consume(ticket)
        key = self.headers.get("Sec-WebSocket-Key", "")
        if not key:
            return self._json({"error": "missing_ws_key"}, 400)
        self.send_response(101, "Switching Protocols")
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", _ws_accept(key))
        self.send_header("Sec-WebSocket-Protocol", PROTOCOL)
        self.end_headers()
        hello = {
            "type": "mobile.controller.hello",
            "device_id": issued.device_id,
            "capabilities": issued.capabilities,
        }
        self.wfile.write(_ws_text(json.dumps(hello)))
        self.wfile.flush()
        with self.state.lock:
            self.state.lanes[issued.device_id] = self.wfile
        try:
            while True:
                raw = _ws_recv(self.rfile, self.wfile)
                if raw is None:
                    break
                self.state.on_frame(raw)
        finally:
            with self.state.lock:
                if self.state.lanes.get(issued.device_id) is self.wfile:
                    self.state.lanes.pop(issued.device_id, None)
        self.close_connection = True
        return None

    def _proxy(self):
        # Rebuild the already-consumed request so uvicorn sees a full HTTP message.
        length = int(self.headers.get("Content-Length") or 0)
        rest = self.rfile.read(length) if length else b""
        upgrade = (self.headers.get("Upgrade") or "").lower() == "websocket"
        req = f"{self.command} {self.path} {self.request_version}\r\n".encode()
        host = f"{self.upstream[0]}:{self.upstream[1]}"
        skip = {"host", "connection"}
        req += f"Host: {host}\r\n".encode()
        req += b"Connection: Upgrade\r\n" if upgrade else b"Connection: close\r\n"
        for key, value in self.headers.items():
            if key.lower() in skip:
                continue
            req += f"{key}: {value}\r\n".encode()
        req += b"\r\n" + rest
        proxy_tcp(self.connection, req, self.upstream[0], self.upstream[1])
        self.close_connection = True


def make_server(
    bind: str = DEFAULT_BIND,
    upstream: str = DEFAULT_UPSTREAM,
    state: RelayState | None = None,
) -> ThreadingHTTPServer:
    host, port = parse_bind(bind)
    httpd = ThreadingHTTPServer((host, port), CompanionHandler)
    CompanionHandler.state = state or RelayState()
    CompanionHandler.upstream = parse_upstream(upstream)
    return httpd


def persistent_state() -> RelayState:
    return RelayState(pairing=PairingStore(path=default_store_path()))


def serve_forever(bind: str = DEFAULT_BIND, upstream: str = DEFAULT_UPSTREAM) -> None:
    httpd = make_server(bind, upstream, persistent_state())
    httpd.serve_forever()


def start_background(
    bind: str = DEFAULT_BIND,
    upstream: str = DEFAULT_UPSTREAM,
    state: RelayState | None = None,
) -> ThreadingHTTPServer:
    httpd = make_server(bind, upstream, state)
    thread = threading.Thread(target=httpd.serve_forever, name="companion-relay", daemon=True)
    thread.start()
    return httpd


if __name__ == "__main__":
    host, port = parse_bind(DEFAULT_BIND)
    print(f"companion relay {host}:{port} -> {DEFAULT_UPSTREAM}", flush=True)
    serve_forever()
