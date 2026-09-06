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

# Fail-closed denylist, mirrored from domain/DeviceLanePolicy.kt (keep both in sync).
# Empty by default so Hermes Companion controls everything.
# Only caller-supplied extra blocklist rules are blocked.
PROTECTED_PACKAGES: frozenset[str] = frozenset()


def is_protected(package: str, extra=()) -> bool:
    """True when ``package`` matches a built-in or caller-supplied denylist rule."""
    pkg = (package or "").strip().lower()
    if not pkg:
        return False
    for rule in (*PROTECTED_PACKAGES, *extra):
        if rule.endswith("*"):
            prefix = rule[:-1]
            if pkg.startswith(prefix) or pkg == prefix.rstrip("."):
                return True
        elif pkg == rule:
            return True
    return False

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
    size: dict[str, int] = field(default_factory=lambda: {"w": 1080, "h": 2400})
    safe_area: dict[str, int] = field(default_factory=lambda: {"top": 104, "bottom": 68, "left": 0, "right": 0})
    handler: Callable[[str, dict[str, Any]], dict[str, Any]] | None = None

    def execute(self, action: str, arguments: dict[str, Any]) -> dict[str, Any]:
        if self.handler:
            return self.handler(action, arguments)
        if action == "device.noop":
            return {"ok": True}
        if action == "device.snapshot":
            return {
                "app": self.foreground_app,
                "size": dict(self.size),
                "safe_area": dict(self.safe_area),
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
    size: dict[str, int] = field(default_factory=lambda: {"w": 1080, "h": 2400})
    safe_area: dict[str, int] = field(default_factory=lambda: {"top": 104, "bottom": 68, "left": 0, "right": 0})

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
            if "size" in result and isinstance(result["size"], dict):
                self.size = result["size"]
            if "safe_area" in result and isinstance(result["safe_area"], dict):
                self.safe_area = result["safe_area"]
            return result
        return {"ok": True}


@dataclass
class Broker:
    device: MockDevice | LiveDevice | None = None
    extra_protected: tuple[str, ...] = field(default_factory=tuple)
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
        if action not in META and (is_protected(app, self.extra_protected) or is_protected(target, self.extra_protected)):
            raise BrokerError("protected_package", target)
        if action == "device.click" and "xy" in arguments:
            xy = arguments["xy"]
            if isinstance(xy, (list, tuple)) and len(xy) == 2:
                x, y = xy[0], xy[1]
                size = getattr(self.device, "size", {}) or {}
                safe = getattr(self.device, "safe_area", {}) or {}
                w = size.get("w", 0)
                h = size.get("h", 0)
                top = safe.get("top", 0)
                bottom = safe.get("bottom", 0)
                left = safe.get("left", 0)
                right = safe.get("right", 0)
                if (top > 0 and y < top) or \
                   (bottom > 0 and h > 0 and y > (h - bottom)) or \
                   (left > 0 and x < left) or \
                   (right > 0 and w > 0 and x > (w - right)):
                    raise BrokerError("safe_area_violation", f"click ({x}, {y}) is within system safe area")
        now = self.clock()
        self.hits = [t for t in self.hits if now - t < 1.0]
        if len(self.hits) >= RATE_PER_SEC:
            raise BrokerError("rate_limited", "more than 10 commands/sec")
        self.hits.append(now)
        result = self.device.execute(action, arguments)
        self.audit.record(action, target, True, at=now)
        return result
