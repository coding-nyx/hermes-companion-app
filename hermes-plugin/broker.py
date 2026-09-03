"""Fail-closed command broker. Phone is source of truth for armed state."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable

ALLOWLIST = frozenset(
    {
        "device.noop",
        "device.snapshot",
        "device.click",
        "device.type",
        "device.swipe",
        "device.scroll",
        "device.press",
        "device.open_app",
        "device.apps",
        "device.wait",
        "device.screenshot",
    }
)

PROTECTED_PACKAGES = frozenset(
    {
        "com.google.android.apps.authenticator2",
        "com.android.vending",
        "com.android.settings",
    }
)

RATE_PER_SEC = 10


class BrokerError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass
class MockDevice:
    armed: bool = False
    foreground_app: str = "com.example.fixture"
    handler: Callable[[str, dict[str, Any]], dict[str, Any]] | None = None

    def execute(self, action: str, arguments: dict[str, Any]) -> dict[str, Any]:
        if self.handler:
            return self.handler(action, arguments)
        if action == "device.noop":
            return {"ok": True}
        if action == "device.snapshot":
            return {
                "app": self.foreground_app,
                "nodes": [
                    {
                        "ref": "e1",
                        "role": "button",
                        "text": "Submit",
                        "clickable": True,
                        "bounds": [80, 400, 1000, 496],
                    }
                ],
            }
        if action == "device.click":
            return {"clicked": arguments.get("ref") or arguments.get("xy")}
        return {"ok": True, "action": action}


@dataclass
class Broker:
    device: MockDevice | None = None
    hits: list[float] = field(default_factory=list)
    clock: Callable[[], float] = lambda: 0.0

    def dispatch(self, action: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
        arguments = arguments or {}
        if action not in ALLOWLIST:
            raise BrokerError("capability_denied", f"{action} is not grantable")
        if self.device is None:
            raise BrokerError("no_device", "nothing paired")
        if not self.device.armed:
            raise BrokerError("disarmed", "device is DISARMED")
        app = self.device.foreground_app
        if app in PROTECTED_PACKAGES:
            raise BrokerError("protected_package", app)
        now = self.clock()
        self.hits = [t for t in self.hits if now - t < 1.0]
        if len(self.hits) >= RATE_PER_SEC:
            raise BrokerError("rate_limited", "more than 10 commands/sec")
        self.hits.append(now)
        return self.device.execute(action, arguments)
