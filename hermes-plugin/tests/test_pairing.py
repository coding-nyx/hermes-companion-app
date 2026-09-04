from __future__ import annotations

import sys
import tempfile
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

    def test_phone_offer_then_approve_then_status(self):
        store = PairingStore(now=lambda: 50.0)
        code = store.offer("k7m-2qx", "coder")
        self.assertEqual(code, "K7M2QX")
        self.assertEqual(store.status(code)["status"], "pending")
        device = store.approve("k7m2qx")
        peek = store.status(code)
        self.assertEqual(peek["status"], "approved")
        self.assertEqual(peek["device_id"], device.device_id)
        self.assertEqual(peek["credential"], device.credential)
        claimed = store.claim(code)
        self.assertEqual(claimed.device_id, device.device_id)
        with self.assertRaises(PairingError):
            store.claim(code)

    def test_persist_roundtrip(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "companion-devices.json"
            store = PairingStore(now=lambda: 1.0, path=path)
            device = store.approve(store.issue("knight"))
            self.assertTrue(path.exists())
            listed = store.public_list()
            self.assertEqual(listed[0]["device_id"], device.device_id)
            self.assertNotIn("credential", listed[0])
            again = PairingStore(path=path)
            self.assertEqual(again.devices[device.device_id].profile, "knight")
            self.assertEqual(again.devices[device.device_id].credential, device.credential)


if __name__ == "__main__":
    unittest.main()
