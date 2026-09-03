"""Hermes Companion plugin — mobile toolset + pairing store."""

from __future__ import annotations

from broker import Broker
from pairing import PairingStore
from schemas import CLICK, PRESS, SNAPSHOT, STATUS, TYPE
from tools import TOOLSET, make_handlers

pairing_store = PairingStore()
broker = Broker()
HANDLERS = make_handlers(broker)


def register(ctx):
    for schema in (STATUS, SNAPSHOT, CLICK, TYPE, PRESS):
        ctx.register_tool(
            name=schema["name"],
            toolset=TOOLSET,
            schema=schema,
            handler=HANDLERS[schema["name"]],
        )
