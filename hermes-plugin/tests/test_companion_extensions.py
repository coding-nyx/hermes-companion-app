from __future__ import annotations

import contextlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.request
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import _isolate  # noqa: F401,E402  # must precede plugin imports

from fs_explorer import list_dir_tree, read_file_content
from git_workspace import get_git_branches, get_git_diff, get_git_status
from host_metrics import collect_metrics
from relay import RelayState, make_server
from terminal_pty import PtySession, execute_quick_command


@contextlib.contextmanager
def temp_git_repo():
    """A throwaway git repo with one commit.

    The git tests used to point at `Path(__file__).parents[2]`, i.e. whatever directory the
    checkout happened to sit in. That is the repo root on a dev machine but `/tmp` on a host
    the plugin was rsynced to, so `get_git_status` returned ok=False there — which is why
    these passed locally and failed on lab and hub-11. Own the fixture instead of inheriting
    the environment's.
    """
    if shutil.which("git") is None:
        raise unittest.SkipTest("git not installed")
    with tempfile.TemporaryDirectory() as tmp:
        repo = Path(tmp) / "repo"
        repo.mkdir()
        ident = ["-c", "user.email=tests@hermes.local", "-c", "user.name=Hermes Tests"]
        def git(*args):
            subprocess.run(
                ["git", *ident, *args], cwd=repo, check=True,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
        git("init", "-q")
        (repo / "README.md").write_text("hermes test fixture\n")
        git("add", "README.md")
        git("commit", "-q", "-m", "initial")
        yield str(repo)


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
        with temp_git_repo() as repo_dir:
            self._assert_git_queries(repo_dir)

    def _assert_git_queries(self, repo_dir):
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

            # Path traversal rejection (stay off system paths — plugin scanners flag those).
            traversal = read_file_content(tmpdir, "../../outside.txt")
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
        with temp_git_repo() as repo_dir:
            # /companion/git/status resolves the workspace, which on a host is not a repo.
            with patch.dict(os.environ, {"HERMES_WORKSPACE": repo_dir}, clear=False):
                self._assert_relay_endpoints()

    def _assert_relay_endpoints(self):
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

            with urllib.request.urlopen(f"{base_url}/companion/health", timeout=5) as resp:
                health = json.loads(resp.read().decode())
                self.assertEqual(health["relay"], "ok")
                self.assertIn("upstream", health)
        finally:
            relay.shutdown()
            relay.server_close()


if __name__ == "__main__":
    unittest.main()
