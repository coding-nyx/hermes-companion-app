"""Value-free handoff to the phone's local private-entry UI.

Never accept a secret as a tool argument, return node content, or follow this command
with an automatic snapshot. The phone owns the privacy gate and its local release.
"""

from __future__ import annotations

import json
import re
from uuid import UUID

try:
    from .broker import BrokerError
except ImportError:
    from broker import BrokerError


_PACKAGE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+")
_REF = re.compile(r"@?e[1-9][0-9]{0,3}")
_STATUSES = frozenset({"pending", "filling", "filled", "cancelled", "expired", "failed", "idle", "released"})
_ERROR_CODES = frozenset({
    "invalid_args", "invalid_secure_entry_result", "no_device", "unknown_device", "ambiguous_device",
    "lane_down", "timeout", "disarmed", "a11y_unavailable", "protected_package", "privacy_active",
    "stale_ref", "no_focus", "rate_limited", "capability_denied", "room_hands", "room_control_denied",
    "secure_entry_busy", "secure_entry_unavailable", "secure_entry_failed", "unknown_request",
    "request_expired", "request_not_found", "privacy_unavailable", "target_changed", "not_editable", "permission_denied",
})


def _uuid(value, field="request_id"):
    if not isinstance(value, str):
        raise BrokerError("invalid_args", f"{field} must be the returned UUID")
    try:
        return str(UUID(value))
    except (ValueError, AttributeError):
        raise BrokerError("invalid_args", f"{field} must be the returned UUID") from None


def make_secure_entry_handler(broker, room_guard, err):
    def mobile_secure_entry(params, **kwargs):
        del kwargs
        try:
            room_guard()
            if not isinstance(params, dict):
                raise BrokerError("invalid_args", "supply action=request, status or cancel; never supply a value")
            action = params.get("action")
            fields = {"action", "device", "package", "ref", "label", "snapshot_id"} if action == "request" else {"action", "device", "request_id"}
            if action not in {"request", "status", "cancel"} or set(params) - fields:
                raise BrokerError("invalid_args", "only request, status and cancel metadata are accepted; never supply a value")
            args = {"action": action}
            if "device" in params:
                if not isinstance(params["device"], str) or not params["device"].strip():
                    raise BrokerError("invalid_args", "device must identify the paired phone")
                args["device"] = params["device"]
            if action == "request":
                package, ref, label = (params.get(key) for key in ("package", "ref", "label"))
                if not isinstance(package, str) or not _PACKAGE.fullmatch(package):
                    raise BrokerError("invalid_args", "request requires the target Android package")
                if not isinstance(ref, str) or not _REF.fullmatch(ref):
                    raise BrokerError("invalid_args", "request requires a field ref from the latest snapshot")
                if not isinstance(label, str) or not label.strip() or len(label) > 80:
                    raise BrokerError("invalid_args", "label must be a generic field name of 1–80 characters, never its value")
                args.update(package=package, ref=ref.removeprefix("@"), label=label.strip(),
                            snapshot_id=_uuid(params.get("snapshot_id"), "snapshot_id"))
            else:
                args["request_id"] = _uuid(params.get("request_id"))
            result = broker.dispatch("device.secure_entry", args)
            # A protocol bug must not accidentally publish text, screenshots, labels,
            # a target application's error message, or any other untrusted payload.
            if not isinstance(result, dict) or result.get("status") not in _STATUSES or not isinstance(result.get("privacy_active"), bool):
                raise BrokerError("invalid_secure_entry_result", "phone returned invalid private-entry metadata")
            safe = {"status": result["status"], "privacy_active": result["privacy_active"],
                    "request_id": _uuid(result.get("request_id"))}
            return json.dumps({"ok": True, "result": safe})
        except BrokerError as exc:
            # Never propagate a target application's message through this private path.
            code = exc.code if exc.code in _ERROR_CODES else "secure_entry_failed"
            return err(BrokerError(code, "private entry request could not complete"))

    return mobile_secure_entry
