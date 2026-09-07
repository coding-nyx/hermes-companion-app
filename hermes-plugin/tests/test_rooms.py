from __future__ import annotations

import base64
import json
import os
import secrets
import socket
import struct
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from pairing import PairingStore  # noqa: E402
from relay import CompanionHandler, RelayState, _ws_accept, _ws_recv, _ws_text, make_server  # noqa: E402
from rooms import (  # noqa: E402
    OPERATOR,
    RoomError,
    RoomStore,
    build_turn_prompt,
    glyph,
    is_pass,
    mentions,
    render_delta,
)


# ---------------------------------------------------------------------------------------------
# Fake dashboard: JSON-RPC over websocket with scripted, streaming replies per profile.
# ---------------------------------------------------------------------------------------------

class FakeDashboard(BaseHTTPRequestHandler):
    scripts: dict = {}  # profile -> callable(prompt_text, turn_index) -> reply or list of chunks
    calls: list = []
    delay: float = 0.0
    result_first: bool = False  # real dashboards ack prompt.submit before streaming
    stored: dict = {}  # durable session ids the fake knows about
    turns: dict = {}
    lock = threading.Lock()

    def log_message(self, format, *args):
        del format, args

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path.startswith("/api/ws"):
            return self._ws(parse_qs(parsed.query))
        body = b"<html>__HERMES_SESSION_TOKEN__ = \"tok-123\"</html>"
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _event(self, etype, sid, payload):
        return {"jsonrpc": "2.0", "method": "event", "params": {"type": etype, "session_id": sid, "payload": payload}}

    def _ws(self, qs):
        self.wlock = threading.Lock()
        self.interrupted = threading.Event()
        profile = (qs.get("profile") or ["default"])[0]
        key = self.headers.get("Sec-WebSocket-Key", "")
        self.send_response(101, "Switching Protocols")
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", _ws_accept(key))
        self.end_headers()
        ready = self._event("gateway.ready", None, {"instance_id": "fake", "profile": profile})
        self.wfile.write(_ws_text(json.dumps(ready)))
        self.wfile.flush()
        while True:
            raw = _ws_recv(self.rfile, self.wfile)
            if raw is None:
                return
            if not raw:
                continue
            req = json.loads(raw)
            rid = req.get("id")
            method = req.get("method")
            params = req.get("params") or {}
            with FakeDashboard.lock:
                FakeDashboard.calls.append((method, params))
            if method == "session.create":
                live = f"live-{secrets.token_hex(2)}"
                stored = f"stored-{profile}-{secrets.token_hex(2)}"
                with FakeDashboard.lock:
                    FakeDashboard.stored[stored] = profile
                self._reply(rid, {"session_id": live, "stored_session_id": stored, "title": params.get("title")})
            elif method == "session.resume":
                sid = str(params.get("session_id") or "")
                with FakeDashboard.lock:
                    known = sid in FakeDashboard.stored
                if known:
                    self._reply(rid, {"session_id": f"live-{secrets.token_hex(2)}", "stored_session_id": sid})
                else:
                    self._write({"jsonrpc": "2.0", "id": rid, "error": {"code": 4006, "message": "session not found"}})
            elif method == "session.interrupt":
                self.interrupted.set()
                self._reply(rid, {"status": "interrupted"})
            elif method == "prompt.submit":
                # Stream on a side thread so the socket keeps reading (a real dashboard is async
                # and will see session.interrupt while a turn is still producing tokens).
                self.interrupted = threading.Event()
                threading.Thread(target=self._stream, args=(rid, profile, params), daemon=True).start()
            else:
                self._reply(rid, {"ok": True})

    def _stream(self, rid, profile, params):
        sid = params["session_id"]
        with FakeDashboard.lock:
            n = FakeDashboard.turns.get(profile, 0)
            FakeDashboard.turns[profile] = n + 1
        script = FakeDashboard.scripts.get(profile)
        reply = script(params.get("text", ""), n) if script else f"{profile} says hi"
        chunks = reply if isinstance(reply, list) else [reply[i:i + 12] for i in range(0, len(reply), 12)] or [""]
        if FakeDashboard.result_first:
            self._reply(rid, {"status": "streaming"})
            time.sleep(0.05)
        self._write(self._event("tool.start", sid, {"name": "terminal", "detail": "echo ok"}))
        self._write(self._event("tool.complete", sid, {"name": "terminal", "detail": "echo ok", "duration_ms": 4}))
        for chunk in chunks:
            if FakeDashboard.delay:
                time.sleep(FakeDashboard.delay)
            if self.interrupted.is_set():
                break
            self._write(self._event("message.delta", sid, {"text": chunk}))
        self._write(self._event("message.complete", sid, {"ok": True}))
        if not FakeDashboard.result_first:
            self._reply(rid, {"status": "ok"})

    def _write(self, obj):
        with self.wlock:
            try:
                self.wfile.write(_ws_text(json.dumps(obj)))
                self.wfile.flush()
            except OSError:
                pass

    def _reply(self, rid, result):
        self._write({"jsonrpc": "2.0", "id": rid, "result": result})


def _ws_client(host, port, path):
    """Minimal client for the relay's room events socket; returns (sock, rfile)."""
    sock = socket.create_connection((host, port), timeout=10)
    key = base64.b64encode(secrets.token_bytes(16)).decode()
    sock.sendall(
        f"GET {path} HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n".encode()
    )
    rfile = sock.makefile("rb")
    status = rfile.readline()
    assert b" 101 " in status, status
    while rfile.readline() not in (b"\r\n", b""):
        pass
    return sock, rfile


def _drain_until(rfile, stop_type, timeout=15.0):
    events = []
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        raw = _ws_recv(rfile, None)
        if raw is None:
            break
        if not raw:
            continue
        ev = json.loads(raw)
        events.append(ev)
        if ev.get("type") == stop_type:
            break
    return events


def _http(method, url, payload=None, headers=None):
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read().decode() or "{}")


# ---------------------------------------------------------------------------------------------

class RoomUnitTests(unittest.TestCase):
    def test_glyph_pass_and_mentions(self):
        self.assertEqual(glyph("coder"), "COD")
        self.assertEqual(glyph(OPERATOR), "YOU")
        self.assertTrue(is_pass("PASS"))
        self.assertTrue(is_pass("pass — nothing to add"))
        self.assertFalse(is_pass("I pass the ball"))
        parts = ["coder", "ops"]
        self.assertEqual(mentions("@OPS can you pull the journal? @cod too", parts), ["ops", "coder"])
        self.assertEqual(mentions("no mentions here, mail me at a@b.c", parts), [])
        self.assertEqual(mentions("@ops @OPS @ops", parts), ["ops"])

    def test_store_persists_with_mode_600(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "rooms.json"
            store = RoomStore(path=path)
            room = store.create("triage", ["coder", "ops", "coder"], {"max_rounds": 9})
            self.assertEqual(room.participants, ["coder", "ops"])
            self.assertEqual(room.policy.max_rounds, 4)  # capped
            store.append(room, OPERATOR, "hello")
            store.append(room, "coder", "hi back", tools=[{"name": "terminal", "detail": "ls"}])
            self.assertEqual(oct(path.stat().st_mode & 0o777), "0o600")
            again = RoomStore(path=path)
            got = again.get(room.id)
            self.assertEqual([m.text for m in got.transcript], ["hello", "hi back"])
            self.assertEqual(got.last_seen["coder"], 2)
            with self.assertRaises(RoomError):
                RoomStore().create("x", [])

    def test_turn_prompt_shows_only_unseen_lines(self):
        store = RoomStore()
        room = store.create("triage", ["coder", "ops"])
        store.append(room, OPERATOR, "gateway restarted twice")
        store.append(room, "coder", "I see ticket mint failing. @OPS journal?", tools=[{"name": "terminal", "detail": "journalctl"}])
        prompt = build_turn_prompt(room, "ops", first_turn=True)
        self.assertIn('you are OPS', prompt)
        self.assertIn("[room] YOU: gateway restarted twice", prompt)
        self.assertIn("[COD ran terminal · journalctl]", prompt)
        self.assertIn("[room] COD: I see ticket mint failing", prompt)
        # coder already saw everything up to its own line; nothing new for it.
        self.assertEqual(render_delta(room, "coder", room.last_seen["coder"]), "")


class RoomRelayTests(unittest.TestCase):
    def setUp(self):
        FakeDashboard.calls = []
        FakeDashboard.turns = {}
        FakeDashboard.delay = 0.0
        FakeDashboard.scripts = {}
        FakeDashboard.result_first = False
        FakeDashboard.stored = {}
        self.up = ThreadingHTTPServer(("127.0.0.1", 0), FakeDashboard)
        threading.Thread(target=self.up.serve_forever, daemon=True).start()
        uh, upp = self.up.server_address
        self.env = patch.dict(os.environ, {"HERMES_COMPANION_STANDALONE": "0"}, clear=False)
        self.env.start()
        self.tmp = tempfile.TemporaryDirectory()
        state = RelayState(pairing=PairingStore(), rooms=RoomStore(path=Path(self.tmp.name) / "rooms.json"))
        self.relay = make_server("127.0.0.1:0", f"http://{uh}:{upp}", state)
        threading.Thread(target=self.relay.serve_forever, daemon=True).start()
        rh, rp = self.relay.server_address
        self.base = f"http://{rh}:{rp}"
        self.host, self.port = rh, rp

    def tearDown(self):
        self.relay.shutdown()
        self.relay.server_close()
        self.up.shutdown()
        self.up.server_close()
        self.env.stop()
        self.tmp.cleanup()

    def test_mention_handoff_round_cap_and_pass(self):
        FakeDashboard.scripts = {
            "coder": lambda text, n: "Mint fails after 30s. @OPS can you pull the journal?" if n == 0 else "PASS",
            "ops": lambda text, n: "Journal shows two restarts. @COD your turn." if n == 0 else "PASS",
        }
        code, body = _http("POST", f"{self.base}/companion/rooms", {"title": "triage", "participants": ["coder", "ops"], "policy": {"max_rounds": 2}})
        self.assertEqual(code, 201, body)
        rid = body["room"]["id"]
        sock, rfile = _ws_client(self.host, self.port, f"/companion/rooms/events?room_id={rid}")
        try:
            code, body = _http("POST", f"{self.base}/companion/rooms/{rid}/post", {"text": "gateway restarted twice, who owns it?"})
            self.assertEqual(code, 202, body)
            events = _drain_until(rfile, "room.idle")
        finally:
            sock.close()
        types = [e["type"] for e in events]
        self.assertEqual(types[0], "room.ready")
        self.assertIn("room.post", types)
        self.assertIn("room.delta", types)
        self.assertIn("room.tool.start", types)
        turns = [(e["speaker"], e.get("passed")) for e in events if e["type"] == "room.turn.end"]
        # Round 1: coder then ops (room order). Coder's @OPS hand-off is folded into ops' pending
        # round-1 turn (ops will see the line anyway). Ops' @COD hand-off queues coder for round 2,
        # who replies PASS. max_rounds=2 and PASS stop the plan there.
        self.assertEqual([s for s, _ in turns], ["coder", "ops", "coder"])
        self.assertEqual([p for _, p in turns], [False, False, True])
        code, hist = _http("GET", f"{self.base}/companion/rooms/{rid}/history")
        self.assertEqual(code, 200)
        speakers = [m["speaker"] for m in hist["messages"]]
        self.assertEqual(speakers, ["operator", "coder", "ops", "coder"])
        self.assertEqual(hist["messages"][1]["tools"][0]["name"], "terminal")
        self.assertFalse(hist["room"]["busy"])
        # Each profile got exactly one backing session and its prompts carried the room delta.
        creates = [p for m, p in FakeDashboard.calls if m == "session.create"]
        self.assertEqual(sorted(p["profile"] for p in creates), ["coder", "ops"])
        self.assertTrue(all(p["title"].startswith("room:") for p in creates))
        submits = [p for m, p in FakeDashboard.calls if m == "prompt.submit"]
        self.assertIn("[room] YOU: gateway restarted twice", submits[0]["text"])
        self.assertIn("you are COD", submits[0]["text"])
        self.assertIn("[room] COD: Mint fails after 30s", submits[1]["text"])  # ops sees coder's line
        self.assertNotIn("you are", submits[2]["text"])  # coder's second turn: reminder only, no preamble
        self.assertIn("[room] OPS: Journal shows two restarts", submits[2]["text"])
        # Rounds annotate the transcript.
        self.assertEqual([m["round"] for m in hist["messages"][1:]], [1, 1, 2])

    def test_busy_room_rejects_second_post_and_interrupt_stops_turn(self):
        FakeDashboard.delay = 0.25
        FakeDashboard.scripts = {"coder": lambda text, n: ["slow ", "reply ", "that ", "keeps ", "going ", "on ", "and ", "on"]}
        code, body = _http("POST", f"{self.base}/companion/rooms", {"title": "slow", "participants": ["coder"]})
        rid = body["room"]["id"]
        sock, rfile = _ws_client(self.host, self.port, f"/companion/rooms/events?room_id={rid}")
        try:
            code, _ = _http("POST", f"{self.base}/companion/rooms/{rid}/post", {"text": "go"})
            self.assertEqual(code, 202)
            time.sleep(0.4)
            code, body = _http("POST", f"{self.base}/companion/rooms/{rid}/post", {"text": "again"})
            self.assertEqual(code, 409)
            self.assertEqual(body["error"], "room_busy")
            code, body = _http("GET", f"{self.base}/companion/rooms/{rid}")
            self.assertTrue(body["room"]["busy"])
            self.assertEqual(body["room"]["speaking"], "coder")
            code, body = _http("POST", f"{self.base}/companion/rooms/{rid}/interrupt", {})
            self.assertTrue(body["interrupted"])
            events = _drain_until(rfile, "room.idle")
        finally:
            sock.close()
        end = next(e for e in events if e["type"] == "room.turn.end")
        self.assertEqual(end["error"], "interrupted")
        interrupts = [p for m, p in FakeDashboard.calls if m == "session.interrupt"]
        self.assertTrue(interrupts and interrupts[0]["profile"] == "coder", FakeDashboard.calls)
        code, body = _http("POST", f"{self.base}/companion/rooms/{rid}/post", {"text": "again"})
        self.assertEqual(code, 202)

    def test_turn_keeps_reading_when_dashboard_acks_before_streaming(self):
        """Real dashboards reply {"status":"streaming"} first; the text must still be captured."""
        FakeDashboard.result_first = True
        FakeDashboard.scripts = {"coder": lambda text, n: "streamed after the ack"}
        code, body = _http("POST", f"{self.base}/companion/rooms", {"participants": ["coder"]})
        rid = body["room"]["id"]
        sock, rfile = _ws_client(self.host, self.port, f"/companion/rooms/events?room_id={rid}")
        try:
            _http("POST", f"{self.base}/companion/rooms/{rid}/post", {"text": "hello"})
            events = _drain_until(rfile, "room.idle")
        finally:
            sock.close()
        end = next(e for e in events if e["type"] == "room.turn.end")
        self.assertEqual(end["message"]["text"], "streamed after the ack")
        self.assertEqual(end["message"]["tools"][0]["name"], "terminal")

    def test_backing_sessions_are_resumed_by_stored_id_and_recreated_when_gone(self):
        FakeDashboard.scripts = {"coder": lambda text, n: "hi"}
        code, body = _http("POST", f"{self.base}/companion/rooms", {"participants": ["coder"]})
        rid = body["room"]["id"]
        for _ in range(2):
            sock, rfile = _ws_client(self.host, self.port, f"/companion/rooms/events?room_id={rid}")
            try:
                _http("POST", f"{self.base}/companion/rooms/{rid}/post", {"text": "go"})
                _drain_until(rfile, "room.idle")
            finally:
                sock.close()
        methods = [m for m, _ in FakeDashboard.calls]
        # First turn creates; second turn resumes the *stored* id, never the live handle.
        self.assertEqual(methods.count("session.create"), 1)
        resumes = [p for m, p in FakeDashboard.calls if m == "session.resume"]
        self.assertEqual(len(resumes), 1)
        self.assertTrue(resumes[0]["session_id"].startswith("stored-coder-"))
        submits = [p for m, p in FakeDashboard.calls if m == "prompt.submit"]
        self.assertTrue(all(p["session_id"].startswith("live-") for p in submits))
        # Dashboard forgot the session (reset): the next turn recreates instead of failing.
        FakeDashboard.stored.clear()
        sock, rfile = _ws_client(self.host, self.port, f"/companion/rooms/events?room_id={rid}")
        try:
            _http("POST", f"{self.base}/companion/rooms/{rid}/post", {"text": "again"})
            events = _drain_until(rfile, "room.idle")
        finally:
            sock.close()
        end = next(e for e in events if e["type"] == "room.turn.end")
        self.assertEqual(end["error"], "")
        self.assertEqual([m for m, _ in FakeDashboard.calls].count("session.create"), 2)

    def test_standalone_mode_reports_rooms_unavailable(self):
        with patch.dict(os.environ, {"HERMES_COMPANION_STANDALONE": "1"}, clear=False):
            code, body = _http("POST", f"{self.base}/companion/rooms", {"title": "x", "participants": ["coder"]})
            self.assertEqual(code, 503)
            self.assertEqual(body["error"], "rooms_unavailable")
            code, health = _http("GET", f"{self.base}/companion/health")
            self.assertEqual(health["rooms"], "unavailable_standalone")
        code, health = _http("GET", f"{self.base}/companion/health")
        self.assertEqual(health["rooms"], "ok")

    def test_participants_and_delete(self):
        code, body = _http("POST", f"{self.base}/companion/rooms", {"participants": ["coder"]})
        rid = body["room"]["id"]
        self.assertEqual(body["room"]["title"], "COD")
        code, body = _http("POST", f"{self.base}/companion/rooms/{rid}/participants", {"add": ["ops"], "remove": ["coder"]})
        self.assertEqual([p["profile"] for p in body["room"]["participants"]], ["ops"])
        code, body = _http("POST", f"{self.base}/companion/rooms/{rid}/participants", {"remove": ["ops"]})
        self.assertEqual(code, 400)
        code, _ = _http("DELETE", f"{self.base}/companion/rooms/{rid}")
        self.assertEqual(code, 200)
        code, _ = _http("GET", f"{self.base}/companion/rooms/{rid}")
        self.assertEqual(code, 404)


class CompanionAuthGateTests(unittest.TestCase):
    def _handler(self, client_ip, headers, pairing):
        h = CompanionHandler.__new__(CompanionHandler)
        h.client_address = (client_ip, 4242)
        h.headers = headers
        h.state = RelayState(pairing=pairing)
        return h

    def test_gate_rules(self):
        pairing = PairingStore()
        device = pairing.approve(pairing.issue("ops"))
        remote = self._handler("192.168.0.6", {}, pairing)
        self.assertTrue(remote._companion_authorized("/companion/health"))
        self.assertTrue(remote._companion_authorized("/companion/device/pair"))
        self.assertTrue(remote._companion_authorized("/companion/device/pair/ABC123"))
        self.assertFalse(remote._companion_authorized("/companion/device/pair/ABC123/approve"))
        self.assertTrue(remote._companion_authorized("/companion/device/register"))
        self.assertTrue(remote._companion_authorized("/companion/media/abc"))
        self.assertFalse(remote._companion_authorized("/companion/device/command"))
        self.assertFalse(remote._companion_authorized("/companion/rooms"))
        self.assertFalse(remote._companion_authorized("/companion/terminal/exec"))
        local = self._handler("127.0.0.1", {}, pairing)
        self.assertTrue(local._companion_authorized("/companion/device/command"))
        good = self._handler("192.168.0.6", {"Authorization": f"Companion {device.device_id}:{device.credential}"}, pairing)
        self.assertTrue(good._companion_authorized("/companion/rooms"))
        bad = self._handler("192.168.0.6", {"Authorization": f"Companion {device.device_id}:nope"}, pairing)
        self.assertFalse(bad._companion_authorized("/companion/rooms"))
        bearer = self._handler("192.168.0.6", {"Authorization": "Bearer x"}, pairing)
        self.assertFalse(bearer._companion_authorized("/companion/rooms"))


if __name__ == "__main__":
    unittest.main()
