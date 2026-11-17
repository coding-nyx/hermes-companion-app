"""Phone-authoritative readiness and approval survive the Companion client migration."""
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import _isolate  # noqa: F401,E402
from broker import Broker, BrokerError, LiveDevice, MockDevice
from pairing import PairingStore
from relay import RelayState
import schemas
from tools import armed_hint, make_handlers


class HandsMigrationTests(unittest.TestCase):
    def test_private_entry_uses_screen_readiness_instead_of_accessibility_binding(self):
        device = MockDevice(armed=True, a11y_bound=False, screen_control_available=True,
                            handler=lambda action, args: {"requested": True})
        self.assertTrue(Broker(device=device).dispatch("device.secure_entry", {"action": "request"})["requested"])
        device.screen_control_available = False
        device.a11y_bound = True
        with self.assertRaises(BrokerError) as refused:
            Broker(device=device).dispatch("device.secure_entry", {"action": "request"})
        self.assertEqual("a11y_unavailable", refused.exception.code)

    def test_old_readiness_falls_back_but_live_cached_state_never_blocks_another_phone(self):
        old = MockDevice(armed=True, a11y_bound=True, handler=lambda a, p: {})
        Broker(device=old).dispatch("device.secure_entry", {"action": "request"})
        calls = []
        live = LiveDevice(lambda a, p: calls.append(a) or {"screen_control_available": True},
                          a11y_bound=False, screen_control_available=False)
        Broker(device=live).dispatch("device.secure_entry", {"action": "request", "device": "another-phone"})
        self.assertEqual(["device.secure_entry"], calls)

    def test_local_approval_and_private_entry_clear_cached_arm(self):
        for response in ({"local_approval_required": True}, {"privacy_active": True}):
            live = LiveDevice(lambda a, p: response, armed=True)
            live.execute("device.files.enable", {})
            self.assertFalse(live.armed)

    def test_explicit_screen_false_does_not_turn_into_truthy_string(self):
        live = LiveDevice(lambda a, p: {"screen_control_available": "false", "a11y_bound": True})
        live.execute("device.status", {})
        self.assertFalse(live.screen_control_available)
        live.send = lambda a, p: {"a11y_bound": True}
        live.execute("device.status", {})
        self.assertIsNone(live.screen_control_available)

    def test_relay_and_discovery_publish_readiness_and_local_approval_stop(self):
        pairing = PairingStore(now=lambda: 10)
        device = pairing.approve(pairing.issue("test"))
        state = RelayState(pairing=pairing)
        state._apply_live_meta(device.device_id, {"armed": True, "a11y_bound": False, "screen_control_available": True})
        state.on_frame(json.dumps({"type": "mobile.controller.result", "command_id": "approval",
                                  "result": {"local_approval_required": True}}), device.device_id)
        row = pairing.lane_descriptors([device.device_id], state.live_meta)[0]
        self.assertTrue(row["screen_control_available"])
        self.assertFalse(row["armed"])
        self.assertTrue(row["local_approval_required"])
        state._apply_live_meta(device.device_id, {"armed": True})
        self.assertNotIn("local_approval_required", pairing.lane_descriptors([device.device_id], state.live_meta)[0])

    def test_notification_enable_is_not_a_passive_permission_grant(self):
        with self.assertRaises(BrokerError) as refused:
            Broker(device=MockDevice(armed=False)).dispatch("device.notifications.nls.enable")
        self.assertEqual("disarmed", refused.exception.code)

    def test_plugin_stream_tool_can_only_stop_phone_local_scope(self):
        calls = []
        handler = make_handlers(Broker(device=MockDevice(armed=True, handler=lambda a, p: calls.append((a, p)) or {})))["mobile_notifications_stream_set"]
        for args in ({"enabled": True}, {"enabled": False, "profile": "other"}, {"origin": "https://elsewhere.invalid"}):
            self.assertEqual("phone_consent_required", json.loads(handler(args))["error"]["code"])
        self.assertEqual([], calls)
        self.assertTrue(json.loads(handler({"enabled": False}))["ok"])

    def test_intent_schemas_expose_only_explicit_primary_handoff(self):
        for name in ("SMS_COMPOSE", "CALL_DIAL", "CALL_PLACE", "ALARM_SET", "TIMER_SET", "MEDIA_PLAY_SEARCH",
                     "CALENDAR_ADD", "SHARE", "SETTINGS_OPEN", "APP_UNINSTALL"):
            field = getattr(schemas, name)["parameters"]["properties"]["allow_primary_handoff"]
            self.assertFalse(field["default"])
        hint = armed_hint(True, True)
        self.assertIn("screen_control_available", hint)
        self.assertIn("Access", hint)
        self.assertIn("Alerts", hint)
        self.assertIn("local_approval_required", hint)


if __name__ == "__main__":
    unittest.main()
