from __future__ import annotations

import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
import threading

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from broker import Broker, BrokerError, MockDevice
from live import resolve_device_id
from pairing import PairingStore
from tools import bind_stores, make_handlers


class FakeState:
    def __init__(self, lanes, pairing=None):
        self.lanes = {k: object() for k in lanes}
        self.lock = threading.Lock()
        self.pairing = pairing
        self.live_meta = {}


class LiveResolveTests(unittest.TestCase):
    def test_single_device_auto(self):
        state = FakeState(["dev_a"])
        self.assertEqual(resolve_device_id(state), "dev_a")

    def test_ambiguous_without_default(self):
        pairing = PairingStore(now=lambda: 1.0)
        a = pairing.approve(pairing.issue("ops"))
        b = pairing.approve(pairing.issue("ops"))
        pairing.rename(a.device_id, "S22")
        pairing.rename(b.device_id, "Pixel")
        pairing.default_device_id = None
        state = FakeState([a.device_id, b.device_id], pairing)
        with self.assertRaises(BrokerError) as ctx:
            resolve_device_id(state)
        self.assertEqual(ctx.exception.code, "ambiguous_device")

    def test_default_and_name_resolve(self):
        pairing = PairingStore(now=lambda: 1.0)
        a = pairing.approve(pairing.issue("ops"))
        b = pairing.approve(pairing.issue("ops"))
        pairing.rename(a.device_id, "S22")
        pairing.set_default(a.device_id)
        state = FakeState([a.device_id, b.device_id], pairing)
        self.assertEqual(resolve_device_id(state), a.device_id)
        self.assertEqual(resolve_device_id(state, "S22"), a.device_id)

    def test_mobile_devices_tool(self):
        pairing = PairingStore(now=lambda: 1.0)
        a = pairing.approve(pairing.issue("ops"))
        pairing.rename(a.device_id, "S22")
        state = FakeState([a.device_id], pairing)
        bind_stores(pairing, state)
        handlers = make_handlers(Broker(device=MockDevice(armed=True)))
        import json
        payload = json.loads(handlers["mobile_devices"]({}))
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["devices"][0]["name"], "S22")


if __name__ == "__main__":
    unittest.main()
