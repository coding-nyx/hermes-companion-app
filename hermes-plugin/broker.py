"""Fail-closed command broker. Phone is source of truth for armed state."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable

from audit import AuditLog

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
        "device.arm",
        "device.disarm",
    }
)

META = frozenset({"device.arm", "device.disarm"})

PROTECTED_PACKAGES = frozenset(
    {
        "com.google.android.apps.authenticator2",
        "com.android.vending",
        "com.android.settings",
        "com.android.systemui",
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
    a11y_bound: bool = True
    overlay: bool = False
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
        if action == "device.swipe":
            return {"swiped": True}
        if action == "device.scroll":
            return {"direction": arguments.get("direction")}
        if action == "device.apps":
            return {"apps": [{"package": "com.example.fixture", "label": "Fixture"}]}
        if action == "device.open_app":
            return {"package": arguments.get("package")}
        if action == "device.wait":
            return {"ms": min(int(arguments.get("ms") or 0), 5000)}
        if action == "device.screenshot":
            return {
                "mime": "image/png",
                "w": 1,
                "h": 1,
                "png_b64": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
            }
        if action == "device.arm":
            if not self.a11y_bound:
                raise BrokerError("a11y_unavailable", "accessibility off")
            self.armed = True
            return {"armed": True}
        if action == "device.disarm":
            self.armed = False
            return {"armed": False}
        return {"ok": True, "action": action}


@dataclass
class LiveDevice:
    """Phone-backed device. Phone is source of truth for DISARMED."""

    send: Callable[[str, dict[str, Any]], dict[str, Any]]
    armed: bool = True
    foreground_app: str = ""
    a11y_bound: bool = True
    overlay: bool = True

    def execute(self, action: str, arguments: dict[str, Any]) -> dict[str, Any]:
        result = self.send(action, arguments)
        if isinstance(result, dict):
            app = result.get("app")
            if isinstance(app, str) and app:
                self.foreground_app = app
            if "armed" in result:
                self.armed = bool(result["armed"])
            if "a11y_bound" in result:
                self.a11y_bound = bool(result["a11y_bound"])
            if "overlay" in result:
                self.overlay = bool(result["overlay"])
            return result
        return {"ok": True}


@dataclass
class Broker:
    device: MockDevice | LiveDevice | None = None
    hits: list[float] = field(default_factory=list)
    clock: Callable[[], float] = lambda: 0.0
    audit: AuditLog = field(default_factory=AuditLog)

    def dispatch(self, action: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
        arguments = arguments or {}
        if action not in ALLOWLIST:
            raise BrokerError("capability_denied", f"{action} is not grantable")
        if self.device is None:
            raise BrokerError("no_device", "nothing paired")
        if action not in META and not self.device.armed:
            raise BrokerError("disarmed", "device is DISARMED")
        app = self.device.foreground_app
        target = str(arguments.get("package") or "") or app
        if action not in META and (app in PROTECTED_PACKAGES or target in PROTECTED_PACKAGES):
            raise BrokerError("protected_package", target)
        now = self.clock()
        self.hits = [t for t in self.hits if now - t < 1.0]
        if len(self.hits) >= RATE_PER_SEC:
            raise BrokerError("rate_limited", "more than 10 commands/sec")
        self.hits.append(now)
        result = self.device.execute(action, arguments)
        self.audit.record(action, target, True, at=now)
        return result
