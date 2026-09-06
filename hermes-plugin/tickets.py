"""Single-use device-lane tickets. 30s TTL. Subprotocol only — never a query param."""

from __future__ import annotations

import secrets
import time
from dataclasses import dataclass, field

PROTOCOL = "hermes-mobile-control-v1"
TICKET_PREFIX = "hermes-mobile-control-ticket."
TTL_SEC = 30

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
        "device.notifications",
    }
)


class TicketError(Exception):
    def __init__(self, code: str, message: str = ""):
        super().__init__(message or code)
        self.code = code


@dataclass
class IssuedTicket:
    ticket: str
    device_id: str
    profile: str
    capabilities: list[str]
    created_at: float
    used: bool = False


def parse_subprotocols(header: str) -> tuple[bool, str | None]:
    parts = [p.strip() for p in (header or "").split(",") if p.strip()]
    has_v1 = PROTOCOL in parts
    ticket = None
    for part in parts:
        if part.startswith(TICKET_PREFIX):
            ticket = part[len(TICKET_PREFIX) :]
            break
    return has_v1, ticket or None


@dataclass
class TicketStore:
    tickets: dict[str, IssuedTicket] = field(default_factory=dict)
    now: callable = time.time

    def mint(self, device_id: str, profile: str, capabilities: list[str] | None = None) -> IssuedTicket:
        caps = [c for c in (capabilities or []) if c in ALLOWLIST]
        if not caps:
            caps = ["device.noop"]
        token = "dt-" + secrets.token_hex(8)
        issued = IssuedTicket(
            ticket=token,
            device_id=device_id,
            profile=profile,
            capabilities=caps,
            created_at=self.now(),
        )
        self.tickets[token] = issued
        return issued

    def consume(self, ticket: str) -> IssuedTicket:
        issued = self.tickets.get(ticket)
        if issued is None:
            raise TicketError("unknown_ticket")
        if issued.used:
            raise TicketError("reused_ticket")
        if self.now() - issued.created_at >= TTL_SEC:
            raise TicketError("expired_ticket")
        issued.used = True
        return issued
