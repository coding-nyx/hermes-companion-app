from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from broker import ALLOWLIST, Broker, BrokerError, LiveDevice, MockDevice
from tools import error_hint, find_clickable, make_handlers


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


    def test_snapshot_disarmed_refuses_with_hint(self):
        handlers = make_handlers(Broker(device=MockDevice(armed=False)))
        payload = json.loads(handlers["mobile_snapshot"]({}))
        self.assertFalse(payload["ok"])
        self.assertEqual(payload["error"]["code"], "disarmed")
        self.assertIn("mobile_arm", payload.get("hint", "") + payload["error"].get("hint", ""))


    def test_snapshot_when_armed(self):
        broker = Broker(device=MockDevice(armed=True))
        result = broker.dispatch("device.snapshot")
        self.assertEqual(result["nodes"][0]["ref"], "e1")

    def test_builtin_denylist_empty_settings_allowed(self):
        broker = Broker(
            device=MockDevice(armed=True, foreground_app="com.android.settings")
        )
        result = broker.dispatch("device.snapshot")
        self.assertEqual(result["nodes"][0]["ref"], "e1")

    def test_custom_protects_settings(self):
        broker = Broker(
            device=MockDevice(armed=True, foreground_app="com.android.settings"),
            extra_protected=("com.android.settings",),
        )
        with self.assertRaises(BrokerError) as ctx:
            broker.dispatch("device.snapshot")
        self.assertEqual(ctx.exception.code, "protected_package")

    def test_default_allows_unlisted_apps(self):
        broker = Broker(
            device=MockDevice(armed=True, foreground_app="org.telegram.messenger")
        )
        result = broker.dispatch("device.snapshot")
        self.assertEqual(result["nodes"][0]["ref"], "e1")

    def test_protected_package_in_blocklist(self):
        broker = Broker(
            device=MockDevice(armed=True, foreground_app="com.google.android.apps.authenticator2"),
            extra_protected=("com.google.android.apps.authenticator2",),
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

    def test_swipe_and_apps_when_armed(self):
        broker = Broker(device=MockDevice(armed=True))
        swipe = broker.dispatch("device.swipe", {"x1": 10, "y1": 10, "x2": 10, "y2": 400})
        self.assertTrue(swipe["swiped"])
        apps = broker.dispatch("device.apps")
        self.assertEqual(apps["apps"][0]["package"], "com.example.fixture")
        shot = broker.dispatch("device.screenshot")
        self.assertEqual(shot["mime"], "image/png")
        self.assertNotIn("EXIF", json.dumps(shot))

    def test_open_protected_package(self):
        broker = Broker(
            device=MockDevice(armed=True, foreground_app="com.example.fixture"),
            extra_protected=("com.android.settings",),
        )
        with self.assertRaises(BrokerError) as ctx:
            broker.dispatch("device.open_app", {"package": "com.android.settings"})
        self.assertEqual(ctx.exception.code, "protected_package")

    def test_type_not_in_audit(self):
        broker = Broker(device=MockDevice(armed=True))
        broker.dispatch("device.type", {"text": "hunter2"})
        blob = " ".join(f"{r.action} {r.app} {r.code}" for r in broker.audit.rows)
        self.assertNotIn("hunter2", blob)

    def test_status_includes_permissions(self):
        handlers = make_handlers(Broker(device=MockDevice(armed=True, overlay=True)))
        payload = json.loads(handlers["mobile_status"]({}))
        self.assertTrue(payload["ok"])
        self.assertTrue(payload["a11y_bound"])
        self.assertTrue(payload["overlay"])
        self.assertIn("ARMED", payload["hint"])

    def test_live_device_uses_phone_result(self):
        def send(action, arguments):
            del arguments
            self.assertEqual(action, "device.snapshot")
            return {"app": "app.hermes.companion", "nodes": [{"ref": "e1"}]}

        broker = Broker(device=LiveDevice(send=send))
        result = broker.dispatch("device.snapshot")
        self.assertEqual(result["app"], "app.hermes.companion")
        self.assertEqual(broker.device.foreground_app, "app.hermes.companion")

    def test_host_arm_and_disarm(self):
        device = MockDevice(armed=False, a11y_bound=True)
        broker = Broker(device=device)
        armed = broker.dispatch("device.arm")
        self.assertTrue(armed["armed"])
        self.assertTrue(device.armed)
        snap = broker.dispatch("device.snapshot")
        self.assertEqual(snap["nodes"][0]["ref"], "e1")
        disarmed = broker.dispatch("device.disarm")
        self.assertFalse(disarmed["armed"])
        self.assertFalse(device.armed)
        with self.assertRaises(BrokerError) as ctx:
            broker.dispatch("device.snapshot")
        self.assertEqual(ctx.exception.code, "disarmed")

    def test_host_arm_requires_a11y(self):
        broker = Broker(device=MockDevice(armed=False, a11y_bound=False))
        with self.assertRaises(BrokerError) as ctx:
            broker.dispatch("device.arm")
        self.assertEqual(ctx.exception.code, "a11y_unavailable")

    def test_live_device_maps_disarmed(self):
        def send(action, arguments):
            del action, arguments
            raise BrokerError("disarmed", "device is DISARMED")

        broker = Broker(device=LiveDevice(send=send))
        with self.assertRaises(BrokerError) as ctx:
            broker.dispatch("device.snapshot")
        self.assertEqual(ctx.exception.code, "disarmed")

    def test_click_safe_area_status_bar_rejected(self):
        broker = Broker(device=MockDevice(armed=True))
        handlers = make_handlers(broker)
        # top safe_area is 104; y=50 is in status bar
        resp = json.loads(handlers["mobile_click"]({"x": 500, "y": 50}))
        self.assertFalse(resp["ok"])
        self.assertEqual(resp["error"]["code"], "safe_area_violation")

    def test_click_safe_area_gesture_bar_rejected(self):
        broker = Broker(device=MockDevice(armed=True))
        handlers = make_handlers(broker)
        # bottom safe_area is 68, height is 2400; y=2360 is in gesture bar
        resp = json.loads(handlers["mobile_click"]({"x": 500, "y": 2360}))
        self.assertFalse(resp["ok"])
        self.assertEqual(resp["error"]["code"], "safe_area_violation")

    def test_click_safe_area_content_allowed(self):
        broker = Broker(device=MockDevice(armed=True))
        handlers = make_handlers(broker)
        resp = json.loads(handlers["mobile_click"]({"x": 500, "y": 500}))
        self.assertTrue(resp["ok"])
        self.assertEqual(resp["result"]["clicked"], [500, 500])
        self.assertIn("nodes", resp["result"]["snapshot"])
        self.assertIn("snapshot.nodes", resp["result"]["next"])

    def test_click_by_text_resolves_unique_ref(self):
        handlers = make_handlers(Broker(device=MockDevice(armed=True)))
        resp = json.loads(handlers["mobile_click"]({"text": "Submit"}))
        self.assertTrue(resp["ok"])
        self.assertEqual(resp["result"]["clicked"], "e1")
        self.assertEqual(resp["result"]["snapshot"]["nodes"][0]["ref"], "e1")

    def test_click_by_text_ambiguous_returns_candidates(self):
        def handler(action, arguments):
            del arguments
            if action == "device.snapshot":
                return {
                    "app": "com.example.fixture",
                    "nodes": [
                        {"ref": "e1", "role": "button", "text": "Send", "clickable": True},
                        {"ref": "e2", "role": "button", "text": "Send", "clickable": True},
                    ],
                }
            return {"clicked": "nope"}

        handlers = make_handlers(Broker(device=MockDevice(armed=True, handler=handler)))
        resp = json.loads(handlers["mobile_click"]({"text": "Send"}))
        self.assertFalse(resp["ok"])
        self.assertEqual(resp["error"]["code"], "no_match")
        self.assertEqual(len(resp["error"]["candidates"]), 2)
        self.assertIn("unique", resp["hint"])

    def test_error_hints_cover_common_failures(self):
        self.assertIn("mobile_arm", error_hint("disarmed"))
        self.assertIn("mobile_snapshot", error_hint("stale_ref"))
        self.assertIn("input", error_hint("no_focus"))
        self.assertIn("input", error_hint("a11y_unavailable", "no focused field"))

    def test_find_clickable_prefers_exact_button(self):
        nodes = [
            {"ref": "e1", "text": "Send", "clickable": True},
            {"ref": "e2", "text": "Send to group", "clickable": True},
        ]
        node, _ = find_clickable(nodes, "Send")
        self.assertEqual(node["ref"], "e1")
        none, matched = find_clickable(nodes, "Sen")
        self.assertIsNone(none)
        self.assertEqual(len(matched), 2)

    def test_open_app_tells_agent_to_wait(self):
        handlers = make_handlers(Broker(device=MockDevice(armed=True)))
        resp = json.loads(handlers["mobile_open_app"]({"package": "com.example.fixture"}))
        self.assertTrue(resp["ok"])
        self.assertIn("mobile_wait", resp["result"]["next"])

    def test_authenticator_and_password_managers_protected_in_blocklist(self):
        blocked = (
            "com.authy.authy",
            "com.azure.authenticator",
            "com.onepassword.android",
            "com.bitwarden.mobile",
            "com.x8bit.bitwarden",
            "com.kunzisoft.keepass.free",
            "com.beemdevelopment.aegis",
            "com.google.android.permissioncontroller",
            "com.samsung.android.spay",
            "com.samsung.android.settings.deviceowner",
            "com.phonepe.app",
            "com.capitalone.mobile",
        )
        broker = Broker(device=MockDevice(armed=True), extra_protected=blocked)
        for pkg in blocked:
            with self.assertRaises(BrokerError) as ctx:
                broker.dispatch("device.open_app", {"package": pkg})
            self.assertEqual(ctx.exception.code, "protected_package")


if __name__ == "__main__":
    unittest.main()
