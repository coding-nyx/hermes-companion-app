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


    def test_rename_and_default(self):
        store = PairingStore(now=lambda: 1.0)
        a = store.approve(store.issue("ops"))
        b = store.approve(store.issue("ops"))
        store.rename(a.device_id, "Galaxy S22")
        store.set_default("Galaxy S22")
        self.assertEqual(store.default_device_id, a.device_id)
        self.assertEqual(store.resolve("galaxy s22").device_id, a.device_id)
        self.assertEqual(store.display_name(a), "Galaxy S22")
        rows = store.lane_descriptors([a.device_id, b.device_id], {a.device_id: {"armed": True}})
        self.assertTrue(any(r["is_default"] and r["name"] == "Galaxy S22" for r in rows))
        store.touch(a.device_id, model="SM-S901E", manufacturer="samsung", os_version="Android 16", extra_protected=["com.mybank.app"])
        self.assertIn("com.mybank.app", store.all_extra_protected())
        self.assertEqual(store.devices[a.device_id].model, "SM-S901E")

    def test_persist_metadata_roundtrip(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "companion-devices.json"
            store = PairingStore(now=lambda: 1.0, path=path)
            device = store.approve(store.issue("knight"))
            store.rename(device.device_id, "Pixel")
            store.touch(device.device_id, model="Pixel 8", extra_protected=["com.corp.*"])
            store.set_default(device.device_id)
            again = PairingStore(path=path)
            self.assertEqual(again.devices[device.device_id].name, "Pixel")
            self.assertEqual(again.devices[device.device_id].model, "Pixel 8")
            self.assertEqual(again.default_device_id, device.device_id)
            self.assertEqual(again.devices[device.device_id].extra_protected, ("com.corp.*",))


if __name__ == "__main__":
    unittest.main()
