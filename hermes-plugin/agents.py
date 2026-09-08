"""Coding-agent sessions on the host (P23 / A23.1).

Run Claude Code, Codex, Cline (or any CLI) **inside tmux** on the Hermes host and drive it from the
phone. tmux gives us three things for free: the session survives the phone dropping off, a laptop can
``tmux attach -t hc-<id>`` to the very same session, and ``capture-pane`` renders the screen for us so
the phone needs no terminal emulator — it shows a snapshot of the pane and sends keys back.

Discovery is **dynamic and per host**: a registry of known tools is probed with ``which`` + ``--version``
on every request (cached 60 s), so a CLI installed on the host shows up on the phone on the next probe
with no app or plugin change. Nothing is assumed installed.

Trust boundary = the existing terminal: paired device (``/companion/*`` gate) plus a cwd allow-list,
a cap on live sessions and an audit line per spawn / kill / key batch.
"""

from __future__ import annotations

import json
import os
import queue
import re
import secrets
import shlex
import shutil
import subprocess
import threading
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Callable

TOOL_REGISTRY: list[dict] = [
    {"id": "claude", "label": "Claude Code", "glyph": "CC", "binaries": ["claude"], "version_flag": "--version",
     "modes": ["pty", "structured"], "install_hint": "npm i -g @anthropic-ai/claude-code",
     "login_hint": "run `claude` once on the host and finish the login there"},
    {"id": "codex", "label": "Codex", "glyph": "CX", "binaries": ["codex"], "version_flag": "--version",
     "modes": ["pty", "structured"], "install_hint": "npm i -g @openai/codex", "login_hint": "run `codex login` on the host"},
    {"id": "cline", "label": "Cline", "glyph": "CL", "binaries": ["cline"], "version_flag": "--version",
     "modes": ["pty"], "install_hint": "npm i -g cline", "login_hint": "run `cline auth` on the host"},
    {"id": "agy", "label": "Antigravity", "glyph": "AG", "binaries": ["agy", "antigravity"], "version_flag": "--version",
     "modes": ["pty"], "install_hint": "install the Antigravity CLI (agy) on the host", "login_hint": "run `agy` once on the host and finish the login there"},
    {"id": "aider", "label": "Aider", "glyph": "AI", "binaries": ["aider"], "version_flag": "--version",
     "modes": ["pty"], "install_hint": "pipx install aider-chat", "login_hint": "export the provider key in the host shell"},
    {"id": "opencode", "label": "OpenCode", "glyph": "OC", "binaries": ["opencode"], "version_flag": "--version",
     "modes": ["pty"], "install_hint": "npm i -g opencode-ai", "login_hint": "run `opencode auth login` on the host"},
    {"id": "shell", "label": "Shell", "glyph": "SH", "binaries": [], "version_flag": "", "modes": ["pty"],
     "install_hint": "", "login_hint": "", "always": True},
]

EXTRA_PATH_DIRS = ("~/.npm-global/bin", "~/.local/bin", "~/.cargo/bin", "~/.bun/bin", "/opt/homebrew/bin", "/usr/local/bin")
DEFAULT_COLS, DEFAULT_ROWS = 100, 40
MAX_LIVE_SESSIONS = int(os.environ.get("HERMES_COMPANION_AGENT_MAX", "4"))
CACHE_TTL_S = 60.0
STATUSES = ("starting", "running", "waiting_input", "waiting_approval", "exited")
SPECIAL_KEYS = {
    "enter": "Enter", "return": "Enter", "esc": "Escape", "escape": "Escape", "tab": "Tab", "btab": "BTab",
    "up": "Up", "down": "Down", "left": "Left", "right": "Right", "home": "Home", "end": "End",
    "pgup": "PPage", "pgdn": "NPage", "pageup": "PPage", "pagedown": "NPage",
    "backspace": "BSpace", "bspace": "BSpace", "delete": "DC", "del": "DC", "space": "Space",
    "c-c": "C-c", "ctrl-c": "C-c", "c-d": "C-d", "ctrl-d": "C-d", "c-z": "C-z", "c-l": "C-l", "c-u": "C-u",
    "c-r": "C-r", "c-a": "C-a", "c-e": "C-e", "c-k": "C-k", "c-w": "C-w", "f1": "F1", "f2": "F2",
}


class AgentError(Exception):
    def __init__(self, code: str, message: str = "", status: int = 400):
        super().__init__(message or code)
        self.code = code
        self.message = message or code
        self.status = status


# -- discovery -----------------------------------------------------------------------------------

def search_path() -> str:
    parts = [p for p in (os.environ.get("PATH") or "").split(os.pathsep) if p]
    extra = [p for p in (os.environ.get("HERMES_COMPANION_AGENT_PATHS") or "").split(os.pathsep) if p]
    for d in extra + [os.path.expanduser(d) for d in EXTRA_PATH_DIRS]:
        if d and d not in parts:
            parts.append(d)
    return os.pathsep.join(parts)


def _version(path: str, flag: str, timeout: float = 4.0) -> str:
    if not flag:
        return ""
    try:
        out = subprocess.run([path, flag], capture_output=True, text=True, timeout=timeout)
        text = (out.stdout or out.stderr or "").strip().splitlines()
        return text[0][:60] if text else ""
    except Exception:
        return ""


class ToolDiscovery:
    """Probe the registry against this host. Injected ``which``/``version`` make it unit-testable."""

    def __init__(self, which: Callable[[str], str | None] | None = None,
                 version: Callable[[str, str], str] | None = None, ttl: float = CACHE_TTL_S,
                 now: Callable[[], float] = time.monotonic):
        self.which = which or (lambda b: shutil.which(b, path=search_path()))
        self.version = version or _version
        self.ttl = ttl
        self.now = now
        self._cache: list[dict] | None = None
        self._at = 0.0
        self.lock = threading.Lock()

    def tmux(self) -> str | None:
        return self.which("tmux")

    def probe(self, refresh: bool = False) -> list[dict]:
        with self.lock:
            if not refresh and self._cache is not None and self.now() - self._at < self.ttl:
                return list(self._cache)
            out: list[dict] = []
            for spec in TOOL_REGISTRY:
                path = None
                for b in spec["binaries"]:
                    path = self.which(b)
                    if path:
                        break
                installed = bool(path) or bool(spec.get("always"))
                out.append({
                    "id": spec["id"], "label": spec["label"], "glyph": spec["glyph"],
                    "installed": installed, "path": path or "",
                    "version": self.version(path, spec["version_flag"]) if path else "",
                    "modes": list(spec["modes"]), "install_hint": spec["install_hint"], "login_hint": spec["login_hint"],
                })
            self._cache, self._at = out, self.now()
            return list(out)

    def get(self, tool_id: str, refresh: bool = True) -> dict:
        for t in self.probe(refresh=refresh):
            if t["id"] == tool_id:
                return t
        raise AgentError("unknown_tool", f"no such tool {tool_id}", 404)


# -- cwd policy ----------------------------------------------------------------------------------

def allowed_roots() -> list[Path]:
    roots = [Path.home()]
    for env in ("HERMES_WORKSPACE", "HERMES_HOME"):
        v = (os.environ.get(env) or "").strip()
        if v:
            roots.append(Path(v))
    for extra in (os.environ.get("HERMES_COMPANION_AGENT_ROOTS") or "").split(os.pathsep):
        if extra.strip():
            roots.append(Path(extra.strip()))
    roots.append(Path("/tmp"))
    out: list[Path] = []
    for r in roots:
        try:
            rr = r.expanduser().resolve()
        except OSError:
            continue
        if rr not in out:
            out.append(rr)
    return out


def resolve_cwd(cwd: str | None) -> str:
    raw = (cwd or "").strip() or (os.environ.get("HERMES_WORKSPACE") or "").strip() or str(Path.home())
    try:
        path = Path(raw).expanduser().resolve()
    except OSError:
        raise AgentError("cwd_invalid", f"bad cwd {raw!r}")
    if not path.is_dir():
        raise AgentError("cwd_missing", f"{path} is not a directory", 400)
    for root in allowed_roots():
        try:
            path.relative_to(root)
            return str(path)
        except ValueError:
            continue
    raise AgentError("cwd_denied", f"{path} is outside the allowed roots", 403)


ROOT_LABELS = {"HERMES_WORKSPACE": "WORKSPACE", "HERMES_HOME": "HERMES"}
PROJECT_MARKERS = ("package.json", "pyproject.toml", "Cargo.toml", "go.mod", "build.gradle.kts", "build.gradle", "pom.xml", "Makefile")


def root_entries() -> list[dict]:
    """Allowed roots with a short label for the picker chips (deduplicated, in priority order)."""
    seen: set[str] = set()
    out: list[dict] = []

    def add(path: Path, label: str) -> None:
        try:
            rp = str(path.expanduser().resolve())
        except OSError:
            return
        if rp in seen or not Path(rp).is_dir():
            return
        seen.add(rp)
        out.append({"path": rp, "label": label})

    ws = (os.environ.get("HERMES_WORKSPACE") or "").strip()
    if ws:
        add(Path(ws), "WORKSPACE")
    add(Path.home(), "HOME")
    hh = (os.environ.get("HERMES_HOME") or "").strip()
    if hh:
        add(Path(hh), "HERMES")
    for extra in (os.environ.get("HERMES_COMPANION_AGENT_ROOTS") or "").split(os.pathsep):
        if extra.strip():
            add(Path(extra.strip()), Path(extra.strip()).name.upper()[:12] or "ROOT")
    add(Path("/tmp"), "TMP")
    return out


def list_dirs(path: str | None, hidden: bool = False, limit: int = 400) -> dict:
    """Directory picker listing for the phone: subdirectories of `path` (an allowed cwd), with
    the parent (while still inside a root), root chips, and project/git hints. Files are omitted."""
    resolved = resolve_cwd(path)
    target = Path(resolved)
    roots = allowed_roots()
    parent: str | None = None
    if target.parent != target:
        try:
            p = target.parent
            for r in roots:
                try:
                    p.relative_to(r)
                    parent = str(p)
                    break
                except ValueError:
                    continue
        except OSError:
            parent = None
    dirs: list[dict] = []
    truncated = False
    try:
        with os.scandir(target) as it:
            for entry in it:
                try:
                    if not entry.is_dir(follow_symlinks=True):
                        continue
                except OSError:
                    continue
                if not hidden and entry.name.startswith("."):
                    continue
                if entry.name in ("node_modules", "__pycache__", ".git", ".gradle"):
                    continue
                ep = Path(entry.path)
                info = {"name": entry.name, "path": str(ep), "git": (ep / ".git").exists(),
                        "project": any((ep / m).exists() for m in PROJECT_MARKERS)}
                dirs.append(info)
                if len(dirs) >= limit:
                    truncated = True
                    break
    except PermissionError:
        raise AgentError("cwd_denied", f"{target} is not readable", 403)
    except OSError as exc:
        raise AgentError("cwd_invalid", str(exc), 400)
    dirs.sort(key=lambda d: (not (d["git"] or d["project"]), d["name"].lower()))
    return {"path": str(target), "parent": parent, "roots": root_entries(), "dirs": dirs,
            "truncated": truncated, "git": (target / ".git").exists()}


# -- tmux ----------------------------------------------------------------------------------------

class Tmux:
    """Thin wrapper; every call is one ``tmux`` invocation. Replaceable by a fake in tests."""

    def __init__(self, binary: str | None = None):
        self.binary = binary or shutil.which("tmux", path=search_path()) or "tmux"

    def run(self, *args: str, check: bool = True, timeout: float = 8.0) -> str:
        try:
            out = subprocess.run([self.binary, *args], capture_output=True, text=True, timeout=timeout)
        except FileNotFoundError:
            raise AgentError("tmux_missing", "tmux is not installed on the host", 503)
        except subprocess.TimeoutExpired:
            raise AgentError("tmux_timeout", f"tmux {args[0]} timed out", 504)
        if check and out.returncode != 0:
            raise AgentError("tmux_failed", (out.stderr or out.stdout or f"tmux {args[0]} failed").strip()[:200], 502)
        return out.stdout

    def new_session(self, name: str, cwd: str, command: str, cols: int, rows: int, env: dict | None = None) -> None:
        args = ["new-session", "-d", "-s", name, "-x", str(cols), "-y", str(rows), "-c", cwd]
        for k, v in (env or {}).items():
            args += ["-e", f"{k}={v}"]
        args.append(command)
        self.run(*args)
        self.run("set-option", "-t", name, "remain-on-exit", "on", check=False)
        self.run("set-option", "-t", name, "history-limit", "5000", check=False)

    def has(self, name: str) -> bool:
        try:
            self.run("has-session", "-t", name)
            return True
        except AgentError:
            return False

    def list(self) -> list[str]:
        try:
            out = self.run("list-sessions", "-F", "#{session_name}", check=False)
        except AgentError:
            return []
        return [l.strip() for l in out.splitlines() if l.strip()]

    def capture(self, name: str, rows: int) -> str:
        return self.run("capture-pane", "-p", "-e", "-J", "-t", name, "-S", f"-{max(rows, 1)}")

    def display(self, name: str, fmt: str) -> str:
        return self.run("display-message", "-p", "-t", name, fmt).strip()

    def send_keys(self, name: str, *keys: str, literal: bool = False) -> None:
        args = ["send-keys", "-t", name]
        if literal:
            args.append("-l")
        self.run(*args, *keys)

    def resize(self, name: str, cols: int, rows: int) -> None:
        self.run("resize-window", "-t", name, "-x", str(cols), "-y", str(rows), check=False)

    def kill(self, name: str) -> None:
        self.run("kill-session", "-t", name, check=False)


# -- registry ------------------------------------------------------------------------------------

@dataclass
class AgentSession:
    id: str
    tool: str
    mode: str
    cwd: str
    title: str
    command: str
    created_at: float
    updated_at: float
    status: str = "starting"
    exit_code: int | None = None
    last_line: str = ""
    cols: int = DEFAULT_COLS
    rows: int = DEFAULT_ROWS
    prompt: str = ""

    @property
    def tmux(self) -> str:
        return f"hc-{self.id}"

    def public(self) -> dict:
        d = asdict(self)
        d["tmux"] = self.tmux if self.mode != "structured" else ""
        d["attach"] = f"tmux attach -t {self.tmux}" if self.mode != "structured" else self.command
        return d


def default_store_path() -> Path:
    home = Path(os.environ.get("HERMES_HOME") or (Path.home() / ".hermes"))
    return home / "companion-agents.json"


def default_audit_path() -> Path:
    return default_store_path().with_suffix(".log")


class AgentSessions:
    def __init__(self, tmux: Tmux | None = None, discovery: ToolDiscovery | None = None,
                 path: Path | None = None, audit_path: Path | None = None,
                 now: Callable[[], float] = time.time, max_live: int = MAX_LIVE_SESSIONS):
        self.tmux = tmux or Tmux()
        self.discovery = discovery or ToolDiscovery()
        self.path = Path(path) if path is not None else None
        self.audit_path = Path(audit_path) if audit_path is not None else None
        self.now = now
        self.max_live = max_live
        self.sessions: dict[str, AgentSession] = {}
        self.lock = threading.RLock()
        self._pane_cache: dict[str, tuple[str, float]] = {}
        # Structured (stream-json) processes and their event fan-out, by session id.
        self.procs: dict[str, StructuredProcess | CodexStructuredProcess] = {}
        self.events: dict[str, list["queue.Queue[dict]"]] = {}
        self.transcripts: dict[str, list[dict]] = {}
        self.popen: Callable[..., subprocess.Popen] = subprocess.Popen
        if self.path is not None:
            self.load()

    # -- structured event fan-out
    def subscribe(self, session_id: str) -> "queue.Queue[dict]":
        q: "queue.Queue[dict]" = queue.Queue(maxsize=4096)
        with self.lock:
            self.events.setdefault(session_id, []).append(q)
        return q

    def unsubscribe(self, session_id: str, q) -> None:
        with self.lock:
            subs = self.events.get(session_id) or []
            self.events[session_id] = [s for s in subs if s is not q]

    def _emit(self, event: dict) -> None:
        sid = str(event.get("session_id") or "")
        with self.lock:
            self.transcripts.setdefault(sid, []).append(event)
            if len(self.transcripts[sid]) > 2000:
                self.transcripts[sid] = self.transcripts[sid][-2000:]
            subs = list(self.events.get(sid) or [])
            s = self.sessions.get(sid)
            if s is not None:
                t = event.get("type")
                if t == "agent.turn.start":
                    s.status, s.updated_at = "running", self.now()
                elif t == "agent.approval":
                    s.status, s.updated_at = "waiting_approval", self.now()
                elif t in ("agent.approval.resolved",):
                    s.status = "running"
                elif t == "agent.turn.end":
                    s.status, s.updated_at = "waiting_input", self.now()
                    s.last_line = str(event.get("text") or "")[:120].replace("\n", " ")
                elif t == "agent.exit":
                    s.status, s.updated_at, s.exit_code = "exited", self.now(), event.get("exit_code")
                elif t == "agent.ready":
                    s.status = "waiting_input" if s.status == "starting" else s.status
                    if event.get("claude_session_id"):
                        s.command = (f"codex resume {event['claude_session_id']}" if s.tool == "codex"
                                     else f"claude --resume {event['claude_session_id']}")
        for q in subs:
            try:
                q.put_nowait(event)
            except queue.Full:
                pass
        if event.get("type") in ("agent.turn.end", "agent.exit", "agent.ready"):
            self.save()

    def transcript(self, session_id: str, after: int = 0) -> list[dict]:
        with self.lock:
            rows = list(self.transcripts.get(session_id) or [])
        return rows[after:]

    # -- persistence
    def load(self) -> None:
        if self.path is None or not self.path.exists():
            return
        try:
            raw = json.loads(self.path.read_text() or "{}")
        except (OSError, json.JSONDecodeError):
            return
        for row in raw.get("sessions") or []:
            try:
                s = AgentSession(
                    id=str(row["id"]), tool=str(row.get("tool") or "shell"), mode=str(row.get("mode") or "pty"),
                    cwd=str(row.get("cwd") or ""), title=str(row.get("title") or ""), command=str(row.get("command") or ""),
                    created_at=float(row.get("created_at") or 0), updated_at=float(row.get("updated_at") or 0),
                    status=str(row.get("status") or "exited"), exit_code=row.get("exit_code"),
                    last_line=str(row.get("last_line") or ""), cols=int(row.get("cols") or DEFAULT_COLS),
                    rows=int(row.get("rows") or DEFAULT_ROWS), prompt=str(row.get("prompt") or ""),
                )
            except (KeyError, TypeError, ValueError):
                continue
            self.sessions[s.id] = s

    def save(self) -> None:
        if self.path is None:
            return
        payload = {"sessions": [asdict(s) for s in self.sessions.values()]}
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        try:
            tmp.write_text(json.dumps(payload, indent=1))
            os.chmod(tmp, 0o600)
            tmp.replace(self.path)
        except OSError:
            # Never let a persistence hiccup take down a reader thread; the next save retries.
            return

    def audit(self, action: str, session: AgentSession | None, detail: str = "", ok: bool = True) -> None:
        if self.audit_path is None:
            return
        row = {"at": self.now(), "action": action, "session": session.id if session else "", "tool": session.tool if session else "",
               "cwd": session.cwd if session else "", "ok": ok, "detail": detail[:200]}
        try:
            self.audit_path.parent.mkdir(parents=True, exist_ok=True)
            with self.audit_path.open("a") as fh:
                fh.write(json.dumps(row) + "\n")
            os.chmod(self.audit_path, 0o600)
        except OSError:
            pass

    # -- capabilities
    def recent_cwds(self, limit: int = 6) -> list[str]:
        """Most recently used working directories (newest first, deduplicated, still existing)."""
        seen: set[str] = set()
        out: list[str] = []
        with self.lock:
            rows = sorted(self.sessions.values(), key=lambda s: s.updated_at, reverse=True)
        for s in rows:
            if s.cwd in seen or not Path(s.cwd).is_dir():
                continue
            seen.add(s.cwd)
            out.append(s.cwd)
            if len(out) >= limit:
                break
        return out

    def health(self) -> dict:
        tmux = self.discovery.tmux()
        tools = [t for t in self.discovery.probe() if t["installed"] and t["id"] != "shell"]
        return {"tmux": bool(tmux), "tools": [t["id"] for t in tools],
                "live": len([s for s in self.sessions.values() if s.status != "exited"])}

    # -- lifecycle
    def reconcile(self) -> None:
        """Sessions whose tmux is gone are exited; dead panes carry their exit status."""
        with self.lock:
            live = set(self.tmux.list())
            changed = False
            for s in self.sessions.values():
                if s.status == "exited":
                    continue
                if s.mode == "structured":
                    p = self.procs.get(s.id)
                    if p is None or not p.alive:
                        s.status, s.updated_at, changed = "exited", self.now(), True
                    continue
                if s.tmux not in live:
                    s.status, s.updated_at, changed = "exited", self.now(), True
                    continue
                try:
                    info = self.tmux.display(s.tmux, "#{pane_dead} #{pane_dead_status}")
                except AgentError:
                    continue
                parts = info.split()
                if parts and parts[0] == "1":
                    s.status = "exited"
                    try:
                        s.exit_code = int(parts[1]) if len(parts) > 1 else None
                    except ValueError:
                        s.exit_code = None
                    s.updated_at, changed = self.now(), True
                elif s.status == "starting":
                    s.status, changed = "running", True
            if changed:
                self.save()

    def list(self) -> list[AgentSession]:
        self.reconcile()
        with self.lock:
            return sorted(self.sessions.values(), key=lambda s: s.updated_at, reverse=True)

    def get(self, session_id: str) -> AgentSession:
        with self.lock:
            s = self.sessions.get(str(session_id or ""))
        if s is None:
            raise AgentError("unknown_session", f"no agent session {session_id}", 404)
        return s

    def build_command(self, tool: dict, mode: str, prompt: str, args: list[str]) -> str:
        if tool["id"] == "shell":
            shell = os.environ.get("SHELL") or "/bin/bash"
            return f"{shlex.quote(shell)} -l"
        parts = [tool["path"]] + [a for a in args if a]
        if prompt.strip():
            parts.append(prompt.strip())
        return " ".join(shlex.quote(p) for p in parts)

    def start(self, tool_id: str, mode: str = "pty", cwd: str | None = None, prompt: str = "",
              args: list[str] | None = None, title: str = "", cols: int = DEFAULT_COLS, rows: int = DEFAULT_ROWS) -> AgentSession:
        tool = self.discovery.get(tool_id, refresh=True)
        if not tool["installed"]:
            raise AgentError("tool_missing", tool.get("install_hint") or f"{tool_id} is not installed", 409)
        if mode not in tool["modes"]:
            raise AgentError("mode_unsupported", f"{tool_id} has no {mode} mode", 400)
        if mode == "structured" and tool["id"] not in ("claude", "codex"):
            raise AgentError("mode_unsupported", f"structured {tool_id} sessions are not wired yet (use pty)", 400)
        if mode == "pty" and not self.discovery.tmux():
            raise AgentError("tmux_missing", "tmux is not installed on the host (apt install tmux / brew install tmux)", 503)
        resolved = resolve_cwd(cwd)
        self.reconcile()
        with self.lock:
            live = [s for s in self.sessions.values() if s.status != "exited"]
            if len(live) >= self.max_live:
                raise AgentError("too_many_sessions", f"max {self.max_live} live sessions; kill one first", 409)
            sid = secrets.token_hex(3)
            while sid in self.sessions:
                sid = secrets.token_hex(3)
            cols = max(40, min(int(cols or DEFAULT_COLS), 220))
            rows = max(10, min(int(rows or DEFAULT_ROWS), 80))
            now = self.now()
            if mode == "structured":
                if tool["id"] == "codex":
                    command = f"{tool['path']} exec --json"
                else:
                    command = f"{tool['path']} " + " ".join(CLAUDE_STRUCTURED_ARGS)
                s = AgentSession(id=sid, tool=tool["id"], mode=mode, cwd=resolved, title=(title or "").strip()[:80] or default_title(tool, prompt, resolved),
                                 command=command, created_at=now, updated_at=now, cols=cols, rows=rows, prompt=prompt.strip()[:500])
                self.sessions[s.id] = s
                runner = CodexStructuredProcess if tool["id"] == "codex" else StructuredProcess
                self.procs[s.id] = runner(s, tool["path"], resolved, self._emit, extra_args=list(args or []), popen=self.popen, now=self.now)
                self.save()
                self.audit("start", s, s.command)
                if prompt.strip():
                    self.prompt(s.id, prompt.strip())
                return s
            command = self.build_command(tool, mode, prompt, list(args or []))
            s = AgentSession(id=sid, tool=tool["id"], mode=mode, cwd=resolved, title=(title or "").strip()[:80] or default_title(tool, prompt, resolved),
                             command=command, created_at=now, updated_at=now, cols=cols, rows=rows, prompt=prompt.strip()[:500])
            self.tmux.new_session(s.tmux, resolved, command, cols, rows, env={"HERMES_COMPANION_AGENT": s.id})
            self.sessions[s.id] = s
            self.save()
        self.audit("start", s, command)
        return s

    # -- structured actions
    def _proc(self, session_id: str) -> StructuredProcess | CodexStructuredProcess:
        s = self.get(session_id)
        p = self.procs.get(s.id)
        if s.mode != "structured" or p is None or not p.alive:
            if p is not None and not p.alive and s.status != "exited":
                with self.lock:
                    s.status = "exited"
                    self.save()
            raise AgentError("session_exited", "structured session is not running", 409)
        return p

    def prompt(self, session_id: str, text: str) -> AgentSession:
        s = self.get(session_id)
        text = (text or "").strip()
        if not text:
            raise AgentError("text_required", "text required")
        self._proc(s.id).prompt(text)
        self._emit({"type": "agent.user", "session_id": s.id, "text": text})
        with self.lock:
            s.updated_at = self.now()
        self.audit("prompt", s, text)
        return s

    def approve(self, session_id: str, request_id: str, decision: str) -> bool:
        s = self.get(session_id)
        ok = self._proc(s.id).approve(request_id, decision)
        if ok:
            self.audit("approve", s, f"{request_id}:{decision}")
        return ok

    def pending_approval(self, session_id: str) -> dict | None:
        p = self.procs.get(session_id)
        if p is None:
            return None
        with p.lock:
            w = next(iter(p.pending.values()), None)
        return w.public() if w else None

    def pane(self, session_id: str, cols: int | None = None, rows: int | None = None) -> dict:
        s = self.get(session_id)
        if s.mode == "structured":
            self.reconcile()
            return {"session": s.public(), "ansi": "", "cursor": [0, 0], "cols": s.cols, "rows": s.rows,
                    "transcript": self.transcript(s.id), "approval": self.pending_approval(s.id)}
        if cols and rows and (cols != s.cols or rows != s.rows):
            with self.lock:
                s.cols, s.rows = max(40, min(int(cols), 220)), max(10, min(int(rows), 80))
            if s.status != "exited":
                self.tmux.resize(s.tmux, s.cols, s.rows)
        self.reconcile()
        if not self.tmux.has(s.tmux):
            return {"session": s.public(), "ansi": "", "cursor": [0, 0], "cols": s.cols, "rows": s.rows, "seq": 0}
        ansi = self.tmux.capture(s.tmux, s.rows)
        try:
            cx, cy = (int(v) for v in self.tmux.display(s.tmux, "#{cursor_x} #{cursor_y}").split()[:2])
        except (AgentError, ValueError):
            cx, cy = 0, 0
        with self.lock:
            lines = [l for l in strip_ansi(ansi).splitlines() if l.strip()]
            s.last_line = lines[-1][:120] if lines else s.last_line
        return {"session": s.public(), "ansi": ansi, "cursor": [cx, cy], "cols": s.cols, "rows": s.rows}

    def keys(self, session_id: str, text: str = "", key: str = "", keys: list[str] | None = None) -> AgentSession:
        s = self.get(session_id)
        if s.mode == "structured":
            # Structured sessions take prompts; Ctrl-C maps to interrupt, everything else is a prompt.
            if any(str(k).lower() in ("c-c", "ctrl-c") for k in ([key] if key else []) + list(keys or [])):
                self._proc(s.id).interrupt()
                return s
            if text.strip():
                return self.prompt(s.id, text)
            return s
        if s.status == "exited" or not self.tmux.has(s.tmux):
            raise AgentError("session_exited", "session has exited", 409)
        sent: list[str] = []
        if text:
            self.tmux.send_keys(s.tmux, text, literal=True)
            sent.append(f"text:{len(text)}")
        for k in ([key] if key else []) + list(keys or []):
            name = SPECIAL_KEYS.get(str(k).strip().lower())
            if not name:
                raise AgentError("unknown_key", f"unknown key {k!r}")
            self.tmux.send_keys(s.tmux, name)
            sent.append(name)
        with self.lock:
            s.updated_at = self.now()
            self.save()
        self.audit("keys", s, " ".join(sent))
        return s

    def kill(self, session_id: str) -> AgentSession:
        s = self.get(session_id)
        p = self.procs.pop(s.id, None)
        if p is not None:
            p.kill()
        if s.mode != "structured":
            self.tmux.kill(s.tmux)
        with self.lock:
            s.status, s.updated_at = "exited", self.now()
            self.save()
        self.audit("kill", s)
        return s

    def forget(self, session_id: str) -> None:
        s = self.get(session_id)
        if s.status != "exited":
            self.kill(session_id)
        with self.lock:
            self.sessions.pop(s.id, None)
            self.save()

    def shutdown(self) -> int:
        """Relay is exiting: stop structured child processes (they cannot be re-attached) but leave
        tmux panes alone — those survive a relay restart and are reconciled on load."""
        n = 0
        for sid, p in list(self.procs.items()):
            try:
                p.kill()
                n += 1
            except Exception:
                pass
            self.procs.pop(sid, None)
            s = self.sessions.get(sid)
            if s is not None and s.status != "exited":
                s.status, s.updated_at = "exited", self.now()
        if n:
            self.save()
            self.audit("shutdown", None, f"structured={n}")
        return n

    def kill_all(self, reason: str = "") -> int:
        n = 0
        for s in list(self.sessions.values()):
            if s.status != "exited":
                try:
                    self.kill(s.id)
                    n += 1
                except AgentError:
                    pass
        if n:
            self.audit("kill_all", None, reason)
        return n


# -- structured sessions (Claude Code stream-json) ------------------------------------------------

CLAUDE_STRUCTURED_ARGS = [
    "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
    "--include-partial-messages", "--permission-prompt-tool", "stdio",
]


@dataclass
class StructuredApproval:
    request_id: str
    tool: str
    detail: str
    choices: list[str]
    created_at: float
    event: threading.Event = field(default_factory=threading.Event)
    decision: str | None = None

    def public(self) -> dict:
        return {"request_id": self.request_id, "kind": "approval", "tool": self.tool, "command": self.detail,
                "choices": self.choices}


class StructuredProcess:
    """One long-lived `claude -p --input-format stream-json …` process: prompts go in as user
    messages, everything it emits is normalised to `agent.*` events for the phone, and
    `can_use_tool` control requests block until the operator answers (or deny after the timeout).

    Not tmux: the transcript is the UI here, so the phone renders it in the chat view."""

    def __init__(self, session: "AgentSession", binary: str, cwd: str, emit: Callable[[dict], None],
                 resume: str | None = None, approval_timeout_s: float = 600.0, extra_args: list[str] | None = None,
                 popen: Callable[..., subprocess.Popen] = subprocess.Popen, now: Callable[[], float] = time.time):
        self.session = session
        self.emit = emit
        self.approval_timeout_s = approval_timeout_s
        self.now = now
        self.lock = threading.Lock()
        self.pending: dict[str, StructuredApproval] = {}
        self.claude_session_id: str | None = resume
        self.turn_active = False
        self.text_parts: list[str] = []
        self.open_tools: dict[str, dict] = {}
        args = [binary, *CLAUDE_STRUCTURED_ARGS, *(extra_args or [])]
        if resume:
            args += ["--resume", resume]
        env = os.environ.copy()
        env.setdefault("CLAUDE_CODE_ENTRYPOINT", "hermes-companion")
        self.proc = popen(args, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          text=True, bufsize=1, env=env)
        self.reader = threading.Thread(target=self._read, name=f"claude-{session.id}", daemon=True)
        self.reader.start()
        self.err_reader = threading.Thread(target=self._read_err, name=f"claude-err-{session.id}", daemon=True)
        self.err_reader.start()
        self.stderr_tail: list[str] = []

    # -- input
    def _write(self, obj: dict) -> None:
        if self.proc.stdin is None:
            raise AgentError("session_exited", "stdin closed", 409)
        with self.lock:
            try:
                self.proc.stdin.write(json.dumps(obj) + "\n")
                self.proc.stdin.flush()
            except (OSError, ValueError) as exc:
                raise AgentError("session_exited", f"claude stdin closed ({exc})", 409)

    def prompt(self, text: str) -> None:
        if self.proc.poll() is not None:
            raise AgentError("session_exited", "claude has exited", 409)
        if self.turn_active:
            raise AgentError("turn_active", "a turn is still running; wait or interrupt", 409)
        self.turn_active = True
        self.text_parts = []
        self.emit({"type": "agent.turn.start", "session_id": self.session.id})
        self._write({"type": "user", "message": {"role": "user", "content": text}, "parent_tool_use_id": None,
                     **({"session_id": self.claude_session_id} if self.claude_session_id else {})})

    def interrupt(self) -> None:
        try:
            self._write({"type": "control_request", "request_id": f"int-{secrets.token_hex(3)}", "request": {"subtype": "interrupt"}})
        except AgentError:
            pass
        for w in list(self.pending.values()):
            w.decision = None
            w.event.set()

    def approve(self, request_id: str, decision: str) -> bool:
        with self.lock:
            w = self.pending.get(request_id)
        if w is None:
            return False
        w.decision = (decision or "deny").strip().lower()
        w.event.set()
        return True

    def kill(self) -> None:
        for w in list(self.pending.values()):
            w.decision = None
            w.event.set()
        try:
            if self.proc.stdin:
                self.proc.stdin.close()
        except OSError:
            pass
        try:
            self.proc.terminate()
            self.proc.wait(timeout=3)
        except Exception:
            try:
                self.proc.kill()
            except Exception:
                pass

    @property
    def alive(self) -> bool:
        return self.proc.poll() is None

    # -- output
    def _read_err(self) -> None:
        try:
            for line in self.proc.stderr or []:
                line = line.rstrip()
                if line:
                    self.stderr_tail = (self.stderr_tail + [line])[-20:]
        except (OSError, ValueError):
            pass

    def _read(self) -> None:
        try:
            for raw in self.proc.stdout or []:
                raw = raw.strip()
                if not raw:
                    continue
                try:
                    obj = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                try:
                    self._handle(obj)
                except Exception as exc:  # pragma: no cover - defensive
                    self.emit({"type": "agent.error", "session_id": self.session.id, "error": f"parse:{exc.__class__.__name__}"})
        except (OSError, ValueError):
            pass
        finally:
            code = self.proc.poll()
            if code is None:
                try:
                    code = self.proc.wait(timeout=2)
                except Exception:
                    code = None
            self.turn_active = False
            self.emit({"type": "agent.exit", "session_id": self.session.id, "exit_code": code, "stderr": "\n".join(self.stderr_tail[-5:])})

    def _handle(self, obj: dict) -> None:
        t = str(obj.get("type") or "")
        sid = self.session.id
        if t == "system" and obj.get("subtype") == "init":
            self.claude_session_id = str(obj.get("session_id") or self.claude_session_id or "")
            self.emit({"type": "agent.ready", "session_id": sid, "claude_session_id": self.claude_session_id,
                       "model": str(obj.get("model") or ""), "cwd": str(obj.get("cwd") or "")})
        elif t == "stream_event":
            ev = obj.get("event") or {}
            if ev.get("type") == "content_block_delta":
                delta = ev.get("delta") or {}
                piece = str(delta.get("text") or "") if delta.get("type") == "text_delta" else ""
                if piece:
                    self.text_parts.append(piece)
                    self.emit({"type": "agent.delta", "session_id": sid, "text": piece})
        elif t == "assistant":
            msg = obj.get("message") or {}
            for block in msg.get("content") or []:
                if not isinstance(block, dict):
                    continue
                if block.get("type") == "tool_use":
                    tid = str(block.get("id") or secrets.token_hex(3))
                    name = str(block.get("name") or "tool")
                    detail = tool_input_summary(name, block.get("input") or {})
                    self.open_tools[tid] = {"name": name, "detail": detail, "at": time.monotonic()}
                    self.emit({"type": "agent.tool.start", "session_id": sid, "tool_id": tid, "name": name, "detail": detail})
                elif block.get("type") == "text" and not self.text_parts:
                    # No partial deltas arrived (e.g. --include-partial-messages unsupported): emit the whole text.
                    text = str(block.get("text") or "")
                    if text:
                        self.text_parts.append(text)
                        self.emit({"type": "agent.delta", "session_id": sid, "text": text})
        elif t == "user":
            msg = obj.get("message") or {}
            for block in msg.get("content") or []:
                if isinstance(block, dict) and block.get("type") == "tool_result":
                    tid = str(block.get("tool_use_id") or "")
                    started = self.open_tools.pop(tid, None)
                    content = block.get("content")
                    if isinstance(content, list):
                        content = " ".join(str(c.get("text") or "") for c in content if isinstance(c, dict))
                    out = str(content or "")[:400]
                    self.emit({"type": "agent.tool.complete", "session_id": sid, "tool_id": tid,
                               "name": (started or {}).get("name", "tool"), "detail": out or (started or {}).get("detail", ""),
                               "error": bool(block.get("is_error")),
                               "duration_ms": int((time.monotonic() - started["at"]) * 1000) if started else 0})
        elif t == "control_request":
            req = obj.get("request") or {}
            rid = str(obj.get("request_id") or "")
            if req.get("subtype") == "can_use_tool":
                self._approval(rid, req)
            else:
                # Anything else (hooks, mcp status …): acknowledge so the CLI does not stall.
                self._write({"type": "control_response", "response": {"subtype": "success", "request_id": rid, "response": {}}})
        elif t == "result":
            text = "".join(self.text_parts).strip() or str(obj.get("result") or "")
            self.turn_active = False
            self.text_parts = []
            self.emit({"type": "agent.turn.end", "session_id": sid, "text": text, "is_error": bool(obj.get("is_error")),
                       "subtype": str(obj.get("subtype") or ""), "cost_usd": obj.get("total_cost_usd"),
                       "duration_ms": obj.get("duration_ms"), "claude_session_id": str(obj.get("session_id") or self.claude_session_id or "")})

    def _approval(self, rid: str, req: dict) -> None:
        tool = str(req.get("tool_name") or "tool")
        detail = tool_input_summary(tool, req.get("input") or {})
        wait = StructuredApproval(request_id=rid, tool=tool, detail=detail, choices=["once", "deny"], created_at=self.now())
        with self.lock:
            self.pending[rid] = wait
        self.emit({"type": "agent.approval", "session_id": self.session.id, **wait.public()})
        answered = wait.event.wait(timeout=self.approval_timeout_s)
        with self.lock:
            self.pending.pop(rid, None)
        decision = wait.decision if answered else None
        allow = decision in ("once", "allow", "yes", "y", "always")
        response = ({"behavior": "allow", "updatedInput": req.get("input") or {}} if allow
                    else {"behavior": "deny", "message": "denied by the operator from the companion phone" if decision else "no answer from the operator (timeout)"})
        self._write({"type": "control_response", "response": {"subtype": "success", "request_id": rid, "response": response}})
        self.emit({"type": "agent.approval.resolved", "session_id": self.session.id, "request_id": rid,
                   "decision": decision or "timeout"})


class CodexStructuredProcess:
    """Codex has no long-lived stdio protocol for `exec`, so each turn is one `codex exec --json`
    process; follow-up turns use `codex exec resume <thread_id>` so the thread keeps its context.
    Approvals do not exist in exec mode (the sandbox decides), so this runner never emits them."""

    def __init__(self, session: "AgentSession", binary: str, cwd: str, emit: Callable[[dict], None],
                 resume: str | None = None, extra_args: list[str] | None = None,
                 popen: Callable[..., subprocess.Popen] = subprocess.Popen, now: Callable[[], float] = time.time):
        self.session = session
        self.binary = binary
        self.cwd = cwd
        self.emit = emit
        self.extra_args = list(extra_args or [])
        self.popen = popen
        self.now = now
        self.lock = threading.Lock()
        self.pending: dict[str, StructuredApproval] = {}
        self.thread_id: str | None = resume
        self.turn_active = False
        self.proc: subprocess.Popen | None = None
        self.killed = False
        self.emit({"type": "agent.ready", "session_id": session.id, "claude_session_id": self.thread_id or "",
                   "model": "", "cwd": cwd})

    @property
    def alive(self) -> bool:
        return not self.killed

    def _args(self, text: str) -> list[str]:
        base = [self.binary, "exec", "--json", "--skip-git-repo-check", "--cd", self.cwd, *self.extra_args]
        if self.thread_id:
            return [*base, "resume", self.thread_id, text]
        return [*base, text]

    def prompt(self, text: str) -> None:
        if self.killed:
            raise AgentError("session_exited", "codex session was closed", 409)
        if self.turn_active:
            raise AgentError("turn_active", "a turn is still running; wait or interrupt", 409)
        self.turn_active = True
        self.emit({"type": "agent.turn.start", "session_id": self.session.id})
        try:
            self.proc = self.popen(self._args(text), cwd=self.cwd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, text=True, bufsize=1)
        except OSError as exc:
            self.turn_active = False
            raise AgentError("spawn_failed", f"codex failed to start: {exc}", 503)
        threading.Thread(target=self._read, args=(self.proc,), name=f"codex-{self.session.id}", daemon=True).start()

    def interrupt(self) -> None:
        p = self.proc
        if p is not None and p.poll() is None:
            try:
                p.terminate()
            except OSError:
                pass

    def approve(self, request_id: str, decision: str) -> bool:
        return False

    def kill(self) -> None:
        self.killed = True
        self.interrupt()
        self.emit({"type": "agent.exit", "session_id": self.session.id, "exit_code": None, "stderr": ""})

    def _read(self, proc: subprocess.Popen) -> None:
        sid = self.session.id
        text_parts: list[str] = []
        open_items: dict[str, float] = {}
        try:
            for raw in proc.stdout or []:
                raw = raw.strip()
                if not raw:
                    continue
                try:
                    obj = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                t = str(obj.get("type") or "")
                if t == "thread.started":
                    self.thread_id = str(obj.get("thread_id") or self.thread_id or "")
                    self.emit({"type": "agent.ready", "session_id": sid, "claude_session_id": self.thread_id, "model": "", "cwd": self.cwd})
                elif t in ("item.started", "item.completed", "item.updated"):
                    item = obj.get("item") or {}
                    kind = str(item.get("type") or "")
                    iid = str(item.get("id") or secrets.token_hex(3))
                    if kind == "agent_message" and t == "item.completed":
                        piece = str(item.get("text") or "")
                        if piece:
                            text_parts.append(piece)
                            self.emit({"type": "agent.delta", "session_id": sid, "text": piece + "\n"})
                    elif kind in ("command_execution", "file_change", "mcp_tool_call", "web_search"):
                        detail = str(item.get("command") or item.get("query") or item.get("tool") or "")
                        if kind == "file_change":
                            detail = ", ".join(str(c.get("path") or "") for c in item.get("changes") or [] if isinstance(c, dict)) or detail
                        if t == "item.started":
                            open_items[iid] = time.monotonic()
                            self.emit({"type": "agent.tool.start", "session_id": sid, "tool_id": iid, "name": kind, "detail": detail[:300]})
                        elif t == "item.completed":
                            started = open_items.pop(iid, None)
                            if started is None:
                                self.emit({"type": "agent.tool.start", "session_id": sid, "tool_id": iid, "name": kind, "detail": detail[:300]})
                            out = str(item.get("aggregated_output") or "")[-400:] or detail
                            failed = item.get("status") not in (None, "completed") or (item.get("exit_code") not in (None, 0))
                            self.emit({"type": "agent.tool.complete", "session_id": sid, "tool_id": iid, "name": kind, "detail": out[:400],
                                       "error": bool(failed), "duration_ms": int((time.monotonic() - started) * 1000) if started else 0})
                elif t == "turn.failed" or t == "error":
                    msg = str((obj.get("error") or {}).get("message") if isinstance(obj.get("error"), dict) else obj.get("message") or obj.get("error") or "turn failed")
                    self.emit({"type": "agent.error", "session_id": sid, "error": msg[:300]})
        except (OSError, ValueError):
            pass
        finally:
            code = None
            try:
                code = proc.wait(timeout=5)
            except Exception:
                pass
            err = ""
            try:
                err = (proc.stderr.read() if proc.stderr else "") or ""
            except (OSError, ValueError):
                pass
            self.turn_active = False
            is_error = bool(code) and not self.killed
            self.emit({"type": "agent.turn.end", "session_id": sid, "text": "\n".join(text_parts).strip() or (err.strip()[-300:] if is_error else ""),
                       "is_error": is_error, "subtype": "success" if not is_error else f"exit_{code}", "cost_usd": None,
                       "duration_ms": None, "claude_session_id": self.thread_id or ""})


def tool_input_summary(name: str, inp: dict, limit: int = 300) -> str:
    """One line for a tool row from a Claude tool_use input."""
    if not isinstance(inp, dict):
        return str(inp)[:limit]
    for key in ("command", "file_path", "path", "pattern", "query", "url", "description", "prompt", "content"):
        v = inp.get(key)
        if isinstance(v, str) and v.strip():
            return v.strip().replace("\n", " ")[:limit]
    try:
        return json.dumps(inp, ensure_ascii=False)[:limit]
    except (TypeError, ValueError):
        return name


_ANSI = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\x07]*\x07|\x1b[()][A-Za-z0-9]")


def strip_ansi(text: str) -> str:
    return _ANSI.sub("", text)


def default_title(tool: dict, prompt: str, cwd: str) -> str:
    base = Path(cwd).name or cwd
    if prompt.strip():
        return f"{tool['glyph']} {prompt.strip()[:40]}"
    return f"{tool['glyph']} {base}"
