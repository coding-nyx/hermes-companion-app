from __future__ import annotations

import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import _isolate  # noqa: F401,E402
from broker import ALLOWLIST, Broker, BrokerError, MockDevice
from pairing import PairingStore
from relay import RelayState
from schemas import SECURE_ENTRY
from tickets import ALLOWLIST as TICKET_ALLOWLIST
from tools import error_hint, make_handlers


REQUEST_ID = "a4d84517-4954-4192-9c22-eb44206103ba"
SNAPSHOT_ID = "58cfb916-a137-4914-861f-58276b56f330"
REQUEST = {"action": "request", "package": "com.example.app", "ref": "@e3", "snapshot_id": SNAPSHOT_ID, "label": "Email address"}
META = {"request_id": REQUEST_ID, "status": "pending", "privacy_active": True}


class SecureEntryToolsTests(unittest.TestCase):
    def test_sms_permission_recovery_points_to_in_app_consent(self):
        hint = error_hint("permission_denied", "missing android.permission.READ_SMS")
        self.assertIn("Hands", hint)
        self.assertIn("Access", hint)
        self.assertIn("SMS access", hint)
        self.assertNotIn("pm grant", hint)

    def test_registration_and_wire_capability(self):
        self.assertIn("mobile_secure_entry", make_handlers(Broker()))
        self.assertFalse(SECURE_ENTRY["parameters"]["additionalProperties"])
        properties = SECURE_ENTRY["parameters"]["properties"]
        self.assertEqual(properties["snapshot_id"]["format"], "uuid")
        self.assertIn("snapshot_id", SECURE_ENTRY["parameters"]["oneOf"][0]["required"])
        for name in ("text", "value", "content", "password"):
            self.assertNotIn(name, properties)
        self.assertIn("device.secure_entry", ALLOWLIST)
        self.assertIn("device.secure_entry", TICKET_ALLOWLIST)
        manifest = (Path(__file__).resolve().parents[1] / "plugin.yaml").read_text()
        self.assertIn("  - mobile_secure_entry\n", manifest)

    def test_handoff_returns_metadata_without_followup_snapshot(self):
        calls = []
        def send(action, args):
            calls.append((action, args))
            return {**META, "text": "should never reach model", "nodes": [{"text": "private"}], "png_b64": "private"}
        handlers = make_handlers(Broker(device=MockDevice(armed=True, handler=send)))
        payload = json.loads(handlers["mobile_secure_entry"]({**REQUEST, "device": "phone"}))
        self.assertEqual(payload, {"ok": True, "result": META})
        self.assertEqual(calls, [("device.secure_entry", {**REQUEST, "ref": "e3", "_device": "phone"})])

    def test_filling_status_returns_only_metadata_without_followup_action(self):
        calls = []
        filling = {**META, "status": "filling"}
        def send(action, args):
            calls.append((action, args))
            return {**filling, "value": "PRIVATE_SENTINEL", "content": "PRIVATE_SENTINEL"}
        handler = make_handlers(Broker(device=MockDevice(armed=True, handler=send)))["mobile_secure_entry"]
        payload = json.loads(handler({"action": "status", "request_id": REQUEST_ID}))
        self.assertEqual(payload, {"ok": True, "result": filling})
        self.assertEqual(calls, [("device.secure_entry", {"action": "status", "request_id": REQUEST_ID})])

    def test_snapshot_tokens_survive_direct_and_gesture_observation(self):
        tree = {"snapshot_id": SNAPSHOT_ID, "app": "com.example.app", "nodes": [{"ref": "e3", "text": "Email address", "clickable": True}]}
        calls = []
        def send(action, args):
            calls.append((action, args))
            return tree if action == "device.snapshot" else {}
        handlers = make_handlers(Broker(device=MockDevice(armed=True, handler=send)))
        direct = json.loads(handlers["mobile_snapshot"]({}))
        self.assertEqual(direct["result"]["snapshot_id"], SNAPSHOT_ID)
        gesture = json.loads(handlers["mobile_type"]({"text": "ordinary non-sensitive text"}))
        self.assertEqual(gesture["result"]["snapshot"]["snapshot_id"], SNAPSHOT_ID)
        self.assertEqual([action for action, _ in calls], ["device.snapshot", "device.type", "device.snapshot"])

    def test_missing_or_malformed_snapshot_token_never_dispatches(self):
        calls = []
        handler = make_handlers(Broker(device=MockDevice(armed=True, handler=lambda a, p: calls.append(a) or META)))["mobile_secure_entry"]
        missing = {key: value for key, value in REQUEST.items() if key != "snapshot_id"}
        for args in (missing, {**REQUEST, "snapshot_id": "PRIVATE_SENTINEL"}, {**REQUEST, "snapshot_id": None}):
            payload = handler(args)
            self.assertEqual(json.loads(payload)["error"]["code"], "invalid_args")
            self.assertNotIn("PRIVATE_SENTINEL", payload)
        self.assertEqual(calls, [])

    def test_actual_value_arguments_and_remote_release_are_refused_before_dispatch(self):
        calls = []
        handlers = make_handlers(Broker(device=MockDevice(armed=True, handler=lambda a, p: calls.append(a) or META)))
        for name in ("text", "value", "password", "content", "data_base64", "release"):
            payload = json.loads(handlers["mobile_secure_entry"]({**REQUEST, name: "PRIVATE_SENTINEL"}))
            self.assertFalse(payload["ok"], name)
            self.assertEqual(payload["error"]["code"], "invalid_args")
            self.assertNotIn("PRIVATE_SENTINEL", json.dumps(payload))
        for action in ("release", "resume", "fill", "type"):
            self.assertFalse(json.loads(handlers["mobile_secure_entry"]({"action": action}))["ok"])
        self.assertEqual(calls, [])

    def test_request_needs_valid_target_ref_label_and_no_request_id(self):
        handlers = make_handlers(Broker(device=MockDevice(armed=True)))
        for request in ({}, {**REQUEST, "label": "x" * 81}, {**REQUEST, "label": " "},
                        {**REQUEST, "package": "not a package"}, {**REQUEST, "ref": "e0"},
                        {**REQUEST, "request_id": REQUEST_ID}, {**REQUEST, "label": 5}):
            self.assertEqual(json.loads(handlers["mobile_secure_entry"](request))["error"]["code"], "invalid_args")

    def test_status_and_cancel_require_uuid_and_do_not_accept_target_or_value(self):
        calls = []
        handler = make_handlers(Broker(device=MockDevice(armed=False, a11y_bound=False,
            foreground_app="com.protected.app", handler=lambda a, p: calls.append((a, p)) or META),
            extra_protected=("com.protected.app",)))["mobile_secure_entry"]
        for action in ("status", "cancel"):
            payload = json.loads(handler({"action": action, "request_id": REQUEST_ID}))
            self.assertEqual(payload["result"], META)
            for extra in ({"request_id": "PRIVATE_SENTINEL"}, {"package": "com.example.app"}, {"text": "PRIVATE_SENTINEL"}):
                invalid = json.loads(handler({"action": action, "request_id": REQUEST_ID, **extra}))
                self.assertFalse(invalid["ok"])
                self.assertNotIn("PRIVATE_SENTINEL", json.dumps(invalid))
        self.assertEqual([args["action"] for _, args in calls], ["status", "cancel"])

    def test_request_requires_armed_screen_control_and_protects_both_packages(self):
        for device, protected, expected in (
            (MockDevice(armed=False), (), "disarmed"),
            (MockDevice(armed=True, a11y_bound=False), (), "a11y_unavailable"),
            (MockDevice(armed=True), ("com.example.app",), "protected_package"),
            (MockDevice(armed=True, foreground_app="com.blocked.app"), ("com.blocked.app",), "protected_package"),
        ):
            payload = json.loads(make_handlers(Broker(device=device, extra_protected=protected))["mobile_secure_entry"](REQUEST))
            self.assertEqual(payload["error"]["code"], expected)

    def test_untrusted_result_and_error_messages_cannot_echo_values(self):
        for result in ({**META, "status": "PRIVATE_SENTINEL"}, {**META, "request_id": "PRIVATE_SENTINEL"},
                       {**META, "privacy_active": "PRIVATE_SENTINEL"}):
            handler = make_handlers(Broker(device=MockDevice(armed=True, handler=lambda a, p: result)))["mobile_secure_entry"]
            payload = handler(REQUEST)
            self.assertFalse(json.loads(payload)["ok"])
            self.assertNotIn("PRIVATE_SENTINEL", payload)
        def denied(a, p):
            raise BrokerError("privacy_active", "PRIVATE_SENTINEL")
        payload = make_handlers(Broker(device=MockDevice(armed=True, handler=denied)))["mobile_secure_entry"](REQUEST)
        self.assertNotIn("PRIVATE_SENTINEL", payload)
        self.assertIn("privacy_active", payload)
        def unsafe_error(a, p):
            raise BrokerError("PRIVATE_SENTINEL", "PRIVATE_SENTINEL")
        payload = make_handlers(Broker(device=MockDevice(armed=True, handler=unsafe_error)))["mobile_secure_entry"](REQUEST)
        self.assertNotIn("PRIVATE_SENTINEL", payload)
        self.assertEqual(json.loads(payload)["error"]["code"], "secure_entry_failed")
        for code in ("request_not_found", "privacy_unavailable"):
            def phone_error(a, p):
                raise BrokerError(code, "PRIVATE_SENTINEL")
            payload = make_handlers(Broker(device=MockDevice(armed=True, handler=phone_error)))["mobile_secure_entry"](REQUEST)
            self.assertNotIn("PRIVATE_SENTINEL", payload)
            self.assertEqual(json.loads(payload)["error"]["code"], code)

    def test_room_guard_prevents_handoff(self):
        calls = []
        with patch("tools._room_guard", side_effect=BrokerError("room_hands", "another holder")):
            handler = make_handlers(Broker(device=MockDevice(armed=True, handler=lambda a, p: calls.append(a) or META)))["mobile_secure_entry"]
            self.assertEqual(json.loads(handler(REQUEST))["error"]["code"], "room_hands")
        self.assertEqual(calls, [])

    def test_relay_shield_hides_ring_and_suppresses_new_notification_without_wake(self):
        state = RelayState(pairing=PairingStore(now=lambda: 1.0))
        event = {"profile": "default", "ts_ms": 1, "notification": {"package": "com.example.app", "text": "earlier"}}
        with patch("agent_wake.maybe_wake_for_notification") as wake:
            self.assertEqual(state.push_notification("phone", event), "accepted")
            self.assertEqual(len(state.list_notifications(device_id="phone")), 1)
            wake.reset_mock()
            state.on_frame(json.dumps({"type": "mobile.controller.result", "command_id": "c1", "result": META}), "phone")
            self.assertTrue(state.live_meta["phone"]["privacy_active"])
            self.assertEqual(state.list_notifications(device_id="phone"), [])
            self.assertEqual(state.list_notifications(), [])
            self.assertEqual(state.push_notification("phone", {**event, "notification": {"text": "PRIVATE_SENTINEL"}}), "privacy_active")
            self.assertNotIn("PRIVATE_SENTINEL", json.dumps(state.notif_rings))
            wake.assert_not_called()
            state.on_frame(json.dumps({"type": "mobile.controller.status", "privacy_active": False}), "phone")
            self.assertEqual(len(state.list_notifications(device_id="phone")), 1)


if __name__ == "__main__":
    unittest.main()
