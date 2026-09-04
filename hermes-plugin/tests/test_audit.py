from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from audit import AuditLog, redact
from tools import armed_hint


class AuditTests(unittest.TestCase):
    def test_type_never_keeps_text(self):
        log = AuditLog()
        row = log.record("device.type", "com.example.fixture", True, detail="hunter2")
        self.assertEqual(row.action, "device.type")
        self.assertNotIn("hunter2", row.code)
        self.assertEqual(redact("device.type", "password123"), "")

    def test_hint_only_when_paired(self):
        self.assertIsNone(armed_hint(False, True))
        self.assertIn("DISARMED", armed_hint(True, False) or "")
        self.assertIn("ARMED", armed_hint(True, True) or "")


if __name__ == "__main__":
    unittest.main()
