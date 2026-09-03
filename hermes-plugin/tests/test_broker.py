from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from broker import ALLOWLIST, Broker, BrokerError, MockDevice
from tools import make_handlers


class BrokerTests(unittest.TestCase):
    def test_fail_closed_when_unpaired(self):
        broker = Broker(device=None)
        with self.assertRaises(BrokerError) as ctx:
            broker.dispatch("device.snapshot")
        self.assertEqual(ctx.exception.code, "no_device")

    def test_fail_closed_when_disarmed(self):
        broker = Broker(device=MockDevice(armed=False))
        with self.assertRaises(BrokerError) as ctx:
            broker.dispatch("device.snapshot")
        self.assertEqual(ctx.exception.code, "disarmed")

    def test_snapshot_when_armed(self):
        broker = Broker(device=MockDevice(armed=True))
        result = broker.dispatch("device.snapshot")
        self.assertEqual(result["nodes"][0]["ref"], "e1")

    def test_protected_package(self):
        broker = Broker(
            device=MockDevice(armed=True, foreground_app="com.google.android.apps.authenticator2")
        )
        with self.assertRaises(BrokerError) as ctx:
            broker.dispatch("device.snapshot")
        self.assertEqual(ctx.exception.code, "protected_package")

    def test_unknown_action_denied(self):
        broker = Broker(device=MockDevice(armed=True))
        with self.assertRaises(BrokerError) as ctx:
            broker.dispatch("device.root")
        self.assertEqual(ctx.exception.code, "capability_denied")
        self.assertNotIn("device.root", ALLOWLIST)

    def test_tool_handler_json_error(self):
        handlers = make_handlers(Broker(device=None))
        payload = json.loads(handlers["mobile_snapshot"]({}))
        self.assertFalse(payload["ok"])
        self.assertEqual(payload["error"]["code"], "no_device")

    def test_rate_limit(self):
        t = {"n": 0.0}
        broker = Broker(device=MockDevice(armed=True), clock=lambda: t["n"])
        for _ in range(10):
            broker.dispatch("device.noop")
        with self.assertRaises(BrokerError) as ctx:
            broker.dispatch("device.noop")
        self.assertEqual(ctx.exception.code, "rate_limited")


if __name__ == "__main__":
    unittest.main()
