from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import pairing as pairing_mod
from pairing import PairingError, PairingStore


class PairingTests(unittest.TestCase):
    def test_approve_consumes_code(self):
        store = PairingStore(now=lambda: 100.0)
        code = store.issue("coder")
        device = store.approve(code)
        self.assertEqual(device.profile, "coder")
        self.assertTrue(device.device_id.startswith("dev_"))
        with self.assertRaises(PairingError):
            store.approve(code)

    def test_expired_code_rejected(self):
        clock = {"t": 0.0}
        store = PairingStore(now=lambda: clock["t"])
        code = store.issue("default")
        clock["t"] = pairing_mod.TTL_SEC + 1
        with self.assertRaises(PairingError):
            store.approve(code)

    def test_revoke(self):
        store = PairingStore(now=lambda: 1.0)
        device = store.approve(store.issue("ops"))
        store.revoke(device.device_id)
        self.assertNotIn(device.device_id, store.devices)


if __name__ == "__main__":
    unittest.main()
