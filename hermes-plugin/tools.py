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
                }
            )
        except BrokerError as exc:
            return _err(exc)

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
            payload["xy"] = [args["x"], args["y"]]
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

    return {
        "mobile_status": mobile_status,
        "mobile_snapshot": mobile_snapshot,
        "mobile_click": mobile_click,
        "mobile_type": mobile_type,
        "mobile_press": mobile_press,
    }
