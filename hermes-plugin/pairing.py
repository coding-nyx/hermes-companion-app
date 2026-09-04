"""Short-code device pairing. Single consume, 10 minute TTL."""

from __future__ import annotations

import json
import os
import secrets
import string
import time
from dataclasses import dataclass, field
from pathlib import Path

CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
CODE_LEN = 6
TTL_SEC = 10 * 60


def generate_code() -> str:
    return "".join(secrets.choice(CODE_ALPHABET) for _ in range(CODE_LEN))


@dataclass
class PendingPair:
    code: str
    created_at: float
    profile: str


@dataclass
class Device:
    device_id: str
    profile: str
    credential: str
    created_at: float


def normalize_code(code: str) -> str:
    return (code or "").strip().upper().replace("-", "").replace(" ", "")


def valid_code(code: str) -> bool:
    n = normalize_code(code)
    return len(n) == CODE_LEN and all(ch in CODE_ALPHABET for ch in n)


def default_store_path() -> Path:
    home = Path(os.environ.get("HERMES_HOME") or (Path.home() / ".hermes"))
    return home / "companion-devices.json"


@dataclass
class PairingStore:
    pending: dict[str, PendingPair] = field(default_factory=dict)
    devices: dict[str, Device] = field(default_factory=dict)
    unclaimed: dict[str, Device] = field(default_factory=dict)
    now: callable = time.time
    path: Path | None = None

    def __post_init__(self):
        if self.path is not None:
            self.path = Path(self.path)
            self.load()

    def issue(self, profile: str) -> str:
        self._expire()
        code = generate_code()
        while code in self.pending or code in self.unclaimed:
            code = generate_code()
        return self.offer(code, profile)

    def offer(self, code: str, profile: str) -> str:
        self._expire()
        code = normalize_code(code)
        if not valid_code(code):
            raise PairingError("invalid_code")
        if code in self.pending or code in self.unclaimed:
            raise PairingError("code_in_use")
        self.pending[code] = PendingPair(code=code, created_at=self.now(), profile=profile)
        return code

    def approve(self, code: str) -> Device:
        self._expire()
        code = normalize_code(code)
        pending = self.pending.pop(code, None)
        if pending is None:
            raise PairingError("unknown_or_expired_code")
        device = Device(
            device_id="dev_" + secrets.token_hex(8),
            profile=pending.profile,
            credential=secrets.token_urlsafe(32),
            created_at=self.now(),
        )
        self.devices[device.device_id] = device
        self.unclaimed[code] = device
        self.save()
        return device

    def status(self, code: str) -> dict:
        self._expire()
        code = normalize_code(code)
        pending = self.pending.get(code)
        if pending is not None:
            return {"status": "pending", "profile": pending.profile}
        device = self.unclaimed.get(code)
        if device is not None:
            return {
                "status": "approved",
                "device_id": device.device_id,
                "profile": device.profile,
                "credential": device.credential,
            }
        raise PairingError("unknown_or_expired_code")

    def claim(self, code: str) -> Device:
        self._expire()
        code = normalize_code(code)
        if code in self.pending:
            raise PairingError("pending")
        device = self.unclaimed.pop(code, None)
        if device is None:
            raise PairingError("unknown_or_claimed")
        self.save()
        return device

    def revoke(self, device_id: str) -> None:
        if device_id not in self.devices:
            raise PairingError("unknown_device")
        del self.devices[device_id]
        dead = [c for c, d in self.unclaimed.items() if d.device_id == device_id]
        for c in dead:
            del self.unclaimed[c]
        self.save()

    def public_list(self) -> list[dict]:
        return [
            {
                "device_id": d.device_id,
                "profile": d.profile,
                "created_at": d.created_at,
            }
            for d in self.devices.values()
        ]

    def load(self) -> None:
        if self.path is None or not self.path.exists():
            return
        try:
            data = json.loads(self.path.read_text())
        except (OSError, json.JSONDecodeError):
            return
        self.devices.clear()
        for row in data.get("devices") or []:
            if not isinstance(row, dict) or not row.get("device_id"):
                continue
            device = Device(
                device_id=str(row["device_id"]),
                profile=str(row.get("profile") or "default"),
                credential=str(row.get("credential") or ""),
                created_at=float(row.get("created_at") or 0),
            )
            self.devices[device.device_id] = device
        self.unclaimed.clear()
        raw_unclaimed = data.get("unclaimed") or {}
        if isinstance(raw_unclaimed, dict):
            for code, row in raw_unclaimed.items():
                if not isinstance(row, dict):
                    continue
                device = self.devices.get(str(row.get("device_id") or ""))
                if device is not None:
                    self.unclaimed[normalize_code(code)] = device

    def save(self) -> None:
        if self.path is None:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "devices": [
                {
                    "device_id": d.device_id,
                    "profile": d.profile,
                    "credential": d.credential,
                    "created_at": d.created_at,
                }
                for d in self.devices.values()
            ],
            "unclaimed": {code: {"device_id": d.device_id} for code, d in self.unclaimed.items()},
        }
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(payload))
        try:
            tmp.chmod(0o600)
        except OSError:
            pass
        tmp.replace(self.path)
        try:
            self.path.chmod(0o600)
        except OSError:
            pass

    def _expire(self) -> None:
        cutoff = self.now() - TTL_SEC
        dead = [c for c, p in self.pending.items() if p.created_at < cutoff]
        for c in dead:
            del self.pending[c]


class PairingError(Exception):
    pass
