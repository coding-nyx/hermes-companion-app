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
from urllib.parse import parse_qs, urlparse

try:
    from .drift import self_test
    from .fs_explorer import list_dir_tree, read_file_content
    from .git_workspace import commit_git, get_git_branches, get_git_diff, get_git_status, stage_git_file
    from .host_metrics import collect_metrics
    from .media import MediaError, MediaStore
    from .standalone import Operator, OperatorError
    from .pairing import PairingError, PairingStore, default_store_path
    from .terminal_pty import PtySession, execute_quick_command
    from .tickets import ALLOWLIST, PROTOCOL, TICKET_PREFIX, TicketError, TicketStore, parse_subprotocols
except ImportError:  # script/tests on sys.path
    from drift import self_test
    from fs_explorer import list_dir_tree, read_file_content
    from git_workspace import commit_git, get_git_branches, get_git_diff, get_git_status, stage_git_file
    from host_metrics import collect_metrics
    from media import MediaError, MediaStore
    from standalone import Operator, OperatorError
    from pairing import PairingError, PairingStore, default_store_path
    from terminal_pty import PtySession, execute_quick_command
    from tickets import ALLOWLIST, PROTOCOL, TICKET_PREFIX, TicketError, TicketStore, parse_subprotocols

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

DEFAULT_BIND = os.environ.get("HERMES_COMPANION_BIND", "0.0.0.0:9120")
DEFAULT_UPSTREAM = os.environ.get("HERMES_DASHBOARD", "http://127.0.0.1:9119")


def standalone_enabled() -> bool:
    return os.environ.get("HERMES_COMPANION_STANDALONE", "1").strip().lower() not in ("0", "false", "no")


class UpstreamError(OSError):
    pass


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


def probe_upstream(host: str, port: int, timeout: float = 1.5) -> str:
    try:
        sock = socket.create_connection((host, port), timeout=timeout)
        sock.close()
        return "reachable"
    except OSError:
        return "refused"


def proxy_tcp(client: socket.socket, already: bytes, upstream_host: str, upstream_port: int) -> None:
    _keepalive(client)
    try:
        up = socket.create_connection((upstream_host, upstream_port), timeout=8)
    except OSError as exc:
        raise UpstreamError(str(exc)) from exc
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
    def __init__(
        self,
        pairing: PairingStore | None = None,
        tickets: TicketStore | None = None,
        operator: Operator | None = None,
        media: MediaStore | None = None,
    ):
        self.pairing = pairing or PairingStore()
        self.tickets = tickets or TicketStore()
        self.operator = operator or Operator()
        self.media = media or MediaStore()
        self.lanes: dict[str, object] = {}
        # Per-device armed / foreground hints from phone status frames.
        self.live_meta: dict[str, dict] = {}
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

    def _apply_live_meta(self, device_id: str, fields: dict) -> None:
        """Merge phone status / arm-disarm hints into live_meta + pairing last_seen."""
        if not device_id or not isinstance(fields, dict):
            return
        now = None
        try:
            now = float(self.pairing.now())
        except Exception:
            now = None
        with self.lock:
            meta = self.live_meta.setdefault(device_id, {})
            if "armed" in fields:
                meta["armed"] = bool(fields["armed"])
            if "foreground_app" in fields:
                meta["foreground_app"] = str(fields.get("foreground_app") or "")
            app = fields.get("app")
            if isinstance(app, str) and app and "foreground_app" not in fields:
                meta["foreground_app"] = app
            if "a11y_bound" in fields:
                meta["a11y_bound"] = bool(fields["a11y_bound"])
            if "overlay" in fields:
                meta["overlay"] = bool(fields["overlay"])
            if now is not None:
                meta["last_seen"] = now
        # Pairing store last_seen feeds GET /companion/device/lanes for offline-ish age.
        try:
            self.pairing.note_seen(device_id, persist=False)
        except Exception:
            pass

    def on_frame(self, raw: str, device_id: str | None = None) -> None:
        if not raw:
            return
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            return
        if not isinstance(payload, dict):
            return
        kind = payload.get("type")
        lane_id = str(payload.get("device_id") or device_id or "")
        if kind == "mobile.controller.status":
            if lane_id:
                self._apply_live_meta(lane_id, payload)
            return
        if kind != "mobile.controller.result":
            return
        command_id = str(payload.get("command_id") or "")
        if not command_id:
            return
        # Arm/disarm (and status-bearing) results must refresh lanes JSON immediately.
        result = payload.get("result") if isinstance(payload.get("result"), dict) else {}
        if lane_id and isinstance(result, dict) and (
            "armed" in result or "foreground_app" in result or "app" in result
            or "a11y_bound" in result or "overlay" in result
        ):
            self._apply_live_meta(lane_id, result)
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
        if standalone_enabled():
            if not self._standalone(parsed):
                self._json({"error": "not_found", "path": parsed.path}, 404)
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

    def _read_body(self) -> bytes:
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length else b""

    def _health(self):
        host, port = self.upstream
        upstream = "unused" if standalone_enabled() else probe_upstream(host, port)
        if standalone_enabled():
            payload = self.state.operator.health()
            payload["upstream"] = probe_upstream(host, port)
            payload["mode"] = "standalone"
        else:
            drift = self_test()
            payload = {
                "relay": "ok",
                "mode": "proxy",
                "upstream": upstream,
                "hermes_version": drift.get("hermes_version") or "",
                "profiles_dir": drift.get("profiles_dir") or "",
                "drift": "proxy",
                "warnings": [],
            }
        payload["upstream_url"] = f"http://{host}:{port}"
        return self._json(payload)

    def _media_put(self):
        body = self._read_json()
        raw = body.get("data") or body.get("content") or ""
        try:
            blob = base64.b64decode(raw) if isinstance(raw, str) else b""
        except Exception as exc:
            raise MediaError("bad_base64") from exc
        rec = self.state.media.put(blob, str(body.get("filename") or "file"), str(body.get("mime") or body.get("content_type") or ""))
        return self._json(rec, 201)

    def _media_get(self, media_id: str):
        found = self.state.media.get(media_id)
        if found is None:
            return self._json({"error": "not_found"}, 404)
        data, meta = found
        self.send_response(200)
        self.send_header("Content-Type", str(meta.get("mime") or "application/octet-stream"))
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "private, max-age=86400")
        self.end_headers()
        self.wfile.write(data)

    def _standalone(self, parsed) -> bool:
        path = parsed.path
        query = parse_qs(parsed.query)
        if path.startswith("/api/ws") and (self.headers.get("Upgrade") or "").lower() == "websocket":
            self._operator_ws(query)
            return True
        if path.startswith("/api/sessions/") and path.endswith("/chat/stream") and self.command == "POST":
            self._operator_stream(path, query)
            return True
        if path == "/api/chat/image-upload" and self.command == "POST":
            try:
                self._media_put()
            except MediaError as extra:
                self._json({"error": extra.code}, extra.status)
            return True
        raw = self._read_body()
        try:
            body = json.loads(raw.decode() or "{}") if raw else {}
        except json.JSONDecodeError:
            body = {}
        headers = {k: self.headers.get(k) for k in ("Authorization", "Cookie", "X-Hermes-Session-Token") if self.headers.get(k)}
        try:
            handled = self.state.operator.handle_rest(self.command, path, query, headers, body if isinstance(body, dict) else {})
        except OperatorError as extra:
            self._json({"error": extra.error}, extra.status)
            return True
        if handled is None:
            return False
        status, ctype, payload = handled
        extra_headers = {}
        if path == "/auth/password-login" and status == 200:
            extra_headers["Set-Cookie"] = "hermes_session=standalone; Path=/"
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(payload)))
        for key, value in extra_headers.items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(payload)
        return True

    def _operator_stream(self, path: str, query: dict):
        sid = path.split("/")[3]
        profile = (query.get("profile") or [None])[0] or ""
        body = self._read_json()
        text = str(body.get("input") or body.get("text") or "")
        parts = body.get("parts") if isinstance(body.get("parts"), list) else None
        try:
            submitted = self.state.operator.submit(sid, profile, text, str(body.get("model") or ""), parts)
        except OperatorError as extra:
            return self._json({"error": extra.error}, extra.status)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()

        def ev(event, data):
            frame = f"event: {event}\ndata: {json.dumps(data)}\n\n".encode()
            self.wfile.write(frame)
            self.wfile.flush()

        reply = submitted.get("reply") or ""
        for i in range(0, max(len(reply), 1), 48):
            ev("assistant.delta", {"text": reply[i : i + 48]})
        ev("run.completed", {"ok": True})
        return None

    def _operator_ws(self, query: dict):
        if self.state.operator.auth_required():
            ticket = (query.get("ticket") or [None])[0]
            if (query.get("token") or [None])[0]:
                return self._json({"error": "gated_no_token"}, 401)
            if not self.state.operator.consume_ticket(ticket or ""):
                return self._json({"error": "bad_ticket"}, 401)
        key = self.headers.get("Sec-WebSocket-Key", "")
        if not key:
            return self._json({"error": "missing_ws_key"}, 400)
        profile = (query.get("profile") or [None])[0]
        self.send_response(101, "Switching Protocols")
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", _ws_accept(key))
        self.end_headers()
        ready = {
            "jsonrpc": "2.0",
            "method": "event",
            "params": {
                "type": "gateway.ready",
                "payload": {
                    "change_events": True,
                    "heartbeat": True,
                    "instance_id": "companion-standalone",
                    "profile": profile or "default",
                },
            },
        }
        self.wfile.write(_ws_text(json.dumps(ready)))
        self.wfile.flush()
        while True:
            raw = _ws_recv(self.rfile, self.wfile)
            if raw is None:
                break
            for line in raw.splitlines():
                line = line.strip()
                if not line:
                    continue
                try:
                    req = json.loads(line)
                except json.JSONDecodeError:
                    continue
                rid = req.get("id")
                method = req.get("method") or ""
                params = req.get("params") if isinstance(req.get("params"), dict) else {}
                try:
                    result, events = self.state.operator.rpc(method, params, profile)
                except OperatorError as extra:
                    if rid is None:
                        continue
                    err = {"jsonrpc": "2.0", "id": rid, "error": {"code": extra.status, "message": extra.error}}
                    self.wfile.write(_ws_text(json.dumps(err)))
                    self.wfile.flush()
                    continue
                for event in events:
                    self.wfile.write(_ws_text(json.dumps(event)))
                    self.wfile.flush()
                if rid is None:
                    continue
                self.wfile.write(_ws_text(json.dumps({"jsonrpc": "2.0", "id": rid, "result": result})))
                self.wfile.flush()
        self.close_connection = True
        return None

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
                extras = body.get("protected_packages")
                if not isinstance(extras, list):
                    extras = body.get("extra_protected")
                if not isinstance(extras, list):
                    extras = None
                try:
                    self.state.pairing.touch(
                        device_id,
                        name=body.get("device_name") or body.get("name"),
                        model=body.get("model"),
                        manufacturer=body.get("manufacturer"),
                        os_version=body.get("os_version"),
                        extra_protected=extras,
                    )
                except PairingError:
                    pass
                caps = [c for c in (body.get("capabilities") or []) if c in ALLOWLIST]
                issued = self.state.tickets.mint(device_id, profile or device.profile, caps)
                return self._json({"ticket": issued.ticket, "device_id": device_id, "ttl_ms": 30_000})
            if path == "/companion/device/revoke" and self.command == "POST":
                body = self._read_json()
                self.state.pairing.revoke(str(body.get("device_id") or ""))
                return self._json({"ok": True})
            if path == "/companion/device/default" and self.command == "POST":
                body = self._read_json()
                hint = str(body.get("device_id") or body.get("device") or "")
                device = self.state.pairing.set_default(hint)
                return self._json({"ok": True, "device_id": device.device_id, "name": self.state.pairing.display_name(device)})
            if path == "/companion/device/rename" and self.command == "POST":
                body = self._read_json()
                device = self.state.pairing.rename(
                    str(body.get("device_id") or ""),
                    str(body.get("name") or body.get("device_name") or ""),
                )
                return self._json({"ok": True, "device_id": device.device_id, "name": device.name})
            if path == "/companion/device/list" and self.command == "GET":
                return self._json({"devices": self.state.pairing.public_list()})
            if path == "/companion/device/lanes" and self.command == "GET":
                with self.state.lock:
                    ids = list(self.state.lanes)
                    meta = {k: dict(v) for k, v in self.state.live_meta.items()}
                return self._json({"devices": self.state.pairing.lane_descriptors(ids, meta)})
            if path == "/companion/device/command" and self.command == "POST":
                # Operator HTTP control path (same resolution as mobile_* / attach_http).
                # Intentionally accepts either an operator bearer/basic session OR an
                # unauthenticated local call on the companion bind — tickets gate the
                # device WS only; this endpoint is for the host/agent/CLI.
                body = self._read_json()
                try:
                    from .live import resolve_device_id
                    from .broker import BrokerError as _BrokerError
                except ImportError:
                    from live import resolve_device_id
                    from broker import BrokerError as _BrokerError
                hint = body.get("device_id") or body.get("device") or ""
                try:
                    device_id = resolve_device_id(self.state, str(hint) if hint else None)
                except _BrokerError as exc:
                    status = 503 if exc.code in ("lane_down", "no_device") else 400
                    return self._json({"error": exc.code, "message": exc.message}, status)
                command_id = self.state.send_command(
                    device_id,
                    str(body.get("action") or "device.noop"),
                    body.get("arguments") if isinstance(body.get("arguments"), dict) else {},
                )
                if body.get("wait") is False:
                    return self._json({"command_id": command_id, "device_id": device_id})
                result = self.state.wait_result(command_id)
                result["command_id"] = command_id
                result["device_id"] = device_id
                # Mirror arm/disarm into live_meta even if the phone omitted device_id on the frame.
                res = result.get("result") if isinstance(result.get("result"), dict) else {}
                if isinstance(res, dict) and "armed" in res:
                    self.state._apply_live_meta(device_id, res)
                return self._json(result)
            if path in ("/companion/host/metrics", "/companion/host/status") and self.command == "GET":
                repo_dir = os.environ.get("HERMES_WORKSPACE", os.getcwd())
                return self._json(collect_metrics(repo_dir))
            if path == "/companion/git/status" and self.command == "GET":
                repo_dir = os.environ.get("HERMES_WORKSPACE", os.getcwd())
                return self._json(get_git_status(repo_dir))
            if path == "/companion/git/diff" and self.command == "GET":
                repo_dir = os.environ.get("HERMES_WORKSPACE", os.getcwd())
                query = parse_qs(urlparse(self.path).query)
                file_arg = query.get("file", [None])[0]
                staged_arg = query.get("staged", ["false"])[0].lower() in ("true", "1")
                return self._json(get_git_diff(repo_dir, file_path=file_arg, staged=staged_arg))
            if path == "/companion/git/branches" and self.command == "GET":
                repo_dir = os.environ.get("HERMES_WORKSPACE", os.getcwd())
                return self._json(get_git_branches(repo_dir))
            if path == "/companion/git/stage" and self.command == "POST":
                repo_dir = os.environ.get("HERMES_WORKSPACE", os.getcwd())
                body = self._read_json()
                return self._json(stage_git_file(repo_dir, str(body.get("path") or ""), bool(body.get("stage", True))))
            if path == "/companion/git/commit" and self.command == "POST":
                repo_dir = os.environ.get("HERMES_WORKSPACE", os.getcwd())
                body = self._read_json()
                return self._json(commit_git(repo_dir, str(body.get("message") or "")))
            if path == "/companion/terminal/exec" and self.command == "POST":
                repo_dir = os.environ.get("HERMES_WORKSPACE", os.getcwd())
                body = self._read_json()
                return self._json(execute_quick_command(str(body.get("cmd") or ""), cwd=repo_dir))
            if path == "/companion/terminal/ws":
                return self._terminal_ws()
            if path == "/companion/fs/tree" and self.command == "GET":
                repo_dir = os.environ.get("HERMES_WORKSPACE", os.getcwd())
                query = parse_qs(urlparse(self.path).query)
                subpath = query.get("path", [""])[0]
                return self._json(list_dir_tree(repo_dir, subpath))
            if path == "/companion/fs/read" and self.command == "GET":
                repo_dir = os.environ.get("HERMES_WORKSPACE", os.getcwd())
                query = parse_qs(urlparse(self.path).query)
                filepath = query.get("path", [""])[0]
                return self._json(read_file_content(repo_dir, filepath))
            if path == "/companion/health" and self.command == "GET":
                return self._health()
            if path == "/companion/media" and self.command == "POST":
                return self._media_put()
            if path.startswith("/companion/media/") and self.command == "GET":
                return self._media_get(path.rsplit("/", 1)[-1])
            if path == "/companion/device/ws":
                return self._device_ws()
            return self._json({"error": "not_found"}, 404)
        except MediaError as extra:
            return self._json({"error": extra.code}, extra.status)
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
                self.state.on_frame(raw, device_id=issued.device_id)
        finally:
            with self.state.lock:
                if self.state.lanes.get(issued.device_id) is self.wfile:
                    self.state.lanes.pop(issued.device_id, None)
                    self.state.live_meta.pop(issued.device_id, None)
        self.close_connection = True
        return None

    def _terminal_ws(self):
        if self.headers.get("Upgrade", "").lower() != "websocket":
            return self._json({"error": "upgrade_required"}, 426)
        key = self.headers.get("Sec-WebSocket-Key", "")
        if not key:
            return self._json({"error": "missing_ws_key"}, 400)
        self.send_response(101, "Switching Protocols")
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", _ws_accept(key))
        self.end_headers()

        repo_dir = os.environ.get("HERMES_WORKSPACE", os.getcwd())
        pty_session = PtySession(cwd=repo_dir)

        def _pump_pty():
            try:
                while pty_session.alive:
                    chunk = pty_session.read(max_bytes=4096, timeout=0.05)
                    if chunk is None:
                        break
                    if chunk:
                        msg = json.dumps({"type": "terminal.data", "data": chunk.decode("utf-8", errors="replace")})
                        self.wfile.write(_ws_text(msg))
                        self.wfile.flush()
            except (OSError, BrokenPipeError):
                pass
            finally:
                pty_session.kill()

        pump_thread = threading.Thread(target=_pump_pty, daemon=True)
        pump_thread.start()

        try:
            while pty_session.alive:
                raw = _ws_recv(self.rfile, self.wfile)
                if raw is None:
                    break
                if not raw:
                    continue
                try:
                    frame = json.loads(raw)
                    ftype = frame.get("type", "")
                    if ftype == "terminal.input":
                        pty_session.write(frame.get("data", ""))
                    elif ftype == "terminal.resize":
                        pty_session.resize(int(frame.get("cols", 80)), int(frame.get("rows", 24)))
                    elif ftype == "terminal.kill":
                        pty_session.kill()
                        break
                except (json.JSONDecodeError, AttributeError):
                    pty_session.write(raw)
        finally:
            pty_session.kill()
            pump_thread.join(timeout=0.5)

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
        try:
            proxy_tcp(self.connection, req, self.upstream[0], self.upstream[1])
        except UpstreamError as extra:
            host, port = self.upstream
            print(f"companion relay upstream refused {host}:{port}: {extra}", flush=True)
            payload = {
                "error": "dashboard_unreachable",
                "upstream": f"http://{host}:{port}",
                "hint": "start `hermes dashboard --no-open` on this host or set HERMES_DASHBOARD",
            }
            body = json.dumps(payload).encode()
            try:
                self.send_response(502)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except OSError:
                pass
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


def check_upstream(upstream: str = DEFAULT_UPSTREAM) -> dict:
    host, port = parse_upstream(upstream)
    status = probe_upstream(host, port)
    report = {
        "upstream": f"http://{host}:{port}",
        "status": status,
        "mode": "standalone" if standalone_enabled() else "proxy",
        "health": "/companion/health",
    }
    if status != "reachable" and not standalone_enabled():
        report["warning"] = "dashboard closed; phone will see host_dashboard_down. start hermes dashboard or set HERMES_COMPANION_STANDALONE=1"
    return report


def serve_forever(bind: str = DEFAULT_BIND, upstream: str = DEFAULT_UPSTREAM) -> None:
    report = check_upstream(upstream)
    print(
        f"companion relay check upstream={report['upstream']} {report['status']} mode={report['mode']}",
        flush=True,
    )
    if report.get("warning"):
        print(f"companion relay warning: {report['warning']}", flush=True)
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
    import argparse

    parser = argparse.ArgumentParser(description="Hermes companion relay")
    parser.add_argument("--check", action="store_true", help="print upstream reachability and exit")
    args, _ = parser.parse_known_args()
    if args.check:
        report = check_upstream()
        print(json.dumps(report, indent=2))
        raise SystemExit(0 if report["status"] == "reachable" or standalone_enabled() else 2)
    host, port = parse_bind(DEFAULT_BIND)
    print(f"companion relay {host}:{port} -> {DEFAULT_UPSTREAM}", flush=True)
    serve_forever()
