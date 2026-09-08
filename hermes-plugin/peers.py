"""Peer links between companion relays (P22 / A22.11).

A room lives on one host; a participant written ``profile@peer`` runs its turns on the peer's
relay. Two hosts trust each other through a *peer credential*:

  * host B mints a **grant** (``POST /companion/peers/grant``) → ``{host_id, secret}``;
  * host A stores it as a **peer** (``POST /companion/peers``) and presents
    ``Authorization: Peer <host_id>:<secret>`` when it asks B to run a turn.

The phone, paired with both, brokers that exchange with two taps; the CLI does the same with a
one-time code. Grants and peers live in ``~/.hermes/companion-peers.json`` (mode 600).

Remote turn: ``POST /companion/peers/turn`` streams NDJSON events (``session``, ``delta``,
``tool.start``, ``tool.complete``, ``approval``, ``complete``, ``error``) until the turn ends.
Approvals are answered with ``POST /companion/peers/turn/<turn_id>/approval`` and a turn is
cancelled with ``POST /companion/peers/turn/<turn_id>/interrupt``.
"""

from __future__ import annotations

import http.client
import json
import os
import secrets
import threading
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Callable, Iterator
from urllib.parse import urlparse

try:
    from .rooms import (
        ApprovalWait, Policy, Room, RoomError, RoomStore, TurnResult, UpstreamTurnRunner, ChatQTurnRunner,
        build_turn_prompt, split_participant,
    )
except ImportError:  # pragma: no cover - flat layout
    from rooms import (  # type: ignore
        ApprovalWait, Policy, Room, RoomError, RoomStore, TurnResult, UpstreamTurnRunner, ChatQTurnRunner,
        build_turn_prompt, split_participant,
    )


@dataclass
class Peer:
    name: str
    origin: str
    host_id: str
    secret: str
    added_at: float

    def public(self) -> dict:
        return {"name": self.name, "origin": self.origin, "host_id": self.host_id, "added_at": self.added_at}


@dataclass
class Grant:
    host_id: str
    secret: str
    name: str
    created_at: float

    def public(self) -> dict:
        return {"host_id": self.host_id, "name": self.name, "created_at": self.created_at}


def default_peers_path() -> Path:
    home = Path(os.environ.get("HERMES_HOME") or (Path.home() / ".hermes"))
    return home / "companion-peers.json"


class PeerStore:
    def __init__(self, path: Path | None = None, now: Callable[[], float] = time.time):
        self.path = Path(path) if path is not None else None
        self.now = now
        self.peers: dict[str, Peer] = {}
        self.grants: dict[str, Grant] = {}
        self.lock = threading.RLock()
        if self.path is not None:
            self.load()

    def load(self) -> None:
        if self.path is None or not self.path.exists():
            return
        try:
            raw = json.loads(self.path.read_text() or "{}")
        except (OSError, json.JSONDecodeError):
            return
        for row in raw.get("peers") or []:
            try:
                p = Peer(str(row["name"]), str(row["origin"]), str(row["host_id"]), str(row["secret"]), float(row.get("added_at") or 0))
            except (KeyError, TypeError, ValueError):
                continue
            self.peers[p.name] = p
        for row in raw.get("grants") or []:
            try:
                g = Grant(str(row["host_id"]), str(row["secret"]), str(row.get("name") or ""), float(row.get("created_at") or 0))
            except (KeyError, TypeError, ValueError):
                continue
            self.grants[g.host_id] = g

    def save(self) -> None:
        if self.path is None:
            return
        payload = {"peers": [asdict(p) for p in self.peers.values()], "grants": [asdict(g) for g in self.grants.values()]}
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(payload, indent=1))
        os.chmod(tmp, 0o600)
        tmp.replace(self.path)

    @staticmethod
    def clean_name(name: str) -> str:
        n = "".join(ch for ch in (name or "").strip() if ch.isalnum() or ch in "-_.")[:32]
        if not n:
            raise RoomError("peer_name_required", "peer name required")
        return n

    def grant(self, name: str) -> Grant:
        """Mint a credential another relay may use to ask *this* host for turns."""
        with self.lock:
            g = Grant(host_id="h-" + secrets.token_hex(4), secret=secrets.token_urlsafe(24),
                      name=self.clean_name(name), created_at=float(self.now()))
            self.grants[g.host_id] = g
            self.save()
            return g

    def add(self, name: str, origin: str, host_id: str, secret: str) -> Peer:
        origin = (origin or "").strip().rstrip("/")
        parsed = urlparse(origin)
        if parsed.scheme not in ("http", "https") or not parsed.hostname:
            raise RoomError("peer_origin_invalid", "origin must be http(s)://host:port")
        if not host_id or not secret:
            raise RoomError("peer_credential_required", "host_id and secret required")
        with self.lock:
            p = Peer(name=self.clean_name(name), origin=origin, host_id=str(host_id), secret=str(secret), added_at=float(self.now()))
            self.peers[p.name] = p
            self.save()
            return p

    def remove(self, name: str) -> None:
        with self.lock:
            if self.peers.pop(str(name or ""), None) is None:
                raise RoomError("peer_unknown", f"no peer {name}", 404)
            self.save()

    def revoke_grant(self, host_id: str) -> None:
        with self.lock:
            if self.grants.pop(str(host_id or ""), None) is None:
                raise RoomError("grant_unknown", f"no grant {host_id}", 404)
            self.save()

    def get(self, name: str) -> Peer:
        with self.lock:
            p = self.peers.get(str(name or ""))
        if p is None:
            raise RoomError("peer_unknown", f"no peer link for {name}", 409)
        return p

    def authorized(self, header: str) -> Grant | None:
        scheme, _, value = (header or "").partition(" ")
        if scheme.lower() != "peer" or ":" not in value:
            return None
        host_id, _, secret = value.strip().partition(":")
        with self.lock:
            g = self.grants.get(host_id)
        return g if g is not None and secret and g.secret == secret else None

    def public(self) -> dict:
        with self.lock:
            return {"peers": [p.public() for p in self.peers.values()], "grants": [g.public() for g in self.grants.values()]}

    def turn_runner(self, name: str) -> "PeerTurnRunner":
        return PeerTurnRunner(PeerClient(self.get(name)))


# -- room host side ------------------------------------------------------------------------------

class PeerClient:
    def __init__(self, peer: Peer, timeout: float = 15.0):
        self.peer = peer
        self.timeout = timeout

    def _conn(self, timeout: float | None = None) -> http.client.HTTPConnection:
        u = urlparse(self.peer.origin)
        cls = http.client.HTTPSConnection if u.scheme == "https" else http.client.HTTPConnection
        return cls(u.hostname, u.port or (443 if u.scheme == "https" else 80), timeout=timeout or self.timeout)

    def _headers(self) -> dict:
        return {"Authorization": f"Peer {self.peer.host_id}:{self.peer.secret}", "Content-Type": "application/json"}

    def post_json(self, path: str, body: dict, timeout: float | None = None) -> tuple[int, dict]:
        conn = self._conn(timeout)
        try:
            conn.request("POST", path, body=json.dumps(body), headers=self._headers())
            resp = conn.getresponse()
            raw = resp.read().decode(errors="replace")
            try:
                data = json.loads(raw) if raw else {}
            except json.JSONDecodeError:
                data = {"raw": raw}
            return resp.status, data
        finally:
            conn.close()

    def health(self) -> dict:
        conn = self._conn(5.0)
        try:
            conn.request("GET", "/companion/health")
            resp = conn.getresponse()
            return json.loads(resp.read().decode(errors="replace") or "{}")
        finally:
            conn.close()

    def stream_turn(self, body: dict, timeout: float) -> Iterator[dict]:
        """Yield NDJSON events from ``POST /companion/peers/turn`` until the peer closes the stream."""
        conn = self._conn(timeout)
        self._active_conn = conn
        try:
            conn.request("POST", "/companion/peers/turn", body=json.dumps(body), headers=self._headers())
            resp = conn.getresponse()
            if resp.status != 200:
                raw = resp.read().decode(errors="replace")
                try:
                    err = json.loads(raw).get("error") or f"http_{resp.status}"
                except (json.JSONDecodeError, AttributeError):
                    err = f"http_{resp.status}"
                raise RoomError(f"peer_{err}" if not str(err).startswith("peer_") else str(err),
                                f"peer refused turn ({resp.status})", 502)
            while True:
                line = resp.readline()
                if not line:
                    return
                line = line.strip()
                if not line:
                    continue
                try:
                    yield json.loads(line.decode(errors="replace"))
                except json.JSONDecodeError:
                    continue
        finally:
            conn.close()


class _RemoteHandle:
    def __init__(self, client: PeerClient, turn_id: str):
        self.client = client
        self.turn_id = turn_id

    def interrupt(self) -> None:
        self.client.post_json(f"/companion/peers/turn/{self.turn_id}/interrupt", {}, timeout=5.0)

    def approve(self, request_id: str, decision: str) -> None:
        self.client.post_json(f"/companion/peers/turn/{self.turn_id}/approval",
                              {"request_id": request_id, "decision": decision}, timeout=5.0)


class PeerTurnRunner:
    """Runs one room turn on a peer relay and re-emits its events as local ``room.*`` events."""

    def __init__(self, client: PeerClient):
        self.client = client

    def run(self, ctx, store: RoomStore, first_turn_hint: bool, round_no: int, turns_used: int) -> TurnResult:
        room, participant, plan = ctx.room, ctx.profile, ctx.plan
        profile, _ = split_participant(participant)
        res = TurnResult()
        prompt = build_turn_prompt(room, participant, first_turn=first_turn_hint or participant not in room.backing,
                                   round_no=round_no, turns_used=turns_used)
        if plan.summarize_by == participant:
            try:
                from .rooms import SUMMARY_REQUEST
            except ImportError:  # pragma: no cover
                from rooms import SUMMARY_REQUEST  # type: ignore
            prompt += "\n" + SUMMARY_REQUEST
        body = {
            "turn_id": ctx.turn_id,
            "profile": profile,
            "session_id": room.backing.get(participant) or "",
            "title": f"room:{room.id} {room.title}"[:80],
            "text": prompt,
            "timeout_s": room.policy.turn_timeout_s,
            "approval_timeout_s": room.policy.approval_timeout_s,
        }
        handle = _RemoteHandle(self.client, ctx.turn_id)
        with ctx.ctl.lock:
            plan.active_remote = handle
        text_parts: list[str] = []
        try:
            for ev in self.client.stream_turn(body, timeout=room.policy.turn_timeout_s + room.policy.approval_timeout_s + 30):
                if plan.cancelled:
                    res.error = "interrupted"
                    break
                et = str(ev.get("type") or "")
                if et == "session":
                    sid = str(ev.get("session_id") or "")
                    if sid:
                        store.bind_backing(room, participant, sid)
                elif et == "delta":
                    piece = str(ev.get("text") or "")
                    if piece:
                        text_parts.append(piece)
                        ctx.emit("room.delta", text=piece)
                elif et == "tool.start":
                    tool = {"name": str(ev.get("name") or "tool"), "detail": str(ev.get("detail") or "")}
                    res.tools.append(tool)
                    ctx.emit("room.tool.start", **tool)
                elif et == "tool.complete":
                    ctx.emit("room.tool.complete", name=str(ev.get("name") or "tool"), detail=str(ev.get("detail") or ""),
                             duration_ms=int(ev.get("duration_ms") or 0))
                elif et == "approval":
                    rid = str(ev.get("request_id") or "")
                    decision = ctx.wait_approval(rid, str(ev.get("kind") or "approval"), str(ev.get("command") or ""),
                                                 [str(c) for c in (ev.get("choices") or [])])
                    if decision is None:
                        res.error = "approval_timeout"
                        try:
                            handle.interrupt()
                        except Exception:
                            pass
                        break
                    handle.approve(rid, decision)
                elif et == "complete":
                    if not text_parts and ev.get("text"):
                        text_parts.append(str(ev.get("text")))
                    if ev.get("error"):
                        res.error = str(ev.get("error"))
                    break
                elif et == "error":
                    res.error = str(ev.get("error") or "peer_turn_failed")
                    break
        except RoomError as exc:
            res.error = exc.code
        except (OSError, http.client.HTTPException) as exc:
            res.error = "peer_unreachable"
        except Exception as exc:  # pragma: no cover - defensive
            res.error = f"turn_failed:{exc.__class__.__name__}"
        finally:
            with ctx.ctl.lock:
                plan.active_remote = None
        res.text = "".join(text_parts).strip()
        return res


# -- serving side --------------------------------------------------------------------------------

class _ServiceCtl:
    """Minimal stand-in for RoomController that a runner needs: lock, events, approval waits."""

    def __init__(self, write: Callable[[dict], None], approval_timeout_s: float):
        self.lock = threading.RLock()
        self._write = write
        self.approval_timeout_s = approval_timeout_s
        self.awaiting: ApprovalWait | None = None

        class _Events:
            def __init__(inner, outer):
                inner.outer = outer

            def emit(inner, event: dict) -> None:
                t = str(event.get("type") or "")
                out = {k: v for k, v in event.items() if k not in ("room_id", "speaker")}
                if t == "room.delta":
                    out["type"] = "delta"
                elif t == "room.tool.start":
                    out["type"] = "tool.start"
                elif t == "room.tool.complete":
                    out["type"] = "tool.complete"
                else:
                    return
                inner.outer._write(out)

        self.events = _Events(self)

    def _wait_approval(self, room, plan, ctx, request_id, kind, command, choices):
        wait = ApprovalWait(request_id=request_id, kind=kind, speaker=ctx.profile, turn_id=ctx.turn_id,
                            command=command, choices=choices, deadline=time.monotonic() + self.approval_timeout_s)
        with self.lock:
            self.awaiting = wait
        self._write({"type": "approval", **wait.public()})
        answered = wait.event.wait(timeout=self.approval_timeout_s)
        with self.lock:
            self.awaiting = None
        return wait.decision if answered and not plan.cancelled else None


@dataclass
class _ServiceTurn:
    ctl: _ServiceCtl
    plan: object
    done: threading.Event = field(default_factory=threading.Event)


class RemoteTurnService:
    """Serving side of ``/companion/peers/turn``: run one turn for a local profile and stream it."""

    def __init__(self, upstream: tuple[str, int], ws_factory, token_provider, available: Callable[[], bool],
                 chatq: ChatQTurnRunner | None = None):
        self.upstream = upstream
        self.ws_factory = ws_factory
        self.token_provider = token_provider
        self.available = available
        self.chatq = chatq or ChatQTurnRunner()
        self.lock = threading.Lock()
        self.turns: dict[str, _ServiceTurn] = {}

    def mode(self) -> str:
        if self.available():
            return "ok"
        return "ok_text_only" if self.chatq.available() else "unavailable"

    def approve(self, turn_id: str, request_id: str, decision: str) -> bool:
        with self.lock:
            t = self.turns.get(turn_id)
        if t is None or t.ctl.awaiting is None:
            return False
        if request_id and t.ctl.awaiting.request_id != request_id:
            return False
        t.ctl.awaiting.decision = decision or "deny"
        t.ctl.awaiting.event.set()
        return True

    def interrupt(self, turn_id: str) -> bool:
        with self.lock:
            t = self.turns.get(turn_id)
        if t is None:
            return False
        t.plan.cancelled = True
        if t.ctl.awaiting is not None:
            t.ctl.awaiting.event.set()
        ws, sid, proc = getattr(t.plan, "active_ws", None), getattr(t.plan, "active_session", None), getattr(t.plan, "active_proc", None)
        if ws is not None and sid:
            try:
                ws._send({"jsonrpc": "2.0", "id": "interrupt", "method": "session.interrupt",
                          "params": {"session_id": sid, "profile": getattr(t.plan, "active", "")}})
            except Exception:
                pass
        if proc is not None:
            try:
                proc.kill()
            except Exception:
                pass
        return True

    def run(self, body: dict, write: Callable[[dict], None]) -> None:
        """Blocking: runs the turn and writes NDJSON events through ``write`` until it ends."""
        try:
            from .rooms import _Plan, TurnContext  # local import: avoid cycles at module load
        except ImportError:  # pragma: no cover - flat layout
            from rooms import _Plan, TurnContext  # type: ignore
        profile = str(body.get("profile") or "").strip()
        turn_id = str(body.get("turn_id") or ("t-" + secrets.token_hex(3)))
        prompt = str(body.get("text") or "")
        if not profile or not prompt:
            write({"type": "error", "error": "profile_and_text_required"})
            return
        mode = self.mode()
        if mode == "unavailable":
            write({"type": "error", "error": "rooms_unavailable"})
            return
        try:
            timeout_s = float(body.get("timeout_s") or 180.0)
            approval_timeout_s = float(body.get("approval_timeout_s") or 600.0)
        except (TypeError, ValueError):
            timeout_s, approval_timeout_s = 180.0, 600.0
        ctl = _ServiceCtl(write, approval_timeout_s)
        store = RoomStore()  # in-memory; only used for bind_backing on this turn
        room = Room(id="peer", title=str(body.get("title") or "room")[:80].removeprefix("room:").strip(),
                    participants=[profile], created_at=time.time(), updated_at=time.time(),
                    policy=Policy(turn_timeout_s=max(10.0, min(timeout_s, 900.0)),
                                  approval_timeout_s=max(30.0, min(approval_timeout_s, 3600.0))))
        sid = str(body.get("session_id") or "")
        if sid:
            room.backing[profile] = sid
        store.rooms[room.id] = room
        plan = _Plan(room_id=room.id, started_at=time.monotonic())
        plan.active = profile
        ctx = TurnContext(ctl, room, plan, profile, turn_id)  # type: ignore[arg-type]
        ctx.prompt_override = prompt
        with self.lock:
            self.turns[turn_id] = _ServiceTurn(ctl=ctl, plan=plan)
        try:
            runner = (UpstreamTurnRunner(self.upstream, self.ws_factory, self.token_provider)
                      if mode == "ok" else self.chatq)
            res = runner.run(ctx, store, not sid, 0, 0)
            new_sid = room.backing.get(profile)
            if new_sid and new_sid != sid:
                write({"type": "session", "session_id": new_sid})
            write({"type": "complete", "text": res.text, "tools": res.tools, "error": res.error})
        except Exception as exc:  # pragma: no cover - defensive
            write({"type": "error", "error": f"turn_failed:{exc.__class__.__name__}"})
        finally:
            with self.lock:
                self.turns.pop(turn_id, None)
