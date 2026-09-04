"""In-plugin audit ring. Never stores typed text from device.type."""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass


KEEP = 50


@dataclass(frozen=True)
class AuditRow:
    action: str
    app: str
    ok: bool
    code: str = ""
    at: float = 0.0


def redact(action: str, detail: str) -> str:
    if action == "device.type":
        return ""
    return (detail or "")[:80]


class AuditLog:
    def __init__(self, keep: int = KEEP):
        self.rows: deque[AuditRow] = deque(maxlen=keep)

    def record(self, action: str, app: str, ok: bool, code: str = "", detail: str = "", at: float = 0.0) -> AuditRow:
        row = AuditRow(action=action, app=app, ok=ok, code=code or redact(action, detail), at=at)
        self.rows.append(row)
        return row
