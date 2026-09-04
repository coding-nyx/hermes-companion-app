from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import tickets as tickets_mod
from pairing import PairingStore
from tickets import TicketError, TicketStore, parse_subprotocols


class TicketTests(unittest.TestCase):
    def test_mint_consume_once(self):
        store = TicketStore(now=lambda: 10.0)
        issued = store.mint("dev_ab", "coder", ["device.snapshot", "device.root"])
        self.assertEqual(issued.capabilities, ["device.snapshot"])
        store.consume(issued.ticket)
        with self.assertRaises(TicketError) as ctx:
            store.consume(issued.ticket)
        self.assertEqual(ctx.exception.code, "reused_ticket")

    def test_expired(self):
        clock = {"t": 0.0}
        store = TicketStore(now=lambda: clock["t"])
        issued = store.mint("dev_ab", "coder", ["device.noop"])
        clock["t"] = tickets_mod.TTL_SEC + 1
        with self.assertRaises(TicketError) as ctx:
            store.consume(issued.ticket)
        self.assertEqual(ctx.exception.code, "expired_ticket")

    def test_subprotocol_not_query(self):
        header = "hermes-mobile-control-v1, hermes-mobile-control-ticket.dt-aa"
        has_v1, ticket = parse_subprotocols(header)
        self.assertTrue(has_v1)
        self.assertEqual(ticket, "dt-aa")

    def test_register_requires_device_cred(self):
        pairs = PairingStore(now=lambda: 1.0)
        device = pairs.approve(pairs.issue("coder"))
        self.assertEqual(device.profile, "coder")
        self.assertTrue(device.credential)
        self.assertNotEqual(device.credential, "nope")


if __name__ == "__main__":
    unittest.main()
