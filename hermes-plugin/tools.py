"""Tool handlers. JSON in, JSON out. Fail closed."""

from __future__ import annotations

import json

from broker import Broker, BrokerError

TOOLSET = "mobile"


def _dump(payload: dict) -> str:
    return json.dumps(payload)


def _err(exc: BrokerError) -> str:
    return _dump({"ok": False, "error": {"code": exc.code, "message": exc.message}})


def make_handlers(broker: Broker):
    def mobile_status(params, **kwargs):
        del params, kwargs
        try:
            if broker.device is None:
                raise BrokerError("no_device", "nothing paired")
            return _dump(
                {
                    "ok": True,
                    "armed": broker.device.armed,
                    "foreground_app": broker.device.foreground_app,
                    "a11y_bound": broker.device.a11y_bound,
                    "overlay": broker.device.overlay,
                    "hint": armed_hint(True, broker.device.armed),
                }
            )
        except BrokerError as exc:
            return _err(exc)

    def mobile_arm(params, **kwargs):
        del params, kwargs
        try:
            result = broker.dispatch("device.arm", {})
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    def mobile_disarm(params, **kwargs):
        del params, kwargs
        try:
            result = broker.dispatch("device.disarm", {})
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    def mobile_snapshot(params, **kwargs):
        del kwargs
        try:
            result = broker.dispatch("device.snapshot", params or {})
            return _dump({"ok": True, "result": result})
        except BrokerError as exc:
            return _err(exc)

    def mobile_click(params, **kwargs):
        del kwargs
        args = params or {}
        payload = {}
        if "ref" in args:
            payload["ref"] = args["ref"]
        if "x" in args and "y" in args:
            x, y = args["x"], args["y"]
            device = broker.device
            if device is not None:
                size = getattr(device, "size", {}) or {}
                safe = getattr(device, "safe_area", {}) or {}
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
                    return _err(BrokerError("safe_area_violation", f"click ({x}, {y}) is within system safe area"))
            payload["xy"] = [x, y]
        try:
            result = broker.dispatch("device.click", payload)
            return _dump({"ok": True, "result": result})
        except BrokerError as exc:
            return _err(exc)

    def mobile_type(params, **kwargs):
        del kwargs
        try:
            result = broker.dispatch("device.type", {"text": (params or {}).get("text", "")})
            return _dump({"ok": True, "result": result})
        except BrokerError as exc:
            return _err(exc)

    def mobile_press(params, **kwargs):
        del kwargs
        try:
            result = broker.dispatch("device.press", params or {})
            return _dump({"ok": True, "result": result})
        except BrokerError as exc:
            return _err(exc)

    def mobile_swipe(params, **kwargs):
        del kwargs
        try:
            result = broker.dispatch("device.swipe", params or {})
            return _dump({"ok": True, "result": result})
        except BrokerError as exc:
            return _err(exc)

    def mobile_scroll(params, **kwargs):
        del kwargs
        try:
            result = broker.dispatch("device.scroll", params or {})
            return _dump({"ok": True, "result": result})
        except BrokerError as exc:
            return _err(exc)

    def mobile_open_app(params, **kwargs):
        del kwargs
        pkg = (params or {}).get("package", "")
        try:
            result = broker.dispatch("device.open_app", {"package": pkg})
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    def mobile_apps(params, **kwargs):
        del params, kwargs
        try:
            result = broker.dispatch("device.apps", {})
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    def mobile_wait(params, **kwargs):
        del kwargs
        try:
            result = broker.dispatch("device.wait", params or {})
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    def mobile_screenshot(params, **kwargs):
        del kwargs
        try:
            result = broker.dispatch("device.screenshot", params or {})
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    return {
        "mobile_status": mobile_status,
        "mobile_arm": mobile_arm,
        "mobile_disarm": mobile_disarm,
        "mobile_snapshot": mobile_snapshot,
        "mobile_click": mobile_click,
        "mobile_type": mobile_type,
        "mobile_press": mobile_press,
        "mobile_swipe": mobile_swipe,
        "mobile_scroll": mobile_scroll,
        "mobile_open_app": mobile_open_app,
        "mobile_apps": mobile_apps,
        "mobile_wait": mobile_wait,
        "mobile_screenshot": mobile_screenshot,
    }


def armed_hint(paired: bool, armed: bool) -> str | None:
    if not paired:
        return None
    if armed:
        return "Android companion is ARMED. Prefer mobile_snapshot then mobile_click. Call mobile_disarm when done."
    return "Android companion is DISARMED. Call mobile_arm before gestures (requires Accessibility on the phone)."
