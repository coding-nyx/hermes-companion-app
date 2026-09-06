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
    name: str = ""
    model: str = ""
    manufacturer: str = ""
    os_version: str = ""
    last_seen: float = 0.0
    extra_protected: tuple[str, ...] = field(default_factory=tuple)


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
    default_device_id: str | None = None
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
        if self.default_device_id is None:
            self.default_device_id = device.device_id
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
        if self.default_device_id == device_id:
            self.default_device_id = next(iter(self.devices), None)
        self.save()

    def touch(
        self,
        device_id: str,
        *,
        name: str | None = None,
        model: str | None = None,
        manufacturer: str | None = None,
        os_version: str | None = None,
        extra_protected: list[str] | tuple[str, ...] | None = None,
    ) -> Device:
        device = self.devices.get(device_id)
        if device is None:
            raise PairingError("unknown_device")
        if name is not None and str(name).strip():
            device.name = str(name).strip()
        if model is not None:
            device.model = str(model or "").strip()
        if manufacturer is not None:
            device.manufacturer = str(manufacturer or "").strip()
        if os_version is not None:
            device.os_version = str(os_version or "").strip()
        if extra_protected is not None:
            cleaned = []
            for raw in extra_protected:
                pkg = str(raw or "").strip().lower()
                if pkg:
                    cleaned.append(pkg)
            device.extra_protected = tuple(dict.fromkeys(cleaned))
        device.last_seen = self.now()
        self.save()
        return device

    def rename(self, device_id: str, name: str) -> Device:
        device = self.devices.get(device_id)
        if device is None:
            raise PairingError("unknown_device")
        clean = (name or "").strip()
        if not clean:
            raise PairingError("name_required")
        device.name = clean
        self.save()
        return device

    def set_default(self, device_id_or_name: str) -> Device:
        device = self.resolve(device_id_or_name)
        if device is None:
            raise PairingError("unknown_device")
        self.default_device_id = device.device_id
        self.save()
        return device

    def resolve(self, hint: str | None) -> Device | None:
        """Resolve by exact device_id, then case-insensitive name/alias."""
        raw = (hint or "").strip()
        if not raw:
            return None
        if raw in self.devices:
            return self.devices[raw]
        needle = raw.lower()
        matches = [d for d in self.devices.values() if (d.name or "").lower() == needle]
        if len(matches) == 1:
            return matches[0]
        return None

    def display_name(self, device: Device) -> str:
        if device.name.strip():
            return device.name.strip()
        if device.model.strip():
            return device.model.strip()
        return device.device_id

    def public_list(self) -> list[dict]:
        return [self._public_row(d, lane_open=False) for d in self.devices.values()]

    def lane_descriptors(self, live_ids: list[str], live_state: dict[str, dict] | None = None) -> list[dict]:
        live_state = live_state or {}
        live_set = set(live_ids)
        rows = []
        for device_id in live_ids:
            device = self.devices.get(device_id)
            if device is None:
                rows.append(
                    {
                        "device_id": device_id,
                        "name": device_id,
                        "model": "",
                        "manufacturer": "",
                        "os_version": "",
                        "armed": bool((live_state.get(device_id) or {}).get("armed")),
                        "foreground_app": str((live_state.get(device_id) or {}).get("foreground_app") or ""),
                        "is_default": device_id == self.default_device_id,
                        "lane": True,
                        "last_seen": 0,
                    }
                )
                continue
            st = live_state.get(device_id) or {}
            rows.append(self._public_row(device, lane_open=True, live=st))
        # Also include paired-but-offline for discovery tools.
        for device in self.devices.values():
            if device.device_id in live_set:
                continue
            rows.append(self._public_row(device, lane_open=False))
        return rows

    def all_extra_protected(self) -> tuple[str, ...]:
        out: list[str] = []
        for d in self.devices.values():
            out.extend(d.extra_protected)
        return tuple(dict.fromkeys(out))

    def _public_row(self, d: Device, *, lane_open: bool, live: dict | None = None) -> dict:
        live = live or {}
        return {
            "device_id": d.device_id,
            "profile": d.profile,
            "name": self.display_name(d),
            "model": d.model,
            "manufacturer": d.manufacturer,
            "os_version": d.os_version,
            "created_at": d.created_at,
            "last_seen": d.last_seen,
            "is_default": d.device_id == self.default_device_id,
            "lane": lane_open,
            "armed": bool(live.get("armed")),
            "foreground_app": str(live.get("foreground_app") or ""),
        }

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
            extras = row.get("extra_protected") or []
            if not isinstance(extras, list):
                extras = []
            device = Device(
                device_id=str(row["device_id"]),
                profile=str(row.get("profile") or "default"),
                credential=str(row.get("credential") or ""),
                created_at=float(row.get("created_at") or 0),
                name=str(row.get("name") or ""),
                model=str(row.get("model") or ""),
                manufacturer=str(row.get("manufacturer") or ""),
                os_version=str(row.get("os_version") or ""),
                last_seen=float(row.get("last_seen") or 0),
                extra_protected=tuple(str(x).strip().lower() for x in extras if str(x).strip()),
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
        default_id = data.get("default_device_id")
        if isinstance(default_id, str) and default_id in self.devices:
            self.default_device_id = default_id
        elif self.devices and self.default_device_id not in self.devices:
            self.default_device_id = next(iter(self.devices))

    def save(self) -> None:
        if self.path is None:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "default_device_id": self.default_device_id,
            "devices": [
                {
                    "device_id": d.device_id,
                    "profile": d.profile,
                    "credential": d.credential,
                    "created_at": d.created_at,
                    "name": d.name,
                    "model": d.model,
                    "manufacturer": d.manufacturer,
                    "os_version": d.os_version,
                    "last_seen": d.last_seen,
                    "extra_protected": list(d.extra_protected),
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
