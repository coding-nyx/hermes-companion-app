"""Interactive PTY terminal session manager and command runner for Hermes Companion."""

from __future__ import annotations

import fcntl
import os
import pty
import select
import signal
import struct
import subprocess
import termios
import threading
import time


class PtySession:
    def __init__(
        self,
        shell: str | None = None,
        cols: int = 80,
        rows: int = 24,
        cwd: str | None = None,
        env: dict[str, str] | None = None,
    ):
        self.cols = cols
        self.rows = rows
        self.cwd = cwd or os.getcwd()
        self.shell = shell or os.environ.get("SHELL", "/bin/bash")
        self.master_fd: int | None = None
        self.proc: subprocess.Popen | None = None
        self.alive = False
        self.lock = threading.Lock()
        self._spawn(env)

    def _spawn(self, env_override: dict[str, str] | None):
        master, slave = pty.openpty()
        self.master_fd = master

        # Set initial terminal size
        winsize = struct.pack("HHHH", self.rows, self.cols, 0, 0)
        try:
            fcntl.ioctl(master, termios.TIOCSWINSZ, winsize)
        except OSError:
            pass

        full_env = dict(os.environ)
        full_env["TERM"] = "xterm-256color"
        full_env["COLORTERM"] = "truecolor"
        if env_override:
            full_env.update(env_override)

        try:
            self.proc = subprocess.Popen(
                [self.shell],
                preexec_fn=os.setsid,
                stdin=slave,
                stdout=slave,
                stderr=slave,
                cwd=self.cwd,
                env=full_env,
                close_fds=True,
            )
            self.alive = True
        finally:
            os.close(slave)

    def write(self, data: bytes | str) -> None:
        if not self.alive or self.master_fd is None:
            return
        if isinstance(data, str):
            data = data.encode("utf-8")
        with self.lock:
            try:
                os.write(self.master_fd, data)
            except OSError:
                self.alive = False

    def read(self, max_bytes: int = 4096, timeout: float = 0.05) -> bytes | None:
        if not self.alive or self.master_fd is None:
            return None
        r, _, _ = select.select([self.master_fd], [], [], timeout)
        if not r:
            return b""
        try:
            data = os.read(self.master_fd, max_bytes)
            if not data:
                self.alive = False
                return None
            return data
        except OSError:
            self.alive = False
            return None

    def resize(self, cols: int, rows: int) -> None:
        self.cols = cols
        self.rows = rows
        if self.master_fd is not None:
            winsize = struct.pack("HHHH", rows, cols, 0, 0)
            try:
                fcntl.ioctl(self.master_fd, termios.TIOCSWINSZ, winsize)
            except OSError:
                pass

    def kill(self) -> None:
        self.alive = False
        if self.proc is not None:
            try:
                os.killpg(os.getpgid(self.proc.pid), signal.SIGTERM)
            except (OSError, ProcessLookupError):
                pass
            try:
                self.proc.terminate()
            except OSError:
                pass
        if self.master_fd is not None:
            try:
                os.close(self.master_fd)
            except OSError:
                pass
            self.master_fd = None


def execute_quick_command(cmd: str, cwd: str | None = None, timeout: float = 15.0) -> dict:
    """Execute a one-shot command returning stdout, stderr, and exit_code."""
    try:
        proc = subprocess.run(
            cmd,
            shell=True,
            cwd=cwd or os.getcwd(),
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        return {
            "ok": proc.returncode == 0,
            "exit_code": proc.returncode,
            "stdout": proc.stdout,
            "stderr": proc.stderr,
        }
    except subprocess.TimeoutExpired:
        return {"ok": False, "exit_code": 124, "stdout": "", "stderr": "command timed out"}
    except Exception as exc:
        return {"ok": False, "exit_code": 1, "stdout": "", "stderr": str(exc)}
