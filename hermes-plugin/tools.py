"""Tool handlers. JSON in, JSON out. Fail closed."""

from __future__ import annotations

import json

try:
    from .broker import Broker, BrokerError
except ImportError:  # script/tests on sys.path
    from broker import Broker, BrokerError

TOOLSET = "mobile"

# Filled by plugin __init__ so tools can list/select devices without circular imports.
_pairing_store = None
_relay_state = None


def bind_stores(pairing_store, relay_state=None) -> None:
    global _pairing_store, _relay_state
    _pairing_store = pairing_store
    _relay_state = relay_state


def _dump(payload: dict) -> str:
    return json.dumps(payload)


def _err(exc: BrokerError) -> str:
    return _dump({"ok": False, "error": {"code": exc.code, "message": exc.message}})


def _with_device(params: dict | None) -> dict:
    args = dict(params or {})
    return args


def make_handlers(broker: Broker):
    def mobile_status(params, **kwargs):
        del kwargs
        try:
            if broker.device is None:
                raise BrokerError("no_device", "nothing paired")
            # Touch the lane when a device hint is present so resolution errors surface.
            hint = (params or {}).get("device")
            if hint:
                broker.dispatch("device.noop", {"device": hint})
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

    def mobile_devices(params, **kwargs):
        del params, kwargs
        try:
            pairing = _pairing_store
            if pairing is None:
                raise BrokerError("no_device", "pairing store unavailable")
            live_ids = []
            live_meta = {}
            state = _relay_state
            if state is not None:
                with state.lock:
                    live_ids = list(state.lanes)
                    live_meta = {k: dict(v) for k, v in getattr(state, "live_meta", {}).items()}
            rows = pairing.lane_descriptors(live_ids, live_meta)
            return _dump({"ok": True, "devices": rows, "default_device_id": pairing.default_device_id})
        except BrokerError as exc:
            return _err(exc)

    def mobile_select_device(params, **kwargs):
        del kwargs
        try:
            pairing = _pairing_store
            if pairing is None:
                raise BrokerError("no_device", "pairing store unavailable")
            hint = str((params or {}).get("device") or "")
            device = pairing.set_default(hint)
            return _dump(
                {
                    "ok": True,
                    "device_id": device.device_id,
                    "name": pairing.display_name(device),
                    "is_default": True,
                }
            )
        except Exception as exc:
            # PairingError or BrokerError
            code = getattr(exc, "args", ["unknown_device"])[0] if not hasattr(exc, "code") else getattr(exc, "code", "unknown_device")
            if hasattr(exc, "code"):
                return _err(exc) if isinstance(exc, BrokerError) else _err(BrokerError(str(exc), str(exc)))
            try:
                from .pairing import PairingError
            except ImportError:
                from pairing import PairingError

            if isinstance(exc, PairingError):
                return _err(BrokerError(str(exc), str(exc)))
            return _err(BrokerError("unknown_device", str(exc)))

    def mobile_arm(params, **kwargs):
        del kwargs
        try:
            result = broker.dispatch("device.arm", _with_device(params))
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    def mobile_disarm(params, **kwargs):
        del kwargs
        try:
            result = broker.dispatch("device.disarm", _with_device(params))
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    def mobile_snapshot(params, **kwargs):
        del kwargs
        try:
            result = broker.dispatch("device.snapshot", _with_device(params))
            return _dump({"ok": True, "result": result})
        except BrokerError as exc:
            return _err(exc)

    def mobile_click(params, **kwargs):
        del kwargs
        args = _with_device(params)
        payload = {}
        if "device" in args:
            payload["device"] = args["device"]
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
        args = _with_device(params)
        try:
            result = broker.dispatch("device.type", {"text": args.get("text", ""), **({"device": args["device"]} if "device" in args else {})})
            return _dump({"ok": True, "result": result})
        except BrokerError as exc:
            return _err(exc)

    def mobile_press(params, **kwargs):
        del kwargs
        try:
            result = broker.dispatch("device.press", _with_device(params))
            return _dump({"ok": True, "result": result})
        except BrokerError as exc:
            return _err(exc)

    def mobile_swipe(params, **kwargs):
        del kwargs
        try:
            result = broker.dispatch("device.swipe", _with_device(params))
            return _dump({"ok": True, "result": result})
        except BrokerError as exc:
            return _err(exc)

    def mobile_scroll(params, **kwargs):
        del kwargs
        try:
            result = broker.dispatch("device.scroll", _with_device(params))
            return _dump({"ok": True, "result": result})
        except BrokerError as exc:
            return _err(exc)

    def mobile_open_app(params, **kwargs):
        del kwargs
        args = _with_device(params)
        pkg = args.get("package", "")
        payload = {"package": pkg}
        if "device" in args:
            payload["device"] = args["device"]
        try:
            result = broker.dispatch("device.open_app", payload)
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    def mobile_apps(params, **kwargs):
        del kwargs
        try:
            result = broker.dispatch("device.apps", _with_device(params))
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    def mobile_wait(params, **kwargs):
        del kwargs
        try:
            result = broker.dispatch("device.wait", _with_device(params))
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    def mobile_screenshot(params, **kwargs):
        del kwargs
        try:
            result = broker.dispatch("device.screenshot", _with_device(params))
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    return {
        "mobile_status": mobile_status,
        "mobile_devices": mobile_devices,
        "mobile_select_device": mobile_select_device,
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
