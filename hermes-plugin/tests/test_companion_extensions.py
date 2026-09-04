from __future__ import annotations

import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fs_explorer import list_dir_tree, read_file_content
from git_workspace import get_git_branches, get_git_diff, get_git_status
from host_metrics import collect_metrics
from relay import RelayState, make_server
from terminal_pty import PtySession, execute_quick_command


class CompanionExtensionsTest(unittest.TestCase):
    def test_collect_metrics(self):
        result = collect_metrics()
        self.assertTrue(result["ok"])
        metrics = result["metrics"]
        self.assertIn("cpu", metrics)
        self.assertIn("memory", metrics)
        self.assertIn("disk", metrics)
        self.assertIn("system", metrics)
        self.assertGreater(metrics["memory"]["total_bytes"], 0)
        self.assertGreaterEqual(metrics["cpu"]["percent"], 0.0)

    def test_git_workspace_queries(self):
        # Test against current repo directory
        repo_dir = str(Path(__file__).resolve().parents[2])
        status = get_git_status(repo_dir)
        self.assertTrue(status["ok"])
        self.assertIn("branch", status)
        self.assertIsInstance(status["staged_files"], list)

        diff = get_git_diff(repo_dir)
        self.assertTrue(diff["ok"])
        self.assertIn("raw_diff", diff)

        branches = get_git_branches(repo_dir)
        self.assertTrue(branches["ok"])
        self.assertIn("current", branches)

    def test_fs_explorer(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            test_file = Path(tmpdir) / "hello.txt"
            test_file.write_text("Hello from Hermes!")
            sub_dir = Path(tmpdir) / "subdir"
            sub_dir.mkdir()
            (sub_dir / "nested.txt").write_text("Nested content")

            # List root
            tree = list_dir_tree(tmpdir, "")
            self.assertTrue(tree["ok"])
            names = [item["name"] for item in tree["items"]]
            self.assertIn("hello.txt", names)
            self.assertIn("subdir", names)

            # Read content
            read_res = read_file_content(tmpdir, "hello.txt")
            self.assertTrue(read_res["ok"])
            self.assertEqual(read_res["content"], "Hello from Hermes!")

            # Path traversal rejection
            traversal = read_file_content(tmpdir, "../../etc/passwd")
            self.assertFalse(traversal["ok"])

    def test_terminal_quick_exec(self):
        res = execute_quick_command("echo 'hermes-pty-test'")
        self.assertTrue(res["ok"])
        self.assertEqual(res["exit_code"], 0)
        self.assertIn("hermes-pty-test", res["stdout"])

    def test_pty_session(self):
        pty = PtySession(cols=80, rows=24)
        try:
            self.assertTrue(pty.alive)
            pty.write("echo 'terminal-live'\n")
            output = b""
            for _ in range(20):
                chunk = pty.read(timeout=0.1)
                if chunk:
                    output += chunk
                    if b"terminal-live" in output:
                        break
            self.assertIn(b"terminal-live", output)
        finally:
            pty.kill()

    def test_relay_companion_endpoints(self):
        state = RelayState()
        relay = make_server("127.0.0.1:0", "http://127.0.0.1:9119", state)
        threading.Thread(target=relay.serve_forever, daemon=True).start()
        try:
            rhost, rport = relay.server_address
            base_url = f"http://{rhost}:{rport}"

            # 1. Host metrics
            with urllib.request.urlopen(f"{base_url}/companion/host/metrics", timeout=5) as resp:
                data = json.loads(resp.read().decode())
                self.assertTrue(data["ok"])
                self.assertIn("cpu", data["metrics"])

            # 2. Git status
            with urllib.request.urlopen(f"{base_url}/companion/git/status", timeout=5) as resp:
                data = json.loads(resp.read().decode())
                self.assertTrue(data["ok"])
                self.assertIn("branch", data)

            # 3. Terminal exec
            req = urllib.request.Request(
                f"{base_url}/companion/terminal/exec",
                data=json.dumps({"cmd": "echo 'api-exec'"}).encode(),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=5) as resp:
                data = json.loads(resp.read().decode())
                self.assertTrue(data["ok"])
                self.assertIn("api-exec", data["stdout"])

            # 4. FS Tree
            with urllib.request.urlopen(f"{base_url}/companion/fs/tree", timeout=5) as resp:
                data = json.loads(resp.read().decode())
                self.assertTrue(data["ok"])
                self.assertIsInstance(data["items"], list)
        finally:
            relay.shutdown()
            relay.server_close()


if __name__ == "__main__":
    unittest.main()
