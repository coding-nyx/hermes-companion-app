"""Short-code device pairing. Single consume, 10 minute TTL."""

from __future__ import annotations

import secrets
import string
import time
from dataclasses import dataclass, field

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


@dataclass
class PairingStore:
    pending: dict[str, PendingPair] = field(default_factory=dict)
    devices: dict[str, Device] = field(default_factory=dict)
    now: callable = time.time

    def issue(self, profile: str) -> str:
        self._expire()
        code = generate_code()
        while code in self.pending:
            code = generate_code()
        self.pending[code] = PendingPair(code=code, created_at=self.now(), profile=profile)
        return code

    def approve(self, code: str) -> Device:
        self._expire()
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
        return device

    def revoke(self, device_id: str) -> None:
        if device_id not in self.devices:
            raise PairingError("unknown_device")
        del self.devices[device_id]

    def _expire(self) -> None:
        cutoff = self.now() - TTL_SEC
        dead = [c for c, p in self.pending.items() if p.created_at < cutoff]
        for c in dead:
            del self.pending[c]


class PairingError(Exception):
    pass
