"""Hermes Companion plugin — mobile toolset + pairing store."""

from __future__ import annotations

import errno
import os
from pathlib import Path

try:
    from .broker import Broker
    from .pairing import PairingStore, default_store_path
    from .schemas import (
        APPS, ARM, AUDIO_RINGER, AUDIO_VOLUME_GET, AUDIO_VOLUME_SET, CLICK, CLIPBOARD_GET, CLIPBOARD_SET,
        DEVICES, DEVICE_INFO, DISARM, MEDIA_CONTROL, MEDIA_NOW_PLAYING, MEDIA_SESSIONS,
        NOTIFICATION_ACTION, NOTIFICATION_ACTIONS, NOTIFICATION_DISMISS, NOTIFICATION_REPLY,
        CALL_DIAL, CALL_LOG, CALL_PLACE, CONTACTS_SEARCH,
        NOTIFICATION_POLICY, NOTIFICATION_POLICY_SET,
        NOTIFICATION_SNOOZE, NOTIFICATIONS, NOTIFICATIONS_INJECT, NOTIFICATIONS_NLS, NOTIFICATIONS_NLS_ENABLE,
        NOTIFICATIONS_STREAM, NOTIFICATIONS_STREAM_SET, OPEN, PRESS, SCREENSHOT, SCROLL, SELECT_DEVICE,
        SMS_COMPOSE, SMS_LIST, SMS_SEND,
        SNAPSHOT, STATUS, SWIPE, TYPE,
        VOICE_SPEAK, VOICE_STATUS, VOICE_STOP, WAIT,
        ALARM_SET, APP_UNINSTALL, APP_USAGE, BATTERY, CALENDAR_ADD, CALENDAR_LIST,
        DND_GET, DND_SET, LOCATION_LAST, MEDIA_PLAY_SEARCH, POWER_STATE, SETTINGS_OPEN,
        SHARE, THERMAL, TIMER_SET,
    )
    from .tools import TOOLSET, armed_hint, bind_stores, make_handlers
except ImportError:  # script/tests on sys.path
    from broker import Broker
    from pairing import PairingStore, default_store_path
    from schemas import (
        APPS, ARM, AUDIO_RINGER, AUDIO_VOLUME_GET, AUDIO_VOLUME_SET, CLICK, CLIPBOARD_GET, CLIPBOARD_SET,
        DEVICES, DEVICE_INFO, DISARM, MEDIA_CONTROL, MEDIA_NOW_PLAYING, MEDIA_SESSIONS,
        NOTIFICATION_ACTION, NOTIFICATION_ACTIONS, NOTIFICATION_DISMISS, NOTIFICATION_REPLY,
        CALL_DIAL, CALL_LOG, CALL_PLACE, CONTACTS_SEARCH,
        NOTIFICATION_POLICY, NOTIFICATION_POLICY_SET,
        NOTIFICATION_SNOOZE, NOTIFICATIONS, NOTIFICATIONS_INJECT, NOTIFICATIONS_NLS, NOTIFICATIONS_NLS_ENABLE,
        NOTIFICATIONS_STREAM, NOTIFICATIONS_STREAM_SET, OPEN, PRESS, SCREENSHOT, SCROLL, SELECT_DEVICE,
        SMS_COMPOSE, SMS_LIST, SMS_SEND,
        SNAPSHOT, STATUS, SWIPE, TYPE,
        VOICE_SPEAK, VOICE_STATUS, VOICE_STOP, WAIT,
        ALARM_SET, APP_UNINSTALL, APP_USAGE, BATTERY, CALENDAR_ADD, CALENDAR_LIST,
        DND_GET, DND_SET, LOCATION_LAST, MEDIA_PLAY_SEARCH, POWER_STATE, SETTINGS_OPEN,
        SHARE, THERMAL, TIMER_SET,
    )
    from tools import TOOLSET, armed_hint, bind_stores, make_handlers

pairing_store = PairingStore(path=default_store_path())
broker = Broker()
broker.extra_protected_fn = pairing_store.all_extra_protected
HANDLERS = make_handlers(broker)
bind_stores(pairing_store)

_SKILL = Path(__file__).resolve().parent / "skills" / "hermes-companion" / "SKILL.md"


def _prompt(_info=None) -> str:
    paired = bool(pairing_store.devices)
    armed = bool(getattr(broker.device, "armed", False)) if broker.device else False
    hint = armed_hint(paired, armed) or (
        "Android companion plugin is loaded. Pair from the phone Device tab, then `hermes companion approve CODE`."
    )
    stream_on = False
    try:
        from .tools import _relay_state as _rs
    except ImportError:
        from tools import _relay_state as _rs
    state = _rs
    if state is not None:
        with state.lock:
            stream_on = any(bool(m.get("notifications_stream")) for m in getattr(state, "live_meta", {}).values())
    stream_hint = (
        " Phone notification stream is ON. Host may wake this profile with a companion-wake "
        "operating prompt when shade events arrive — that is not an inject and must not be parroted. "
        "On wake: immediately call mobile_notifications, briefly tell Nyx what matters from tool results, "
        "and only call mobile_notifications_inject if Nyx would want it in the active thread "
        "(default: do not inject). Never invent notification bodies."
        if stream_on
        else " Phone notification stream may be off; mobile_notifications returns recent ring if any."
    )
    return (
        "Companion tools: mobile_devices, mobile_select_device, mobile_status, mobile_arm, mobile_disarm, "
        "mobile_snapshot, mobile_click, mobile_type, mobile_swipe, mobile_scroll, mobile_press, "
        "mobile_open_app, mobile_apps, mobile_wait, mobile_screenshot, mobile_notifications, "
        "mobile_notifications_inject, "
        # L0 (direct API) — do not require a11y; preferred over a11y tree when an action maps to one of these.
        "mobile_audio_volume_get, mobile_audio_volume_set, mobile_audio_ringer, "
        "mobile_media_sessions, mobile_media_now_playing, mobile_media_control, "
        "mobile_clipboard_get, mobile_clipboard_set, mobile_device_info, "
        # L0 stream + NLS controls (open the Android NLS settings page, read/toggle the shade stream).
        "mobile_notifications_nls, mobile_notifications_nls_enable, mobile_notifications_stream, mobile_notifications_stream_set, "
        # L0 notification actions — act on a notification instead of tapping its UI.
        "mobile_notification_actions, mobile_notification_action, mobile_notification_reply, "
        "mobile_notification_dismiss, mobile_notification_snooze, "
        # Per-app notification policy: off (never reaches the host) / silent (readable, never wakes) / respond.
        "mobile_notification_policy, mobile_notification_policy_set, "
        # L0 messaging / call log / contacts.
        "mobile_sms_list, mobile_sms_send, mobile_sms_compose, mobile_calls_log, "
        "mobile_calls_dial, mobile_calls_place, mobile_contacts_search, "
        # L0 voice out: no permission, works with the screen off.
        "mobile_speak, mobile_speak_stop, mobile_speak_status. "
        "mobile_speak is the cheapest way to reach a human near the phone; if its result says "
        "audible=false the phone is muted and nobody heard it. "
        # L2 intent handoffs (put an app on screen) and L0 read surfaces.
        "mobile_alarm_set, mobile_timer_set, mobile_media_play_search, mobile_calendar_add, "
        "mobile_share, mobile_settings_open, mobile_app_uninstall, mobile_calendar_list, "
        "mobile_location_last, mobile_battery, mobile_power_state, mobile_thermal, "
        "mobile_app_usage, mobile_dnd, mobile_dnd_set. "
        "Check mobile_power_state before a snapshot run: driving UI with the screen off does nothing. "
        "mobile_settings_open is the honest answer when the platform will not let you change something. "
        "To message or call someone: mobile_contacts_search for the number, then "
        "mobile_sms_compose or mobile_calls_dial (no permission, user confirms on screen). "
        "mobile_sms_send and mobile_calls_place act without confirmation on a real person — "
        "confirm recipient and wording with the user first. "
        "To act on a notification: mobile_notifications for a key, mobile_notification_actions to see its "
        "buttons, then mobile_notification_action for a plain button or mobile_notification_reply for a text one. "
        "mobile_notification_reply sends a real message to a real person — confirm the wording with the user first. "
        "Arm-first: call mobile_arm before snapshot/gestures (and before long replies) so the phone "
        "does not lock while you plan. Loop: snapshot, tap with text= or @eN, use the snapshot returned on "
        "the gesture (old refs die), click a field before mobile_type, wait after open_app. "
        "Status/devices/select/notifications stay allowed while disarmed; control and personal-data reads "
        "(including clipboard, SMS, call log, contacts, calendar, location and app usage) "
        f"ops refuse with disarmed until armed. {hint}{stream_hint} Never use banking/authenticator/Settings. "
        "Fail closed: no_device, disarmed, a11y_unavailable, protected_package, stale_ref, no_match, no_focus. "
        "Rooms: a user turn starting with `[room` puts you in a live group chat with other profiles — reply in "
        "plain text when you have something to add, ask others with `@GLYPH`, reach the operator with `@YOU`, reply "
        "exactly `PASS` when you have nothing to add; the room goes quiet when everyone passes. Only the room's "
        "hands holder may use mobile_* control tools during a room turn (the relay refuses others with room_hands)."
    )


def register(ctx):
    for schema in (
        DEVICES, SELECT_DEVICE, STATUS, ARM, DISARM, SNAPSHOT, CLICK, TYPE, PRESS, SWIPE, SCROLL,
        OPEN, APPS, WAIT, SCREENSHOT, NOTIFICATIONS, NOTIFICATIONS_INJECT,
        # L0 (direct API) tools
        AUDIO_VOLUME_GET, AUDIO_VOLUME_SET, AUDIO_RINGER,
        MEDIA_SESSIONS, MEDIA_NOW_PLAYING, MEDIA_CONTROL,
        CLIPBOARD_GET, CLIPBOARD_SET, DEVICE_INFO,
        # L0 stream + NLS controls
        NOTIFICATIONS_NLS, NOTIFICATIONS_NLS_ENABLE,
        NOTIFICATIONS_STREAM, NOTIFICATIONS_STREAM_SET,
        # L0 notification actions
        NOTIFICATION_ACTIONS, NOTIFICATION_ACTION, NOTIFICATION_REPLY,
        NOTIFICATION_DISMISS, NOTIFICATION_SNOOZE,
        # Per-app notification policy
        NOTIFICATION_POLICY, NOTIFICATION_POLICY_SET,
        # L0 messaging / call log / contacts
        SMS_LIST, SMS_SEND, SMS_COMPOSE,
        CALL_LOG, CALL_DIAL, CALL_PLACE,
        CONTACTS_SEARCH,
        # L0 voice out
        VOICE_SPEAK, VOICE_STOP, VOICE_STATUS,
        # L2 intent lane
        ALARM_SET, TIMER_SET, MEDIA_PLAY_SEARCH, CALENDAR_ADD, SHARE, SETTINGS_OPEN, APP_UNINSTALL,
        # L0 read surfaces
        CALENDAR_LIST, LOCATION_LAST, BATTERY, POWER_STATE, THERMAL, APP_USAGE, DND_GET, DND_SET,
    ):
        ctx.register_tool(
            name=schema["name"],
            toolset=TOOLSET,
            schema=schema,
            handler=HANDLERS[schema["name"]],
        )
    if _SKILL.exists() and hasattr(ctx, "register_skill"):
        ctx.register_skill("hermes-companion", _SKILL, description="Drive the paired Android companion.")
    if hasattr(ctx, "register_system_prompt_section"):
        ctx.register_system_prompt_section("hermes-companion", _prompt)
    if hasattr(ctx, "register_cli_command"):
        try:
            from .cli import handle, setup
        except ImportError:
            from cli import handle, setup

        ctx.register_cli_command(
            name="companion",
            help="Pair and manage the Android Hermes Companion",
            setup_fn=setup,
            handler_fn=handle,
            description="approve/list/revoke/lanes/default/rename for paired phones",
        )
    if hasattr(ctx, "register_command"):
        ctx.register_command("companion", lambda raw: HANDLERS["mobile_status"]({}), description="Android companion status")
    def _attach_http():
        """Point the broker at whatever already owns :9120. Never fatal."""
        try:
            try:
                from .live import attach_http
            except ImportError:
                from live import attach_http
            attach_http(broker)
        except Exception as exc:  # noqa: BLE001 - registration must survive this
            print(f"companion: http attach skipped: {exc}", flush=True)

    if os.environ.get("HERMES_COMPANION_RELAY", "1") == "0":
        _attach_http()
        return
    try:
        try:
            from .relay import CompanionHandler, RelayState, port_in_use, start_background
            from .live import attach_inprocess
        except ImportError:
            from relay import CompanionHandler, RelayState, port_in_use, start_background
            from live import attach_inprocess

        if port_in_use():
            # launchd/systemd already owns :9120 — talk to it, don't log an error.
            _attach_http()
            return
        start_background(state=RelayState(pairing=pairing_store))
        bind_stores(pairing_store, CompanionHandler.state)
        attach_inprocess(broker, CompanionHandler.state)
        print("companion relay started in-process on :9120", flush=True)
    except OSError as exc:
        if getattr(exc, "errno", None) != errno.EADDRINUSE:
            print(f"companion relay bind skipped: {exc}", flush=True)
        _attach_http()
    except Exception as exc:  # noqa: BLE001
        # The tools above are already registered; nothing in the relay-attach path is
        # allowed to take them down with it. `hermes plugins doctor` runs register() with
        # network access disabled, and port_in_use() raised a non-OSError there — which
        # propagated out of register() and made the runtime discard all 37 tools
        # ("Plugin registration failed", "registrations: 0 tool(s)"). Attaching to the
        # relay is an optimisation; exposing the tools is the contract.
        print(f"companion relay attach skipped: {exc}", flush=True)
        _attach_http()
