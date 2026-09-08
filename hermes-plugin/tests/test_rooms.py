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
from peers import PeerStore  # noqa: E402
from rooms import (  # noqa: E402
    OPERATOR,
    ChatQTurnRunner,
    Policy,
    RoomError,
    RoomStore,
    build_turn_prompt,
    display_glyph,
    glyph,
    is_pass,
    mentions,
    render_delta,
    split_participant,
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
    approval_events: dict = {}
    decisions: dict = {}
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
            elif method in ("approval.respond", "clarify.respond", "sudo.respond", "secret.respond"):
                req_id = str(params.get("request_id") or "")
                with FakeDashboard.lock:
                    FakeDashboard.decisions[req_id] = str(params.get("decision") or params.get("choice") or params.get("answer") or "")
                    ev = FakeDashboard.approval_events.get(req_id)
                if ev is not None:
                    ev.set()
                self._reply(rid, {"ok": True})
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
        if FakeDashboard.result_first:
            self._reply(rid, {"status": "streaming"})
            time.sleep(0.05)
        if isinstance(reply, dict) and "approval" in reply:
            # Agent needs the operator: raise approval.request and wait for the answer.
            req = dict(reply["approval"])
            req_id = str(req.get("request_id") or f"apr-{secrets.token_hex(2)}")
            ev = threading.Event()
            with FakeDashboard.lock:
                FakeDashboard.approval_events[req_id] = ev
            self._write(self._event("approval.request", sid, {"request_id": req_id, "command": req.get("command", "rm -rf /tmp/x"),
                                                               "choices": req.get("choices", ["once", "deny"])}))
            if not ev.wait(timeout=float(req.get("wait", 10.0))):
                self._write(self._event("message.complete", sid, {"ok": False}))
                if not FakeDashboard.result_first:
                    self._reply(rid, {"status": "ok"})
                return
            with FakeDashboard.lock:
                decision = FakeDashboard.decisions.get(req_id, "")
            reply = reply.get("reply", f"decision was {decision}").replace("{decision}", decision)
        chunks = reply if isinstance(reply, list) else [reply[i:i + 12] for i in range(0, len(reply), 12)] or [""]
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
        FakeDashboard.approval_events = {}
        FakeDashboard.decisions = {}
        self.up = ThreadingHTTPServer(("127.0.0.1", 0), FakeDashboard)
        threading.Thread(target=self.up.serve_forever, daemon=True).start()
        uh, upp = self.up.server_address
        self.env = patch.dict(os.environ, {"HERMES_COMPANION_STANDALONE": "0"}, clear=False)
        self.env.start()
        self.tmp = tempfile.TemporaryDirectory()
        state = RelayState(pairing=PairingStore(), rooms=RoomStore(path=Path(self.tmp.name) / "rooms.json"),
                           peers=PeerStore(path=Path(self.tmp.name) / "peers.json"))
        # No hermes CLI on the test box: the text-only runner is off unless a test installs a fake one.
        state.room_controller.chatq = ChatQTurnRunner(argv_provider=lambda: None)
        state.remote_turns.chatq = state.room_controller.chatq
        self.state = state
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
        code, body = _http("POST", f"{self.base}/companion/rooms", {"title": "triage", "participants": ["coder", "ops"], "policy": {"mode": "bounded", "max_rounds": 2}})
        self.assertEqual(code, 201, body)
        self.assertEqual(body["room"]["mode"], "bounded")
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

    def test_operator_can_post_mid_plan_and_interrupt_stops_turn(self):
        FakeDashboard.delay = 0.25
        FakeDashboard.scripts = {"coder": lambda text, n: ["slow ", "reply ", "that ", "keeps ", "going ", "on ", "and ", "on"]}
        code, body = _http("POST", f"{self.base}/companion/rooms", {"title": "slow", "participants": ["coder"]})
        rid = body["room"]["id"]
        sock, rfile = _ws_client(self.host, self.port, f"/companion/rooms/events?room_id={rid}")
        try:
            code, _ = _http("POST", f"{self.base}/companion/rooms/{rid}/post", {"text": "go"})
            self.assertEqual(code, 202)
            time.sleep(0.4)
            # v2: the operator is never locked out; the line lands and steers the floor.
            code, body = _http("POST", f"{self.base}/companion/rooms/{rid}/post", {"text": "again"})
            self.assertEqual(code, 202, body)
            code, body = _http("GET", f"{self.base}/companion/rooms/{rid}")
            self.assertTrue(body["room"]["busy"])
            self.assertEqual(body["room"]["state"], "running")
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
        code, hist = _http("GET", f"{self.base}/companion/rooms/{rid}/history")
        self.assertEqual([m["text"] for m in hist["messages"] if m["speaker"] == "operator"], ["go", "again"])
        self.assertEqual(hist["room"]["state"], "idle")

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
        self.assertEqual(health["peers"], [])

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



    # ---- v2: conversation policy -------------------------------------------------------------

    def _run_room(self, rid, text, stop="room.idle"):
        sock, rfile = _ws_client(self.host, self.port, f"/companion/rooms/events?room_id={rid}")
        try:
            code, body = _http("POST", f"{self.base}/companion/rooms/{rid}/post", {"text": text})
            self.assertEqual(code, 202, body)
            return _drain_until(rfile, stop, timeout=20.0)
        finally:
            sock.close()

    def test_converse_keeps_the_floor_moving_until_everyone_passes(self):
        # coder and ops answer the operator, then answer each other once, then both pass → quiet.
        FakeDashboard.scripts = {
            "coder": lambda text, n: ["I think it is the ticket TTL.", "Agreed with ops, TTL it is.", "PASS"][min(n, 2)],
            "ops": lambda text, n: ["Journal shows two restarts, not TTL.", "PASS", "PASS"][min(n, 2)],
        }
        code, body = _http("POST", f"{self.base}/companion/rooms", {"title": "why", "participants": ["coder", "ops"]})
        self.assertEqual(body["room"]["mode"], "converse")
        self.assertEqual(body["room"]["state"], "idle")
        rid = body["room"]["id"]
        events = self._run_room(rid, "why did the gateway restart?")
        turns = [(e["speaker"], e["passed"]) for e in events if e["type"] == "room.turn.end"]
        # operator → coder, ops (both answer) → topic is ops' line → coder answers → topic is coder's
        # line → ops PASS → nobody left who has not spoken since coder's line → quiet.
        self.assertEqual([s for s, _ in turns], ["coder", "ops", "coder", "ops"])
        self.assertEqual([p for _, p in turns], [False, False, False, True])
        states = [(e["state"], e.get("reason", "")) for e in events if e["type"] == "room.state"]
        self.assertEqual(states[0], ("running", ""))
        self.assertEqual(states[-1], ("quiet", ""))
        idle = next(e for e in events if e["type"] == "room.idle")
        self.assertEqual(idle["state"], "quiet")
        code, body = _http("GET", f"{self.base}/companion/rooms/{rid}")
        self.assertEqual(body["room"]["state"], "quiet")
        self.assertEqual(body["room"]["turns_used"], 4)
        submits = [p["text"] for m, p in FakeDashboard.calls if m == "prompt.submit"]
        self.assertIn("this is a live conversation", submits[0])
        self.assertIn("turns left before the operator is asked to continue", submits[2])
        self.assertTrue(events[0]["type"] == "room.ready" and "seq" in events[0])

    def test_budget_pauses_the_room_and_continue_resumes_it(self):
        FakeDashboard.scripts = {"coder": lambda text, n: ["first the TTL angle", "second the journal angle", "third the mint angle", "fourth the deploy angle"][n % 4],
                                 "ops": lambda text, n: ["restarts came in pairs", "the second restart was manual", "no crash in dmesg", "cron overlapped"][n % 4]}
        code, body = _http("POST", f"{self.base}/companion/rooms",
                           {"participants": ["coder", "ops"], "policy": {"max_turns": 3}})
        rid = body["room"]["id"]
        events = self._run_room(rid, "keep going")
        self.assertEqual(len([e for e in events if e["type"] == "room.turn.end"]), 3)
        paused = next(e for e in events if e["type"] == "room.state" and e["state"] == "paused")
        self.assertEqual(paused["reason"], "budget")
        code, body = _http("GET", f"{self.base}/companion/rooms/{rid}")
        self.assertEqual((body["room"]["state"], body["room"]["pause_reason"]), ("paused", "budget"))
        # CONTINUE widens the window by two turns and picks the floor back up.
        sock, rfile = _ws_client(self.host, self.port, f"/companion/rooms/events?room_id={rid}")
        try:
            code, body = _http("POST", f"{self.base}/companion/rooms/{rid}/continue", {"turns": 2})
            self.assertEqual(code, 202)
            self.assertEqual(body["room"]["state"], "running")
            events = _drain_until(rfile, "room.idle", timeout=20.0)
        finally:
            sock.close()
        self.assertEqual(len([e for e in events if e["type"] == "room.turn.end"]), 2)
        self.assertEqual(events[-1]["state"], "paused")
        # A fresh operator line resets the budget window.
        events = self._run_room(rid, "new topic")
        self.assertEqual(len([e for e in events if e["type"] == "room.turn.end"]), 3)

    def test_pause_is_a_soft_stop_after_the_current_turn(self):
        FakeDashboard.delay = 0.15
        FakeDashboard.scripts = {"coder": lambda text, n: ["a ", "b ", "c ", "d "], "ops": lambda text, n: "never mind"}
        code, body = _http("POST", f"{self.base}/companion/rooms", {"participants": ["coder", "ops"]})
        rid = body["room"]["id"]
        sock, rfile = _ws_client(self.host, self.port, f"/companion/rooms/events?room_id={rid}")
        try:
            _http("POST", f"{self.base}/companion/rooms/{rid}/post", {"text": "go"})
            time.sleep(0.25)
            code, body = _http("POST", f"{self.base}/companion/rooms/{rid}/pause", {})
            self.assertTrue(body["pausing"])
            events = _drain_until(rfile, "room.idle", timeout=20.0)
        finally:
            sock.close()
        ends = [e for e in events if e["type"] == "room.turn.end"]
        self.assertEqual(len(ends), 1)
        self.assertEqual(ends[0]["error"], "")  # finished, not interrupted
        self.assertEqual(events[-1]["reason"], "operator")

    def test_stall_detector_pauses_repeating_speaker(self):
        FakeDashboard.scripts = {"coder": lambda text, n: "The answer is the ticket TTL, nothing else matters here.",
                                 "ops": lambda text, n: ["still not sure about that", "hmm, what about the journal?", "and the deploy?"][n % 3]}
        code, body = _http("POST", f"{self.base}/companion/rooms", {"participants": ["coder", "ops"], "policy": {"max_turns": 20}})
        rid = body["room"]["id"]
        events = self._run_room(rid, "thoughts?")
        paused = next(e for e in events if e["type"] == "room.state" and e["state"] == "paused")
        self.assertEqual(paused["reason"], "stall")
        self.assertLessEqual(len([e for e in events if e["type"] == "room.turn.end"]), 4)

    def test_moderated_room_ends_on_end_mark(self):
        FakeDashboard.scripts = {
            "ash": lambda text, n: ["@COD what broke?", "[END]"][min(n, 1)],
            "coder": lambda text, n: "the ticket TTL",
        }
        code, body = _http("POST", f"{self.base}/companion/rooms",
                           {"participants": ["ash", "coder"], "policy": {"mode": "moderated", "moderator": "ash"}})
        self.assertEqual(body["room"]["moderator"], "ash")
        rid = body["room"]["id"]
        events = self._run_room(rid, "standup")
        turns = [e["speaker"] for e in events if e["type"] == "room.turn.end"]
        self.assertEqual(turns, ["ash", "coder", "ash"])
        self.assertEqual(events[-1]["state"], "quiet")
        submits = [p["text"] for m, p in FakeDashboard.calls if m == "prompt.submit"]
        self.assertIn("you moderate", submits[0])
        self.assertIn("ASH moderates", submits[1])

    def test_approval_is_forwarded_to_the_room_and_answered(self):
        FakeDashboard.scripts = {"coder": lambda text, n: {"approval": {"request_id": "apr-9", "command": "rm -rf build"}, "reply": "ran it ({decision})"}}
        code, body = _http("POST", f"{self.base}/companion/rooms", {"participants": ["coder"]})
        rid = body["room"]["id"]
        sock, rfile = _ws_client(self.host, self.port, f"/companion/rooms/events?room_id={rid}")
        try:
            _http("POST", f"{self.base}/companion/rooms/{rid}/post", {"text": "clean the build dir"})
            events = _drain_until(rfile, "room.approval", timeout=10.0)
            approval = events[-1]
            self.assertEqual((approval["speaker"], approval["request_id"], approval["kind"]), ("coder", "apr-9", "approval"))
            self.assertEqual(approval["choices"], ["once", "deny"])
            code, body = _http("GET", f"{self.base}/companion/rooms/{rid}")
            self.assertEqual(body["room"]["approval"]["request_id"], "apr-9")
            code, body = _http("POST", f"{self.base}/companion/rooms/{rid}/approval", {"request_id": "apr-9", "decision": "once"})
            self.assertEqual(code, 200, body)
            events = _drain_until(rfile, "room.idle", timeout=10.0)
        finally:
            sock.close()
        resolved = next(e for e in events if e["type"] == "room.approval.resolved")
        self.assertEqual(resolved["decision"], "once")
        end = next(e for e in events if e["type"] == "room.turn.end")
        self.assertEqual(end["message"]["text"], "ran it (once)")
        responds = [(m, p) for m, p in FakeDashboard.calls if m == "approval.respond"]
        self.assertEqual(responds[0][1]["request_id"], "apr-9")
        code, body = _http("POST", f"{self.base}/companion/rooms/{rid}/approval", {"request_id": "apr-9", "decision": "once"})
        self.assertEqual(code, 409)

    def test_patch_rename_and_policy_and_summary(self):
        FakeDashboard.scripts = {"coder": lambda text, n: "summary: TTL is the cause" if "summary request" in text else "PASS"}
        code, body = _http("POST", f"{self.base}/companion/rooms", {"title": "old", "participants": ["coder"]})
        rid = body["room"]["id"]
        code, body = _http("PATCH", f"{self.base}/companion/rooms/{rid}", {"title": "new name", "policy": {"mode": "bounded", "max_rounds": 3, "hands": "coder"}})
        self.assertEqual(code, 200, body)
        self.assertEqual((body["room"]["title"], body["room"]["mode"], body["room"]["policy"]["max_rounds"], body["room"]["hands"]), ("new name", "bounded", 3, "coder"))
        self._run_room(rid, "hello")
        sock, rfile = _ws_client(self.host, self.port, f"/companion/rooms/events?room_id={rid}")
        try:
            code, body = _http("POST", f"{self.base}/companion/rooms/{rid}/summarize", {})
            self.assertEqual(code, 202, body)
            events = _drain_until(rfile, "room.idle", timeout=10.0)
        finally:
            sock.close()
        end = next(e for e in events if e["type"] == "room.turn.end")
        self.assertEqual(end["message"]["kind"], "summary")
        code, body = _http("GET", f"{self.base}/companion/rooms/{rid}")
        self.assertEqual(body["room"]["summary"]["text"], "summary: TTL is the cause")

    def test_hands_guard_during_a_turn(self):
        FakeDashboard.delay = 0.2
        FakeDashboard.scripts = {"coder": lambda text, n: ["one ", "two ", "three ", "four "], "ops": lambda text, n: "PASS"}
        code, body = _http("POST", f"{self.base}/companion/rooms", {"participants": ["coder", "ops"], "policy": {"hands": "ops"}})
        rid = body["room"]["id"]
        sock, rfile = _ws_client(self.host, self.port, f"/companion/rooms/events?room_id={rid}")
        try:
            _http("POST", f"{self.base}/companion/rooms/{rid}/post", {"text": "@COD go"})
            time.sleep(0.3)
            code, guard = _http("GET", f"{self.base}/companion/rooms/guard?profile=coder")
            self.assertEqual((guard["in_room_turn"], guard["allowed"], guard["hands"]), (True, False, "ops"))
            code, guard = _http("GET", f"{self.base}/companion/rooms/guard?profile=knight")
            self.assertEqual((guard["in_room_turn"], guard["allowed"]), (False, True))
            _drain_until(rfile, "room.idle", timeout=20.0)
        finally:
            sock.close()
        submits = [p["text"] for m, p in FakeDashboard.calls if m == "prompt.submit"]
        self.assertIn("hands: @OPS", submits[0])

    def test_text_only_runner_when_no_dashboard(self):
        fake = Path(self.tmp.name) / "hermes"
        fake.write_text("#!/bin/sh\n# echo the profile and the last room line\nprintf 'text-only reply from %s' \"$2\"\n")
        fake.chmod(0o755)
        self.state.room_controller.chatq = ChatQTurnRunner(argv_provider=lambda: [str(fake)])
        with patch.dict(os.environ, {"HERMES_COMPANION_STANDALONE": "1"}, clear=False):
            code, health = _http("GET", f"{self.base}/companion/health")
            self.assertEqual(health["rooms"], "ok_text_only")
            code, body = _http("POST", f"{self.base}/companion/rooms", {"participants": ["coder"]})
            self.assertEqual(code, 201, body)
            rid = body["room"]["id"]
            events = self._run_room(rid, "hello from standalone")
        end = next(e for e in events if e["type"] == "room.turn.end")
        self.assertEqual(end["message"]["text"], "text-only reply from coder")
        self.assertTrue(any(e["type"] == "room.delta" for e in events))


class PeerRoomTests(unittest.TestCase):
    """Two relays sharing one fake dashboard: a room on A with a participant from B."""

    def setUp(self):
        FakeDashboard.calls = []
        FakeDashboard.turns = {}
        FakeDashboard.delay = 0.0
        FakeDashboard.scripts = {}
        FakeDashboard.result_first = False
        FakeDashboard.stored = {}
        FakeDashboard.approval_events = {}
        FakeDashboard.decisions = {}
        self.up = ThreadingHTTPServer(("127.0.0.1", 0), FakeDashboard)
        threading.Thread(target=self.up.serve_forever, daemon=True).start()
        uh, upp = self.up.server_address
        self.env = patch.dict(os.environ, {"HERMES_COMPANION_STANDALONE": "0"}, clear=False)
        self.env.start()
        self.tmp = tempfile.TemporaryDirectory()
        self.relays = {}
        for name in ("a", "b"):
            st = RelayState(pairing=PairingStore(), rooms=RoomStore(path=Path(self.tmp.name) / f"rooms-{name}.json"),
                            peers=PeerStore(path=Path(self.tmp.name) / f"peers-{name}.json"))
            st.room_controller.chatq = ChatQTurnRunner(argv_provider=lambda: None)
            st.remote_turns.chatq = st.room_controller.chatq
            srv = make_server("127.0.0.1:0", f"http://{uh}:{upp}", st)
            # make_server binds the handler class state globally; keep a per-server handler class.
            handler = type(f"Handler{name}", (CompanionHandler,), {"state": st, "upstream": (uh, upp)})
            srv.RequestHandlerClass = handler
            threading.Thread(target=srv.serve_forever, daemon=True).start()
            h, p = srv.server_address
            self.relays[name] = (srv, st, f"http://{h}:{p}", h, p)

    def tearDown(self):
        for srv, _, _, _, _ in self.relays.values():
            srv.shutdown()
            srv.server_close()
        self.up.shutdown()
        self.up.server_close()
        self.env.stop()
        self.tmp.cleanup()

    def _link(self):
        _, _, a, _, _ = self.relays["a"]
        _, _, b, _, _ = self.relays["b"]
        code, body = _http("POST", f"{b}/companion/peers/grant", {"name": "lab"})
        self.assertEqual(code, 201, body)
        g = body["grant"]
        code, body = _http("POST", f"{a}/companion/peers", {"name": "hub", "origin": b, "host_id": g["host_id"], "secret": g["secret"]})
        self.assertEqual(code, 201, body)
        return a, b, g

    def test_room_with_remote_participant_streams_and_binds_backing(self):
        FakeDashboard.scripts = {"coder": lambda text, n: "local view: TTL", "ops": lambda text, n: "remote view: restarts. PASS" if n else "remote view: restarts"}
        a, b, _ = self._link()
        code, body = _http("POST", f"{a}/companion/rooms", {"participants": ["coder", "ops@nope"]})
        self.assertEqual((code, body["error"]), (409, "peer_unknown"))
        code, body = _http("POST", f"{a}/companion/rooms", {"title": "x-host", "participants": ["coder", "ops@hub"], "policy": {"max_turns": 4}})
        self.assertEqual(code, 201, body)
        rid = body["room"]["id"]
        parts = body["room"]["participants"]
        self.assertEqual([(p["profile"], p["host"], p["glyph"]) for p in parts], [("coder", None, "COD"), ("ops", "hub", "OPS")])
        _, _, _, h, p = self.relays["a"]
        sock, rfile = _ws_client(h, p, f"/companion/rooms/events?room_id={rid}")
        try:
            code, body = _http("POST", f"{a}/companion/rooms/{rid}/post", {"text": "who owns it?"})
            self.assertEqual(code, 202, body)
            events = _drain_until(rfile, "room.idle", timeout=20.0)
        finally:
            sock.close()
        ends = [(e["speaker"], e["message"]["text"]) for e in events if e["type"] == "room.turn.end"]
        self.assertIn(("ops@hub", "remote view: restarts"), ends)
        self.assertTrue(any(e["type"] == "room.delta" and e["speaker"] == "ops@hub" for e in events))
        # The remote backing session id was reported back and stored on the room host.
        _, st_a, _, _, _ = self.relays["a"]
        room = st_a.rooms.get(rid)
        self.assertTrue(room.backing.get("ops@hub", "").startswith("stored-ops-"))
        # Remote agent saw a preamble naming its host and the operator's line.
        submits = [p["text"] for m, p in FakeDashboard.calls if m == "prompt.submit" and p["profile"] == "ops"]
        self.assertIn("you are OPS (hub)", submits[0])
        self.assertIn("[room] YOU: who owns it?", submits[0])
        # Second turn on the peer resumes the stored session instead of creating a new one.
        resumes = [p for m, p in FakeDashboard.calls if m == "session.resume" and p["profile"] == "ops"]
        self.assertTrue(resumes)

    def test_remote_approval_round_trips_through_the_room_host(self):
        FakeDashboard.scripts = {"ops": lambda text, n: {"approval": {"request_id": "apr-r", "command": "sudo reboot"}, "reply": "did it ({decision})"}}
        a, b, _ = self._link()
        code, body = _http("POST", f"{a}/companion/rooms", {"participants": ["ops@hub"]})
        rid = body["room"]["id"]
        _, _, _, h, p = self.relays["a"]
        sock, rfile = _ws_client(h, p, f"/companion/rooms/events?room_id={rid}")
        try:
            _http("POST", f"{a}/companion/rooms/{rid}/post", {"text": "reboot it"})
            events = _drain_until(rfile, "room.approval", timeout=10.0)
            self.assertEqual((events[-1]["speaker"], events[-1]["request_id"]), ("ops@hub", "apr-r"))
            code, body = _http("POST", f"{a}/companion/rooms/{rid}/approval", {"request_id": "apr-r", "decision": "deny"})
            self.assertEqual(code, 200, body)
            events = _drain_until(rfile, "room.idle", timeout=10.0)
        finally:
            sock.close()
        end = next(e for e in events if e["type"] == "room.turn.end")
        self.assertEqual(end["message"]["text"], "did it (deny)")

    def test_peer_list_remove_and_grant_revoke(self):
        a, b, g = self._link()
        code, body = _http("GET", f"{a}/companion/peers")
        self.assertEqual([p["name"] for p in body["peers"]], ["hub"])
        code, body = _http("GET", f"{b}/companion/peers")
        self.assertEqual([x["name"] for x in body["grants"]], ["lab"])
        code, health = _http("GET", f"{a}/companion/health")
        self.assertEqual(health["peers"], ["hub"])
        code, body = _http("GET", f"{a}/companion/peers/hub")
        self.assertTrue(body["reachable"])
        code, _ = _http("DELETE", f"{b}/companion/peers/grants/{g['host_id']}")
        self.assertEqual(code, 200)
        code, _ = _http("DELETE", f"{a}/companion/peers/hub")
        self.assertEqual(code, 200)
        code, body = _http("GET", f"{a}/companion/peers")
        self.assertEqual(body["peers"], [])


class PolicyUnitTests(unittest.TestCase):
    def test_policy_parse_defaults_and_legacy(self):
        p = Policy.parse({"max_rounds": 3})
        self.assertEqual((p.mode, p.max_rounds, p.max_turns), ("converse", 3, 12))
        p = Policy.parse({"mode": "bounded", "max_rounds": 9})
        self.assertEqual((p.mode, p.max_rounds), ("bounded", 4))
        p = Policy.parse({"mode": "nope", "max_turns": 500, "cooldown": 7})
        self.assertEqual((p.mode, p.max_turns, p.cooldown), ("converse", 60, 3))
        merged = Policy.parse({"hands": "ops"}, base=Policy(mode="moderated", moderator="ash"))
        self.assertEqual((merged.mode, merged.moderator, merged.hands), ("moderated", "ash", "ops"))

    def test_participant_ids_and_display_glyphs(self):
        self.assertEqual(split_participant("bishop@hub-11"), ("bishop", "hub-11"))
        self.assertEqual(split_participant("coder"), ("coder", None))
        parts = ["ash", "ash@hub-11", "coder"]
        self.assertEqual([display_glyph(p, parts) for p in parts], ["ASH·L", "ASH·H", "COD"])
        self.assertEqual(mentions("@ASH·H and @coder and @you", parts), ["ash@hub-11", "coder", OPERATOR])
        self.assertEqual(mentions("@bishop@hub-11 ping", ["bishop@hub-11"]), ["bishop@hub-11"])
        self.assertEqual(mentions("@ashh", parts), ["ash@hub-11"])

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
        # Peer credentials open only the remote-turn service.
        peers = PeerStore()
        grant = peers.grant("lab")
        peer_ok = self._handler("192.168.0.9", {"Authorization": f"Peer {grant.host_id}:{grant.secret}"}, pairing)
        peer_ok.state.peers = peers
        self.assertTrue(peer_ok._companion_authorized("/companion/peers/turn"))
        self.assertTrue(peer_ok._companion_authorized("/companion/peers/turn/t-1/approval"))
        self.assertFalse(peer_ok._companion_authorized("/companion/rooms"))
        peer_bad = self._handler("192.168.0.9", {"Authorization": f"Peer {grant.host_id}:nope"}, pairing)
        peer_bad.state.peers = peers
        self.assertFalse(peer_bad._companion_authorized("/companion/peers/turn"))


if __name__ == "__main__":
    unittest.main()
