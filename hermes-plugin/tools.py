"""Tool handlers. JSON in, JSON out. Fail closed."""

from __future__ import annotations

import json

try:
    from . import notif_policy
    from .broker import Broker, BrokerError
except ImportError:  # script/tests on sys.path
    import notif_policy
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


ERROR_HINTS = {
    "disarmed": "call mobile_arm first — do not guess adb/shell workarounds",
    "stale_ref": "call mobile_snapshot and use a fresh @eN (or mobile_click text=). Do not reuse old refs.",
    "protected_package": "that package is denylisted; pick another app or ask the user",
    "a11y_unavailable": "user must enable Accessibility → Installed apps → Hermes Companion; you cannot grant it",
    "no_device": "pair from the phone Device tab, then `hermes companion approve CODE`",
    "rate_limited": "wait ~200ms and retry; do not spam gestures",
    "capability_denied": "unknown or ungranted action — use a documented mobile_* tool",
    "safe_area_violation": "click a snapshot @eN or coordinates inside the content area, not the status/nav bars",
    "no_focus": "click the input @eN (or text=) first, then mobile_type",
    "no_match": "snapshot again and click a unique @eN, or pass a more specific text=",
    "click_failed": "snapshot and click an @eN; pixel taps miss often",
    "ambiguous_device": "call mobile_devices then mobile_select_device (or pass device=)",
    "lane_down": "phone lane is down — ask the user to open Companion / stay-connected",
    "nls_unavailable": "ask the user to grant notification access: Settings → Notifications → Device & app notifications → Hermes Companion",
    "policy_denied": "user must grant Do Not Disturb access in Settings before the phone will switch to silent",
    "invalid_stream": "stream must be one of music/ring/notification/alarm/system/voice_call/dtmf",
    "invalid_mode": "ringer mode must be one of normal/vibrate/silent",
    "missing_stream": "pass stream=music|ring|notification|alarm|system|voice_call",
    "missing_level": "pass level=<int 0..max> or step=<int delta>",
    "missing_session": "pass session_id=<package owning the media session>; get it from mobile_media_sessions",
    "missing_args": "required args missing for this action",
    "no_session": "no active media session for that package; call mobile_media_sessions first",
    "control_failed": "media control failed; the app may not support this transport action",
    "clipboard_read_blocked": "Android blocks background clipboard reads; snapshot and read the focused field instead of using mobile_clipboard_get",
    "clipboard_not_text": "the clipboard holds a non-text clip (image/uri); there is no text to read",
    "unknown_notification": "that notification key is unknown; call mobile_notifications first and use a key from it",
    "notification_gone": "the notification left the shade; re-read mobile_notifications",
    "no_such_action": "list the buttons with mobile_notification_actions; use mobile_notification_reply for text ones",
    "action_cancelled": "the owning app cancelled that action; open the app over the a11y lane instead",
    "action_failed": "the notification action could not be delivered",
    "arm_failed": "the phone refused to arm; check it is unlocked and Companion is running",
    "permission_denied": "the phone lacks that Android permission; the message names it — grant it in Settings > Apps > Hermes Companion > Permissions or via adb pm grant",
    "no_handler": "no app on the phone handles that intent (no messaging app or dialer installed)",
    "handoff_failed": "the app handoff could not start; the phone may be locked",
    "query_failed": "the provider query failed; the phone may have no telephony or SIM",
    "tts_unavailable": "the phone has no usable text-to-speech engine, or it failed to start; call mobile_speak_status",
    "speak_failed": "the speech engine refused the utterance",
    "text_too_long": "the text is past the 4000-char speech cap; split it and call mobile_speak per part",
    "unavailable": "the phone cannot do that right now (feature off, or no stored value yet) — the message says which",
}


def error_hint(code: str, message: str = "") -> str:
    if code == "a11y_unavailable" and "focus" in (message or "").lower():
        return ERROR_HINTS["no_focus"]
    return ERROR_HINTS.get(code, "")


def _err(exc: BrokerError) -> str:
    hint = error_hint(exc.code, exc.message)
    payload = {"ok": False, "error": {"code": exc.code, "message": exc.message}}
    if hint:
        payload["error"]["hint"] = hint
        payload["hint"] = hint
    return _dump(payload)


def find_clickable(nodes: list, query: str) -> tuple[dict | None, list[dict]]:
    """Unique node for click text=. Exact clickable, then unique substring, then any node."""
    needle = (query or "").strip().lower()
    if not needle:
        return None, []
    pool = [n for n in nodes if isinstance(n, dict)]

    def hay(node: dict) -> str:
        return str(node.get("text") or "").strip().lower()

    def pick(rows: list[dict], exact: bool) -> tuple[dict | None, list[dict]]:
        matched = [n for n in rows if hay(n) == needle] if exact else [n for n in rows if needle in hay(n)]
        if len(matched) == 1:
            return matched[0], matched
        return None, matched

    clickable = [n for n in pool if n.get("clickable")]
    for rows in (clickable, pool):
        node, matched = pick(rows, exact=True)
        if node is not None:
            return node, matched
        if len(matched) > 1:
            return None, matched
        node, matched = pick(rows, exact=False)
        if node is not None:
            return node, matched
        if len(matched) > 1:
            return None, matched
    return None, []


def _with_follow_snapshot(broker: Broker, result: dict, device: str | None = None) -> dict:
    """Attach a fresh tree so the agent does not reuse stale @eN refs."""
    if not isinstance(result, dict):
        return result
    args = {"device": device} if device else {}
    try:
        tree = broker.dispatch("device.snapshot", args)
    except BrokerError:
        return result
    if not isinstance(tree, dict) or "nodes" not in tree:
        return result
    out = dict(result)
    out["snapshot"] = tree
    out["next"] = "use snapshot.nodes[].ref for the next gesture; previous @eN refs are invalid"
    return out


def _with_device(params: dict | None) -> dict:
    args = dict(params or {})
    return args



def _notifications_via_http(
    *,
    device_id: str | None = None,
    limit: int | str | None = 20,
    since_ms: int | str | None = None,
    profile: str | None = None,
) -> dict:
    """Fetch notification ring from the standalone companion relay (default :9120)."""
    import os
    import urllib.error
    import urllib.parse
    import urllib.request

    base = (os.environ.get("HERMES_COMPANION_RELAY_URL") or "http://127.0.0.1:9120").rstrip("/")
    qs: dict[str, str] = {"limit": str(int(limit or 20))}
    if device_id:
        qs["device_id"] = str(device_id)
    if profile:
        qs["profile"] = str(profile)
    if since_ms not in (None, ""):
        qs["since_ms"] = str(int(since_ms))
    url = f"{base}/companion/device/notifications?{urllib.parse.urlencode(qs)}"
    try:
        with urllib.request.urlopen(url, timeout=8) as resp:
            payload = json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode() if hasattr(exc, "read") else ""
        try:
            body = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            body = {}
        err = body.get("error") if isinstance(body, dict) else None
        if isinstance(err, dict):
            raise BrokerError(str(err.get("code") or "http_error"), str(err.get("message") or exc.reason)) from exc
        raise BrokerError("no_device", f"relay http {exc.code}: {exc.reason}") from exc
    except urllib.error.URLError as exc:
        reason = getattr(exc, "reason", None) or str(exc)
        raise BrokerError("no_device", f"relay unavailable ({reason})") from exc
    if not isinstance(payload, dict) or not payload.get("ok", True):
        err = (payload or {}).get("error") if isinstance(payload, dict) else None
        if isinstance(err, dict):
            raise BrokerError(str(err.get("code") or "relay_error"), str(err.get("message") or "relay error"))
        raise BrokerError("no_device", "relay unavailable")
    rows = payload.get("notifications") or []
    return {
        "ok": True,
        "device_id": device_id or payload.get("device_id"),
        "profile": profile or payload.get("profile"),
        "notifications": rows,
        "count": int(payload.get("count") if payload.get("count") is not None else len(rows)),
        "source": "http",
    }


_GUARD_CACHE: dict = {"at": 0.0, "value": None}


def _current_profile() -> str:
    try:
        try:
            from .hermes_store import _profile_id, hermes_home
        except ImportError:
            from hermes_store import _profile_id, hermes_home
        return _profile_id(hermes_home())
    except Exception:
        return ""


def _room_guard() -> None:
    """Rooms (A22.9): while this profile is taking a room turn, only the room's hands holder may drive
    the phone. Asks the relay; fails open when the relay cannot be reached (mobile control would be
    dead without it anyway), fails closed when it answers `allowed: false`."""
    import os
    import time
    import urllib.parse
    import urllib.request

    now = time.monotonic()
    if _GUARD_CACHE["value"] is not None and now - _GUARD_CACHE["at"] < 2.0:
        verdict = _GUARD_CACHE["value"]
    else:
        profile = _current_profile()
        if not profile:
            return
        base = (os.environ.get("HERMES_COMPANION_RELAY_URL") or "http://127.0.0.1:9120").rstrip("/")
        try:
            with urllib.request.urlopen(f"{base}/companion/rooms/guard?{urllib.parse.urlencode({'profile': profile})}", timeout=1.5) as resp:
                verdict = json.loads(resp.read().decode() or "{}")
        except Exception:
            return
        _GUARD_CACHE.update(at=now, value=verdict)
    if isinstance(verdict, dict) and verdict.get("in_room_turn") and not verdict.get("allowed", True):
        holder = verdict.get("hands") or "nobody"
        raise BrokerError("room_hands", f"you are in a room turn; only {holder} may drive the phone here")


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
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
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
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        del kwargs
        try:
            result = broker.dispatch("device.snapshot", _with_device(params))
            return _dump({"ok": True, "result": result})
        except BrokerError as exc:
            return _err(exc)

    def mobile_click(params, **kwargs):
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        del kwargs
        args = _with_device(params)
        payload = {}
        if "device" in args:
            payload["device"] = args["device"]
        if "ref" in args:
            payload["ref"] = args["ref"]
        text = str(args.get("text") or "").strip()
        if text and "ref" not in payload and not ("x" in args and "y" in args):
            try:
                tree = broker.dispatch("device.snapshot", {"device": args["device"]} if "device" in args else {})
            except BrokerError as extra:
                return _err(extra)
            nodes = tree.get("nodes") if isinstance(tree, dict) else None
            node, matched = find_clickable(nodes or [], text)
            if node is None:
                candidates = [
                    {
                        "ref": n.get("ref"),
                        "text": n.get("text"),
                        "role": n.get("role"),
                        "clickable": n.get("clickable"),
                    }
                    for n in matched[:8]
                ]
                hint = error_hint("no_match")
                return _dump(
                    {
                        "ok": False,
                        "error": {
                            "code": "no_match",
                            "message": f"no unique clickable node matching {text!r}",
                            "hint": hint,
                            "candidates": candidates,
                        },
                        "hint": hint,
                        "snapshot": tree,
                    }
                )
            payload["ref"] = node.get("ref")
            payload["text"] = text
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
            return _dump({"ok": True, "result": _with_follow_snapshot(broker, result, args.get("device"))})
        except BrokerError as exc:
            return _err(exc)

    def mobile_type(params, **kwargs):
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        del kwargs
        args = _with_device(params)
        try:
            result = broker.dispatch("device.type", {"text": args.get("text", ""), **({"device": args["device"]} if "device" in args else {})})
            return _dump({"ok": True, "result": _with_follow_snapshot(broker, result, args.get("device"))})
        except BrokerError as exc:
            return _err(exc)

    def mobile_press(params, **kwargs):
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        del kwargs
        args = _with_device(params)
        try:
            result = broker.dispatch("device.press", args)
            return _dump({"ok": True, "result": _with_follow_snapshot(broker, result, args.get("device"))})
        except BrokerError as exc:
            return _err(exc)

    def mobile_swipe(params, **kwargs):
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        del kwargs
        args = _with_device(params)
        try:
            result = broker.dispatch("device.swipe", args)
            return _dump({"ok": True, "result": _with_follow_snapshot(broker, result, args.get("device"))})
        except BrokerError as exc:
            return _err(exc)

    def mobile_scroll(params, **kwargs):
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        del kwargs
        args = _with_device(params)
        try:
            result = broker.dispatch("device.scroll", args)
            return _dump({"ok": True, "result": _with_follow_snapshot(broker, result, args.get("device"))})
        except BrokerError as exc:
            return _err(exc)

    def mobile_open_app(params, **kwargs):
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        del kwargs
        args = _with_device(params)
        pkg = args.get("package", "")
        payload = {"package": pkg}
        if "device" in args:
            payload["device"] = args["device"]
        try:
            result = broker.dispatch("device.open_app", payload)
            if isinstance(result, dict):
                result = dict(result)
                result["next"] = "call mobile_wait with 800-1500ms, then mobile_snapshot — the UI has not settled yet"
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
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        del kwargs
        try:
            result = broker.dispatch("device.wait", _with_device(params))
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    def mobile_screenshot(params, **kwargs):
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        del kwargs
        try:
            result = broker.dispatch("device.screenshot", _with_device(params))
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    # ---------- L0 (direct API) tools ----------

    def mobile_audio_volume_get(params, **kwargs):
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        del kwargs
        args = _with_device(params)
        try:
            result = broker.dispatch("device.audio.volume.get", args)
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    def mobile_audio_volume_set(params, **kwargs):
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        del kwargs
        args = _with_device(params)
        if "level" not in args and "step" not in args:
            return _err(BrokerError("missing_level", "pass level=<0..max> or step=<delta>"))
        stream = (args.get("stream") or "").strip()
        if not stream:
            return _err(BrokerError("missing_stream", "stream=music|ring|notification|alarm|system|voice_call"))
        try:
            result = broker.dispatch("device.audio.volume.set", args)
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    def mobile_audio_ringer(params, **kwargs):
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        del kwargs
        args = _with_device(params)
        try:
            result = broker.dispatch("device.audio.ringer", args)
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    def mobile_media_sessions(params, **kwargs):
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        del kwargs
        args = _with_device(params)
        try:
            result = broker.dispatch("device.media.sessions", args)
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    def mobile_media_now_playing(params, **kwargs):
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        del kwargs
        args = _with_device(params)
        if not (args.get("session_id") or "").strip():
            return _err(BrokerError("missing_session", "session_id required (package name)"))
        try:
            result = broker.dispatch("device.media.now_playing", args)
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    def mobile_media_control(params, **kwargs):
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        del kwargs
        args = _with_device(params)
        if not (args.get("session_id") or "").strip():
            return _err(BrokerError("missing_session", "session_id required (package name)"))
        if not (args.get("action") or "").strip():
            return _err(BrokerError("missing_args", "action=play|pause|toggle|next|prev|stop|seek"))
        try:
            result = broker.dispatch("device.media.control", args)
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    def mobile_clipboard_get(params, **kwargs):
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        del kwargs
        args = _with_device(params)
        try:
            result = broker.dispatch("device.clipboard.get", args)
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    def mobile_clipboard_set(params, **kwargs):
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        del kwargs
        args = _with_device(params)
        if "text" not in args:
            return _err(BrokerError("missing_args", "text required"))
        try:
            result = broker.dispatch("device.clipboard.set", args)
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    def mobile_device_info(params, **kwargs):
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        del kwargs
        args = _with_device(params)
        try:
            result = broker.dispatch("device.device.info", args)
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    def mobile_notifications_nls(params, **kwargs):
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        del kwargs
        args = _with_device(params)
        try:
            result = broker.dispatch("device.notifications.nls", args)
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    def mobile_notifications_nls_enable(params, **kwargs):
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        del kwargs
        args = _with_device(params)
        try:
            result = broker.dispatch("device.notifications.nls.enable", args)
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    def mobile_notifications_stream(params, **kwargs):
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        del kwargs
        args = _with_device(params)
        try:
            result = broker.dispatch("device.notifications.stream", args)
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)

    def mobile_notifications_stream_set(params, **kwargs):
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        del kwargs
        args = _with_device(params)
        # Empty-string origin/profile clear the override; treat absence as "leave alone".
        if "origin" in args and (args.get("origin") or "").strip() == "":
            args["origin"] = ""
        if "profile" in args and (args.get("profile") or "").strip() == "":
            args["profile"] = ""
        if "enabled" not in args and "origin" not in args and "profile" not in args:
            return _err(BrokerError("missing_args", "pass enabled=true|false and/or origin=… and/or profile=…"))
        try:
            result = broker.dispatch("device.notifications.stream.set", args)
            return _dump({"ok": True, "result": result})
        except BrokerError as extra:
            return _err(extra)


    # ---------- L0 notification actions ----------

    def _notif_act(action, params, *, need=()):
        """Shared shape for the notification action tools: room guard, key check, dispatch."""
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        args = _with_device(params)
        if not str(args.get("key") or "").strip():
            return _err(BrokerError("missing_args", "key required; get one from mobile_notifications"))
        for field in need:
            if args.get(field) in (None, ""):
                return _err(BrokerError("missing_args", f"{field} required"))
        try:
            return _dump({"ok": True, "result": broker.dispatch(action, args)})
        except BrokerError as extra:
            return _err(extra)

    def mobile_notification_actions(params, **kwargs):
        del kwargs
        return _notif_act("device.notifications.actions", params)

    def mobile_notification_action(params, **kwargs):
        del kwargs
        return _notif_act("device.notifications.action", params, need=("index",))

    def mobile_notification_reply(params, **kwargs):
        del kwargs
        return _notif_act("device.notifications.reply", params, need=("text",))

    def mobile_notification_dismiss(params, **kwargs):
        del kwargs
        return _notif_act("device.notifications.dismiss", params)

    def mobile_notification_snooze(params, **kwargs):
        del kwargs
        return _notif_act("device.notifications.snooze", params, need=("duration_ms",))

    # ---------- L0 messaging / call log / contacts ----------

    def _dispatch(action, params, *, need=()):
        """Guard, require args, dispatch. Shared by the messaging and call-log tools."""
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        args = _with_device(params)
        for field in need:
            if not str(args.get(field) or "").strip():
                return _err(BrokerError("missing_args", f"{field} required"))
        try:
            return _dump({"ok": True, "result": broker.dispatch(action, args)})
        except BrokerError as extra:
            return _err(extra)

    def mobile_sms_list(params, **kwargs):
        del kwargs
        return _dispatch("device.sms.list", params)

    def mobile_sms_send(params, **kwargs):
        del kwargs
        return _dispatch("device.sms.send", params, need=("address", "body"))

    def mobile_sms_compose(params, **kwargs):
        del kwargs
        return _dispatch("device.sms.compose", params, need=("address",))

    def mobile_calls_log(params, **kwargs):
        del kwargs
        return _dispatch("device.calls.log", params)

    def mobile_calls_dial(params, **kwargs):
        del kwargs
        return _dispatch("device.calls.dial", params, need=("address",))

    def mobile_calls_place(params, **kwargs):
        del kwargs
        return _dispatch("device.calls.place", params, need=("address",))

    def mobile_contacts_search(params, **kwargs):
        del kwargs
        return _dispatch("device.contacts.search", params)

    # ---------- L2 intent lane + L0 read surfaces ----------

    def mobile_alarm_set(params, **kwargs):
        del kwargs
        return _dispatch("device.alarm.set", params, need=("hour", "minute",))

    def mobile_timer_set(params, **kwargs):
        del kwargs
        return _dispatch("device.timer.set", params, need=("seconds",))

    def mobile_media_play_search(params, **kwargs):
        del kwargs
        return _dispatch("device.media.play_search", params, need=("query",))

    def mobile_calendar_add(params, **kwargs):
        del kwargs
        return _dispatch("device.calendar.add", params, need=("title", "begin_ms",))

    def mobile_share(params, **kwargs):
        del kwargs
        return _dispatch("device.share", params, need=("text",))

    def mobile_settings_open(params, **kwargs):
        del kwargs
        return _dispatch("device.settings.open", params, need=("page",))

    def mobile_app_uninstall(params, **kwargs):
        del kwargs
        return _dispatch("device.apps.uninstall", params, need=("package",))

    def mobile_calendar_list(params, **kwargs):
        del kwargs
        return _dispatch("device.calendar.list", params)

    def mobile_location_last(params, **kwargs):
        del kwargs
        return _dispatch("device.location.last", params)

    def mobile_battery(params, **kwargs):
        del kwargs
        return _dispatch("device.battery", params)

    def mobile_power_state(params, **kwargs):
        del kwargs
        return _dispatch("device.power.state", params)

    def mobile_thermal(params, **kwargs):
        del kwargs
        return _dispatch("device.thermal", params)

    def mobile_app_usage(params, **kwargs):
        del kwargs
        return _dispatch("device.apps.usage", params)

    def mobile_dnd(params, **kwargs):
        del kwargs
        return _dispatch("device.audio.dnd", params)

    def mobile_dnd_set(params, **kwargs):
        del kwargs
        return _dispatch("device.audio.dnd.set", params, need=("mode",))

    # ---------- L0 voice out ----------

    def mobile_speak(params, **kwargs):
        del kwargs
        return _dispatch("device.voice.speak", params, need=("text",))

    def mobile_speak_stop(params, **kwargs):
        del kwargs
        return _dispatch("device.voice.stop", params)

    def mobile_speak_status(params, **kwargs):
        del kwargs
        return _dispatch("device.voice.status", params)

    # ---------- per-app notification policy (host-local; no device dispatch) ----------

    def _phone_rules_for(args):
        """The paired device's own per-app rules, so the read can show both layers."""
        if _pairing_store is None:
            return ()
        hint = args.get("device")
        device = None
        try:
            if hint:
                device = _pairing_store.resolve(str(hint))
            elif _pairing_store.default_device_id:
                device = _pairing_store.devices.get(_pairing_store.default_device_id)
        except Exception:
            device = None
        if device is None:
            devices = list(getattr(_pairing_store, "devices", {}).values())
            device = devices[0] if len(devices) == 1 else None
        return tuple(getattr(device, "extra_protected", ()) or ())

    def mobile_notification_policy(params, **kwargs):
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        del kwargs
        args = dict(params or {})
        try:
            return _dump({
                "ok": True,
                "result": notif_policy.snapshot(
                    profile=args.get("profile"),
                    phone_off=_phone_rules_for(args),
                ),
            })
        except Exception as exc:  # policy file unreadable etc.
            return _err(BrokerError("policy_error", str(exc)))

    def mobile_notification_policy_set(params, **kwargs):
        try:
            _room_guard()
        except BrokerError as guard_err:
            return _err(guard_err)
        del kwargs
        args = dict(params or {})
        package = str(args.get("package") or "").strip()
        mode = str(args.get("mode") or "").strip().lower()
        if not package:
            return _err(BrokerError("missing_args", "package required"))
        if not mode:
            return _err(BrokerError("missing_args", "mode=off|silent|respond|default"))
        try:
            notif_policy.set_mode(
                package,
                None if mode in ("default", "clear", "") else mode,
                profile=args.get("profile"),
            )
        except ValueError as exc:
            return _err(BrokerError("invalid_mode", str(exc)))
        except Exception as exc:
            return _err(BrokerError("policy_error", str(exc)))
        phone_off = _phone_rules_for(args)
        result = notif_policy.describe(
            package, profile=args.get("profile"), phone_off=phone_off
        )
        if result.get("unreachable_override"):
            # Say so rather than reporting a mode that will never be observed.
            result["warning"] = (
                "the phone suppresses this package at source, so this host rule cannot take "
                "effect; clear it in the phone's Device tab to let the app through"
            )
        return _dump({"ok": True, "result": result})

    def mobile_notifications(params, **kwargs):
        del kwargs
        try:
            state = _relay_state
            pairing = _pairing_store
            args = dict(params or {})
            device_id = None
            hint = args.get("device")
            if hint and pairing is not None:
                try:
                    device_id = pairing.resolve(str(hint)).device_id
                except Exception:
                    device_id = str(hint)
            elif pairing is not None and pairing.default_device_id:
                device_id = pairing.default_device_id
            elif state is not None:
                with state.lock:
                    ids = list(state.lanes) or list(getattr(state, "notif_rings", {}))
                if len(ids) == 1:
                    device_id = ids[0]
            limit = args.get("limit", 20)
            since = args.get("since_ms")
            profile = str(args.get("profile") or "").strip() or None
            if state is not None:
                rows = state.list_notifications(
                    device_id=device_id, limit=limit, since_ms=since, profile=profile
                )
                return _dump(
                    {
                        "ok": True,
                        "device_id": device_id,
                        "profile": profile,
                        "notifications": rows,
                        "count": len(rows),
                        "source": "inprocess",
                    }
                )
            # Detached CLI / gateway when companion owns :9120 — read ring over HTTP.
            return _dump(_notifications_via_http(device_id=device_id, limit=limit, since_ms=since, profile=profile))
        except BrokerError as exc:
            return _err(exc)

    def mobile_notifications_inject(params, **kwargs):
        del kwargs
        try:
            state = _relay_state
            if state is None:
                raise BrokerError("no_device", "relay unavailable")
            pairing = _pairing_store
            args = dict(params or {})
            text = str(args.get("text") or "").strip()
            if not text:
                raise BrokerError("text_required", "text required")
            key = str(args.get("notification_key") or "").strip()
            if key and not state.mark_injected(key):
                return _dump({"ok": False, "error": {"code": "already_injected", "message": "notification_key already injected"}})
            profile = str(args.get("profile") or "").strip()
            device_id = None
            hint = args.get("device")
            if pairing is not None:
                try:
                    if hint:
                        device = pairing.resolve(str(hint))
                    elif pairing.default_device_id:
                        device = pairing.devices.get(pairing.default_device_id)
                    else:
                        device = None
                    if device is not None:
                        device_id = device.device_id
                        if not profile:
                            profile = getattr(device, "profile", "") or ""
                except Exception as exc:
                    return _err(BrokerError("unknown_device", str(exc)))
            if not profile:
                raise BrokerError("no_session", "profile required for inject")
            sid = str(args.get("session_id") or "").strip()
            op = getattr(state, "operator", None)
            if op is None:
                raise BrokerError("no_session", "operator unavailable")
            if not sid:
                sid = op.active_session_id(profile) or ""
            if not sid:
                raise BrokerError("no_session", "no active session for profile")
            note = text if text.startswith("[phone notification]") else f"[phone notification] {text}"
            try:
                result = op.inject_context(sid, profile, note, role="user")
            except Exception as exc:
                code = getattr(exc, "error", None) or getattr(exc, "args", ["inject_failed"])[0]
                raise BrokerError(str(code), str(exc)) from exc
            return _dump({"ok": True, "session_id": sid, "device_id": device_id, "profile": profile, "result": result})
        except BrokerError as exc:
            return _err(exc)

    return {
        "mobile_status": mobile_status,
        "mobile_notifications": mobile_notifications,
        "mobile_notifications_inject": mobile_notifications_inject,
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
        # L0 (direct API) tools
        "mobile_audio_volume_get": mobile_audio_volume_get,
        "mobile_audio_volume_set": mobile_audio_volume_set,
        "mobile_audio_ringer": mobile_audio_ringer,
        "mobile_media_sessions": mobile_media_sessions,
        "mobile_media_now_playing": mobile_media_now_playing,
        "mobile_media_control": mobile_media_control,
        "mobile_clipboard_get": mobile_clipboard_get,
        "mobile_clipboard_set": mobile_clipboard_set,
        "mobile_device_info": mobile_device_info,
        # L0 stream + NLS controls.
        "mobile_notifications_nls": mobile_notifications_nls,
        "mobile_notifications_nls_enable": mobile_notifications_nls_enable,
        "mobile_notifications_stream": mobile_notifications_stream,
        "mobile_notifications_stream_set": mobile_notifications_stream_set,
        # L0 notification actions.
        "mobile_notification_actions": mobile_notification_actions,
        "mobile_notification_action": mobile_notification_action,
        "mobile_notification_reply": mobile_notification_reply,
        "mobile_notification_dismiss": mobile_notification_dismiss,
        "mobile_notification_snooze": mobile_notification_snooze,
        # Per-app notification policy (host config).
        "mobile_notification_policy": mobile_notification_policy,
        "mobile_notification_policy_set": mobile_notification_policy_set,
        # L0 messaging / call log / contacts.
        "mobile_sms_list": mobile_sms_list,
        "mobile_sms_send": mobile_sms_send,
        "mobile_sms_compose": mobile_sms_compose,
        "mobile_calls_log": mobile_calls_log,
        "mobile_calls_dial": mobile_calls_dial,
        "mobile_calls_place": mobile_calls_place,
        "mobile_contacts_search": mobile_contacts_search,
        # L0 voice out.
        "mobile_speak": mobile_speak,
        "mobile_speak_stop": mobile_speak_stop,
        "mobile_speak_status": mobile_speak_status,
        "mobile_alarm_set": mobile_alarm_set,
        "mobile_timer_set": mobile_timer_set,
        "mobile_media_play_search": mobile_media_play_search,
        "mobile_calendar_add": mobile_calendar_add,
        "mobile_share": mobile_share,
        "mobile_settings_open": mobile_settings_open,
        "mobile_app_uninstall": mobile_app_uninstall,
        "mobile_calendar_list": mobile_calendar_list,
        "mobile_location_last": mobile_location_last,
        "mobile_battery": mobile_battery,
        "mobile_power_state": mobile_power_state,
        "mobile_thermal": mobile_thermal,
        "mobile_app_usage": mobile_app_usage,
        "mobile_dnd": mobile_dnd,
        "mobile_dnd_set": mobile_dnd_set,
    }


def armed_hint(paired: bool, armed: bool) -> str | None:
    if not paired:
        return None
    if armed:
        return (
            "Android companion is ARMED. Loop: mobile_snapshot → tap @eN or mobile_click text= → "
            "use the snapshot returned on the gesture (old refs are dead). Click the field before "
            "mobile_type. After mobile_open_app, wait 800-1500ms then snapshot. mobile_disarm when done."
        )
    return (
        "Android companion is DISARMED. Call mobile_arm before snapshot/gestures "
        "(and arm early to avoid lock-screen during long replies). Requires Accessibility on the phone."
    )
