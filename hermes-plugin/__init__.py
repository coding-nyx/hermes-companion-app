"""Hermes Companion plugin — mobile toolset + pairing store."""

from __future__ import annotations

import os
from pathlib import Path

try:
    from .broker import Broker
    from .pairing import PairingStore, default_store_path
    from .schemas import APPS, ARM, CLICK, DEVICES, DISARM, NOTIFICATIONS, NOTIFICATIONS_INJECT, OPEN, PRESS, SCREENSHOT, SCROLL, SELECT_DEVICE, SNAPSHOT, STATUS, SWIPE, TYPE, WAIT
    from .tools import TOOLSET, armed_hint, bind_stores, make_handlers
except ImportError:  # script/tests on sys.path
    from broker import Broker
    from pairing import PairingStore, default_store_path
    from schemas import APPS, ARM, CLICK, DEVICES, DISARM, NOTIFICATIONS, NOTIFICATIONS_INJECT, OPEN, PRESS, SCREENSHOT, SCROLL, SELECT_DEVICE, SNAPSHOT, STATUS, SWIPE, TYPE, WAIT
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
        "mobile_notifications_inject. "
        "Arm-first: call mobile_arm before snapshot/gestures (and before long replies) so the phone "
        "does not lock while you plan. Status/devices/select/notifications stay allowed while disarmed; control "
        f"ops refuse with disarmed until armed. {hint}{stream_hint} Never use banking/authenticator/Settings. "
        "Fail closed: no_device, disarmed, a11y_unavailable, protected_package."
    )


def register(ctx):
    for schema in (DEVICES, SELECT_DEVICE, STATUS, ARM, DISARM, SNAPSHOT, CLICK, TYPE, PRESS, SWIPE, SCROLL, OPEN, APPS, WAIT, SCREENSHOT, NOTIFICATIONS, NOTIFICATIONS_INJECT):
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
    if os.environ.get("HERMES_COMPANION_RELAY", "1") != "0":
        try:
            try:
                from .relay import CompanionHandler, RelayState, start_background
                from .live import attach_inprocess
            except ImportError:
                from relay import CompanionHandler, RelayState, start_background
                from live import attach_inprocess

            start_background(state=RelayState(pairing=pairing_store))
            bind_stores(pairing_store, CompanionHandler.state)
            attach_inprocess(broker, CompanionHandler.state)
        except OSError:
            try:
                from .live import attach_http
            except ImportError:
                from live import attach_http

            attach_http(broker)
    else:
        try:
            from .live import attach_http
        except ImportError:
            from live import attach_http

        attach_http(broker)
