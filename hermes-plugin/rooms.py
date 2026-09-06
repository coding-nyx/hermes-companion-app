"""Agent rooms — group chat between Hermes profiles (P21).

A room is a host-side transcript shared by the operator and two or more profiles. Every
participant profile owns a *backing session* in its own profile (created lazily, titled
``room:<id>``) so memory, soul, tools and model stay that agent's own. The controller runs
turns sequentially: operator post → mention-first / round-robin plan → each turn is a normal
``prompt.submit`` on the backing session with the room delta the agent has not seen → events
stream back to phones as ``room.*`` → reply appended to the transcript.

Guards: ``max_rounds`` per operator post (default 2), ``PASS`` replies stay silent, one plan
per room at a time, per-turn timeout with ``session.interrupt``, ``interrupt_all``.

Requires a real dashboard upstream (proxy mode). The standalone stub cannot run agent turns.
"""

from __future__ import annotations

import base64
import json
import os
import queue
import re
import secrets
import socket
import struct
import threading
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Callable, Iterator
from urllib.parse import urlencode

OPERATOR = "operator"
OPERATOR_GLYPH = "YOU"
DEFAULT_MAX_ROUNDS = 2
MAX_ROUNDS_CAP = 4
DEFAULT_TURN_TIMEOUT_S = 180.0
TITLE_PREFIX = "room:"
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def glyph(profile: str) -> str:
    """3-letter machine glyph, same rule as the phone's ProfileGlyph."""
    if profile == OPERATOR:
        return OPERATOR_GLYPH
    p = (profile or "").strip()
    return (p[:3] or "???").upper()


def is_pass(text: str) -> bool:
    t = (text or "").strip()
    if not t:
        return False
    head = t.split(None, 1)[0].rstrip(".,:;—-").upper()
    return head == "PASS"


_MENTION = re.compile(r"(?<![\w@])@([A-Za-z][\w-]{1,31})")


def mentions(text: str, participants: list[str]) -> list[str]:
    """Participants addressed as @GLYPH or @profile, in order of first mention, deduped."""
    found: list[str] = []
    by_key: dict[str, str] = {}
    for p in participants:
        by_key[p.lower()] = p
        by_key[glyph(p).lower()] = p
    for m in _MENTION.finditer(text or ""):
        hit = by_key.get(m.group(1).lower())
        if hit and hit not in found:
            found.append(hit)
    return found


class RoomError(Exception):
    def __init__(self, code: str, message: str = "", status: int = 400):
        super().__init__(message or code)
        self.code = code
        self.message = message or code
        self.status = status


@dataclass
class Policy:
    max_rounds: int = DEFAULT_MAX_ROUNDS
    turn_timeout_s: float = DEFAULT_TURN_TIMEOUT_S

    @classmethod
    def parse(cls, raw: dict | None) -> "Policy":
        raw = raw if isinstance(raw, dict) else {}
        try:
            rounds = int(raw.get("max_rounds", DEFAULT_MAX_ROUNDS))
        except (TypeError, ValueError):
            rounds = DEFAULT_MAX_ROUNDS
        try:
            timeout = float(raw.get("turn_timeout_s", DEFAULT_TURN_TIMEOUT_S))
        except (TypeError, ValueError):
            timeout = DEFAULT_TURN_TIMEOUT_S
        return cls(max_rounds=max(1, min(rounds, MAX_ROUNDS_CAP)), turn_timeout_s=max(10.0, min(timeout, 900.0)))


@dataclass
class RoomMsg:
    seq: int
    speaker: str  # profile id or "operator"
    text: str
    ts_ms: int
    round: int = 0
    turn_id: str = ""
    tools: list[dict] = field(default_factory=list)
    passed: bool = False
    error: str = ""

    def public(self) -> dict:
        d = asdict(self)
        d["glyph"] = glyph(self.speaker)
        d["role"] = "user" if self.speaker == OPERATOR else "assistant"
        return d


@dataclass
class Room:
    id: str
    title: str
    participants: list[str]
    created_at: float
    updated_at: float
    policy: Policy = field(default_factory=Policy)
    backing: dict[str, str] = field(default_factory=dict)  # profile -> live session id
    last_seen: dict[str, int] = field(default_factory=dict)  # profile -> seq
    transcript: list[RoomMsg] = field(default_factory=list)
    seq: int = 0

    def public(self, busy: bool = False, speaking: str | None = None) -> dict:
        return {
            "id": self.id,
            "title": self.title,
            "participants": [{"profile": p, "glyph": glyph(p)} for p in self.participants],
            "policy": asdict(self.policy),
            "seq": self.seq,
            "message_count": len(self.transcript),
            "created_at": self.created_at,
            "updated_at": self.updated_at,
            "busy": busy,
            "speaking": speaking,
            "last": self.transcript[-1].public() if self.transcript else None,
        }


def default_store_path() -> Path:
    home = Path(os.environ.get("HERMES_HOME") or (Path.home() / ".hermes"))
    return home / "companion-rooms.json"


class RoomStore:
    """JSON-backed room registry. Mode 600. Thread-safe."""

    def __init__(self, path: Path | None = None, now: Callable[[], float] = time.time):
        self.path = Path(path) if path is not None else None
        self.now = now
        self.rooms: dict[str, Room] = {}
        self.lock = threading.RLock()
        if self.path is not None:
            self.load()

    # -- persistence -----------------------------------------------------------------
    def load(self) -> None:
        if self.path is None or not self.path.exists():
            return
        try:
            raw = json.loads(self.path.read_text() or "{}")
        except (OSError, json.JSONDecodeError):
            return
        for row in raw.get("rooms") or []:
            try:
                room = Room(
                    id=str(row["id"]),
                    title=str(row.get("title") or ""),
                    participants=[str(p) for p in row.get("participants") or []],
                    created_at=float(row.get("created_at") or 0),
                    updated_at=float(row.get("updated_at") or 0),
                    policy=Policy.parse(row.get("policy")),
                    backing={str(k): str(v) for k, v in (row.get("backing") or {}).items()},
                    last_seen={str(k): int(v) for k, v in (row.get("last_seen") or {}).items()},
                    transcript=[
                        RoomMsg(
                            seq=int(m.get("seq") or 0),
                            speaker=str(m.get("speaker") or OPERATOR),
                            text=str(m.get("text") or ""),
                            ts_ms=int(m.get("ts_ms") or 0),
                            round=int(m.get("round") or 0),
                            turn_id=str(m.get("turn_id") or ""),
                            tools=[t for t in (m.get("tools") or []) if isinstance(t, dict)],
                            passed=bool(m.get("passed")),
                            error=str(m.get("error") or ""),
                        )
                        for m in row.get("transcript") or []
                    ],
                    seq=int(row.get("seq") or 0),
                )
            except (KeyError, TypeError, ValueError):
                continue
            self.rooms[room.id] = room

    def save(self) -> None:
        if self.path is None:
            return
        payload = {
            "rooms": [
                {
                    "id": r.id,
                    "title": r.title,
                    "participants": r.participants,
                    "created_at": r.created_at,
                    "updated_at": r.updated_at,
                    "policy": asdict(r.policy),
                    "backing": r.backing,
                    "last_seen": r.last_seen,
                    "transcript": [asdict(m) for m in r.transcript],
                    "seq": r.seq,
                }
                for r in self.rooms.values()
            ]
        }
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(payload, indent=1))
        os.chmod(tmp, 0o600)
        tmp.replace(self.path)

    # -- CRUD ------------------------------------------------------------------------
    def create(self, title: str, participants: list[str], policy: dict | None = None) -> Room:
        seen: list[str] = []
        for p in participants:
            p = str(p or "").strip()
            if p and p != OPERATOR and p not in seen:
                seen.append(p)
        if len(seen) < 1:
            raise RoomError("participants_required", "a room needs at least one profile")
        if len(seen) > 6:
            raise RoomError("too_many_participants", "max 6 profiles per room")
        with self.lock:
            rid = "r-" + secrets.token_hex(3)
            while rid in self.rooms:
                rid = "r-" + secrets.token_hex(3)
            now = float(self.now())
            room = Room(
                id=rid,
                title=(title or "").strip() or " + ".join(glyph(p) for p in seen),
                participants=seen,
                created_at=now,
                updated_at=now,
                policy=Policy.parse(policy),
            )
            self.rooms[rid] = room
            self.save()
            return room

    def get(self, room_id: str) -> Room:
        with self.lock:
            room = self.rooms.get(str(room_id or ""))
        if room is None:
            raise RoomError("unknown_room", f"no room {room_id}", 404)
        return room

    def delete(self, room_id: str) -> None:
        with self.lock:
            if self.rooms.pop(str(room_id or ""), None) is None:
                raise RoomError("unknown_room", f"no room {room_id}", 404)
            self.save()

    def list(self) -> list[Room]:
        with self.lock:
            return sorted(self.rooms.values(), key=lambda r: r.updated_at, reverse=True)

    def set_participants(self, room_id: str, add: list[str] | None, remove: list[str] | None) -> Room:
        room = self.get(room_id)
        with self.lock:
            for p in add or []:
                p = str(p or "").strip()
                if p and p != OPERATOR and p not in room.participants:
                    room.participants.append(p)
            for p in remove or []:
                p = str(p or "").strip()
                if p in room.participants:
                    room.participants.remove(p)
            if not room.participants:
                raise RoomError("participants_required", "a room needs at least one profile")
            room.updated_at = float(self.now())
            self.save()
        return room

    def append(self, room: Room, speaker: str, text: str, *, round_no: int = 0, turn_id: str = "",
               tools: list[dict] | None = None, passed: bool = False, error: str = "") -> RoomMsg:
        with self.lock:
            room.seq += 1
            msg = RoomMsg(
                seq=room.seq,
                speaker=speaker,
                text=text,
                ts_ms=int(self.now() * 1000),
                round=round_no,
                turn_id=turn_id,
                tools=list(tools or []),
                passed=passed,
                error=error,
            )
            room.transcript.append(msg)
            room.updated_at = float(self.now())
            if speaker != OPERATOR:
                # Speaking implies having seen everything up to and including your own line.
                room.last_seen[speaker] = msg.seq
            self.save()
            return msg

    def history(self, room: Room, after: int = 0, limit: int = 200) -> list[RoomMsg]:
        with self.lock:
            rows = [m for m in room.transcript if m.seq > int(after or 0)]
        return rows[-max(1, min(int(limit or 200), 1000)):]

    def mark_seen(self, room: Room, profile: str, seq: int) -> None:
        with self.lock:
            room.last_seen[profile] = max(int(room.last_seen.get(profile, 0)), int(seq))
            self.save()

    def bind_backing(self, room: Room, profile: str, session_id: str) -> None:
        with self.lock:
            room.backing[profile] = session_id
            self.save()


# -- agent-facing text ---------------------------------------------------------------------

def preamble(room: Room, profile: str) -> str:
    others = [glyph(p) for p in room.participants if p != profile] + [OPERATOR_GLYPH + "(operator)"]
    handoff = ", ".join(f"@{glyph(p)}" for p in room.participants if p != profile) or "@YOU"
    return (
        f'[room "{room.title}" · you are {glyph(profile)} · others: {", ".join(others)}]\n'
        f"[rules: reply to the room in plain text; mention {handoff} to hand off; "
        f"reply exactly PASS to stay silent; max {room.policy.max_rounds} rounds per operator message; "
        f"do not call mobile_* control tools from a room turn]"
    )


def render_delta(room: Room, profile: str, since: int) -> str:
    lines: list[str] = []
    for m in room.transcript:
        if m.seq <= since:
            continue
        if m.passed:
            lines.append(f"[room] {glyph(m.speaker)}: (passed)")
            continue
        for t in m.tools:
            name = str(t.get("name") or "tool")
            detail = str(t.get("detail") or "").strip()
            lines.append(f"[{glyph(m.speaker)} ran {name}" + (f" · {detail[:120]}" if detail else "") + "]")
        text = m.text.strip()
        if text:
            lines.append(f"[room] {glyph(m.speaker)}: {text}")
    return "\n".join(lines)


def build_turn_prompt(room: Room, profile: str, first_turn: bool) -> str:
    since = int(room.last_seen.get(profile, 0))
    delta = render_delta(room, profile, since)
    parts = []
    if first_turn or since == 0:
        parts.append(preamble(room, profile))
    else:
        parts.append(f"[room {glyph(profile)} · reply to the room · @GLYPH hands off · PASS stays silent]")
    parts.append(delta if delta else "[room] (no new messages)")
    return "\n".join(parts)


# -- upstream dashboard websocket client ---------------------------------------------------------

def _ws_text_masked(msg: str) -> bytes:
    data = msg.encode()
    n = len(data)
    mask = secrets.token_bytes(4)
    if n < 126:
        head = bytes([0x81, 0x80 | n])
    elif n < 65536:
        head = bytes([0x81, 0x80 | 126]) + struct.pack("!H", n)
    else:
        head = bytes([0x81, 0x80 | 127]) + struct.pack("!Q", n)
    return head + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data))


class UpstreamWs:
    """One JSON-RPC websocket to the dashboard for one profile. Not thread-safe across turns;
    the controller serialises turns per room and opens one socket per (room, profile)."""

    def __init__(self, host: str, port: int, profile: str, timeout: float = 8.0):
        self.host = host
        self.port = port
        self.profile = profile
        self.timeout = timeout
        self.sock: socket.socket | None = None
        self.rfile = None
        self._id = 0
        self.hello: dict = {}

    # -- lifecycle
    def connect(self, token: str | None = None) -> dict:
        qs = {"profile": self.profile}
        if token:
            qs["token"] = token
        path = "/api/ws?" + urlencode(qs)
        sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        key = base64.b64encode(secrets.token_bytes(16)).decode()
        req = (
            f"GET {path} HTTP/1.1\r\nHost: {self.host}:{self.port}\r\n"
            "Upgrade: websocket\r\nConnection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n"
        ).encode()
        sock.sendall(req)
        rfile = sock.makefile("rb")
        status = rfile.readline()
        if b" 101 " not in status:
            code = status.split(b" ")[1].decode(errors="replace") if b" " in status else "?"
            sock.close()
            raise RoomError("upstream_ws_refused", f"dashboard ws refused ({code})", 502)
        while True:
            line = rfile.readline()
            if line in (b"\r\n", b"\n", b""):
                break
        self.sock = sock
        self.rfile = rfile
        # First event is gateway.ready.
        deadline = time.monotonic() + self.timeout
        while time.monotonic() < deadline:
            msg = self._recv()
            if msg is None:
                break
            if not msg:
                continue
            for obj in _json_lines(msg):
                params = obj.get("params") if isinstance(obj, dict) else None
                if isinstance(params, dict) and params.get("type") == "gateway.ready":
                    self.hello = params.get("payload") or {}
                    return self.hello
        raise RoomError("upstream_no_hello", "dashboard ws gave no gateway.ready", 502)

    def close(self) -> None:
        try:
            if self.sock is not None:
                self.sock.close()
        except OSError:
            pass
        self.sock = None
        self.rfile = None

    # -- io
    def _send(self, obj: dict) -> None:
        if self.sock is None:
            raise RoomError("upstream_closed", "dashboard ws closed", 502)
        self.sock.sendall(_ws_text_masked(json.dumps(obj)))

    def _recv(self) -> str | None:
        if self.rfile is None:
            return None
        try:
            from .relay import _ws_recv
        except ImportError:
            from relay import _ws_recv
        # Client side: pong frames need masking; pass None so _ws_recv does not write.
        return _ws_recv(self.rfile, None)

    def request(self, method: str, params: dict, timeout: float | None = None) -> dict:
        """Send a request and return its result, discarding interleaved events."""
        for kind, payload in self.call(method, params, timeout=timeout):
            if kind == "result":
                return payload
        return {}

    def call(self, method: str, params: dict, timeout: float | None = None) -> Iterator[tuple[str, dict]]:
        """Yield ("event", params) for events and ("result", result) for the reply, in arrival order,
        and keep yielding events afterwards until the caller stops iterating or [timeout] passes.

        The real dashboard answers `prompt.submit` with {"status": "streaming"} *before* any
        token arrives, so a turn must keep reading past the result until message.complete."""
        self._id += 1
        rid = f"r{self._id}"
        self._send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        deadline = time.monotonic() + (timeout or self.timeout)
        if self.sock is not None:
            self.sock.settimeout(max(1.0, min(30.0, deadline - time.monotonic())))
        while True:
            if time.monotonic() > deadline:
                raise RoomError("upstream_timeout", f"{method} timed out", 504)
            try:
                msg = self._recv()
            except socket.timeout:
                continue
            if msg is None:
                raise RoomError("upstream_closed", "dashboard ws closed mid-call", 502)
            if not msg:
                continue
            for obj in _json_lines(msg):
                if not isinstance(obj, dict):
                    continue
                if obj.get("id") == rid:
                    if "error" in obj:
                        err = obj.get("error") or {}
                        raise RoomError(
                            f"rpc_{err.get('code', 'error')}", str(err.get("message") or method), 502
                        )
                    yield "result", obj.get("result") or {}
                    continue
                params_obj = obj.get("params")
                if obj.get("method") == "event" and isinstance(params_obj, dict):
                    yield "event", params_obj


def _json_lines(raw: str) -> list:
    out = []
    for line in raw.split("\n"):
        line = line.strip()
        if not line:
            continue
        try:
            out.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return out


# -- event hub (relay → phones) ------------------------------------------------------------------

class RoomEvents:
    """Fan-out of room.* events to subscriber queues (one per connected phone socket)."""

    def __init__(self):
        self.lock = threading.Lock()
        self.subs: list[tuple[str | None, "queue.Queue[dict]"]] = []

    def subscribe(self, room_id: str | None = None) -> "queue.Queue[dict]":
        q: "queue.Queue[dict]" = queue.Queue(maxsize=2048)
        with self.lock:
            self.subs.append((room_id, q))
        return q

    def unsubscribe(self, q: "queue.Queue[dict]") -> None:
        with self.lock:
            self.subs = [(rid, s) for rid, s in self.subs if s is not q]

    def emit(self, event: dict) -> None:
        rid = event.get("room_id")
        with self.lock:
            targets = [s for want, s in self.subs if want is None or want == rid]
        for s in targets:
            try:
                s.put_nowait(event)
            except queue.Full:
                pass


# -- controller -----------------------------------------------------------------------------------

@dataclass
class _Plan:
    room_id: str
    trigger_seq: int
    queue: list[tuple[str, int]]  # (profile, round)
    cancelled: bool = False
    active: str | None = None
    active_ws: UpstreamWs | None = None
    active_session: str | None = None


class RoomController:
    def __init__(
        self,
        store: RoomStore,
        events: RoomEvents | None = None,
        upstream: tuple[str, int] = ("127.0.0.1", 9119),
        ws_factory: Callable[[str, int, str], UpstreamWs] | None = None,
        available: Callable[[], bool] | None = None,
        token_provider: Callable[[], str | None] | None = None,
    ):
        self.store = store
        self.events = events or RoomEvents()
        self.upstream = upstream
        self.ws_factory = ws_factory or (lambda h, p, prof: UpstreamWs(h, p, prof))
        self.available = available or (lambda: True)
        self.token_provider = token_provider or (lambda: None)
        self.lock = threading.Lock()
        self.plans: dict[str, _Plan] = {}

    # -- status
    def busy(self, room_id: str) -> tuple[bool, str | None]:
        with self.lock:
            plan = self.plans.get(room_id)
            return (plan is not None, plan.active if plan else None)

    def public_room(self, room: Room) -> dict:
        b, speaking = self.busy(room.id)
        return room.public(busy=b, speaking=speaking)

    # -- operator actions
    def post(self, room_id: str, text: str) -> RoomMsg:
        if not self.available():
            raise RoomError("rooms_unavailable", "rooms need the dashboard upstream (proxy mode)", 503)
        room = self.store.get(room_id)
        text = (text or "").strip()
        if not text:
            raise RoomError("text_required", "text required")
        with self.lock:
            if room.id in self.plans:
                raise RoomError("room_busy", "a turn is already running; interrupt first", 409)
            msg = self.store.append(room, OPERATOR, text)
            targets = mentions(text, room.participants) or list(room.participants)
            plan = _Plan(room_id=room.id, trigger_seq=msg.seq, queue=[(p, 1) for p in targets])
            self.plans[room.id] = plan
        self.events.emit({"type": "room.post", "room_id": room.id, "message": msg.public()})
        threading.Thread(target=self._run_plan, args=(room, plan), name=f"room-{room.id}", daemon=True).start()
        return msg

    def interrupt(self, room_id: str) -> bool:
        with self.lock:
            plan = self.plans.get(room_id)
            if plan is None:
                return False
            plan.cancelled = True
            plan.queue.clear()
            ws, sid, prof = plan.active_ws, plan.active_session, plan.active
        if ws is not None and sid:
            try:
                # Best effort; the turn loop also checks `cancelled` between events.
                ws._send({"jsonrpc": "2.0", "id": "interrupt", "method": "session.interrupt",
                          "params": {"session_id": sid, "profile": prof}})
            except Exception:
                pass
        self.events.emit({"type": "room.interrupted", "room_id": room_id})
        return True

    # -- plan execution
    def _run_plan(self, room: Room, plan: _Plan) -> None:
        try:
            while True:
                with self.lock:
                    if plan.cancelled or not plan.queue:
                        break
                    profile, round_no = plan.queue.pop(0)
                    plan.active = profile
                if profile not in room.participants:
                    continue
                reply = self._run_turn(room, plan, profile, round_no)
                if reply is None or plan.cancelled:
                    continue
                if round_no < room.policy.max_rounds and not reply.passed and not reply.error:
                    next_up = [p for p in mentions(reply.text, room.participants) if p != profile]
                    with self.lock:
                        queued = {p for p, _ in plan.queue}
                        for p in next_up:
                            if p not in queued:
                                plan.queue.append((p, round_no + 1))
        finally:
            with self.lock:
                self.plans.pop(room.id, None)
            self.events.emit({"type": "room.idle", "room_id": room.id, "seq": room.seq})

    def _create_backing(self, room: Room, ws: UpstreamWs, profile: str) -> tuple[str, str]:
        """Create the participant's backing session. Returns (stored_id, live_id)."""
        result = ws.request(
            "session.create",
            {"profile": profile, "title": f"{TITLE_PREFIX}{room.id} {room.title}"[:80]},
        )
        live = str(result.get("session_id") or result.get("id") or "")
        stored = str(result.get("stored_session_id") or live)
        if not live:
            raise RoomError("backing_session_failed", "session.create returned no id", 502)
        self.store.bind_backing(room, profile, stored)
        return stored, live

    def _ensure_backing(self, room: Room, ws: UpstreamWs, profile: str) -> tuple[str, str, bool]:
        """Resolve (stored_id, live_id, fresh). Dashboards hand out an ephemeral live handle per
        connection; only `stored_session_id` survives, so every turn resumes it. A stale or unknown
        stored id (e.g. the dashboard was reset) gets a fresh backing session."""
        stored = room.backing.get(profile)
        if not stored:
            s, l = self._create_backing(room, ws, profile)
            return s, l, True
        try:
            result = ws.request("session.resume", {"session_id": stored, "profile": profile})
            live = str(result.get("session_id") or stored)
            return stored, live, False
        except RoomError:
            s, l = self._create_backing(room, ws, profile)
            return s, l, True

    def _run_turn(self, room: Room, plan: _Plan, profile: str, round_no: int) -> RoomMsg | None:
        turn_id = f"t-{secrets.token_hex(3)}"
        host, port = self.upstream
        ws = self.ws_factory(host, port, profile)
        text_parts: list[str] = []
        tools: list[dict] = []
        error = ""
        self.events.emit({"type": "room.turn.start", "room_id": room.id, "speaker": profile,
                          "glyph": glyph(profile), "turn_id": turn_id, "round": round_no})
        try:
            ws.connect(self.token_provider())
            stored, sid, fresh = self._ensure_backing(room, ws, profile)
            with self.lock:
                plan.active_ws, plan.active_session = ws, sid
            prompt = build_turn_prompt(room, profile, first_turn=fresh)
            for kind, payload in ws.call(
                "prompt.submit",
                {"session_id": sid, "text": prompt, "profile": profile},
                timeout=room.policy.turn_timeout_s,
            ):
                if plan.cancelled:
                    error = "interrupted"
                    break
                if kind != "event":
                    continue
                etype = str(payload.get("type") or "")
                evsid = str(payload.get("session_id") or "")
                if evsid and evsid not in (sid, stored):
                    continue
                body = payload.get("payload") if isinstance(payload.get("payload"), dict) else {}
                if etype in ("message.delta", "assistant.delta", "token"):
                    piece = str(body.get("text") or body.get("delta") or body.get("content") or "")
                    if piece:
                        text_parts.append(piece)
                        self.events.emit({"type": "room.delta", "room_id": room.id, "speaker": profile,
                                          "turn_id": turn_id, "text": piece})
                elif etype.startswith("tool.start"):
                    tool = {"name": str(body.get("name") or body.get("tool") or "tool"),
                            "detail": str(body.get("detail") or body.get("command") or body.get("input") or "")}
                    tools.append(tool)
                    self.events.emit({"type": "room.tool.start", "room_id": room.id, "speaker": profile,
                                      "turn_id": turn_id, **tool})
                elif etype.startswith("tool.complete"):
                    self.events.emit({"type": "room.tool.complete", "room_id": room.id, "speaker": profile,
                                      "turn_id": turn_id,
                                      "name": str(body.get("name") or body.get("tool") or "tool"),
                                      "detail": str(body.get("detail") or ""),
                                      "duration_ms": int(body.get("duration_ms") or 0)})
                elif etype in ("message.complete", "run.completed", "done") or etype.endswith(".complete") and etype.startswith("message"):
                    break
                elif "approval" in etype or "clarify" in etype:
                    # Rooms cannot answer approvals; surface and stop this turn.
                    error = "approval_required"
                    break
        except RoomError as exc:
            error = exc.code
        except Exception as exc:  # pragma: no cover - defensive
            error = f"turn_failed:{exc.__class__.__name__}"
        finally:
            with self.lock:
                plan.active_ws, plan.active_session = None, None
            ws.close()
        text = "".join(text_parts).strip()
        passed = is_pass(text) and not error
        msg = self.store.append(room, profile, text, round_no=round_no, turn_id=turn_id,
                                tools=tools, passed=passed, error=error)
        self.events.emit({"type": "room.turn.end", "room_id": room.id, "speaker": profile,
                          "turn_id": turn_id, "seq": msg.seq, "passed": passed, "error": error,
                          "message": msg.public()})
        return msg
