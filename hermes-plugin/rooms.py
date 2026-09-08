"""Agent rooms — group chat between Hermes profiles (P21, v2 in P22).

A room is a host-side transcript shared by the operator and one or more profiles. Every
participant owns a *backing session* in its own profile (created lazily, titled ``room:<id>``)
so memory, soul, tools and model stay that agent's own. Remote participants (``profile@peer``)
run their turns on their own relay through a peer link (peers.py); the room host only stores
the transcript and drives the floor.

v2 turn model — the floor, not the rounds:
  * ``converse`` (default): after every line the participants that have not spoken since the
    last real message get the floor (addressed ones first, least-recent speaker first, no one
    twice in a row unless addressed). The exchange goes **quiet** when everyone has passed.
    ``max_turns`` / ``max_minutes`` are circuit breakers that *pause* the room (CONTINUE resumes).
  * ``moderated``: the moderator speaks after the operator and after every reply that does not
    address someone; ``[END]`` from the moderator ends the exchange.
  * ``bounded``: the v1 mention-first / round-robin plan with a hard ``max_rounds`` cap.
  * The operator may post at any time; the line is appended and steers the floor (no 409).
  * Approvals/clarifications raised by an agent pause its turn and are forwarded to the phone
    (``room.approval``); ``respond_approval`` answers them on the agent's own session.
  * A stall detector pauses the room when a speaker repeats itself.

Runners: the dashboard websocket (streaming) when the relay proxies a dashboard, otherwise the
text-only ``hermes chat -Q`` path, and the peer HTTP stream for remote participants.
"""

from __future__ import annotations

import base64
import difflib
import json
import os
import queue
import re
import secrets
import socket
import struct
import subprocess
import tempfile
import threading
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Callable, Iterator

from urllib.parse import urlencode

OPERATOR = "operator"
OPERATOR_GLYPH = "YOU"
MODES = ("bounded", "converse", "moderated")
DEFAULT_MODE = "converse"
DEFAULT_MAX_ROUNDS = 2
MAX_ROUNDS_CAP = 4
DEFAULT_MAX_TURNS = 12
MAX_TURNS_CAP = 60
DEFAULT_MAX_MINUTES = 10.0
DEFAULT_TURN_TIMEOUT_S = 300.0
DEFAULT_APPROVAL_TIMEOUT_S = 600.0
CONTINUE_STEP = 6
STALL_SIMILARITY = 0.9
STALL_MIN_CHARS = 40
END_MARK = "[END]"
TITLE_PREFIX = "room:"
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
STATES = ("idle", "running", "quiet", "paused")


# -- identity ------------------------------------------------------------------------------------

def split_participant(pid: str) -> tuple[str, str | None]:
    """``bishop@hub-11`` → (``bishop``, ``hub-11``); local participants have no host."""
    p = (pid or "").strip()
    if "@" in p:
        prof, _, host = p.partition("@")
        return prof.strip(), (host.strip() or None)
    return p, None


def glyph(profile: str) -> str:
    """3-letter machine glyph, same rule as the phone's ProfileGlyph. Host suffix is dropped."""
    if profile == OPERATOR:
        return OPERATOR_GLYPH
    p, _ = split_participant(profile)
    return (p[:3] or "???").upper()


def display_glyph(pid: str, participants: list[str]) -> str:
    """Glyph, disambiguated with a host tag when two participants collide (``ASH·L`` / ``ASH·H``)."""
    g = glyph(pid)
    if pid == OPERATOR:
        return g
    same = [p for p in participants if glyph(p) == g]
    if len(same) <= 1:
        return g
    _, host = split_participant(pid)
    tag = (host or "local")[:1].upper()
    return f"{g}·{tag}"


def is_pass(text: str) -> bool:
    t = (text or "").strip()
    if not t:
        return False
    head = t.split(None, 1)[0].rstrip(".,:;—-").upper()
    return head == "PASS"


def is_end(text: str) -> bool:
    return (text or "").strip().upper() == END_MARK


_MENTION = re.compile(r"(?<![\w@])@([A-Za-z][\w·-]{1,31}(?:@[\w.-]{1,31})?)")


def mentions(text: str, participants: list[str]) -> list[str]:
    """Participants addressed as @GLYPH, @profile or @profile@host, in order, deduped.
    The operator is returned as ``operator`` when addressed as @YOU."""
    found: list[str] = []
    by_key: dict[str, str] = {}
    for p in participants:
        prof, host = split_participant(p)
        by_key[p.lower()] = p
        by_key[prof.lower()] = by_key.get(prof.lower(), p)
        by_key[glyph(p).lower()] = by_key.get(glyph(p).lower(), p)
        by_key[display_glyph(p, participants).lower().replace("·", "")] = p
        if host:
            by_key[f"{glyph(p).lower()}@{host.lower()}"] = p
    by_key[OPERATOR_GLYPH.lower()] = OPERATOR
    by_key[OPERATOR] = OPERATOR
    for m in _MENTION.finditer(text or ""):
        key = m.group(1).lower().replace("·", "")
        hit = by_key.get(key)
        if hit and hit not in found:
            found.append(hit)
    return found


def mentions_operator(text: str) -> bool:
    return OPERATOR in mentions(text, [])


class RoomError(Exception):
    def __init__(self, code: str, message: str = "", status: int = 400):
        super().__init__(message or code)
        self.code = code
        self.message = message or code
        self.status = status


# -- model ---------------------------------------------------------------------------------------

@dataclass
class Policy:
    mode: str = DEFAULT_MODE
    max_rounds: int = DEFAULT_MAX_ROUNDS          # bounded mode only
    max_turns: int = DEFAULT_MAX_TURNS            # converse / moderated breaker per operator post
    max_minutes: float = DEFAULT_MAX_MINUTES      # wall-clock breaker per operator post
    cooldown: int = 1                             # no one speaks twice in a row unless addressed
    moderator: str = ""                           # moderated mode
    hands: str = ""                               # the only participant allowed mobile_* (A22.9)
    turn_timeout_s: float = DEFAULT_TURN_TIMEOUT_S
    approval_timeout_s: float = DEFAULT_APPROVAL_TIMEOUT_S

    @classmethod
    def parse(cls, raw: dict | None, base: "Policy | None" = None) -> "Policy":
        raw = raw if isinstance(raw, dict) else {}
        cur = base or cls()

        def _int(key, default, lo, hi):
            try:
                return max(lo, min(int(raw.get(key, default)), hi))
            except (TypeError, ValueError):
                return default

        def _float(key, default, lo, hi):
            try:
                return max(lo, min(float(raw.get(key, default)), hi))
            except (TypeError, ValueError):
                return default

        mode = str(raw.get("mode") or cur.mode or DEFAULT_MODE).strip().lower()
        if mode not in MODES:
            mode = DEFAULT_MODE
        return cls(
            mode=mode,
            max_rounds=_int("max_rounds", cur.max_rounds, 1, MAX_ROUNDS_CAP),
            max_turns=_int("max_turns", cur.max_turns, 1, MAX_TURNS_CAP),
            max_minutes=_float("max_minutes", cur.max_minutes, 0.5, 240.0),
            cooldown=_int("cooldown", cur.cooldown, 0, 3),
            moderator=str(raw.get("moderator", cur.moderator) or "").strip(),
            hands=str(raw.get("hands", cur.hands) or "").strip(),
            turn_timeout_s=_float("turn_timeout_s", cur.turn_timeout_s, 10.0, 900.0),
            approval_timeout_s=_float("approval_timeout_s", cur.approval_timeout_s, 30.0, 3600.0),
        )


@dataclass
class RoomMsg:
    seq: int
    speaker: str  # profile id (optionally profile@host) or "operator"
    text: str
    ts_ms: int
    round: int = 0
    turn_id: str = ""
    tools: list[dict] = field(default_factory=list)
    passed: bool = False
    error: str = ""
    kind: str = ""  # "" | "summary"

    def public(self, participants: list[str] | None = None) -> dict:
        d = asdict(self)
        d["glyph"] = display_glyph(self.speaker, participants or []) if participants else glyph(self.speaker)
        d["role"] = "user" if self.speaker == OPERATOR else "assistant"
        d["mentions_operator"] = bool(self.text) and mentions_operator(self.text)
        return d


@dataclass
class Room:
    id: str
    title: str
    participants: list[str]
    created_at: float
    updated_at: float
    policy: Policy = field(default_factory=Policy)
    backing: dict[str, str] = field(default_factory=dict)  # participant -> stored session id
    last_seen: dict[str, int] = field(default_factory=dict)  # participant -> seq
    transcript: list[RoomMsg] = field(default_factory=list)
    seq: int = 0
    state: str = "idle"
    pause_reason: str = ""
    turns_used: int = 0
    # Extra turns granted by CONTINUE since the last operator line (budget = max_turns + budget_extra).
    budget_extra: int = 0
    summary_seq: int = 0

    def participant_public(self, pid: str) -> dict:
        prof, host = split_participant(pid)
        return {
            "id": pid,
            "profile": prof,
            "host": host,
            "glyph": display_glyph(pid, self.participants),
        }

    def public(self, busy: bool = False, speaking: str | None = None, approval: dict | None = None) -> dict:
        summary = next((m for m in reversed(self.transcript) if m.kind == "summary"), None)
        return {
            "id": self.id,
            "title": self.title,
            "participants": [self.participant_public(p) for p in self.participants],
            "policy": asdict(self.policy),
            "mode": self.policy.mode,
            "state": "running" if busy else (self.state if self.state in STATES else "idle"),
            "pause_reason": self.pause_reason if not busy else "",
            "turns_used": self.turns_used,
            "budget": self.policy.max_turns + self.budget_extra,
            "hands": self.policy.hands,
            "moderator": self.policy.moderator,
            "seq": self.seq,
            "message_count": len(self.transcript),
            "created_at": self.created_at,
            "updated_at": self.updated_at,
            "busy": busy,
            "speaking": speaking,
            "approval": approval,
            "summary": summary.public(self.participants) if summary else None,
            "last": self.transcript[-1].public(self.participants) if self.transcript else None,
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
                            kind=str(m.get("kind") or ""),
                        )
                        for m in row.get("transcript") or []
                    ],
                    seq=int(row.get("seq") or 0),
                    state=str(row.get("state") or "idle"),
                    pause_reason=str(row.get("pause_reason") or ""),
                    turns_used=int(row.get("turns_used") or 0),
                    budget_extra=int(row.get("budget_extra") or 0),
                    summary_seq=int(row.get("summary_seq") or 0),
                )
            except (KeyError, TypeError, ValueError):
                continue
            # A relay restart never leaves a room "running".
            if room.state == "running":
                room.state = "idle"
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
                    "state": r.state,
                    "pause_reason": r.pause_reason,
                    "turns_used": r.turns_used,
                    "budget_extra": r.budget_extra,
                    "summary_seq": r.summary_seq,
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
    @staticmethod
    def _clean_participants(participants: list[str]) -> list[str]:
        seen: list[str] = []
        for p in participants:
            p = str(p or "").strip()
            if not p or p == OPERATOR:
                continue
            prof, host = split_participant(p)
            if not prof:
                continue
            pid = f"{prof}@{host}" if host else prof
            if pid not in seen:
                seen.append(pid)
        return seen

    def create(self, title: str, participants: list[str], policy: dict | None = None) -> Room:
        seen = self._clean_participants(participants)
        if len(seen) < 1:
            raise RoomError("participants_required", "a room needs at least one profile")
        if len(seen) > 6:
            raise RoomError("too_many_participants", "max 6 profiles per room")
        pol = Policy.parse(policy)
        if pol.moderator and pol.moderator not in seen:
            pol.moderator = seen[0] if pol.mode == "moderated" else ""
        if pol.mode == "moderated" and not pol.moderator:
            pol.moderator = seen[0]
        if pol.hands and pol.hands not in seen:
            pol.hands = ""
        with self.lock:
            rid = "r-" + secrets.token_hex(3)
            while rid in self.rooms:
                rid = "r-" + secrets.token_hex(3)
            now = float(self.now())
            room = Room(
                id=rid,
                title=(title or "").strip() or " + ".join(display_glyph(p, seen) for p in seen),
                participants=seen,
                created_at=now,
                updated_at=now,
                policy=pol,
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

    def update(self, room_id: str, title: str | None = None, policy: dict | None = None) -> Room:
        room = self.get(room_id)
        with self.lock:
            if title is not None and str(title).strip():
                room.title = str(title).strip()[:120]
            if isinstance(policy, dict):
                room.policy = Policy.parse(policy, base=room.policy)
                if room.policy.moderator and room.policy.moderator not in room.participants:
                    room.policy.moderator = ""
                if room.policy.mode == "moderated" and not room.policy.moderator and room.participants:
                    room.policy.moderator = room.participants[0]
                if room.policy.hands and room.policy.hands not in room.participants:
                    room.policy.hands = ""
            room.updated_at = float(self.now())
            self.save()
        return room

    def set_participants(self, room_id: str, add: list[str] | None, remove: list[str] | None) -> Room:
        room = self.get(room_id)
        with self.lock:
            for p in self._clean_participants(add or []):
                if p not in room.participants:
                    room.participants.append(p)
            for p in self._clean_participants(remove or []):
                if p in room.participants:
                    room.participants.remove(p)
            if not room.participants:
                raise RoomError("participants_required", "a room needs at least one profile")
            if room.policy.moderator not in room.participants:
                room.policy.moderator = room.participants[0] if room.policy.mode == "moderated" else ""
            if room.policy.hands and room.policy.hands not in room.participants:
                room.policy.hands = ""
            room.updated_at = float(self.now())
            self.save()
        return room

    def set_state(self, room: Room, state: str, reason: str = "") -> None:
        with self.lock:
            room.state = state if state in STATES else "idle"
            room.pause_reason = reason if state == "paused" else ""
            room.updated_at = float(self.now())
            self.save()

    def append(self, room: Room, speaker: str, text: str, *, round_no: int = 0, turn_id: str = "",
               tools: list[dict] | None = None, passed: bool = False, error: str = "", kind: str = "") -> RoomMsg:
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
                kind=kind,
            )
            room.transcript.append(msg)
            room.updated_at = float(self.now())
            if kind == "summary":
                room.summary_seq = msg.seq
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


# -- agent-facing text ---------------------------------------------------------------------------

def _who(room: Room, pid: str) -> str:
    _, host = split_participant(pid)
    g = display_glyph(pid, room.participants)
    return f"{g} ({host})" if host else g


def preamble(room: Room, profile: str) -> str:
    others = [_who(room, p) for p in room.participants if p != profile] + [OPERATOR_GLYPH + "(operator)"]
    pol = room.policy
    rules: list[str]
    if pol.mode == "bounded":
        handoff = ", ".join(f"@{display_glyph(p, room.participants)}" for p in room.participants if p != profile) or "@YOU"
        rules = [
            "reply to the room in plain text",
            f"mention {handoff} to hand off",
            "reply exactly PASS to stay silent",
            f"max {pol.max_rounds} rounds per operator message",
        ]
    else:
        rules = [
            "this is a live conversation. Reply when you have something useful to add or ask; reply exactly PASS when you do not",
            "address a participant with @GLYPH to ask them directly; @YOU reaches the operator",
            "you may disagree, ask follow-ups and change your mind",
            "when the group has reached a conclusion, say so in one line and PASS",
        ]
        if pol.mode == "moderated" and pol.moderator:
            if pol.moderator == profile:
                rules.append(f"you moderate: pick who speaks next with @GLYPH; reply exactly {END_MARK} to close the topic")
            else:
                rules.append(f"{display_glyph(pol.moderator, room.participants)} moderates and decides who speaks next")
    if pol.hands and pol.hands == profile:
        rules.append("you hold the phone: only you may call mobile_* control tools in this room, arm first")
    else:
        rules.append(
            "do not call mobile_* control tools from a room turn"
            + (f" (hands: @{display_glyph(pol.hands, room.participants)})" if pol.hands else "")
        )
    return (
        f'[room "{room.title}" · you are {_who(room, profile)} · others: {", ".join(others)}]\n'
        f"[rules: {'; '.join(rules)}]"
    )


def turn_header(room: Room, profile: str, round_no: int, turns_used: int) -> str:
    pol = room.policy
    g = display_glyph(profile, room.participants)
    if pol.mode == "bounded":
        return f"[room {g} · round {round_no} of {pol.max_rounds} · reply / @GLYPH hands off / PASS stays silent]"
    left = max(0, pol.max_turns - turns_used)
    return f"[room {g} · {left} of {pol.max_turns} turns left before the operator is asked to continue · reply / @GLYPH / PASS]"


def render_delta(room: Room, profile: str, since: int) -> str:
    lines: list[str] = []
    for m in room.transcript:
        if m.seq <= since:
            continue
        g = display_glyph(m.speaker, room.participants)
        if m.passed:
            lines.append(f"[room] {g}: (passed)")
            continue
        for t in m.tools:
            name = str(t.get("name") or "tool")
            detail = str(t.get("detail") or "").strip()
            lines.append(f"[{g} ran {name}" + (f" · {detail[:120]}" if detail else "") + "]")
        text = m.text.strip()
        if text:
            prefix = "[room summary]" if m.kind == "summary" else "[room]"
            lines.append(f"{prefix} {g}: {text}")
    return "\n".join(lines)


def build_turn_prompt(room: Room, profile: str, first_turn: bool, round_no: int = 0, turns_used: int = 0) -> str:
    since = int(room.last_seen.get(profile, 0))
    delta = render_delta(room, profile, since)
    parts = []
    if first_turn or since == 0:
        parts.append(preamble(room, profile))
    else:
        parts.append(turn_header(room, profile, round_no, turns_used))
    parts.append(delta if delta else "[room] (no new messages)")
    return "\n".join(parts)


SUMMARY_REQUEST = (
    "[room summary request from the operator: in at most 5 short lines, summarise what this room has "
    "concluded so far, open questions and who owns what. Plain text, no preamble, do not PASS.]"
)


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
        self.deadline: float = 0.0
        self._send_lock = threading.Lock()

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
        with self._send_lock:
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

    def extend_deadline(self, seconds: float) -> None:
        """Push the current call's deadline out (used while a turn waits on an approval)."""
        self.deadline = max(self.deadline, time.monotonic() + seconds)

    def request(self, method: str, params: dict, timeout: float | None = None) -> dict:
        """Send a request and return its result, discarding interleaved events."""
        for kind, payload in self.call(method, params, timeout=timeout):
            if kind == "result":
                return payload
        return {}

    def call(self, method: str, params: dict, timeout: float | None = None) -> Iterator[tuple[str, dict]]:
        """Yield ("event", params) for events and ("result", result) for the reply, in arrival order,
        and keep yielding events afterwards until the caller stops iterating or the deadline passes.

        The real dashboard answers `prompt.submit` with {"status": "streaming"} *before* any
        token arrives, so a turn must keep reading past the result until message.complete."""
        self._id += 1
        rid = f"r{self._id}"
        self._send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        self.deadline = time.monotonic() + (timeout or self.timeout)
        while True:
            remaining = self.deadline - time.monotonic()
            if remaining <= 0:
                raise RoomError("upstream_timeout", f"{method} timed out", 504)
            if self.sock is not None:
                self.sock.settimeout(max(1.0, min(30.0, remaining)))
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

    def watching(self, room_id: str) -> bool:
        """Is any phone subscribed to this room (or to all rooms) right now? Drives operator wake."""
        with self.lock:
            return any(want is None or want == room_id for want, _ in self.subs)

    def emit(self, event: dict) -> None:
        rid = event.get("room_id")
        with self.lock:
            targets = [s for want, s in self.subs if want is None or want == rid]
        for s in targets:
            try:
                s.put_nowait(event)
            except queue.Full:
                pass


# -- approvals -----------------------------------------------------------------------------------

@dataclass
class ApprovalWait:
    request_id: str
    kind: str
    speaker: str
    turn_id: str
    command: str
    choices: list[str]
    deadline: float
    event: threading.Event = field(default_factory=threading.Event)
    decision: str | None = None

    def public(self) -> dict:
        return {
            "request_id": self.request_id,
            "kind": self.kind,
            "speaker": self.speaker,
            "turn_id": self.turn_id,
            "command": self.command,
            "choices": self.choices,
            "expires_in_s": max(0, int(self.deadline - time.monotonic())),
        }


def parse_approval(etype: str, body: dict) -> tuple[str, str, str, list[str]]:
    """(request_id, kind, command/question, choices) from a dashboard approval-family event."""
    kind = "approval"
    for k in ("clarify", "sudo", "secret"):
        if k in etype:
            kind = k
    rid = str(body.get("request_id") or body.get("id") or "")
    command = str(
        body.get("command") or body.get("question") or body.get("prompt") or body.get("tool") or body.get("detail") or ""
    )
    raw_choices = body.get("choices") or body.get("options") or []
    choices = [str(c) for c in raw_choices if str(c)] if isinstance(raw_choices, list) else []
    if not choices:
        choices = {"clarify": ["ok", "deny"], "sudo": ["submit", "deny"], "secret": ["submit", "deny"]}.get(kind, ["once", "deny"])
    return rid, kind, command, choices


def tool_detail(body: dict, limit: int = 400) -> str:
    """One line for a tool row: the dashboard sends `preview`/`args` on start and `preview`/`result`/`output`
    on complete; older shapes use `detail`/`command`/`input`. Empty when nothing usable is there."""
    for key in ("detail", "preview", "command", "input", "summary", "result", "output"):
        val = body.get(key)
        if isinstance(val, str) and val.strip():
            return val.strip()[:limit]
    args = body.get("args") or body.get("arguments")
    if isinstance(args, dict) and args:
        for key in ("command", "cmd", "path", "query", "pattern", "url", "text", "prompt"):
            val = args.get(key)
            if isinstance(val, str) and val.strip():
                return val.strip()[:limit]
        try:
            return json.dumps(args, ensure_ascii=False)[:limit]
        except (TypeError, ValueError):
            return ""
    return ""


def respond_params(kind: str, session_id: str, request_id: str, decision: str, profile: str) -> tuple[str, dict]:
    """Method + params the dashboard expects, mirroring the phone's DashboardClient.respondPrompt."""
    base = {"session_id": session_id, "request_id": request_id, "profile": profile}
    if kind == "clarify":
        return "clarify.respond", {**base, "answer": decision, "choice": decision}
    if kind == "sudo":
        return "sudo.respond", {**base, "password": decision}
    if kind == "secret":
        return "secret.respond", {**base, "value": decision}
    return "approval.respond", {**base, "choice": decision, "decision": decision}


# -- turn runners --------------------------------------------------------------------------------

@dataclass
class TurnResult:
    text: str = ""
    tools: list[dict] = field(default_factory=list)
    error: str = ""


class TurnContext:
    """What a runner needs from the controller: event emission, cancellation, approval waits."""

    def __init__(self, controller: "RoomController", room: Room, plan: "_Plan", profile: str, turn_id: str):
        self.ctl = controller
        self.room = room
        self.plan = plan
        self.profile = profile
        self.turn_id = turn_id
        # Remote turns arrive with the prompt already rendered by the room host.
        self.prompt_override: str | None = None

    @property
    def cancelled(self) -> bool:
        return self.plan.cancelled

    def emit(self, etype: str, **payload) -> None:
        self.ctl.events.emit({"type": etype, "room_id": self.room.id, "speaker": self.profile, "turn_id": self.turn_id, **payload})

    def wait_approval(self, request_id: str, kind: str, command: str, choices: list[str]) -> str | None:
        """Block until the operator answers (or the approval window closes). Returns the decision."""
        return self.ctl._wait_approval(self.room, self.plan, self, request_id, kind, command, choices)


class UpstreamTurnRunner:
    """Streaming turn through the dashboard JSON-RPC websocket (proxy mode)."""

    def __init__(self, upstream: tuple[str, int], ws_factory, token_provider):
        self.upstream = upstream
        self.ws_factory = ws_factory
        self.token_provider = token_provider

    def run(self, ctx: TurnContext, store: RoomStore, first_turn_hint: bool, round_no: int, turns_used: int) -> TurnResult:
        room, profile, plan = ctx.room, ctx.profile, ctx.plan
        host, port = self.upstream
        ws = self.ws_factory(host, port, profile)
        res = TurnResult()
        text_parts: list[str] = []
        try:
            ws.connect(self.token_provider())
            stored, sid, fresh = self._ensure_backing(store, room, ws, profile)
            with ctx.ctl.lock:
                plan.active_ws, plan.active_session = ws, sid
            prompt = ctx.prompt_override or build_turn_prompt(room, profile, first_turn=fresh or first_turn_hint, round_no=round_no, turns_used=turns_used)
            if plan.summarize_by == profile and not ctx.prompt_override:
                prompt = prompt + "\n" + SUMMARY_REQUEST
            for kind, payload in ws.call(
                "prompt.submit",
                {"session_id": sid, "text": prompt, "profile": profile},
                timeout=room.policy.turn_timeout_s,
            ):
                if plan.cancelled:
                    res.error = "interrupted"
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
                        ctx.emit("room.delta", text=piece)
                elif etype.startswith("tool.start"):
                    tool = {"name": str(body.get("name") or body.get("tool") or body.get("tool_name") or "tool"),
                            "detail": tool_detail(body)}
                    res.tools.append(tool)
                    ctx.emit("room.tool.start", **tool)
                elif etype.startswith("tool.complete"):
                    ctx.emit("room.tool.complete",
                             name=str(body.get("name") or body.get("tool") or body.get("tool_name") or "tool"),
                             detail=tool_detail(body),
                             duration_ms=int(body.get("duration_ms") or 0))
                elif etype in ("message.complete", "run.completed", "done") or (etype.endswith(".complete") and etype.startswith("message")):
                    break
                elif ("approval" in etype or "clarify" in etype or "sudo" in etype or "secret" in etype) and "expire" not in etype:
                    rid, akind, command, choices = parse_approval(etype, body)
                    ws.extend_deadline(room.policy.approval_timeout_s + 30)
                    decision = ctx.wait_approval(rid, akind, command, choices)
                    if decision is None:
                        res.error = "approval_timeout"
                        try:
                            ws._send({"jsonrpc": "2.0", "id": "interrupt", "method": "session.interrupt",
                                      "params": {"session_id": sid, "profile": profile}})
                        except Exception:
                            pass
                        break
                    method, params = respond_params(akind, sid, rid, decision, profile)
                    ws._send({"jsonrpc": "2.0", "id": f"a-{secrets.token_hex(2)}", "method": method, "params": params})
                    ws.extend_deadline(room.policy.turn_timeout_s)
        except RoomError as exc:
            res.error = exc.code
        except Exception as exc:  # pragma: no cover - defensive
            res.error = f"turn_failed:{exc.__class__.__name__}"
        finally:
            with ctx.ctl.lock:
                plan.active_ws, plan.active_session = None, None
            ws.close()
        res.text = "".join(text_parts).strip()
        return res

    def _create_backing(self, store: RoomStore, room: Room, ws: UpstreamWs, profile: str) -> tuple[str, str]:
        result = ws.request(
            "session.create",
            {"profile": profile, "title": f"{TITLE_PREFIX}{room.id} {room.title}"[:80]},
        )
        live = str(result.get("session_id") or result.get("id") or "")
        stored = str(result.get("stored_session_id") or live)
        if not live:
            raise RoomError("backing_session_failed", "session.create returned no id", 502)
        store.bind_backing(room, profile, stored)
        return stored, live

    def _ensure_backing(self, store: RoomStore, room: Room, ws: UpstreamWs, profile: str) -> tuple[str, str, bool]:
        stored = room.backing.get(profile)
        if not stored:
            s, l = self._create_backing(store, room, ws, profile)
            return s, l, True
        try:
            result = ws.request("session.resume", {"session_id": stored, "profile": profile})
            live = str(result.get("session_id") or stored)
            return stored, live, False
        except RoomError:
            s, l = self._create_backing(store, room, ws, profile)
            return s, l, True


def _hermes_argv() -> list[str] | None:
    bin_ = (os.environ.get("HERMES_BIN") or "").strip()
    if bin_ and os.path.exists(bin_):
        return [bin_]
    from shutil import which
    found = which("hermes")
    return [found] if found else None


class ChatQTurnRunner:
    """Text-only turn via ``hermes chat -Q`` (standalone hosts, no dashboard).

    There is no persistent backing session, so every turn carries the preamble plus the *whole*
    recent transcript (capped) instead of the unseen delta. The reply lands as one delta."""

    def __init__(self, argv_provider=_hermes_argv, max_lines: int = 60):
        self.argv_provider = argv_provider
        self.max_lines = max_lines

    def available(self) -> bool:
        return bool(self.argv_provider())

    def run(self, ctx: TurnContext, store: RoomStore, first_turn_hint: bool, round_no: int, turns_used: int) -> TurnResult:
        room, profile, plan = ctx.room, ctx.profile, ctx.plan
        res = TurnResult()
        argv = self.argv_provider()
        if not argv:
            res.error = "hermes_cli_missing"
            return res
        # Whole recent context: no session to resume on this path.
        since = max(0, room.seq - self.max_lines)
        body = render_delta(room, profile, since) or "[room] (no new messages)"
        prompt = ctx.prompt_override or (preamble(room, profile) + "\n" + turn_header(room, profile, round_no, turns_used) + "\n" + body)
        if plan.summarize_by == profile and not ctx.prompt_override:
            prompt += "\n" + SUMMARY_REQUEST
        query_file = None
        proc = None
        try:
            with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".txt", prefix="companion-room-", delete=False) as fh:
                fh.write(prompt)
                query_file = fh.name
            prof, _ = split_participant(profile)
            cmd = list(argv)
            if prof and prof != "default":
                cmd += ["-p", prof]
            cmd += ["chat", "-Q", "--oneshot", "--query-file", query_file, "--source", "companion-room",
                    "--max-turns", os.environ.get("HERMES_COMPANION_ROOM_MAX_TURNS", "8"),
                    "--run-budget", os.environ.get("HERMES_COMPANION_ROOM_RUN_BUDGET", "120"),
                    "--accept-hooks"]
            env = os.environ.copy()
            if prof and prof != "default":
                env.pop("HERMES_HOME", None)
            env.setdefault("HERMES_ACCEPT_HOOKS", "1")
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env)
            with ctx.ctl.lock:
                plan.active_proc = proc
            deadline = time.monotonic() + room.policy.turn_timeout_s
            while True:
                try:
                    out, err = proc.communicate(timeout=0.5)
                    break
                except subprocess.TimeoutExpired:
                    if plan.cancelled:
                        proc.kill()
                        out, err = proc.communicate()
                        res.error = "interrupted"
                        break
                    if time.monotonic() > deadline:
                        proc.kill()
                        out, err = proc.communicate()
                        res.error = "upstream_timeout"
                        break
            if not res.error:
                if proc.returncode != 0 and not (out or "").strip():
                    res.error = f"chat_q_rc_{proc.returncode}"
                text = (out or "").strip()
                if text:
                    ctx.emit("room.delta", text=text)
                res.text = text
        except Exception as exc:  # pragma: no cover - defensive
            res.error = f"turn_failed:{exc.__class__.__name__}"
        finally:
            with ctx.ctl.lock:
                plan.active_proc = None
            if query_file:
                try:
                    os.unlink(query_file)
                except OSError:
                    pass
        return res


# -- controller ----------------------------------------------------------------------------------

@dataclass
class _Plan:
    room_id: str
    started_at: float
    cancelled: bool = False
    pause_requested: bool = False
    active: str | None = None
    active_ws: UpstreamWs | None = None
    active_session: str | None = None
    active_proc: object | None = None
    active_remote: object | None = None
    awaiting: ApprovalWait | None = None
    bounded_queue: list[tuple[str, int]] = field(default_factory=list)
    summarize_by: str | None = None


class RoomController:
    def __init__(
        self,
        store: RoomStore,
        events: RoomEvents | None = None,
        upstream: tuple[str, int] = ("127.0.0.1", 9119),
        ws_factory: Callable[[str, int, str], UpstreamWs] | None = None,
        available: Callable[[], bool] | None = None,
        token_provider: Callable[[], str | None] | None = None,
        chatq_runner: ChatQTurnRunner | None = None,
        peers=None,
        wake: Callable[[Room, str, dict], None] | None = None,
        now: Callable[[], float] = time.time,
    ):
        self.store = store
        self.events = events or RoomEvents()
        self.upstream = upstream
        self.ws_factory = ws_factory or (lambda h, p, prof: UpstreamWs(h, p, prof))
        self.available = available or (lambda: True)
        self.token_provider = token_provider or (lambda: None)
        self.chatq = chatq_runner or ChatQTurnRunner()
        self.peers = peers
        self.wake = wake
        self.now = now
        self.lock = threading.RLock()
        self.plans: dict[str, _Plan] = {}

    # -- capabilities
    def mode(self) -> str:
        """``ok`` (dashboard streaming) · ``ok_text_only`` (hermes chat -Q) · ``unavailable``."""
        if self.available():
            return "ok"
        if self.chatq.available():
            return "ok_text_only"
        return "unavailable"

    def _runner(self, participant: str):
        _, host = split_participant(participant)
        if host:
            if self.peers is None:
                raise RoomError("peer_unknown", f"no peer link for {host}", 409)
            return self.peers.turn_runner(host)
        if self.available():
            return UpstreamTurnRunner(self.upstream, self.ws_factory, self.token_provider)
        if self.chatq.available():
            return self.chatq
        raise RoomError("rooms_unavailable", "rooms need the dashboard upstream or the hermes CLI", 503)

    # -- status
    def busy(self, room_id: str) -> tuple[bool, str | None]:
        with self.lock:
            plan = self.plans.get(room_id)
            return (plan is not None, plan.active if plan else None)

    def pending_approval(self, room_id: str) -> dict | None:
        with self.lock:
            plan = self.plans.get(room_id)
            return plan.awaiting.public() if plan and plan.awaiting else None

    def public_room(self, room: Room) -> dict:
        b, speaking = self.busy(room.id)
        return room.public(busy=b, speaking=speaking, approval=self.pending_approval(room.id))

    def guard(self, profile: str) -> dict:
        """Hands gate (A22.9): is ``profile`` mid room-turn, and may it drive the phone?"""
        prof, _ = split_participant(profile)
        with self.lock:
            for plan in self.plans.values():
                if plan.active and split_participant(plan.active)[0] == prof:
                    room = self.store.rooms.get(plan.room_id)
                    holder = room.policy.hands if room else ""
                    return {"in_room_turn": True, "room_id": plan.room_id,
                            "allowed": bool(holder) and split_participant(holder)[0] == prof, "hands": holder}
        return {"in_room_turn": False, "allowed": True, "room_id": None, "hands": ""}

    # -- operator actions
    def post(self, room_id: str, text: str) -> RoomMsg:
        if self.mode() == "unavailable":
            raise RoomError("rooms_unavailable", "rooms need the dashboard upstream or the hermes CLI", 503)
        room = self.store.get(room_id)
        text = (text or "").strip()
        if not text:
            raise RoomError("text_required", "text required")
        msg = self.store.append(room, OPERATOR, text)
        self.events.emit({"type": "room.post", "room_id": room.id, "message": msg.public(room.participants)})
        with self.lock:
            plan = self.plans.get(room.id)
            # A new operator line resets the budget window and steers the floor.
            room.turns_used = 0
            room.budget_extra = 0
            if plan is not None:
                plan.started_at = time.monotonic()
                if room.policy.mode == "bounded":
                    targets = [p for p in mentions(text, room.participants) if p != OPERATOR] or list(room.participants)
                    plan.bounded_queue = [(p, 1) for p in targets]
                return msg
        self._start_plan(room)
        return msg

    def interrupt(self, room_id: str) -> bool:
        with self.lock:
            plan = self.plans.get(room_id)
            if plan is None:
                return False
            plan.cancelled = True
            plan.bounded_queue.clear()
            ws, sid, prof, proc, remote, awaiting = (plan.active_ws, plan.active_session, plan.active,
                                                       plan.active_proc, plan.active_remote, plan.awaiting)
        if awaiting is not None:
            awaiting.decision = None
            awaiting.event.set()
        if ws is not None and sid:
            try:
                ws._send({"jsonrpc": "2.0", "id": "interrupt", "method": "session.interrupt",
                          "params": {"session_id": sid, "profile": split_participant(prof or "")[0]}})
            except Exception:
                pass
        if proc is not None:
            try:
                proc.kill()
            except Exception:
                pass
        if remote is not None:
            try:
                remote.interrupt()
            except Exception:
                pass
        self.events.emit({"type": "room.interrupted", "room_id": room_id})
        return True

    def pause(self, room_id: str) -> bool:
        """Soft stop: the current turn finishes, nothing else is scheduled."""
        with self.lock:
            plan = self.plans.get(room_id)
            if plan is None:
                return False
            plan.pause_requested = True
        return True

    def resume(self, room_id: str, extra_turns: int = CONTINUE_STEP) -> dict:
        """CONTINUE after a budget/stall/operator pause: widen the window and pick up the floor."""
        room = self.store.get(room_id)
        with self.lock:
            if room.id in self.plans:
                return self.public_room(room)
            room.budget_extra += max(0, int(extra_turns))
        self._start_plan(room)
        return self.public_room(room)

    def summarize(self, room_id: str, by: str | None = None) -> dict:
        room = self.store.get(room_id)
        speaker = (by or "").strip() or (room.policy.moderator or (room.participants[0] if room.participants else ""))
        if speaker not in room.participants:
            raise RoomError("unknown_participant", f"{speaker} is not in the room")
        with self.lock:
            if room.id in self.plans:
                raise RoomError("room_busy", "wait for the room to go quiet or pause it first", 409)
        self._start_plan(room, summarize_by=speaker)
        return self.public_room(room)

    def respond_approval(self, room_id: str, request_id: str, decision: str) -> bool:
        with self.lock:
            plan = self.plans.get(room_id)
            wait = plan.awaiting if plan else None
            if wait is None or (request_id and wait.request_id != request_id):
                return False
            wait.decision = str(decision or "").strip() or "deny"
            wait.event.set()
        return True

    # -- approvals (runner side)
    def _wait_approval(self, room: Room, plan: _Plan, ctx: TurnContext, request_id: str, kind: str,
                       command: str, choices: list[str]) -> str | None:
        wait = ApprovalWait(request_id=request_id, kind=kind, speaker=ctx.profile, turn_id=ctx.turn_id,
                            command=command, choices=choices,
                            deadline=time.monotonic() + room.policy.approval_timeout_s)
        with self.lock:
            plan.awaiting = wait
        self.events.emit({"type": "room.approval", "room_id": room.id, **wait.public()})
        self._wake(room, "room.approval", {"speaker": ctx.profile, "kind": kind})
        answered = wait.event.wait(timeout=room.policy.approval_timeout_s)
        with self.lock:
            plan.awaiting = None
        decision = wait.decision if answered and not plan.cancelled else None
        self.events.emit({"type": "room.approval.resolved", "room_id": room.id, "request_id": request_id,
                          "speaker": ctx.profile, "decision": decision or ("interrupted" if plan.cancelled else "timeout")})
        return decision

    # -- plan execution
    def _start_plan(self, room: Room, summarize_by: str | None = None) -> _Plan:
        with self.lock:
            plan = _Plan(room_id=room.id, started_at=time.monotonic(), summarize_by=summarize_by)
            if room.policy.mode == "bounded" and summarize_by is None:
                last = room.transcript[-1] if room.transcript else None
                text = last.text if last and last.speaker == OPERATOR else ""
                targets = [p for p in mentions(text, room.participants) if p != OPERATOR] or list(room.participants)
                plan.bounded_queue = [(p, 1) for p in targets]
            self.plans[room.id] = plan
        self.store.set_state(room, "running")
        self._emit_state(room, "running")
        threading.Thread(target=self._run_plan, args=(room, plan), name=f"room-{room.id}", daemon=True).start()
        return plan

    def _emit_state(self, room: Room, state: str, reason: str = "", **extra) -> None:
        self.events.emit({"type": "room.state", "room_id": room.id, "state": state, "reason": reason,
                          "turns_used": room.turns_used, "max_turns": room.policy.max_turns,
                          "budget": room.policy.max_turns + room.budget_extra, "seq": room.seq, **extra})

    def _wake(self, room: Room, kind: str, payload: dict) -> None:
        if self.wake is None or self.events.watching(room.id):
            return
        try:
            self.wake(room, kind, payload)
        except Exception:
            pass

    def _run_plan(self, room: Room, plan: _Plan) -> None:
        final_state, reason = "quiet", ""
        try:
            if plan.summarize_by:
                self._run_turn(room, plan, plan.summarize_by, 0)
                final_state, reason = (room.state if room.state in ("quiet", "paused") else "quiet"), room.pause_reason
                return
            while True:
                with self.lock:
                    if plan.cancelled:
                        final_state, reason = "idle", ""
                        return
                    if plan.pause_requested:
                        final_state, reason = "paused", "operator"
                        return
                    budget = room.policy.max_turns + room.budget_extra
                    if room.policy.mode != "bounded" and room.turns_used >= budget:
                        final_state, reason = "paused", "budget"
                        return
                    if room.policy.mode != "bounded" and (time.monotonic() - plan.started_at) > room.policy.max_minutes * 60:
                        final_state, reason = "paused", "time"
                        return
                    nxt = self._select_next(room, plan)
                    if nxt is None:
                        final_state, reason = ("idle" if room.policy.mode == "bounded" else "quiet"), ""
                        return
                    profile, round_no = nxt
                    plan.active = profile
                if profile not in room.participants:
                    continue
                reply = self._run_turn(room, plan, profile, round_no)
                if plan.cancelled:
                    final_state, reason = "idle", ""
                    return
                if reply is None:
                    continue
                if room.policy.mode == "bounded":
                    if round_no < room.policy.max_rounds and not reply.passed and not reply.error:
                        next_up = [p for p in mentions(reply.text, room.participants) if p not in (profile, OPERATOR)]
                        with self.lock:
                            queued = {p for p, _ in plan.bounded_queue}
                            for p in next_up:
                                if p not in queued:
                                    plan.bounded_queue.append((p, round_no + 1))
                    continue
                if self._stalled(room, reply):
                    final_state, reason = "paused", "stall"
                    return
                if room.policy.mode == "moderated" and profile == room.policy.moderator and is_end(reply.text):
                    final_state, reason = "quiet", ""
                    return
        finally:
            with self.lock:
                plan.active = None
                self.plans.pop(room.id, None)
            self.store.set_state(room, final_state, reason)
            self._emit_state(room, final_state, reason)
            self.events.emit({"type": "room.idle", "room_id": room.id, "seq": room.seq, "state": final_state, "reason": reason})
            if final_state == "paused":
                self._wake(room, "room.paused", {"reason": reason})
            elif final_state == "quiet":
                self._wake(room, "room.quiet", {})

    # -- floor selection
    def _select_next(self, room: Room, plan: _Plan) -> tuple[str, int] | None:
        if room.policy.mode == "bounded":
            while plan.bounded_queue:
                p, r = plan.bounded_queue.pop(0)
                if p in room.participants:
                    return p, r
            return None
        tr = room.transcript
        if not tr:
            return None
        # The current topic: the last line with real content (operator or agent, not PASS/error-only).
        topic = next((m for m in reversed(tr) if (m.text.strip() and not m.passed)), None)
        if topic is None:
            return None
        after = [m for m in tr if m.seq > topic.seq]
        spoken_since = {m.speaker for m in after} | {topic.speaker}
        passed_since = {m.speaker for m in after if m.passed}
        last = tr[-1]
        cooldown_blocked = {last.speaker} if room.policy.cooldown > 0 and last.speaker != OPERATOR else set()
        addressed = [p for p in mentions(topic.text, room.participants) if p != OPERATOR and p != topic.speaker]
        for p in addressed:
            if p in room.participants and p not in spoken_since:
                return p, room.turns_used + 1
        if room.policy.mode == "moderated" and room.policy.moderator in room.participants:
            mod = room.policy.moderator
            if topic.speaker != mod and mod not in spoken_since and not addressed:
                return mod, room.turns_used + 1
            if topic.speaker == mod and not addressed:
                # Moderator did not pick anyone: everyone else who has not answered yet may speak.
                pass
        def last_spoke(p: str) -> int:
            for m in reversed(tr):
                if m.speaker == p:
                    return m.seq
            return 0
        eligible = [p for p in room.participants
                    if p not in spoken_since and p not in passed_since and p not in cooldown_blocked]
        if topic.speaker == OPERATOR:
            # Everyone answers the operator; cooldown does not apply to a fresh operator line.
            eligible = [p for p in room.participants if p not in spoken_since and p not in passed_since]
        if not eligible:
            return None
        eligible.sort(key=last_spoke)
        return eligible[0], room.turns_used + 1

    def _stalled(self, room: Room, reply: RoomMsg) -> bool:
        text = reply.text.strip()
        # Short lines ("ok", "agreed 2") legitimately repeat; only long near-duplicates mean a loop.
        if reply.passed or reply.error or len(text) < STALL_MIN_CHARS:
            return False
        prior = [m for m in room.transcript if m.speaker == reply.speaker and m.seq < reply.seq and m.text.strip() and not m.passed][-2:]
        for m in prior:
            if difflib.SequenceMatcher(None, m.text.strip(), reply.text.strip()).ratio() >= STALL_SIMILARITY:
                return True
        return False

    # -- one turn
    def _run_turn(self, room: Room, plan: _Plan, profile: str, round_no: int) -> RoomMsg | None:
        turn_id = f"t-{secrets.token_hex(3)}"
        ctx = TurnContext(self, room, plan, profile, turn_id)
        self.events.emit({"type": "room.turn.start", "room_id": room.id, "speaker": profile,
                          "glyph": display_glyph(profile, room.participants), "turn_id": turn_id, "round": round_no,
                          "turns_used": room.turns_used, "max_turns": room.policy.max_turns})
        try:
            runner = self._runner(profile)
        except RoomError as exc:
            res = TurnResult(error=exc.code)
        else:
            first = profile not in room.backing
            res = runner.run(ctx, self.store, first, round_no, room.turns_used)
        text = res.text.strip()
        passed = is_pass(text) and not res.error
        kind = "summary" if plan.summarize_by == profile and text and not passed else ""
        msg = self.store.append(room, profile, text, round_no=round_no, turn_id=turn_id,
                                tools=res.tools, passed=passed, error=res.error, kind=kind)
        with self.lock:
            room.turns_used += 1
        self.events.emit({"type": "room.turn.end", "room_id": room.id, "speaker": profile,
                          "turn_id": turn_id, "seq": msg.seq, "passed": passed, "error": res.error,
                          "message": msg.public(room.participants), "turns_used": room.turns_used})
        if text and mentions_operator(text):
            self._wake(room, "room.mention", {"speaker": profile})
        return msg
