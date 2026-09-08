from __future__ import annotations

import base64
import json
import os
import sqlite3
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from media import MediaStore
from relay import RelayState, make_server
from standalone import Operator, TINY_PNG


class StandaloneOperatorTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.state_path = Path(self.tmp.name) / "op.json"
        self.media_root = Path(self.tmp.name) / "media"
        self.env = {
            "HERMES_COMPANION_STANDALONE": "1",
            "HERMES_COMPANION_STATE": str(self.state_path),
            "HERMES_COMPANION_MEDIA": str(self.media_root),
            "HERMES_HOME": str(Path(self.tmp.name) / "empty-hermes"),
        }
        Path(self.env["HERMES_HOME"]).mkdir(parents=True, exist_ok=True)
        self.patch = patch.dict(os.environ, self.env, clear=False)
        self.patch.start()
        state = RelayState(operator=Operator(self.state_path), media=MediaStore(self.media_root))
        self.relay = make_server("127.0.0.1:0", "http://127.0.0.1:1", state)
        threading.Thread(target=self.relay.serve_forever, daemon=True).start()
        host, port = self.relay.server_address
        self.origin = f"http://{host}:{port}"

    def tearDown(self):
        self.relay.shutdown()
        self.relay.server_close()
        self.patch.stop()
        self.tmp.cleanup()

    def _json(self, method: str, path: str, payload=None, status=200):
        data = None if payload is None else json.dumps(payload).encode()
        req = urllib.request.Request(
            self.origin + path,
            data=data,
            headers={"Content-Type": "application/json"} if data else {},
            method=method,
        )
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                body = json.loads(resp.read().decode() or "{}")
                self.assertEqual(resp.status, status)
                return body
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode()
            try:
                body = json.loads(raw)
            except json.JSONDecodeError:
                body = {"error": raw}
            if exc.code != status:
                self.fail(f"{method} {path} -> {exc.code} {body}, expected {status}")
            return body

    def test_status_profiles_sessions_without_dashboard(self):
        status = self._json("GET", "/api/status")
        self.assertFalse(status["auth_required"])
        self.assertTrue(status["gateway_running"])
        profiles = self._json("GET", "/api/profiles")["profiles"]
        self.assertTrue(any(p["id"] == "default" for p in profiles))
        sessions = self._json("GET", "/api/sessions?profile=default")["sessions"]
        self.assertTrue(sessions)
        sid = sessions[0]["id"]
        messages = self._json("GET", f"/api/sessions/{sid}/messages?profile=default")["messages"]
        self.assertGreaterEqual(len(messages), 2)
        self.assertIn("standalone", messages[1]["content"])

    def test_create_stream_delete(self):
        created = self._json("POST", "/api/sessions?profile=coder", {"title": "lab"}, status=201)["session"]
        sid = created["id"]
        req = urllib.request.Request(
            f"{self.origin}/api/sessions/{sid}/chat/stream?profile=coder",
            data=json.dumps({"input": "hello table"}).encode(),
            headers={"Content-Type": "application/json", "Accept": "text/event-stream"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            raw = resp.read().decode()
        self.assertIn("assistant.delta", raw)
        self.assertIn("run.completed", raw)
        titled = self._json("GET", "/api/sessions?profile=coder")["sessions"]
        self.assertEqual(titled[0]["title"], "lab")
        self._json("DELETE", f"/api/sessions/{sid}?profile=coder")
        gone = self._json("GET", f"/api/sessions/{sid}/messages?profile=coder", status=404)
        self.assertEqual(gone["error"], "unknown_session")

    def test_media_upload_and_fetch(self):
        blob = base64.b64decode(TINY_PNG)
        rec = self._json(
            "POST",
            "/companion/media",
            {"filename": "dot.png", "mime": "image/png", "data": TINY_PNG},
            status=201,
        )
        self.assertTrue(rec["url"].startswith("/companion/media/"))
        with urllib.request.urlopen(self.origin + rec["url"], timeout=5) as resp:
            self.assertEqual(resp.read(), blob)
            self.assertEqual(resp.headers.get_content_type(), "image/png")

    def test_host_tools_without_dashboard(self):
        jobs = self._json("GET", "/api/cron/jobs")
        self.assertIsInstance(jobs, list)
        catalog = self._json("GET", "/api/model/options")
        self.assertIn("model", catalog)
        check = self._json("GET", "/api/hermes/update/check")
        self.assertIn("current_version", check)
        health = self._json("GET", "/companion/health")
        self.assertEqual(health["mode"], "standalone")
        self.assertEqual(health["relay"], "ok")

    def test_profiles_and_sessions_from_state_db(self):
        home = Path(self.tmp.name) / "bishop-home"
        home.mkdir()
        (home / "config.yaml").write_text("model:\n  default: gpt-5.6-terra\n  provider: openai-codex\n", encoding="utf-8")
        con = sqlite3.connect(home / "state.db")
        con.execute(
            "CREATE TABLE sessions (id TEXT, title TEXT, display_name TEXT, last_activity_at REAL, "
            "started_at REAL, ended_at REAL, end_reason TEXT, profile_name TEXT, hidden INTEGER, "
            "archived INTEGER, message_count INTEGER)"
        )
        con.execute(
            "CREATE TABLE messages (id INTEGER, session_id TEXT, role TEXT, content TEXT, timestamp REAL, active INTEGER)"
        )
        con.execute(
            "INSERT INTO sessions VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            ("20260907_sess", "bishop online", None, 1788770000.0, 1788770000.0, None, None, "bishop", 0, 0, 2),
        )
        con.execute(
            "INSERT INTO messages VALUES (?,?,?,?,?,?)",
            (1, "20260907_sess", "user", "hello bishop", 1788770000.0, 1),
        )
        con.commit()
        con.close()
        with patch.dict(os.environ, {"HERMES_HOME": str(home)}, clear=False):
            profiles = self._json("GET", "/api/profiles")["profiles"]
            # HERMES_HOME itself is Hermes' "default" profile, whatever the directory is called.
            self.assertEqual([p["id"] for p in profiles], ["default"])
            sessions = self._json("GET", "/api/sessions?profile=default")["sessions"]
            self.assertEqual(sessions[0]["id"], "20260907_sess")
            self.assertEqual(sessions[0]["title"], "bishop online")
            msgs = self._json("GET", "/api/sessions/20260907_sess/messages?profile=default")["messages"]
            self.assertEqual(msgs[0]["content"], "hello bishop")

    def test_list_sessions_newest_started_first_with_started_at_and_limit(self):
        first = self._json("POST", "/api/sessions?profile=coder", {"title": "first"}, status=201)["session"]
        op = self.relay.RequestHandlerClass.state.operator
        with op.lock:
            # Force distinct, out-of-order creation instants (creation is ms-resolution).
            for s in op.data["sessions"]:
                if s["id"] == first["id"]:
                    s["started_at"] = s["updated_at"] = 1_700_000_000_000
        second = self._json("POST", "/api/sessions?profile=coder", {"title": "second"}, status=201)["session"]
        self.assertIn("started_at", second)
        rows = self._json("GET", "/api/sessions?profile=coder")["sessions"]
        ids = [r["id"] for r in rows]
        self.assertLess(ids.index(second["id"]), ids.index(first["id"]))
        self.assertTrue(all("started_at" in r for r in rows))
        page = self._json("GET", "/api/sessions?profile=coder&limit=1")
        self.assertEqual([second["id"]], [r["id"] for r in page["sessions"]])
        self.assertEqual(page["total"], len(rows))
        nxt = self._json("GET", "/api/sessions?profile=coder&limit=1&offset=1")["sessions"]
        self.assertEqual([first["id"]], [r["id"] for r in nxt])

    def test_state_db_list_excludes_archived_before_limit_and_orders_by_started(self):
        home = Path(self.tmp.name) / "arch-home"
        home.mkdir()
        (home / "config.yaml").write_text("model: local\n", encoding="utf-8")
        con = sqlite3.connect(home / "state.db")
        con.execute("CREATE TABLE sessions (id TEXT, title TEXT, started_at REAL, last_activity_at REAL, archived INTEGER, hidden INTEGER)")
        rows = [("s-old", "old", 1788000000.0, 1788900000.0, 0, 0),
                ("s-new", "new", 1788800000.0, 1788800000.0, 0, 0)]
        rows += [(f"s-arch-{i}", "archived", 1788850000.0 + i, 1788850000.0 + i, 1, 0) for i in range(5)]
        rows += [("s-hidden", "hidden", 1788860000.0, 1788860000.0, 0, 1)]
        con.executemany("INSERT INTO sessions VALUES (?,?,?,?,?,?)", rows)
        con.commit()
        con.close()
        with patch.dict(os.environ, {"HERMES_HOME": str(home)}, clear=False):
            page = self._json("GET", "/api/sessions?profile=default&limit=2")
            sessions = page["sessions"]
            # Archived rows were filtered in SQL, so the 2-row page still holds both live threads,
            # newest *started* first even though s-old has the later activity.
            self.assertEqual([s["id"] for s in sessions], ["s-new", "s-old"])
            self.assertEqual(page["total"], 2)
            self.assertEqual(self._json("GET", "/api/sessions?profile=default&limit=1&offset=1")["sessions"][0]["id"], "s-old")
            # ARCHIVED toggle: archived rows come back flagged, hidden rows never do.
            full = self._json("GET", "/api/sessions?profile=default&archived=include")
            self.assertEqual(full["total"], 7)
            ids = [s["id"] for s in full["sessions"]]
            self.assertNotIn("s-hidden", ids)
            self.assertEqual(ids[:5], [f"s-arch-{i}" for i in range(4, -1, -1)])
            self.assertTrue(all(s["archived"] for s in full["sessions"] if s["id"].startswith("s-arch")))
            self.assertFalse(next(s for s in full["sessions"] if s["id"] == "s-new")["archived"])
            self.assertEqual(sessions[0]["started_at"], 1788800000000)
            self.assertEqual(sessions[1]["updated_at"], 1788900000000)

    def test_root_profile_listed_as_default_next_to_named_profiles(self):
        home = Path(self.tmp.name) / "mac-home"
        (home / "profiles" / "coder").mkdir(parents=True)
        (home / "config.yaml").write_text("model: MiniMax-M2.7\n", encoding="utf-8")
        (home / "profiles" / "coder" / "config.yaml").write_text("model: gpt-5\n", encoding="utf-8")
        for d, sid in ((home, "root-sess"), (home / "profiles" / "coder", "coder-sess")):
            con = sqlite3.connect(d / "state.db")
            con.execute("CREATE TABLE sessions (id TEXT, title TEXT, started_at REAL)")
            con.execute("INSERT INTO sessions VALUES (?,?,?)", (sid, sid, 1788770000.0))
            con.commit()
            con.close()
        with patch.dict(os.environ, {"HERMES_HOME": str(home)}, clear=False):
            profiles = self._json("GET", "/api/profiles")["profiles"]
            self.assertEqual([p["id"] for p in profiles], ["default", "coder"])
            self.assertEqual(profiles[0]["model"], "MiniMax-M2.7")
            root = self._json("GET", "/api/sessions?profile=default")["sessions"]
            self.assertEqual([s["id"] for s in root], ["root-sess"])
            coder = self._json("GET", "/api/sessions?profile=coder")["sessions"]
            self.assertEqual([s["id"] for s in coder], ["coder-sess"])

    def test_relay_started_inside_a_named_profile_still_lists_root_and_siblings(self):
        # hub-11 runs the relay with HERMES_HOME=<root>/profiles/bishop.
        root = Path(self.tmp.name) / "hub-home"
        for d in (root, root / "profiles" / "bishop", root / "profiles" / "ash"):
            d.mkdir(parents=True, exist_ok=True)
            (d / "config.yaml").write_text(f"model: {d.name}\n", encoding="utf-8")
        with patch.dict(os.environ, {"HERMES_HOME": str(root / "profiles" / "bishop")}, clear=False):
            profiles = self._json("GET", "/api/profiles")["profiles"]
            self.assertEqual([p["id"] for p in profiles], ["default", "ash", "bishop"])
            self.assertEqual(profiles[0]["model"], "hub-home")
            self.assertEqual(profiles[2]["model"], "bishop")

    def test_named_default_profile_dir_wins_over_root(self):
        home = Path(self.tmp.name) / "lab-home"
        (home / "profiles" / "default").mkdir(parents=True)
        (home / "config.yaml").write_text("model: root\n", encoding="utf-8")
        (home / "profiles" / "default" / "config.yaml").write_text("model: named\n", encoding="utf-8")
        with patch.dict(os.environ, {"HERMES_HOME": str(home)}, clear=False):
            profiles = self._json("GET", "/api/profiles")["profiles"]
            self.assertEqual([(p["id"], p["model"]) for p in profiles], [("default", "named")])

    def test_list_sessions_titles_from_first_user_message(self):
        created = self._json("POST", "/api/sessions?profile=coder", {}, status=201)["session"]
        self.assertEqual(created["title"], "new thread")
        sid = created["id"]
        op = self.relay.RequestHandlerClass.state.operator
        with op.lock:
            op.data["messages"][sid] = [{"id": "1", "role": "user", "content": "why is metrics 500"}]
        sessions = self._json("GET", "/api/sessions?profile=coder")["sessions"]
        row = next(s for s in sessions if s["id"] == sid)
        self.assertEqual(row["title"], "why is metrics 500")

    def test_profiles_from_minimal_state_db(self):
        home = Path(self.tmp.name) / "sparse-home"
        home.mkdir()
        (home / "config.yaml").write_text("model: local\n", encoding="utf-8")
        con = sqlite3.connect(home / "state.db")
        con.execute("CREATE TABLE sessions (id TEXT, title TEXT, started_at REAL)")
        con.execute("INSERT INTO sessions VALUES (?,?,?)", ("sess-sparse", "real work", 1788770000.0))
        con.commit()
        con.close()
        with patch.dict(os.environ, {"HERMES_HOME": str(home)}, clear=False):
            profiles = self._json("GET", "/api/profiles")["profiles"]
            self.assertEqual([p["id"] for p in profiles], ["default"])
            sessions = self._json("GET", "/api/sessions?profile=default")["sessions"]
            self.assertEqual(sessions[0]["id"], "sess-sparse")
            self.assertEqual(sessions[0]["title"], "real work")

    def test_model_options_reads_hermes_config(self):
        cfg = Path(self.tmp.name) / "hermes-home"
        cfg.mkdir()
        (cfg / "config.yaml").write_text(
            "model:\n  default: gpt-5.6-terra\n  provider: openai-codex\n",
            encoding="utf-8",
        )
        with patch.dict(os.environ, {"HERMES_HOME": str(cfg), "HERMES_MODEL": "", "HERMES_PROVIDER": ""}, clear=False):
            catalog = self._json("GET", "/api/model/options")
        self.assertEqual(catalog["model"], "gpt-5.6-terra")
        self.assertEqual(catalog["provider"], "openai-codex")
        slugs = [p["slug"] for p in catalog["providers"]]
        self.assertIn("openai-codex", slugs)
        terra = next(p for p in catalog["providers"] if p["slug"] == "openai-codex")
        self.assertIn("gpt-5.6-terra", terra["models"])


if __name__ == "__main__":
    unittest.main()
