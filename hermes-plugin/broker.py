"""Fail-closed command broker. Phone is source of truth for armed state."""

from __future__ import annotations

from dataclasses import dataclass, field
from time import monotonic
from typing import Any, Callable

try:
    from .audit import AuditLog
except ImportError:  # script/tests on sys.path
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
        # L0 (direct API) tools — do not require a11y and are not scoped to the foreground app.
        "device.audio.volume.get",
        "device.audio.volume.set",
        "device.audio.ringer",
        "device.media.sessions",
        "device.media.now_playing",
        "device.media.control",
        "device.clipboard.get",
        "device.clipboard.set",
        "device.device.info",
        # L0 stream/NLS controls.
        "device.notifications.nls",
        "device.notifications.nls.enable",
        "device.notifications.stream",
        "device.notifications.stream.set",
        # L0 notification actions.
        "device.notifications.actions",
        "device.notifications.action",
        "device.notifications.reply",
        "device.notifications.dismiss",
        "device.notifications.snooze",
        # L0 messaging / call-log / contacts.
        "device.sms.list",
        "device.sms.send",
        "device.sms.compose",
        "device.calls.log",
        "device.calls.dial",
        "device.calls.place",
        "device.contacts.search",
        # L0 voice out.
        "device.voice.speak",
        "device.voice.stop",
        "device.voice.status",
        # L2 intent handoffs and L0 read surfaces.
        "device.alarm.set",
        "device.timer.set",
        "device.media.play_search",
        "device.calendar.add",
        "device.share",
        "device.settings.open",
        "device.apps.uninstall",
        "device.calendar.list",
        "device.location.last",
        "device.battery",
        "device.power.state",
        "device.thermal",
        "device.apps.usage",
        "device.audio.dnd",
        "device.audio.dnd.set",
    }
)

# Mirror of the phone-side DeviceLanePolicy.L0_NO_A11Y set. These actions are
# not scoped to the foreground app, so the protected-package check must skip them.
L0_NO_A11Y = frozenset(
    {
        "device.audio.volume.get",
        "device.audio.volume.set",
        "device.audio.ringer",
        "device.media.sessions",
        "device.media.now_playing",
        "device.media.control",
        "device.clipboard.get",
        "device.clipboard.set",
        "device.device.info",
        "device.notifications.nls",
        "device.notifications.nls.enable",
        "device.notifications.stream",
        "device.notifications.stream.set",
        "device.notifications.actions",
        "device.notifications.action",
        "device.notifications.reply",
        "device.notifications.dismiss",
        "device.notifications.snooze",
        "device.sms.list",
        "device.sms.send",
        "device.sms.compose",
        "device.calls.log",
        "device.calls.dial",
        "device.calls.place",
        "device.contacts.search",
        "device.voice.speak",
        "device.voice.stop",
        "device.voice.status",
        "device.alarm.set",
        "device.timer.set",
        "device.media.play_search",
        "device.calendar.add",
        "device.share",
        "device.settings.open",
        "device.apps.uninstall",
        "device.calendar.list",
        "device.location.last",
        "device.battery",
        "device.power.state",
        "device.thermal",
        "device.apps.usage",
        "device.audio.dnd",
        "device.audio.dnd.set",
    }
)

# L0 actions that may run even when the device is DISARMED. Read-only / passive.
# sms.list, calls.log and contacts.search are reads but are deliberately excluded: arming is
# the user's consent, and one sms.list discloses the whole message history. clipboard.get is
# excluded too: clipboards routinely hold just-copied passwords and one-time codes.
L0_PASSIVE = frozenset(
    {
        "device.audio.volume.get",
        "device.media.sessions",
        "device.media.now_playing",
        "device.device.info",
        "device.notifications.nls",
        "device.notifications.nls.enable",
        "device.notifications.stream",
        # Listing a notification's buttons is a read; firing one is not.
        "device.notifications.actions",
        # Checking whether the phone *can* speak is harmless; speaking out loud is not.
        "device.voice.status",
        # Phone health telemetry: no personal data. calendar/location/app-usage are personal
        # and deliberately stay behind arming, like sms.list.
        "device.battery",
        "device.power.state",
        "device.thermal",
        "device.audio.dnd",
    }
)

META = frozenset({"device.arm", "device.disarm"})

# Denylist mirrored from domain/DeviceLanePolicy.kt (keep both in sync).
# Built-in set is empty; protection comes from phone-synced custom rules (extra).
PROTECTED_PACKAGES: frozenset[str] = frozenset()


def is_protected(package: str, extra=()) -> bool:
    """True when ``package`` matches a built-in (empty) or caller-supplied denylist rule."""
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
    # Optional provider merges phone-synced custom denylist rules (per register).
    extra_protected_fn: Callable[[], tuple[str, ...]] | None = None
    hits: list[float] = field(default_factory=list)
    clock: Callable[[], float] = monotonic
    audit: AuditLog = field(default_factory=AuditLog)

    def _extras(self) -> tuple[str, ...]:
        extra = list(self.extra_protected)
        if self.extra_protected_fn is not None:
            extra.extend(self.extra_protected_fn())
        # Preserve order, drop dupes.
        return tuple(dict.fromkeys(extra))

    def dispatch(self, action: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
        arguments = dict(arguments or {})
        # Optional device hint for multi-device routing (live send consumes `_device`).
        device_hint = arguments.pop("device", None)
        if device_hint is not None and str(device_hint).strip():
            arguments["_device"] = str(device_hint).strip()
        if action not in ALLOWLIST:
            raise BrokerError("capability_denied", f"{action} is not grantable")
        if self.device is None:
            raise BrokerError("no_device", "nothing paired")
        if action not in META and not self.device.armed and action not in L0_PASSIVE:
            raise BrokerError("disarmed", "device is DISARMED")
        extras = self._extras()
        app = self.device.foreground_app
        target = str(arguments.get("package") or "") or app
        # L0 actions are not scoped to the foreground app, so the protected-package check is skipped.
        if action not in META and action not in L0_NO_A11Y and (is_protected(app, extras) or is_protected(target, extras)):
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
