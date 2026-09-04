from __future__ import annotations

import base64
import json
import os
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
        }
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


if __name__ == "__main__":
    unittest.main()
