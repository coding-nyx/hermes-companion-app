from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import _isolate  # noqa: F401,E402

from broker import ALLOWLIST, L0_NO_A11Y, L0_PASSIVE, Broker, MockDevice
from tools import make_handlers


class SuperchargedToolsTest(unittest.TestCase):
    def test_new_actions_in_allowlists(self):
        new_actions = {
            "device.telemetry",
            "device.gates.get",
            "device.gates.set",
            "device.shell.exec",
            "device.power.wake",
            "device.keyguard.dismiss",
            "device.shizuku.connect",
        }
        for action in new_actions:
            self.assertIn(action, ALLOWLIST)
            self.assertIn(action, L0_NO_A11Y)

        passive_actions = {
            "device.telemetry",
            "device.gates.get",
            "device.power.wake",
            "device.keyguard.dismiss",
            "device.shizuku.connect",
        }
        for action in passive_actions:
            self.assertIn(action, L0_PASSIVE)

    def test_handler_dispatches_correct_device_actions(self):
        calls = []

        class RecordingDevice(MockDevice):
            def execute(self, action, arguments, **kwargs):
                calls.append((action, arguments))
                if action == "device.telemetry":
                    return {"battery": {"level": 90}, "power": {"interactive": True}}
                if action == "device.gates.get":
                    return {"backend": "hybrid", "target": "agent"}
                if action == "device.gates.set":
                    return {"backend": "shizuku", "target": "agent"}
                if action == "device.shell.exec":
                    return {"exit_code": 0, "stdout": "ok", "stderr": ""}
                if action == "device.power.wake":
                    return {"screen_on": True}
                if action == "device.keyguard.dismiss":
                    return {"dismissed": True, "locked": False}
                if action == "device.shizuku.connect":
                    return {"connected": True, "bound": True, "running": True}
                return super().execute(action, arguments, **kwargs)

        device = RecordingDevice(armed=True)
        broker = Broker(device=device)
        handlers = make_handlers(broker)

        # 1. Telemetry
        res = json.loads(handlers["mobile_telemetry"]({"include_media": True}))
        self.assertTrue(res["ok"])
        self.assertEqual(calls[-1][0], "device.telemetry")

        # 2. Gate Get
        res = json.loads(handlers["mobile_gate_get"]({}))
        self.assertTrue(res["ok"])
        self.assertEqual(calls[-1][0], "device.gates.get")

        # 3. Gate Set
        res = json.loads(handlers["mobile_gate_set"]({"backend": "shizuku"}))
        self.assertTrue(res["ok"])
        self.assertEqual(calls[-1][0], "device.gates.set")

        # 4. Shell
        res = json.loads(handlers["mobile_shell"]({"command": "svc wifi enable"}))
        self.assertTrue(res["ok"])
        self.assertEqual(calls[-1][0], "device.shell.exec")
        self.assertEqual(calls[-1][1]["command"], "svc wifi enable")

        # 5. Power Wake
        res = json.loads(handlers["mobile_power_wake"]({}))
        self.assertTrue(res["ok"])
        self.assertEqual(calls[-1][0], "device.power.wake")

        # 6. Keyguard Dismiss
        res = json.loads(handlers["mobile_keyguard_dismiss"]({}))
        self.assertTrue(res["ok"])
        self.assertEqual(calls[-1][0], "device.keyguard.dismiss")

        # 7. Shizuku Connect
        res = json.loads(handlers["mobile_shizuku_connect"]({}))
        self.assertTrue(res["ok"])
        self.assertEqual(calls[-1][0], "device.shizuku.connect")


if __name__ == "__main__":
    unittest.main()
