#!/usr/bin/env python3
"""Minimal dashboard stand-in for companion profile + chat testing. Bind 0.0.0.0:9119."""

from __future__ import annotations

import base64
import hashlib
import json
import os
import secrets
import struct
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

GATED = os.environ.get("MOCK_GATED") == "1"
AUTO_PAIR = os.environ.get("MOCK_PAIR_AUTO") == "1"
TICKETS = {}
PAIR_PENDING = {}
PAIR_APPROVED = {}
PAIR_DEVICES = {}
PAIR_TTL = 10 * 60
DEVICE_TICKETS = {}
DEVICE_CAPS = {
    "device.noop",
    "device.snapshot",
    "device.click",
    "device.type",
    "device.swipe",
    "device.scroll",
    "device.press",
    "device.open_app",
    "device.apps",
    "device.wait",
    "device.screenshot",
    "device.arm",
    "device.disarm",
}
DEVICE_PROTOCOL = "hermes-mobile-control-v1"
DEVICE_TICKET_PREFIX = "hermes-mobile-control-ticket."

PROFILES = [
    {"id": "coder", "display_name": "coder", "model": "sonnet-4.6", "gateway": "running", "session_count": 2},
    {"id": "personal", "display_name": "personal", "model": "gpt-4.1", "gateway": "running", "session_count": 1},
    {"id": "ops", "display_name": "ops", "model": "kimi-k2", "gateway": "stopped", "session_count": 1},
]

LOCK = threading.Lock()
_BOOT = time.time()
# started_at / updated_at are float seconds like the real dashboard; the phone must sort by them.
SESSIONS = [
    {"id": "sess-cod-1", "profile": "coder", "title": "fix auth ticket", "unread": True, "started_at": _BOOT - 3 * 3600, "updated_at": _BOOT - 60},
    {"id": "sess-cod-2", "profile": "coder", "title": "research: a11y tree", "unread": False, "started_at": _BOOT - 2 * 86400, "updated_at": _BOOT - 2 * 86400 + 900},
    {"id": "sess-per-1", "profile": "personal", "title": "grocery brief", "unread": True, "started_at": _BOOT - 86400, "updated_at": _BOOT - 3600},
    {"id": "sess-ops-1", "profile": "ops", "title": "cleanup build", "unread": False, "started_at": _BOOT - 9 * 86400, "updated_at": _BOOT - 8 * 86400},
]
MESSAGES = {
    "sess-cod-1": [
        {"id": "11", "row_id": 11, "role": "user", "content": "the ws dies after 30s on gated dashboards"},
        {"id": "12", "row_id": 12, "role": "tool", "name": "terminal", "detail": "journalctl -u hermes-gateway · 1.2s", "content": ""},
        {"id": "13", "row_id": 13, "role": "assistant", "content": "ticket is single-use. mint per connect."},
    ],
    "sess-cod-2": [
        {"id": "21", "row_id": 21, "role": "user", "content": "how should we compact the a11y tree?"},
        {"id": "22", "row_id": 22, "role": "assistant", "content": "refs like @eN, drop system ui by default."},
    ],
    "sess-per-1": [
        {"id": "31", "row_id": 31, "role": "user", "content": "what do we need from the shop"},
        {"id": "32", "row_id": 32, "role": "assistant", "content": "oat milk, greens, the usual rice."},
    ],
    "sess-ops-1": [
        {"id": "41", "row_id": 41, "role": "user", "content": "wipe the artifact dir"},
        {"id": "42", "row_id": 42, "role": "assistant", "content": "needs approval for rm -rf build/."},
    ],
}

ROW_SEQ = 1000


def _fresh_row():
    global ROW_SEQ
    ROW_SEQ += 1
    return ROW_SEQ


def _msg_row_id(msg):
    rid = msg.get("row_id")
    if isinstance(rid, int):
        return rid
    ident = str(msg.get("id") or "")
    if ident.isdigit():
        return int(ident)
    return None


PENDING = {
    "sess-ops-1": {
        "request_id": "apr-ops-1",
        "kind": "approval",
        "command": "rm -rf build/",
        "choices": ["once", "deny"],
    },
    "sess-cod-2": {
        "request_id": "cl-cod-2",
        "kind": "clarify",
        "command": "compact a11y tree now?",
        "choices": ["yes", "later", "deny"],
    },
}

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


class RpcError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def _rpc_event(type_name: str, session_id: str, payload: dict) -> dict:
    return {
        "jsonrpc": "2.0",
        "method": "event",
        "params": {"type": type_name, "session_id": session_id, "payload": payload},
    }


def _session(sid: str):
    for s in SESSIONS:
        if s["id"] == sid:
            return s
    return None


def _page_rows(rows, limit, before):
    items = list(rows)
    if before:
        ids = [str(r.get("id") or "") for r in items]
        try:
            items = items[: ids.index(str(before))]
        except ValueError:
            pass
    try:
        n = int(limit) if limit is not None and str(limit).strip() != "" else None
    except (TypeError, ValueError):
        n = None
    if n and n > 0:
        items = items[-n:]
    return items


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
    if opcode == 0x9:
        if wfile is not None:
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


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print("[mock]", self.command, self.path, flush=True)

    def do_GET(self):
        parsed = urlparse(self.path)
        qs = parse_qs(parsed.query)
        path = parsed.path
        profile = (qs.get("profile") or [None])[0]
        if path in ("/", "/index.html"):
            body = b"<html><body>mock dashboard</body></html>"
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/api/status":
            return self._json(
                {
                    "status": "ok",
                    "auth_required": GATED,
                    "auth_providers": ["basic"] if GATED else [],
                    "version": "mock-0.1",
                    "gateway": {"running": True, "status": "running"},
                    "gateway_running": True,
                    "gateway_state": "running",
                    "gateway_platforms": {
                        "telegram": {"state": "connected", "updated_at": "2026-09-03T00:00:00Z"},
                        "discord": {"state": "disconnected", "error_message": "no token"},
                    },
                    "memory": {"pressure": "ok"},
                    "disk": {"pressure": "ok"},
                }
            )
        if path == "/api/profiles":
            return self._json({"profiles": PROFILES})
        if path == "/api/sessions":
            if not profile:
                return self._json({"error": "profile_required"}, 400)
            with LOCK:
                rows = [s for s in SESSIONS if s["profile"] == profile]
            return self._json({"sessions": rows})
        if path.startswith("/api/sessions/") and path.endswith("/messages"):
            sid = path.split("/")[3]
            return self._messages(sid, profile, qs)
        if path.startswith("/api/sessions/") and path.endswith("/approval"):
            sid = path.split("/")[3]
            return self._get_approval(sid, profile)
        if path.startswith("/api/ws") and self.headers.get("Upgrade", "").lower() == "websocket":
            return self._ws_hello(profile, qs)
        if path == "/companion/device/ws" and self.headers.get("Upgrade", "").lower() == "websocket":
            return self._device_ws(qs)
        if path.startswith("/companion/device/pair/"):
            code = path.rsplit("/", 1)[-1]
            return self._pair_status(code)
        return self._json({"error": "not_found", "path": path}, 404)

    def do_POST(self):
        parsed = urlparse(self.path)
        qs = parse_qs(parsed.query)
        path = parsed.path
        profile = (qs.get("profile") or [None])[0]
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        try:
            body = json.loads(raw.decode() or "{}")
        except json.JSONDecodeError:
            body = {}
        if path == "/auth/password-login":
            password = str(body.get("password") or "")
            username = str(body.get("username") or "")
            if not password or not username:
                return self._json({"error": "invalid_credentials"}, 401)
            payload = b'{"ok":true}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Set-Cookie", "hermes_session=mock; Path=/")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(payload)
            return
        if path == "/api/auth/ws-ticket":
            cookie = self.headers.get("Cookie") or ""
            if GATED and "hermes_session=" not in cookie:
                return self._json({"error": "unauthorized"}, 401)
            ticket = f"t-{int(time.time() * 1000)}"
            with LOCK:
                TICKETS[ticket] = False
            return self._json({"ticket": ticket})
        if path == "/api/sessions":
            if not profile:
                return self._json({"error": "profile_required"}, 400)
            sid = f"sess-{profile}-{int(time.time() * 1000)}"
            row = {"id": sid, "profile": profile, "title": body.get("title") or "new thread", "unread": False, "started_at": time.time(), "updated_at": time.time()}
            if body.get("model"):
                row["model"] = body.get("model")
            with LOCK:
                SESSIONS.insert(0, row)
                MESSAGES[sid] = []
            return self._json({"session": row}, 201)
        if path.startswith("/api/sessions/") and path.endswith("/chat/stream"):
            sid = path.split("/")[3]
            return self._stream(sid, profile, str(body.get("input") or body.get("text") or ""))
        if path.startswith("/api/sessions/") and path.endswith("/approval"):
            sid = path.split("/")[3]
            return self._post_approval(sid, profile, body)
        if path == "/companion/device/register":
            return self._device_register(body)
        if path == "/companion/device/pair":
            return self._pair_offer(body)
        if path.startswith("/companion/device/pair/") and path.endswith("/approve"):
            code = path.split("/")[-2]
            return self._pair_approve(code)
        if path == "/companion/device/revoke":
            return self._pair_revoke(str(body.get("device_id") or ""))
        return self._json({"error": "not_found", "path": path}, 404)

    def do_DELETE(self):
        parsed = urlparse(self.path)
        qs = parse_qs(parsed.query)
        path = parsed.path
        profile = (qs.get("profile") or [None])[0]
        if path.startswith("/api/sessions/"):
            parts = [p for p in path.split("/") if p]
            if len(parts) == 3 and parts[0] == "api" and parts[1] == "sessions":
                sid = parts[2]
                sess = _session(sid)
                if sess is None:
                    return self._json({"error": "unknown_session"}, 404)
                if not profile:
                    return self._json({"error": "profile_required"}, 400)
                if sess["profile"] != profile:
                    return self._json({"error": "profile_mismatch"}, 403)
                with LOCK:
                    SESSIONS[:] = [s for s in SESSIONS if s["id"] != sid]
                    MESSAGES.pop(sid, None)
                    PENDING.pop(sid, None)
                return self._json({"ok": True})
        return self._json({"error": "not_found", "path": path}, 404)

    def _get_approval(self, sid, profile):
        sess = _session(sid)
        if sess is None:
            return self._json({"error": "unknown_session"}, 404)
        if not profile or sess["profile"] != profile:
            return self._json({"error": "profile_mismatch"}, 403)
        with LOCK:
            prompt = PENDING.get(sid)
        if not prompt:
            return self._json({"approval": None}, 404)
        return self._json({"approval": prompt})

    def _post_approval(self, sid, profile, body):
        sess = _session(sid)
        if sess is None:
            return self._json({"error": "unknown_session"}, 404)
        if not profile or sess["profile"] != profile:
            return self._json({"error": "profile_mismatch"}, 403)
        with LOCK:
            prompt = PENDING.get(sid)
            if not prompt or prompt["request_id"] != body.get("request_id"):
                return self._json({"error": "unknown_request"}, 404)
            PENDING.pop(sid, None)
            decision = str(body.get("decision") or "deny")
            note = f"{'allowed' if decision != 'deny' else 'denied'} {prompt['command']}"
            MESSAGES.setdefault(sid, []).append(
                {"id": f"apr-{prompt['request_id']}", "role": "assistant", "content": note}
            )
        return self._json({"ok": True, "decision": decision})

    def _pair_expire(self):
        cutoff = time.time() - PAIR_TTL
        dead = [c for c, p in PAIR_PENDING.items() if p["created"] < cutoff]
        for c in dead:
            PAIR_PENDING.pop(c, None)

    def _pair_offer(self, body):
        code = str(body.get("code") or "").strip().upper().replace("-", "").replace(" ", "")
        profile = str(body.get("profile") or "").strip()
        if len(code) != 6 or not profile:
            return self._json({"error": "invalid_code"}, 400)
        with LOCK:
            self._pair_expire()
            if code in PAIR_PENDING or code in PAIR_APPROVED:
                return self._json({"error": "code_in_use"}, 409)
            PAIR_PENDING[code] = {"created": time.time(), "profile": profile}
        if AUTO_PAIR:
            return self._pair_approve(code)
        return self._json({"status": "pending", "profile": profile})

    def _pair_status(self, code):
        code = str(code or "").strip().upper()
        with LOCK:
            self._pair_expire()
            if code in PAIR_PENDING:
                return self._json({"status": "pending", "profile": PAIR_PENDING[code]["profile"]})
            device = PAIR_APPROVED.get(code)
            if device is not None:
                return self._json(
                    {
                        "status": "approved",
                        "device_id": device["device_id"],
                        "profile": device["profile"],
                        "credential": device["credential"],
                    }
                )
        return self._json({"error": "unknown_or_expired_code"}, 404)

    def _pair_approve(self, code):
        code = str(code or "").strip().upper()
        with LOCK:
            self._pair_expire()
            pending = PAIR_PENDING.pop(code, None)
            if pending is None:
                return self._json({"error": "unknown_or_expired_code"}, 404)
            device = {
                "device_id": "dev_" + secrets.token_hex(8),
                "profile": pending["profile"],
                "credential": secrets.token_urlsafe(32),
            }
            PAIR_APPROVED[code] = device
            PAIR_DEVICES[device["device_id"]] = device
        return self._json(
            {
                "status": "approved",
                "device_id": device["device_id"],
                "profile": device["profile"],
                "credential": device["credential"],
            }
        )

    def _pair_revoke(self, device_id):
        if not device_id:
            return self._json({"error": "unknown_device"}, 404)
        with LOCK:
            if device_id not in PAIR_DEVICES:
                return self._json({"error": "unknown_device"}, 404)
            del PAIR_DEVICES[device_id]
            dead = [c for c, d in PAIR_APPROVED.items() if d["device_id"] == device_id]
            for c in dead:
                del PAIR_APPROVED[c]
        return self._json({"ok": True})

    def _device_register(self, body):
        device_id = str(body.get("device_id") or "")
        credential = str(body.get("credential") or "")
        profile = str(body.get("profile") or "")
        requested = body.get("capabilities") or []
        if not isinstance(requested, list):
            requested = []
        with LOCK:
            device = PAIR_DEVICES.get(device_id)
            if device is None or device.get("credential") != credential:
                return self._json({"error": "unauthorized"}, 401)
            caps = [c for c in requested if c in DEVICE_CAPS]
            ticket = "dt-" + secrets.token_hex(8)
            DEVICE_TICKETS[ticket] = {
                "device_id": device_id,
                "profile": profile or device.get("profile"),
                "capabilities": caps,
                "used": False,
                "created": time.time(),
            }
        return self._json({"ticket": ticket, "capabilities": caps, "ttl_sec": 30})

    def _device_ws(self, qs):
        if (qs.get("ticket") or [None])[0] or (qs.get("token") or [None])[0]:
            return self._json({"error": "ticket_not_query"}, 401)
        header = self.headers.get("Sec-WebSocket-Protocol") or ""
        parts = [p.strip() for p in header.split(",") if p.strip()]
        if DEVICE_PROTOCOL not in parts:
            return self._json({"error": "bad_protocol"}, 401)
        ticket = None
        for part in parts:
            if part.startswith(DEVICE_TICKET_PREFIX):
                ticket = part[len(DEVICE_TICKET_PREFIX) :]
                break
        with LOCK:
            issued = DEVICE_TICKETS.get(ticket)
            if not ticket or issued is None or issued["used"] or time.time() - issued["created"] > 30:
                return self._json({"error": "bad_ticket"}, 401)
            issued["used"] = True
            device_id = issued["device_id"]
            caps = issued["capabilities"]
        key = self.headers.get("Sec-WebSocket-Key", "")
        if not key:
            return self._json({"error": "missing_ws_key"}, 400)
        self.send_response(101, "Switching Protocols")
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", _ws_accept(key))
        self.send_header("Sec-WebSocket-Protocol", DEVICE_PROTOCOL)
        self.end_headers()
        hello = {
            "type": "mobile.controller.hello",
            "device_id": device_id,
            "capabilities": caps,
        }
        self.wfile.write(_ws_text(json.dumps(hello)))
        self.wfile.flush()
        while True:
            raw = _ws_recv(self.rfile, self.wfile)
            if raw is None:
                break
        return None

    def _ws_hello(self, profile, qs=None):
        qs = qs or {}
        if GATED:
            ticket = (qs.get("ticket") or [None])[0]
            token = (qs.get("token") or [None])[0]
            if token:
                return self._json({"error": "gated_no_token"}, 401)
            with LOCK:
                used = TICKETS.get(ticket)
                if not ticket or used is None or used:
                    return self._json({"error": "bad_ticket"}, 401)
                TICKETS[ticket] = True
        key = self.headers.get("Sec-WebSocket-Key", "")
        if not key:
            return self._json({"error": "missing_ws_key"}, 400)
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
                    "instance_id": "mock-1",
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
                self._ws_rpc(req, profile)

    def _ws_rpc(self, req, profile):
        rid = req.get("id")
        method = req.get("method") or ""
        params = req.get("params") or {}
        print("[mock] rpc", method, "id=", rid, flush=True)
        if not isinstance(params, dict):
            params = {}
        scoped = (params.get("profile") or profile or "").strip() or None
        try:
            result, events = self._rpc_dispatch(method, params, scoped)
        except RpcError as exc:
            if rid is None:
                return
            err = {"jsonrpc": "2.0", "id": rid, "error": {"code": exc.code, "message": exc.message}}
            self.wfile.write(_ws_text(json.dumps(err)))
            self.wfile.flush()
            return
        for event in events:
            self.wfile.write(_ws_text(json.dumps(event)))
            self.wfile.flush()
        if rid is None:
            return
        self.wfile.write(_ws_text(json.dumps({"jsonrpc": "2.0", "id": rid, "result": result})))
        self.wfile.flush()

    def _rpc_dispatch(self, method, params, profile):
        if method == "gateway.ping":
            return {"ok": True}, []
        if method == "session.list":
            return self._rpc_list(profile), []
        if method == "session.create":
            created = self._rpc_create(profile, params)
            changed = _rpc_event(
                "sessions.changed",
                created["session_id"],
                {
                    "id": created["session_id"],
                    "profile": profile,
                    "op": "upsert",
                    "title": created.get("title") or "new thread",
                },
            )
            return created, [changed]
        if method == "session.resume":
            return self._rpc_resume(profile, params), []
        if method == "session.history":
            return self._rpc_history(profile, params), []
        if method == "session.interrupt":
            return {"status": "interrupted"}, []
        if method in ("approval.respond", "clarify.respond", "sudo.respond", "secret.respond"):
            return self._rpc_approval(profile, params), []
        if method == "prompt.submit":
            return self._rpc_submit(profile, params)
        raise RpcError(4040, f"unknown method {method}")

    def _rpc_list(self, profile):
        if not profile:
            raise RpcError(4001, "profile required")
        with LOCK:
            rows = [s for s in SESSIONS if s["profile"] == profile]
        return {
            "sessions": [
                {
                    "id": s["id"],
                    "title": s.get("title") or "",
                    "preview": s.get("title") or "",
                    "started_at": s.get("started_at") or int(time.time()),
                    "last_activity_at": s.get("updated_at") or s.get("started_at") or int(time.time()),
                    "message_count": len(MESSAGES.get(s["id"], [])),
                    "source": "mock",
                    "profile": s["profile"],
                }
                for s in rows
            ]
        }

    def _rpc_create(self, profile, params):
        if not profile:
            raise RpcError(4001, "profile required")
        sid = f"sess-{profile}-{int(time.time() * 1000)}"
        title = str(params.get("title") or "new thread")
        row = {"id": sid, "profile": profile, "title": title, "unread": False, "started_at": time.time(), "updated_at": time.time()}
        with LOCK:
            SESSIONS.insert(0, row)
            MESSAGES[sid] = []
        return {
            "session_id": sid,
            "stored_session_id": sid,
            "message_count": 0,
            "messages": [],
            "title": title,
            "info": {"profile_name": profile, "lazy": True},
        }

    def _rpc_resume(self, profile, params):
        sid = str(params.get("session_id") or "")
        sess = _session(sid)
        if sess is None:
            raise RpcError(4006, "session not found")
        if not profile or sess["profile"] != profile:
            raise RpcError(4003, "profile mismatch")
        with LOCK:
            rows = list(MESSAGES.get(sid, []))
        return {
            "session_id": sid,
            "stored_session_id": sid,
            "message_count": len(rows),
            "messages": rows,
            "info": {"profile_name": sess["profile"]},
        }

    def _rpc_history(self, profile, params):
        sid = str(params.get("session_id") or "")
        sess = _session(sid)
        if sess is None:
            raise RpcError(4006, "session not found")
        if profile and sess["profile"] != profile:
            raise RpcError(4003, "profile mismatch")
        with LOCK:
            rows = list(MESSAGES.get(sid, []))
        paged = _page_rows(rows, params.get("limit"), params.get("before"))
        return {"count": len(paged), "messages": paged}

    def _rpc_approval(self, profile, params):
        sid = str(params.get("session_id") or "")
        sess = _session(sid)
        if sess is None:
            raise RpcError(4006, "session not found")
        if not profile or sess["profile"] != profile:
            raise RpcError(4003, "profile mismatch")
        with LOCK:
            prompt = PENDING.get(sid)
            if not prompt or prompt["request_id"] != params.get("request_id"):
                raise RpcError(4041, "unknown request")
            PENDING.pop(sid, None)
            decision = str(
                params.get("decision")
                or params.get("choice")
                or params.get("answer")
                or params.get("value")
                or params.get("password")
                or "deny"
            )
            note = f"{'allowed' if decision != 'deny' else 'denied'} {prompt['command']}"
            MESSAGES.setdefault(sid, []).append(
                {"id": f"apr-{prompt['request_id']}", "role": "assistant", "content": note}
            )
        return {"ok": True, "decision": decision}

    def _rpc_submit(self, profile, params):
        sid = str(params.get("session_id") or "")
        text = str(params.get("text") or "")
        sess = _session(sid)
        if sess is None:
            raise RpcError(4006, "session not found")
        if not profile or sess["profile"] != profile:
            raise RpcError(4003, "profile mismatch")
        if not text.strip():
            raise RpcError(4002, "text is required")
        if text.strip() == "__busy__":
            raise RpcError(4009, "session busy")
        truncate = params.get("truncate_before_row_id")
        confirm = bool(params.get("confirm_truncate"))
        has_trunc = (
            truncate is not None
            or params.get("truncate_before_user_ordinal") is not None
            or params.get("truncate_before_message_id") is not None
        )
        if has_trunc and not confirm:
            raise RpcError(4004, "confirm_truncate required")
        if confirm and not has_trunc:
            raise RpcError(4004, "truncate target required")
        survivors = None
        uid = _fresh_row()
        tid = _fresh_row()
        aid = _fresh_row()
        user = {"id": str(uid), "row_id": uid, "role": "user", "content": text}
        reply = f"{profile} · noted: {text}"
        assistant = {"id": str(aid), "row_id": aid, "role": "assistant", "content": reply}
        tool = {
            "id": str(tid),
            "row_id": tid,
            "role": "tool",
            "name": "terminal",
            "detail": "echo ok · 0.4s",
            "content": "",
        }
        events = [
            _rpc_event("tool.start", sid, {"name": "terminal", "detail": "echo ok"}),
            _rpc_event("tool.complete", sid, {"name": "terminal", "detail": "echo ok", "duration_ms": 400}),
        ]
        for part in [f"{profile} · ", "noted: ", text]:
            events.append(_rpc_event("message.delta", sid, {"text": part}))
        events.append(_rpc_event("message.complete", sid, {"ok": True}))
        with LOCK:
            rows = list(MESSAGES.get(sid, []))
            if truncate is not None:
                try:
                    target = int(truncate)
                except (TypeError, ValueError) as exc:
                    raise RpcError(4004, "truncate_before_row_id must be int") from exc
                cut = None
                for i, msg in enumerate(rows):
                    if _msg_row_id(msg) == target and msg.get("role") == "user":
                        cut = i
                        break
                if cut is None:
                    raise RpcError(4018, "unknown row id")
                first_user = next((i for i, msg in enumerate(rows) if msg.get("role") == "user"), None)
                if first_user == cut and not params.get("confirm_empty_truncate"):
                    raise RpcError(4004, "confirm_empty_truncate required")
                kept = []
                survivors = []
                for msg in rows[:cut]:
                    nm = dict(msg)
                    if nm.get("role") == "user":
                        nid = _fresh_row()
                        nm["id"] = str(nid)
                        nm["row_id"] = nid
                        survivors.append(nid)
                    kept.append(nm)
                rows = kept
            rows.extend([user, tool, assistant])
            MESSAGES[sid] = rows
            sess["unread"] = False
            if sess["title"] in ("new thread", sess["id"]):
                sess["title"] = text[:40]
        events.append(
            _rpc_event(
                "sessions.changed",
                sid,
                {"id": sid, "profile": profile, "op": "upsert", "title": sess["title"]},
            )
        )
        result = {"status": "ok"}
        if survivors is not None:
            result["survivor_user_row_ids"] = survivors
        return result, events

    def _messages(self, sid, profile, qs=None):
        sess = _session(sid)
        if sess is None:
            return self._json({"error": "unknown_session"}, 404)
        if not profile or sess["profile"] != profile:
            return self._json({"error": "profile_mismatch"}, 403)
        qs = qs or {}
        limit = (qs.get("limit") or [None])[0]
        before = (qs.get("before") or [None])[0]
        with LOCK:
            rows = list(MESSAGES.get(sid, []))
        return self._json({"messages": _page_rows(rows, limit, before)})

    def _stream(self, sid, profile, text):
        sess = _session(sid)
        if sess is None:
            return self._json({"error": "unknown_session"}, 404)
        if not profile or sess["profile"] != profile:
            return self._json({"error": "profile_mismatch"}, 403)
        if not text.strip():
            return self._json({"error": "text_required"}, 400)
        if text.strip() == "__busy__":
            return self._json({"error": "session busy"}, 409)
        user = {"id": f"u-{int(time.time()*1000)}", "role": "user", "content": text}
        reply = f"{profile} · noted: {text}"
        assistant = {"id": f"a-{int(time.time()*1000)}", "role": "assistant", "content": reply}
        tool = {
            "id": f"t-{int(time.time()*1000)}",
            "role": "tool",
            "name": "terminal",
            "detail": "echo ok · 0.4s",
            "content": "",
        }
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()

        def ev(event, data):
            frame = f"event: {event}\ndata: {json.dumps(data)}\n\n".encode()
            self.wfile.write(frame)
            self.wfile.flush()

        ev("tool.started", {"name": "terminal", "detail": "echo ok"})
        time.sleep(0.12)
        ev("tool.completed", {"name": "terminal", "detail": "echo ok", "duration_ms": 400})
        for part in [f"{profile} · ", "noted: ", text]:
            ev("assistant.delta", {"text": part})
            time.sleep(0.08)
        ev("run.completed", {"ok": True})
        with LOCK:
            MESSAGES.setdefault(sid, []).extend([user, tool, assistant])
            sess["unread"] = False
            if sess["title"] in ("new thread", sess["id"]):
                sess["title"] = text[:40]

    def _json(self, payload, status=200):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    httpd = ThreadingHTTPServer(("0.0.0.0", 9119), Handler)
    print("mock dashboard on http://0.0.0.0:9119", flush=True)
    httpd.serve_forever()
