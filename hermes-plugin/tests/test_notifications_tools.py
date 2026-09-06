from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from unittest.mock import MagicMock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from pairing import PairingStore
from relay import RelayState
from tools import bind_stores, make_handlers
from broker import Broker


class NotificationToolsTests(unittest.TestCase):
    def setUp(self):
        self.pairing = PairingStore(now=lambda: 1.0)
        # approve a fake device
        self.pairing.offer("ABCDEF", "coder")
        device = self.pairing.approve("ABCDEF")
        self.device = device
        self.state = RelayState(pairing=self.pairing)
        self.state.push_notification(
            device.device_id,
            {
                "device_id": device.device_id,
                "profile": "coder",
                "ts_ms": 42,
                "notification": {
                    "key": "k42",
                    "package": "com.example",
                    "title": "Hello",
                    "text": "World",
                },
            },
        )
        bind_stores(self.pairing, self.state)
        self.handlers = make_handlers(Broker())

    def test_mobile_notifications_lists_ring(self):
        raw = self.handlers["mobile_notifications"]({"limit": 5})
        payload = json.loads(raw)
        self.assertTrue(payload["ok"])
        self.assertGreaterEqual(payload["count"], 1)
        self.assertEqual(payload["notifications"][0]["notification"]["title"], "Hello")

    def test_inject_into_active_session(self):
        sess = self.state.operator.create_session("coder", title="t")
        raw = self.handlers["mobile_notifications_inject"](
            {"text": "Mom called", "notification_key": "k42", "profile": "coder", "session_id": sess["id"]}
        )
        payload = json.loads(raw)
        self.assertTrue(payload["ok"], payload)
        msgs = self.state.operator.messages(sess["id"], "coder")
        self.assertTrue(any("Mom called" in str(m.get("content")) for m in msgs))
        # dedupe
        raw2 = self.handlers["mobile_notifications_inject"](
            {"text": "again", "notification_key": "k42", "profile": "coder", "session_id": sess["id"]}
        )
        self.assertFalse(json.loads(raw2)["ok"])


if __name__ == "__main__":
    unittest.main()
