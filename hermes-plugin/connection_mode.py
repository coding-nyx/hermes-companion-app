"""Persistent, operator-selected relay routing; never changes device permissions."""
from __future__ import annotations

import json
import os
from pathlib import Path

MODES = {"proxy", "standalone"}


def default_path() -> Path:
    return Path(os.environ.get("HERMES_HOME") or Path.home() / ".hermes") / "companion-connection.json"


class ConnectionMode:
    def __init__(self, path: Path | None = None):
        self.path = path
        self.saved = None
        if path and path.is_file():
            try:
                mode = json.loads(path.read_text())["mode"]
                if mode in MODES:
                    self.saved = mode
            except (OSError, ValueError, KeyError, TypeError):
                pass

    @property
    def mode(self) -> str:
        if self.saved:
            return self.saved
        return "proxy" if os.environ.get("HERMES_COMPANION_STANDALONE", "1").strip().lower() in ("0", "false", "no") else "standalone"

    @property
    def source(self) -> str:
        return "saved" if self.saved else "host_startup"

    def save(self, mode: str) -> None:
        if mode not in MODES:
            raise ValueError("invalid connection mode")
        if self.path:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.path.with_suffix(".tmp")
            with tmp.open("w") as f:
                os.chmod(tmp, 0o600)
                json.dump({"version": 1, "mode": mode}, f)
                f.flush()
                os.fsync(f.fileno())
            tmp.replace(self.path)
        self.saved = mode
