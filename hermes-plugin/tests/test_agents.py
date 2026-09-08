from __future__ import annotations

import io
import json
import os
import queue
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from agents import (  # noqa: E402
    AgentError, AgentSessions, ToolDiscovery, Tmux, list_dirs, resolve_cwd, strip_ansi, SPECIAL_KEYS,
)
from pairing import PairingStore  # noqa: E402
from relay import CompanionHandler, RelayState, make_server  # noqa: E402


class FakeTmux(Tmux):
    """In-memory tmux: sessions with a scripted screen; keys append to the screen."""

    def __init__(self):
        self.sessions: dict[str, dict] = {}
        self.calls: list[tuple] = []

    def new_session(self, name, cwd, command, cols, rows, env=None):
        self.calls.append(("new", name, cwd, command, cols, rows))
        self.sessions[name] = {"screen": f"$ {command}\n\x1b[32mready\x1b[0m\n", "dead": False, "status": 0, "cols": cols, "rows": rows}

    def has(self, name):
        return name in self.sessions

    def list(self):
        return list(self.sessions)

    def capture(self, name, rows):
        return self.sessions[name]["screen"]

    def display(self, name, fmt):
        s = self.sessions[name]
        if "pane_dead" in fmt:
            return f"{1 if s['dead'] else 0} {s['status']}"
        return "3 1"

    def send_keys(self, name, *keys, literal=False):
        self.calls.append(("keys", name, keys, literal))
        s = self.sessions[name]
        s["screen"] += ("".join(keys) if literal else f"<{keys[0]}>")

    def resize(self, name, cols, rows):
        self.calls.append(("resize", name, cols, rows))
        self.sessions[name].update(cols=cols, rows=rows)

    def kill(self, name):
        self.calls.append(("kill", name))
        self.sessions.pop(name, None)


def fake_discovery(installed=("claude",), tmux=True):
    def which(b):
        if b == "tmux":
            return "/usr/bin/tmux" if tmux else None
        return f"/opt/bin/{b}" if b in installed else None
    return ToolDiscovery(which=which, version=lambda p, f: f"{Path(p).name} 9.9.9" if f else "", ttl=0)


def _http(method, url, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read().decode() or "{}")


class DiscoveryTests(unittest.TestCase):
    def test_probe_reports_installed_missing_and_always_shell(self):
        d = fake_discovery(installed=("claude", "codex"))
        tools = {t["id"]: t for t in d.probe()}
        self.assertTrue(tools["claude"]["installed"])
        self.assertEqual(tools["claude"]["version"], "claude 9.9.9")
        self.assertIn("structured", tools["codex"]["modes"])
        self.assertFalse(tools["cline"]["installed"])
        self.assertEqual(tools["cline"]["install_hint"], "npm i -g cline")
        self.assertTrue(tools["shell"]["installed"])
        self.assertEqual(d.tmux(), "/usr/bin/tmux")

    def test_probe_is_cached_until_refresh(self):
        calls = []
        def which(b):
            calls.append(b)
            return None
        d = ToolDiscovery(which=which, version=lambda p, f: "", ttl=100)
        d.probe(); n = len(calls)
        d.probe(); self.assertEqual(len(calls), n)
        d.probe(refresh=True); self.assertGreater(len(calls), n)

    def test_resolve_cwd_allowlist(self):
        home = str(Path.home())
        self.assertEqual(resolve_cwd(home), str(Path(home).resolve()))
        with tempfile.TemporaryDirectory() as tmp:
            with patch.dict(os.environ, {"HERMES_WORKSPACE": tmp}, clear=False):
                self.assertEqual(resolve_cwd(""), str(Path(tmp).resolve()))
                self.assertEqual(resolve_cwd(tmp), str(Path(tmp).resolve()))
        with self.assertRaises(AgentError) as cm:
            resolve_cwd("/")
        self.assertEqual(cm.exception.code, "cwd_denied")
        with self.assertRaises(AgentError) as cm:
            resolve_cwd(str(Path.home() / "definitely-not-here-xyz"))
        self.assertEqual(cm.exception.code, "cwd_missing")

    def test_list_dirs_is_rooted_and_ranks_projects(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            (root / "zeta").mkdir()
            (root / "alpha").mkdir()
            (root / "repo").mkdir()
            (root / "repo" / ".git").mkdir()
            (root / ".hidden").mkdir()
            (root / "node_modules").mkdir()
            (root / "file.txt").write_text("x")
            with patch.dict(os.environ, {"HERMES_COMPANION_AGENT_ROOTS": str(root), "HERMES_WORKSPACE": str(root)}):
                listing = list_dirs(str(root))
                self.assertEqual(listing["path"], str(root))
                self.assertIsNone(listing["parent"])  # cannot leave the root
                self.assertEqual([d["name"] for d in listing["dirs"]], ["repo", "alpha", "zeta"])
                self.assertTrue(listing["dirs"][0]["git"])
                self.assertIn("WORKSPACE", [r["label"] for r in listing["roots"]])
                hidden = list_dirs(str(root), hidden=True)
                self.assertIn(".hidden", [d["name"] for d in hidden["dirs"]])
                self.assertNotIn("node_modules", [d["name"] for d in hidden["dirs"]])
                sub = list_dirs(str(root / "repo"))
                self.assertEqual(sub["parent"], str(root))
                self.assertTrue(sub["git"])
                with self.assertRaises(AgentError) as cm:
                    list_dirs("/")
                self.assertEqual(cm.exception.code, "cwd_denied")
                with self.assertRaises(AgentError) as cm:
                    list_dirs(str(root / "file.txt"))
                self.assertEqual(cm.exception.code, "cwd_missing")

    def test_strip_ansi_and_key_names(self):
        self.assertEqual(strip_ansi("\x1b[32mok\x1b[0m \x1b]0;title\x07x"), "ok x")
        self.assertEqual(SPECIAL_KEYS["c-c"], "C-c")
        self.assertEqual(SPECIAL_KEYS["enter"], "Enter")


class SessionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.tmux = FakeTmux()
        self.env = patch.dict(os.environ, {"HERMES_WORKSPACE": self.tmp.name}, clear=False)
        self.env.start()
        self.ag = AgentSessions(tmux=self.tmux, discovery=fake_discovery(), path=Path(self.tmp.name) / "agents.json",
                                audit_path=Path(self.tmp.name) / "agents.log", max_live=2)

    def tearDown(self):
        self.env.stop()
        self.tmp.cleanup()

    def test_start_pane_keys_kill_and_persist(self):
        s = self.ag.start("claude", prompt="fix the failing test", cwd=self.tmp.name)
        self.assertEqual(s.tool, "claude")
        self.assertTrue(s.tmux.startswith("hc-"))
        self.assertIn("/opt/bin/claude", s.command)
        self.assertIn("'fix the failing test'", s.command)
        self.assertEqual(s.title, "CC fix the failing test")
        new = [c for c in self.tmux.calls if c[0] == "new"][0]
        self.assertEqual((new[2], new[4], new[5]), (str(Path(self.tmp.name).resolve()), 100, 40))
        pane = self.ag.pane(s.id)
        self.assertIn("ready", pane["ansi"])
        self.assertEqual(pane["session"]["status"], "running")
        self.assertEqual(pane["session"]["last_line"], "ready")
        self.ag.keys(s.id, text="ls -la", key="enter")
        sent = [c for c in self.tmux.calls if c[0] == "keys"]
        self.assertEqual((sent[0][2], sent[0][3]), (("ls -la",), True))
        self.assertEqual((sent[1][2], sent[1][3]), (("Enter",), False))
        with self.assertRaises(AgentError):
            self.ag.keys(s.id, key="bogus")
        self.ag.pane(s.id, cols=80, rows=24)
        self.assertIn(("resize", s.tmux, 80, 24), self.tmux.calls)
        # Persisted; a fresh registry sees the session and reconciles against tmux.
        again = AgentSessions(tmux=self.tmux, discovery=fake_discovery(), path=Path(self.tmp.name) / "agents.json")
        self.assertEqual([x.id for x in again.list()], [s.id])
        self.ag.kill(s.id)
        self.assertEqual(self.ag.get(s.id).status, "exited")
        with self.assertRaises(AgentError) as cm:
            self.ag.keys(s.id, text="x")
        self.assertEqual(cm.exception.code, "session_exited")
        log = [json.loads(l) for l in (Path(self.tmp.name) / "agents.log").read_text().splitlines()]
        self.assertEqual([r["action"] for r in log], ["start", "keys", "kill"])

    def test_dead_pane_marks_exit_code_and_missing_tmux_marks_exited(self):
        s = self.ag.start("shell", cwd=self.tmp.name)
        self.tmux.sessions[s.tmux].update(dead=True, status=3)
        self.ag.reconcile()
        self.assertEqual((self.ag.get(s.id).status, self.ag.get(s.id).exit_code), ("exited", 3))
        t = self.ag.start("shell", cwd=self.tmp.name)
        self.tmux.sessions.pop(t.tmux)
        self.assertEqual([x.status for x in self.ag.list() if x.id == t.id], ["exited"])

    def test_guards(self):
        with self.assertRaises(AgentError) as cm:
            self.ag.start("codex", cwd=self.tmp.name)
        self.assertEqual(cm.exception.code, "tool_missing")
        with self.assertRaises(AgentError) as cm:
            self.ag.start("shell", mode="structured", cwd=self.tmp.name)
        self.assertEqual(cm.exception.code, "mode_unsupported")
        with self.assertRaises(AgentError) as cm:
            self.ag.start("claude", cwd="/")
        self.assertEqual(cm.exception.code, "cwd_denied")
        self.ag.start("shell", cwd=self.tmp.name)
        self.ag.start("shell", cwd=self.tmp.name)
        with self.assertRaises(AgentError) as cm:
            self.ag.start("shell", cwd=self.tmp.name)
        self.assertEqual(cm.exception.code, "too_many_sessions")
        self.assertEqual(self.ag.kill_all("test"), 2)
        self.assertEqual(self.ag.health()["live"], 0)
        no_tmux = AgentSessions(tmux=self.tmux, discovery=fake_discovery(tmux=False))
        with self.assertRaises(AgentError) as cm:
            no_tmux.start("shell", cwd=self.tmp.name)
        self.assertEqual(cm.exception.code, "tmux_missing")
        self.assertFalse(no_tmux.health()["tmux"])


class AgentRelayTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.tmux = FakeTmux()
        self.env = patch.dict(os.environ, {"HERMES_WORKSPACE": self.tmp.name, "HERMES_COMPANION_STANDALONE": "1"}, clear=False)
        self.env.start()
        agents = AgentSessions(tmux=self.tmux, discovery=fake_discovery(installed=("claude", "codex")),
                               path=Path(self.tmp.name) / "agents.json", audit_path=Path(self.tmp.name) / "agents.log")
        self.state = RelayState(pairing=PairingStore(), agents=agents)
        self.relay = make_server("127.0.0.1:0", "http://127.0.0.1:1", self.state)
        threading.Thread(target=self.relay.serve_forever, daemon=True).start()
        h, p = self.relay.server_address
        self.base = f"http://{h}:{p}"

    def tearDown(self):
        self.relay.shutdown()
        self.relay.server_close()
        self.env.stop()
        self.tmp.cleanup()

    def test_tools_sessions_pane_keys_kill_over_http(self):
        code, tools = _http("GET", f"{self.base}/companion/agents/tools?refresh=1")
        self.assertEqual(code, 200)
        self.assertTrue(tools["tmux"])
        ids = {t["id"]: t["installed"] for t in tools["tools"]}
        self.assertEqual((ids["claude"], ids["codex"], ids["cline"], ids["shell"]), (True, True, False, True))
        self.assertEqual(tools["default_cwd"], self.tmp.name)
        code, health = _http("GET", f"{self.base}/companion/health")
        self.assertEqual(health["agents"]["tools"], ["claude", "codex"])
        code, body = _http("POST", f"{self.base}/companion/agents/sessions", {"tool": "claude", "prompt": "hello", "cwd": self.tmp.name})
        self.assertEqual(code, 201, body)
        sid = body["session"]["id"]
        self.assertEqual(body["session"]["attach"], f"tmux attach -t hc-{sid}")
        code, pane = _http("GET", f"{self.base}/companion/agents/sessions/{sid}/pane?cols=80&rows=24")
        self.assertEqual(code, 200)
        self.assertIn("ready", pane["ansi"])
        self.assertEqual((pane["cols"], pane["rows"]), (80, 24))
        code, _ = _http("POST", f"{self.base}/companion/agents/sessions/{sid}/keys", {"text": "y", "keys": ["enter"]})
        self.assertEqual(code, 202)
        code, _ = _http("POST", f"{self.base}/companion/agents/sessions/{sid}/interrupt", {})
        self.assertEqual(code, 202)
        self.assertIn(("keys", f"hc-{sid}", ("C-c",), False), self.tmux.calls)
        code, rows = _http("GET", f"{self.base}/companion/agents/sessions")
        self.assertEqual([s["id"] for s in rows["sessions"]], [sid])
        code, body = _http("POST", f"{self.base}/companion/agents/sessions", {"tool": "cline", "cwd": self.tmp.name})
        self.assertEqual((code, body["error"]), (409, "tool_missing"))
        code, body = _http("DELETE", f"{self.base}/companion/agents/sessions/{sid}")
        self.assertEqual(body["session"]["status"], "exited")
        code, _ = _http("DELETE", f"{self.base}/companion/agents/sessions/{sid}?forget=1")
        code, rows = _http("GET", f"{self.base}/companion/agents/sessions")
        self.assertEqual(rows["sessions"], [])

    def test_dirs_route_lists_and_carries_recent(self):
        code, body = _http("GET", f"{self.base}/companion/agents/dirs?path={self.tmp.name}")
        self.assertEqual(code, 200, body)
        self.assertEqual(body["path"], str(Path(self.tmp.name).resolve()))
        self.assertIn("roots", body)
        self.assertEqual(body["recent"], [])
        _http("POST", f"{self.base}/companion/agents/sessions", {"tool": "shell", "cwd": self.tmp.name})
        code, body = _http("GET", f"{self.base}/companion/agents/dirs")
        self.assertEqual(code, 200, body)
        self.assertEqual(body["recent"], [str(Path(self.tmp.name).resolve())])
        code, body = _http("GET", f"{self.base}/companion/agents/dirs?path=/")
        self.assertEqual(code, 403)
        self.assertEqual(body["error"], "cwd_denied")

    def test_revoke_kills_live_sessions(self):
        device = self.state.pairing.approve(self.state.pairing.issue("coder"))
        _http("POST", f"{self.base}/companion/agents/sessions", {"tool": "shell", "cwd": self.tmp.name})
        self.assertEqual(self.state.agents.health()["live"], 1)
        _http("POST", f"{self.base}/companion/device/revoke", {"device_id": device.device_id})
        self.assertEqual(self.state.agents.health()["live"], 0)


if __name__ == "__main__":
    unittest.main()


class FakePopen:
    """Scripted subprocess: `script(argv, stdin_lines)` yields stdout lines; stdin writes are recorded.

    For Claude the script is a generator that reads user/control lines via `self.inbox` and emits
    responses; for Codex the script runs once per process with the argv."""

    def __init__(self, argv, cwd=None, stdin=None, stdout=None, stderr=None, text=True, bufsize=1, env=None):
        self.argv, self.cwd, self.env = list(argv), cwd, env or {}
        self.inbox: "queue.Queue[str | None]" = queue.Queue()
        self.stdin = self if stdin is not None and stdin != subprocess.DEVNULL else None
        self.returncode = None
        self.writes: list[dict] = []
        self.stdout = iter(self._lines())
        self.stderr = io.StringIO("")
        FakePopen.spawned.append(self)

    spawned: list["FakePopen"] = []
    script = staticmethod(lambda proc: iter(()))

    # stdin file-like
    def write(self, data):
        for line in data.splitlines():
            if line.strip():
                obj = json.loads(line)
                self.writes.append(obj)
                self.inbox.put(line)

    def flush(self):
        pass

    def close(self):
        self.inbox.put(None)

    def _lines(self):
        yield from FakePopen.script(self)
        self.returncode = 0 if self.returncode is None else self.returncode

    def poll(self):
        return self.returncode

    def wait(self, timeout=None):
        if self.returncode is None:
            self.returncode = 0
        return self.returncode

    def terminate(self):
        self.returncode = -15
        self.inbox.put(None)

    def kill(self):
        self.terminate()


def claude_script(proc):
    """A Claude stream-json conversation: init, then per user prompt: delta, tool (with approval), result."""
    yield json.dumps({"type": "system", "subtype": "init", "session_id": "sess-42", "model": "claude-x", "cwd": proc.cwd}) + "\n"
    while True:
        line = proc.inbox.get()
        if line is None:
            return
        obj = json.loads(line)
        if obj.get("type") == "control_response":
            resp = obj["response"]["response"]
            allowed = resp.get("behavior") == "allow"
            yield json.dumps({"type": "user", "message": {"role": "user", "content": [
                {"type": "tool_result", "tool_use_id": "tu1", "content": "hi\n" if allowed else "denied", "is_error": not allowed}]}}) + "\n"
            yield json.dumps({"type": "stream_event", "event": {"type": "content_block_delta", "delta": {"type": "text_delta", "text": "done."}}}) + "\n"
            yield json.dumps({"type": "result", "subtype": "success", "session_id": "sess-42", "total_cost_usd": 0.01, "duration_ms": 5, "result": "x"}) + "\n"
            continue
        if obj.get("type") != "user":
            continue
        text = obj["message"]["content"]
        if text.startswith("plain"):
            yield json.dumps({"type": "stream_event", "event": {"type": "content_block_delta", "delta": {"type": "text_delta", "text": "hello "}}}) + "\n"
            yield json.dumps({"type": "stream_event", "event": {"type": "content_block_delta", "delta": {"type": "text_delta", "text": "world"}}}) + "\n"
            yield json.dumps({"type": "assistant", "message": {"content": [{"type": "text", "text": "hello world"}]}}) + "\n"
            yield json.dumps({"type": "result", "subtype": "success", "session_id": "sess-42", "total_cost_usd": 0.002, "duration_ms": 3, "result": "hello world"}) + "\n"
        else:
            yield json.dumps({"type": "assistant", "message": {"content": [{"type": "tool_use", "id": "tu1", "name": "Bash", "input": {"command": "echo hi"}}]}}) + "\n"
            yield json.dumps({"type": "control_request", "request_id": "req-1", "request": {"subtype": "can_use_tool", "tool_name": "Bash", "input": {"command": "echo hi"}}}) + "\n"


def codex_script(proc):
    argv = proc.argv
    prompt = argv[-1]
    yield json.dumps({"type": "thread.started", "thread_id": "thr-7"}) + "\n"
    yield json.dumps({"type": "turn.started"}) + "\n"
    yield json.dumps({"type": "item.completed", "item": {"id": "item_0", "type": "agent_message", "text": f"ok: {prompt}"}}) + "\n"
    yield json.dumps({"type": "item.started", "item": {"id": "item_1", "type": "command_execution", "command": "/bin/bash -lc 'echo hi'", "status": "in_progress"}}) + "\n"
    yield json.dumps({"type": "item.completed", "item": {"id": "item_1", "type": "command_execution", "command": "/bin/bash -lc 'echo hi'", "aggregated_output": "hi\n", "exit_code": 0, "status": "completed"}}) + "\n"
    yield json.dumps({"type": "turn.completed", "usage": {"input_tokens": 1}}) + "\n"


def _wait_for(sessions, sid, etype, timeout=5.0, count=1):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        found = [e for e in sessions.transcript(sid) if e["type"] == etype]
        if len(found) >= count:
            return found[count - 1]
        time.sleep(0.01)
    raise AssertionError(f"no {etype} in {[e['type'] for e in sessions.transcript(sid)]}")


class StructuredSessionTests(unittest.TestCase):
    def setUp(self):
        FakePopen.spawned = []
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.root = Path(self.tmp.name)
        os.environ["HERMES_COMPANION_AGENT_ROOTS"] = str(self.root)
        self.sessions = AgentSessions(tmux=FakeTmux(), discovery=fake_discovery(("claude", "codex")), path=self.root / "agents.json")
        self.sessions.popen = FakePopen

    def tearDown(self):
        os.environ.pop("HERMES_COMPANION_AGENT_ROOTS", None)
        for s in list(self.sessions.sessions.values()):
            try:
                self.sessions.kill(s.id)
            except AgentError:
                pass
        self.tmp.cleanup()

    def test_claude_structured_turn_streams_deltas_and_result(self):
        FakePopen.script = staticmethod(claude_script)
        s = self.sessions.start("claude", mode="structured", cwd=str(self.root), prompt="plain hello")
        self.assertEqual(s.mode, "structured")
        self.assertEqual(s.public()["tmux"], "")
        argv = FakePopen.spawned[0].argv
        self.assertIn("--input-format", argv)
        self.assertIn("stream-json", argv)
        self.assertIn("--permission-prompt-tool", argv)
        self.assertIn("stdio", argv)
        end = _wait_for(self.sessions, s.id, "agent.turn.end")
        self.assertEqual(end["text"], "hello world")
        self.assertEqual(end["claude_session_id"], "sess-42")
        types = [e["type"] for e in self.sessions.transcript(s.id)]
        self.assertEqual(types.count("agent.delta"), 2, types)  # text block after deltas is not re-emitted
        self.assertIn("agent.ready", types)
        self.assertIn("agent.user", types)
        self.assertEqual(self.sessions.get(s.id).status, "waiting_input")
        self.assertIn("--resume sess-42", self.sessions.get(s.id).command)
        pane = self.sessions.pane(s.id)
        self.assertEqual(pane["ansi"], "")
        self.assertEqual(len(pane["transcript"]), len(types))
        # second prompt goes to the same process with the claude session id
        self.sessions.prompt(s.id, "plain again")
        _wait_for(self.sessions, s.id, "agent.turn.end", count=2)
        user_writes = [w for w in FakePopen.spawned[0].writes if w["type"] == "user"]
        self.assertEqual(len(user_writes), 2)
        self.assertEqual(user_writes[1]["session_id"], "sess-42")
        self.assertEqual(len(FakePopen.spawned), 1)

    def test_claude_approval_blocks_until_answered_then_allows(self):
        FakePopen.script = staticmethod(claude_script)
        s = self.sessions.start("claude", mode="structured", cwd=str(self.root))
        _wait_for(self.sessions, s.id, "agent.ready")
        self.sessions.prompt(s.id, "run something")
        appr = _wait_for(self.sessions, s.id, "agent.approval")
        self.assertEqual(appr["tool"], "Bash")
        self.assertEqual(appr["command"], "echo hi")
        self.assertEqual(self.sessions.get(s.id).status, "waiting_approval")
        self.assertEqual(self.sessions.pending_approval(s.id)["request_id"], "req-1")
        with self.assertRaises(AgentError) as cm:  # turn busy
            self.sessions.prompt(s.id, "another")
        self.assertEqual(cm.exception.code, "turn_active")
        self.assertTrue(self.sessions.approve(s.id, "req-1", "once"))
        end = _wait_for(self.sessions, s.id, "agent.turn.end")
        self.assertEqual(end["text"], "done.")
        resp = [w for w in FakePopen.spawned[0].writes if w["type"] == "control_response"][0]
        self.assertEqual(resp["response"]["request_id"], "req-1")
        self.assertEqual(resp["response"]["response"]["behavior"], "allow")
        types = [e["type"] for e in self.sessions.transcript(s.id)]
        self.assertIn("agent.tool.start", types)
        done = [e for e in self.sessions.transcript(s.id) if e["type"] == "agent.tool.complete"][0]
        self.assertEqual(done["detail"], "hi\n")
        self.assertFalse(done["error"])
        self.assertIsNone(self.sessions.pending_approval(s.id))
        self.assertFalse(self.sessions.approve(s.id, "req-1", "once"))

    def test_claude_approval_deny_and_kill(self):
        FakePopen.script = staticmethod(claude_script)
        s = self.sessions.start("claude", mode="structured", cwd=str(self.root), prompt="run it")
        _wait_for(self.sessions, s.id, "agent.approval")
        self.sessions.approve(s.id, "req-1", "deny")
        resolved = _wait_for(self.sessions, s.id, "agent.approval.resolved")
        self.assertEqual(resolved["decision"], "deny")
        done = _wait_for(self.sessions, s.id, "agent.tool.complete")
        self.assertTrue(done["error"])
        _wait_for(self.sessions, s.id, "agent.turn.end")
        self.sessions.kill(s.id)
        exited = _wait_for(self.sessions, s.id, "agent.exit")
        self.assertEqual(self.sessions.get(s.id).status, "exited")
        self.assertIn(exited["type"], ("agent.exit",))
        with self.assertRaises(AgentError):
            self.sessions.prompt(s.id, "more")

    def test_codex_structured_uses_exec_then_resume(self):
        FakePopen.script = staticmethod(codex_script)
        s = self.sessions.start("codex", mode="structured", cwd=str(self.root), prompt="first task")
        end = _wait_for(self.sessions, s.id, "agent.turn.end")
        self.assertEqual(end["text"], "ok: first task")
        self.assertEqual(end["claude_session_id"], "thr-7")
        first = FakePopen.spawned[0].argv
        self.assertEqual(first[1:3], ["exec", "--json"])
        self.assertNotIn("resume", first)
        self.assertEqual(first[-1], "first task")
        types = [e["type"] for e in self.sessions.transcript(s.id)]
        self.assertIn("agent.tool.start", types)
        self.assertIn("agent.tool.complete", types)
        self.assertEqual(self.sessions.get(s.id).status, "waiting_input")
        self.sessions.prompt(s.id, "second task")
        _wait_for(self.sessions, s.id, "agent.turn.end", count=2)
        second = FakePopen.spawned[1].argv
        self.assertIn("resume", second)
        self.assertEqual(second[second.index("resume") + 1], "thr-7")
        self.assertEqual(second[-1], "second task")
        self.assertIn("codex resume thr-7", self.sessions.get(s.id).command)
        self.assertIsNone(self.sessions.pending_approval(s.id))

    def test_shutdown_kills_structured_but_keeps_tmux(self):
        FakePopen.script = staticmethod(claude_script)
        s = self.sessions.start("claude", mode="structured", cwd=str(self.root))
        _wait_for(self.sessions, s.id, "agent.ready")
        t = self.sessions.start("shell", cwd=str(self.root))
        self.assertEqual(self.sessions.shutdown(), 1)
        self.assertEqual(self.sessions.get(s.id).status, "exited")
        self.assertNotEqual(self.sessions.get(t.id).status, "exited")
        self.assertTrue(self.sessions.tmux.has(t.tmux))
        self.assertEqual(self.sessions.procs, {})

    def test_structured_unsupported_tool_and_shell(self):
        with self.assertRaises(AgentError) as cm:
            self.sessions.start("shell", mode="structured", cwd=str(self.root))
        self.assertEqual(cm.exception.code, "mode_unsupported")


class StructuredRelayTests(unittest.TestCase):
    def setUp(self):
        FakePopen.spawned = []
        FakePopen.script = staticmethod(claude_script)
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.root = Path(self.tmp.name)
        self.env = patch.dict(os.environ, {"HERMES_WORKSPACE": self.tmp.name, "HERMES_COMPANION_STANDALONE": "1"}, clear=False)
        self.env.start()
        agents = AgentSessions(tmux=FakeTmux(), discovery=fake_discovery(("claude",)), path=self.root / "agents.json")
        agents.popen = FakePopen
        self.state = RelayState(pairing=PairingStore(), agents=agents)
        self.server = make_server("127.0.0.1:0", "http://127.0.0.1:1", self.state)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.state.agents.kill_all()
        self.env.stop()
        self.tmp.cleanup()

    def _url(self, path):
        return f"http://127.0.0.1:{self.port}{path}"

    def test_prompt_transcript_and_approval_routes(self):
        code, body = _http("POST", self._url("/companion/agents/sessions"), {"tool": "claude", "mode": "structured", "cwd": str(self.root)})
        self.assertEqual(code, 201, body)
        sid = body["session"]["id"]
        _wait_for(self.state.agents, sid, "agent.ready")
        code, body = _http("POST", self._url(f"/companion/agents/sessions/{sid}/prompt"), {"text": "run it"})
        self.assertEqual(code, 202, body)
        _wait_for(self.state.agents, sid, "agent.approval")
        code, body = _http("GET", self._url(f"/companion/agents/sessions/{sid}/transcript"))
        self.assertEqual(code, 200)
        self.assertEqual(body["approval"]["request_id"], "req-1")
        self.assertEqual(body["session"]["status"], "waiting_approval")
        n = len(body["events"])
        code, body = _http("POST", self._url(f"/companion/agents/sessions/{sid}/approval"), {"request_id": "req-1", "decision": "once"})
        self.assertEqual(code, 200, body)
        _wait_for(self.state.agents, sid, "agent.turn.end")
        code, body = _http("GET", self._url(f"/companion/agents/sessions/{sid}/transcript?after={n}"))
        self.assertTrue(any(e["type"] == "agent.turn.end" for e in body["events"]))
        self.assertTrue(all(e["type"] != "agent.ready" for e in body["events"]))  # `after` skips replayed rows
        code, body = _http("POST", self._url(f"/companion/agents/sessions/{sid}/approval"), {"request_id": "nope", "decision": "once"})
        self.assertEqual(code, 409)
        code, body = _http("POST", self._url(f"/companion/agents/sessions/{sid}/prompt"), {"text": ""})
        self.assertEqual(code, 400)
