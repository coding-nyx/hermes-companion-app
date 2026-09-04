"""Hermes Companion plugin — mobile toolset + pairing store."""

from __future__ import annotations

import os
from pathlib import Path

from broker import Broker
from pairing import PairingStore, default_store_path
from schemas import APPS, ARM, CLICK, DISARM, OPEN, PRESS, SCREENSHOT, SCROLL, SNAPSHOT, STATUS, SWIPE, TYPE, WAIT
from tools import TOOLSET, armed_hint, make_handlers

pairing_store = PairingStore(path=default_store_path())
broker = Broker()
HANDLERS = make_handlers(broker)

_SKILL = Path(__file__).resolve().parent / "skills" / "hermes-companion" / "SKILL.md"


def _prompt(_info=None) -> str:
    paired = bool(pairing_store.devices)
    armed = bool(getattr(broker.device, "armed", False)) if broker.device else False
    hint = armed_hint(paired, armed) or (
        "Android companion plugin is loaded. Pair from the phone Device tab, then `hermes companion approve CODE`."
    )
    return (
        "Companion tools: mobile_status, mobile_arm, mobile_disarm, mobile_snapshot, "
        "mobile_click, mobile_type, mobile_swipe, mobile_scroll, mobile_press, "
        "mobile_open_app, mobile_apps, mobile_wait, mobile_screenshot. "
        f"{hint} Never use banking/authenticator/Settings. Fail closed: "
        "no_device, disarmed, a11y_unavailable, protected_package."
    )


def register(ctx):
    for schema in (STATUS, ARM, DISARM, SNAPSHOT, CLICK, TYPE, PRESS, SWIPE, SCROLL, OPEN, APPS, WAIT, SCREENSHOT):
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
        from cli import handle, setup

        ctx.register_cli_command(
            name="companion",
            help="Pair and manage the Android Hermes Companion",
            setup_fn=setup,
            handler_fn=handle,
            description="approve/list/revoke/lanes for the paired phone",
        )
    if hasattr(ctx, "register_command"):
        ctx.register_command("companion", lambda raw: HANDLERS["mobile_status"]({}), description="Android companion status")
    if os.environ.get("HERMES_COMPANION_RELAY", "1") != "0":
        try:
            from relay import CompanionHandler, RelayState, start_background
            from live import attach_inprocess

            start_background(state=RelayState(pairing=pairing_store))
            attach_inprocess(broker, CompanionHandler.state)
        except OSError:
            from live import attach_http

            attach_http(broker)
    else:
        from live import attach_http

        attach_http(broker)
