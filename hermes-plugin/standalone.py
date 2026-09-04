"""Dashboard-independent operator protocol. Local store; Hermes internals optional."""

from __future__ import annotations

import json
import os
import secrets
import threading
import time
from pathlib import Path
from urllib.parse import parse_qs

from drift import profiles_dir, self_test

TINY_PNG = (
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)


def default_state_path() -> Path:
    override = os.environ.get("HERMES_COMPANION_STATE")
    if override:
        return Path(override)
    return Path.home() / ".hermes" / "companion-operator.json"


def _now_ms() -> int:
    return int(time.time() * 1000)


def _page(rows: list, limit, before):
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


class OperatorError(Exception):
    def __init__(self, error: str, status: int = 400):
        super().__init__(error)
        self.error = error
        self.status = status


class Operator:
    def __init__(self, path: Path | None = None):
        self.path = Path(path) if path else default_state_path()
        self.lock = threading.Lock()
        self.tokens: dict[str, float] = {}
        self.tickets: dict[str, float] = {}
        self.seq = 1000
        self.data = self._load()

    def _load(self) -> dict:
        if self.path.is_file():
            try:
                raw = json.loads(self.path.read_text(encoding="utf-8"))
                if isinstance(raw, dict) and raw.get("profiles"):
                    return raw
            except (OSError, json.JSONDecodeError):
                pass
        return self._seed()

    def _seed(self) -> dict:
        sid = "sess-local-1"
        return {
            "profiles": [
                {
                    "id": "default",
                    "display_name": "default",
                    "model": os.environ.get("HERMES_MODEL") or "default",
                    "gateway": "running",
                    "session_count": 1,
                },
                {
                    "id": "coder",
                    "display_name": "coder",
                    "model": "sonnet-4.6",
                    "gateway": "running",
                    "session_count": 0,
                },
            ],
            "sessions": [
                {
                    "id": sid,
                    "profile": "default",
                    "title": "companion standalone",
                    "updated_at": _now_ms(),
                    "unread": True,
                }
            ],
            "messages": {
                sid: [
                    {
                        "id": "11",
                        "row_id": 11,
                        "role": "user",
                        "content": "render markdown, images, and files without the dashboard",
                    },
                    {
                        "id": "12",
                        "row_id": 12,
                        "role": "assistant",
                        "content": (
                            "standalone lane is up.\n\n"
                            "| lane | status |\n| --- | --- |\n| operator | live |\n| dashboard | optional |\n\n"
                            f"![signal](data:image/png;base64,{TINY_PNG})"
                        ),
                    },
                    {
                        "id": "13",
                        "row_id": 13,
                        "role": "tool",
                        "name": "device.screenshot",
                        "detail": "png",
                        "content": [
                            {
                                "type": "image",
                                "mime": "image/png",
                                "data": TINY_PNG,
                            }
                        ],
                    },
                ]
            },
            "pending": {},
            "model": os.environ.get("HERMES_MODEL") or "default",
            "provider": os.environ.get("HERMES_PROVIDER") or "local",
        }

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.data), encoding="utf-8")
        tmp.replace(self.path)

    def _row(self) -> int:
        self.seq += 1
        return self.seq

    def mint_token(self) -> str:
        token = "op-" + secrets.token_hex(16)
        self.tokens[token] = time.time() + 12 * 3600
        return token

    def check_token(self, headers: dict, query: dict) -> bool:
        user = os.environ.get("HERMES_DASHBOARD_BASIC_AUTH_USER") or ""
        if not user:
            return True
        auth = (headers.get("Authorization") or "").strip()
        cookie = headers.get("Cookie") or ""
        token = (query.get("token") or [""])[0]
        xh = headers.get("X-Hermes-Session-Token") or ""
        if xh and xh in self.tokens:
            return True
        if token and token in self.tokens:
            return True
        if "hermes_session=" in cookie:
            return True
        if auth.lower().startswith("basic "):
            return True
        return False

    def auth_required(self) -> bool:
        return bool(os.environ.get("HERMES_DASHBOARD_BASIC_AUTH_USER"))

    def health(self) -> dict:
        drift = self_test()
        return {
            "relay": "ok",
            "mode": "standalone",
            "upstream": "unused",
            "hermes_version": drift.get("hermes_version") or "",
            "profiles_dir": drift.get("profiles_dir") or profiles_dir(),
            "drift": drift.get("fallback") or "ok",
            "warnings": drift.get("warnings") or [],
        }

    def status(self) -> dict:
        return {
            "status": "ok",
            "auth_required": self.auth_required(),
            "auth_providers": ["basic"] if self.auth_required() else [],
            "version": "companion-standalone",
            "gateway": {"running": True, "status": "running"},
            "gateway_running": True,
            "gateway_state": "running",
            "gateway_platforms": {},
            "memory": {"pressure": "ok"},
            "disk": {"pressure": "ok"},
        }

    def index_html(self) -> str:
        token = self.mint_token()
        return (
            "<html><body>hermes companion standalone"
            f'<script>window.__HERMES_SESSION_TOKEN__ = "{token}"</script></body></html>'
        )

    def login(self, body: dict) -> dict:
        expect_user = os.environ.get("HERMES_DASHBOARD_BASIC_AUTH_USER") or ""
        expect_pass = os.environ.get("HERMES_DASHBOARD_BASIC_AUTH_PASSWORD") or ""
        user = str(body.get("username") or "")
        password = str(body.get("password") or "")
        if expect_user and (user != expect_user or password != expect_pass):
            raise OperatorError("invalid_credentials", 401)
        if not user or not password:
            raise OperatorError("invalid_credentials", 401)
        return {"ok": True, "token": self.mint_token()}

    def ws_ticket(self) -> dict:
        ticket = "t-" + secrets.token_hex(8)
        self.tickets[ticket] = time.time() + 30
        return {"ticket": ticket}

    def consume_ticket(self, ticket: str) -> bool:
        exp = self.tickets.pop(ticket or "", None)
        return bool(exp and exp >= time.time())

    def profiles(self) -> list:
        with self.lock:
            rows = list(self.data["profiles"])
            counts: dict[str, int] = {}
            for sess in self.data["sessions"]:
                counts[sess["profile"]] = counts.get(sess["profile"], 0) + 1
            for row in rows:
                row["session_count"] = counts.get(row["id"], 0)
            return rows

    def _session(self, sid: str) -> dict | None:
        for row in self.data["sessions"]:
            if row["id"] == sid:
                return row
        return None

    def list_sessions(self, profile: str) -> list:
        if not profile:
            raise OperatorError("profile_required", 400)
        with self.lock:
            return [s for s in self.data["sessions"] if s["profile"] == profile]

    def create_session(self, profile: str, title: str = "", model: str = "") -> dict:
        if not profile:
            raise OperatorError("profile_required", 400)
        sid = f"sess-{profile}-{_now_ms()}"
        row = {
            "id": sid,
            "profile": profile,
            "title": title or "new thread",
            "updated_at": _now_ms(),
            "unread": False,
        }
        if model:
            row["model"] = model
        with self.lock:
            self.data["sessions"].insert(0, row)
            self.data["messages"][sid] = []
            self._save()
        return row

    def delete_session(self, sid: str, profile: str) -> None:
        with self.lock:
            sess = self._session(sid)
            if sess is None:
                raise OperatorError("unknown_session", 404)
            if not profile:
                raise OperatorError("profile_required", 400)
            if sess["profile"] != profile:
                raise OperatorError("profile_mismatch", 403)
            self.data["sessions"] = [s for s in self.data["sessions"] if s["id"] != sid]
            self.data["messages"].pop(sid, None)
            self.data["pending"].pop(sid, None)
            self._save()

    def messages(self, sid: str, profile: str, limit=None, before=None) -> list:
        with self.lock:
            sess = self._session(sid)
            if sess is None:
                raise OperatorError("unknown_session", 404)
            if not profile or sess["profile"] != profile:
                raise OperatorError("profile_mismatch", 403)
            rows = list(self.data["messages"].get(sid, []))
        return _page(rows, limit, before)

    def get_approval(self, sid: str, profile: str) -> dict:
        with self.lock:
            sess = self._session(sid)
            if sess is None:
                raise OperatorError("unknown_session", 404)
            if not profile or sess["profile"] != profile:
                raise OperatorError("profile_mismatch", 403)
            prompt = self.data["pending"].get(sid)
        if not prompt:
            raise OperatorError("not_found", 404)
        return prompt

    def post_approval(self, sid: str, profile: str, body: dict) -> dict:
        with self.lock:
            sess = self._session(sid)
            if sess is None:
                raise OperatorError("unknown_session", 404)
            if not profile or sess["profile"] != profile:
                raise OperatorError("profile_mismatch", 403)
            prompt = self.data["pending"].get(sid)
            if not prompt or prompt.get("request_id") != body.get("request_id"):
                raise OperatorError("unknown_request", 404)
            self.data["pending"].pop(sid, None)
            decision = str(body.get("decision") or "deny")
            note = f"{'allowed' if decision != 'deny' else 'denied'} {prompt.get('command')}"
            rid = self._row()
            self.data["messages"].setdefault(sid, []).append(
                {"id": str(rid), "row_id": rid, "role": "assistant", "content": note}
            )
            self._save()
        return {"ok": True, "decision": decision}

    def submit(self, sid: str, profile: str, text: str, model: str = "", parts: list | None = None) -> dict:
        if not text.strip() and not parts:
            raise OperatorError("text_required", 400)
        with self.lock:
            sess = self._session(sid)
            if sess is None:
                raise OperatorError("unknown_session", 404)
            if not profile or sess["profile"] != profile:
                raise OperatorError("profile_mismatch", 403)
            uid = self._row()
            user: dict = {"id": str(uid), "row_id": uid, "role": "user", "content": text}
            if parts:
                user["content"] = parts if not text.strip() else [{"type": "text", "text": text}, *parts]
            reply = self._reply(text, parts or [])
            aid = self._row()
            assistant = {"id": str(aid), "row_id": aid, "role": "assistant", "content": reply}
            self.data["messages"].setdefault(sid, []).extend([user, assistant])
            sess["unread"] = False
            sess["updated_at"] = _now_ms()
            if sess["title"] in ("new thread", sess["id"]):
                sess["title"] = (text or "attachment")[:40]
            if model:
                sess["model"] = model
            self._save()
        return {"user": user, "assistant": assistant, "reply": reply}

    def _reply(self, text: str, parts: list) -> str:
        n_img = sum(1 for p in parts if str(p.get("type") or "").startswith("image"))
        n_vid = sum(1 for p in parts if str(p.get("type") or "").startswith("video"))
        n_file = sum(1 for p in parts if str(p.get("type") or "") in ("file", "document"))
        bits = []
        if n_img:
            bits.append(f"{n_img} image")
        if n_vid:
            bits.append(f"{n_vid} video")
        if n_file:
            bits.append(f"{n_file} file")
        media = (", ".join(bits) + " received. ") if bits else ""
        body = text.strip() or "attachment"
        return (
            f"{media}standalone · noted:\n\n{body}\n\n"
            "| kind | count |\n| --- | --- |\n"
            f"| images | {n_img} |\n| video | {n_vid} |\n| files | {n_file} |"
        )

    def cron_jobs(self) -> list:
        return list(self.data.get("cron") or [])

    def model_options(self) -> dict:
        current = self.data.get("model") or "default"
        provider = self.data.get("provider") or "local"
        return {
            "model": current,
            "provider": provider,
            "providers": [
                {
                    "slug": provider,
                    "name": provider,
                    "models": [current],
                    "capabilities": {current: {"fast": True}},
                }
            ],
        }

    def set_model(self, model: str, provider: str) -> dict:
        with self.lock:
            if model:
                self.data["model"] = model
            if provider:
                self.data["provider"] = provider
            self._save()
        return {"ok": True, "model": self.data.get("model"), "provider": self.data.get("provider")}

    def update_check(self) -> dict:
        return {
            "current_version": "companion-standalone",
            "update_available": False,
            "can_apply": False,
            "behind": 0,
            "update_command": "hermes update",
            "commits": [],
        }

    def apply_update(self) -> dict:
        return {"ok": False, "error": "standalone_no_update"}

    def rpc(self, method: str, params: dict, profile: str | None):
        scoped = (params.get("profile") or profile or "").strip()
        if method == "gateway.ping":
            return {"ok": True}, []
        if method == "session.list":
            rows = self.list_sessions(scoped) if scoped else []
            return {"sessions": rows}, []
        if method == "session.create":
            row = self.create_session(scoped, str(params.get("title") or ""), str(params.get("model") or ""))
            ev = {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "type": "sessions.changed",
                    "session_id": row["id"],
                    "payload": {
                        "id": row["id"],
                        "profile": scoped,
                        "op": "upsert",
                        "title": row["title"],
                        "updated_at": row["updated_at"],
                    },
                },
            }
            return {"session_id": row["id"], "id": row["id"], "title": row["title"], "profile": scoped}, [ev]
        if method == "session.resume":
            sid = str(params.get("session_id") or params.get("id") or "")
            rows = self.messages(sid, scoped)
            return {"session_id": sid, "stored_session_id": sid, "messages": rows}, []
        if method == "session.history":
            sid = str(params.get("session_id") or "")
            rows = self.messages(sid, scoped, params.get("limit"), params.get("before"))
            return {"messages": rows}, []
        if method == "session.interrupt":
            return {"status": "interrupted"}, []
        if method in ("approval.respond", "clarify.respond", "sudo.respond", "secret.respond"):
            sid = str(params.get("session_id") or "")
            return self.post_approval(sid, scoped, params), []
        if method == "prompt.submit":
            sid = str(params.get("session_id") or "")
            text = str(params.get("text") or params.get("input") or "")
            parts = params.get("parts") if isinstance(params.get("parts"), list) else None
            submitted = self.submit(sid, scoped, text, str(params.get("model") or ""), parts)
            events = [
                {
                    "jsonrpc": "2.0",
                    "method": "event",
                    "params": {
                        "type": "message.delta",
                        "session_id": sid,
                        "payload": {"text": submitted["reply"]},
                    },
                },
                {
                    "jsonrpc": "2.0",
                    "method": "event",
                    "params": {
                        "type": "message.complete",
                        "session_id": sid,
                        "payload": {"ok": True},
                    },
                },
            ]
            return {"ok": True}, events
        raise OperatorError(f"unknown method {method}", 404)

    def handle_rest(self, method: str, path: str, query: dict, headers: dict, body: dict):
        profile = (query.get("profile") or [None])[0]
        if path in ("/", "/index.html") and method == "GET":
            html = self.index_html().encode()
            return 200, "text/html; charset=utf-8", html
        if path == "/api/status" and method == "GET":
            return 200, "application/json", json.dumps(self.status()).encode()
        if path == "/api/auth/status" and method == "GET":
            return 200, "application/json", json.dumps({"ok": True}).encode()
        if path == "/auth/password-login" and method == "POST":
            result = self.login(body)
            return 200, "application/json", json.dumps(result).encode()
        if path == "/api/auth/ws-ticket" and method == "POST":
            return 200, "application/json", json.dumps(self.ws_ticket()).encode()
        if path == "/api/profiles" and method == "GET":
            return 200, "application/json", json.dumps({"profiles": self.profiles()}).encode()
        if path == "/api/sessions" and method == "GET":
            return 200, "application/json", json.dumps({"sessions": self.list_sessions(profile or "")}).encode()
        if path == "/api/sessions" and method == "POST":
            row = self.create_session(profile or "", str(body.get("title") or ""), str(body.get("model") or ""))
            return 201, "application/json", json.dumps({"session": row}).encode()
        if path.startswith("/api/sessions/") and path.endswith("/messages") and method == "GET":
            sid = path.split("/")[3]
            rows = self.messages(sid, profile or "", (query.get("limit") or [None])[0], (query.get("before") or [None])[0])
            return 200, "application/json", json.dumps({"messages": rows}).encode()
        if path.startswith("/api/sessions/") and path.endswith("/approval") and method == "GET":
            sid = path.split("/")[3]
            return 200, "application/json", json.dumps({"approval": self.get_approval(sid, profile or "")}).encode()
        if path.startswith("/api/sessions/") and path.endswith("/approval") and method == "POST":
            sid = path.split("/")[3]
            return 200, "application/json", json.dumps(self.post_approval(sid, profile or "", body)).encode()
        if path.startswith("/api/sessions/") and method == "DELETE":
            parts = [p for p in path.split("/") if p]
            if len(parts) == 3:
                self.delete_session(parts[2], profile or "")
                return 200, "application/json", json.dumps({"ok": True}).encode()
        if path == "/api/cron/jobs" and method == "GET":
            return 200, "application/json", json.dumps(self.cron_jobs()).encode()
        if path.startswith("/api/cron/jobs/") and method == "POST":
            return 200, "application/json", json.dumps({"ok": True}).encode()
        if path == "/api/model/options" and method == "GET":
            return 200, "application/json", json.dumps(self.model_options()).encode()
        if path in ("/api/model/set",) and method == "POST":
            return 200, "application/json", json.dumps(
                self.set_model(str(body.get("model") or ""), str(body.get("provider") or ""))
            ).encode()
        if path.startswith("/api/profiles/") and path.endswith("/model") and method == "POST":
            return 200, "application/json", json.dumps(
                self.set_model(str(body.get("model") or ""), str(body.get("provider") or ""))
            ).encode()
        if path == "/api/hermes/update/check" and method == "GET":
            return 200, "application/json", json.dumps(self.update_check()).encode()
        if path == "/api/hermes/update" and method == "POST":
            return 200, "application/json", json.dumps(self.apply_update()).encode()
        return None


def parse_query(path: str) -> dict:
    from urllib.parse import urlparse

    return parse_qs(urlparse(path).query)
