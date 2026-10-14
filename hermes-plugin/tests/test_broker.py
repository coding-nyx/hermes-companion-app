from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import _isolate  # noqa: F401,E402  # must precede plugin imports

from broker import ALLOWLIST, L0_NO_A11Y, L0_PASSIVE, Broker, BrokerError, LiveDevice, MockDevice
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


    def test_l0_passive_dispatches_while_disarmed(self):
        # Read-only L0 is the health-check surface: it has to answer on an unarmed phone.
        echo = lambda action, args: {"action": action}
        for action in sorted(L0_PASSIVE):
            broker = Broker(device=MockDevice(armed=False, handler=echo))
            self.assertEqual(broker.dispatch(action)["action"], action)

    def test_l0_destructive_refuses_while_disarmed(self):
        destructive = L0_NO_A11Y - L0_PASSIVE
        self.assertTrue(destructive)
        for action in sorted(destructive):
            broker = Broker(device=MockDevice(armed=False))
            with self.assertRaises(BrokerError) as ctx:
                broker.dispatch(action)
            self.assertEqual(ctx.exception.code, "disarmed", action)

    def test_l0_ignores_protected_foreground_package(self):
        # L0 targets a stream or session handle, not the foreground app, so a protected
        # app on screen must not veto a volume change.
        echo = lambda action, args: {"action": action}
        broker = Broker(
            device=MockDevice(armed=True, foreground_app="com.example.banking", handler=echo),
            extra_protected=("com.example.banking",),
        )
        self.assertEqual(
            broker.dispatch("device.audio.volume.set")["action"],
            "device.audio.volume.set",
        )
        with self.assertRaises(BrokerError) as ctx:
            broker.dispatch("device.snapshot")
        self.assertEqual(ctx.exception.code, "protected_package")

    def test_notification_action_tools_require_a_key(self):
        # Every one of these addresses a specific notification; without a key the phone would
        # have to guess which one, so refuse before dispatching.
        handlers = make_handlers(Broker(device=MockDevice(armed=True)))
        for name in (
            "mobile_notification_actions",
            "mobile_notification_action",
            "mobile_notification_reply",
            "mobile_notification_dismiss",
            "mobile_notification_snooze",
        ):
            payload = json.loads(handlers[name]({}))
            self.assertFalse(payload["ok"], name)
            self.assertEqual(payload["error"]["code"], "missing_args", name)

    def test_notification_reply_requires_text(self):
        handlers = make_handlers(Broker(device=MockDevice(armed=True)))
        payload = json.loads(handlers["mobile_notification_reply"]({"key": "abc123"}))
        self.assertFalse(payload["ok"])
        self.assertEqual(payload["error"]["code"], "missing_args")

    def test_notification_action_requires_index(self):
        handlers = make_handlers(Broker(device=MockDevice(armed=True)))
        payload = json.loads(handlers["mobile_notification_action"]({"key": "abc123"}))
        self.assertFalse(payload["ok"])
        self.assertEqual(payload["error"]["code"], "missing_args")

    def test_notification_writes_need_arming_but_listing_does_not(self):
        echo = lambda action, args: {"action": action}
        # Listing buttons is a read.
        broker = Broker(device=MockDevice(armed=False, handler=echo))
        self.assertEqual(
            broker.dispatch("device.notifications.actions", {"key": "k"})["action"],
            "device.notifications.actions",
        )
        # Firing, replying, dismissing and snoozing are not.
        for action in (
            "device.notifications.action",
            "device.notifications.reply",
            "device.notifications.dismiss",
            "device.notifications.snooze",
        ):
            with self.assertRaises(BrokerError) as ctx:
                Broker(device=MockDevice(armed=False)).dispatch(action, {"key": "k"})
            self.assertEqual(ctx.exception.code, "disarmed", action)

    def test_notification_error_hints_point_at_the_recovery(self):
        self.assertIn("mobile_notifications", error_hint("unknown_notification"))
        self.assertIn("mobile_notification_reply", error_hint("no_such_action"))

    def test_messaging_tools_require_their_args(self):
        handlers = make_handlers(Broker(device=MockDevice(armed=True)))
        cases = [
            ("mobile_sms_send", {}),
            ("mobile_sms_send", {"address": "+15550100"}),   # no body
            ("mobile_sms_send", {"body": "hi"}),             # no address
            ("mobile_sms_compose", {}),
            ("mobile_calls_dial", {}),
            ("mobile_calls_place", {}),
        ]
        for name, args in cases:
            payload = json.loads(handlers[name](args))
            self.assertFalse(payload["ok"], (name, args))
            self.assertEqual(payload["error"]["code"], "missing_args", (name, args))

    def test_private_history_reads_are_not_passive(self):
        # Arming is the user's consent. One sms.list discloses the whole message history, so
        # these reads must refuse on an unarmed phone even though they mutate nothing.
        # clipboard.get likewise: clipboards routinely hold just-copied passwords and OTPs.
        for action in ("device.sms.list", "device.calls.log", "device.contacts.search", "device.clipboard.get"):
            self.assertNotIn(action, L0_PASSIVE, action)
            with self.assertRaises(BrokerError) as ctx:
                Broker(device=MockDevice(armed=False)).dispatch(action)
            self.assertEqual(ctx.exception.code, "disarmed", action)

    def test_messaging_tools_do_not_need_a11y(self):
        echo = lambda action, args: {"action": action}
        for action in (
            "device.sms.list", "device.sms.send", "device.sms.compose",
            "device.calls.log", "device.calls.dial", "device.calls.place",
            "device.contacts.search",
        ):
            self.assertIn(action, L0_NO_A11Y, action)
            broker = Broker(device=MockDevice(armed=True, a11y_bound=False, handler=echo))
            self.assertEqual(broker.dispatch(action)["action"], action)

    def test_permission_hint_names_the_recovery(self):
        self.assertIn("permission", error_hint("permission_denied"))
        self.assertIn("no messaging app", error_hint("no_handler"))

    def test_speak_requires_text_and_arming(self):
        handlers = make_handlers(Broker(device=MockDevice(armed=True)))
        payload = json.loads(handlers["mobile_speak"]({}))
        self.assertFalse(payload["ok"])
        self.assertEqual(payload["error"]["code"], "missing_args")
        # Speaking out loud is a control action, so it needs consent.
        with self.assertRaises(BrokerError) as ctx:
            Broker(device=MockDevice(armed=False)).dispatch("device.voice.speak", {"text": "hi"})
        self.assertEqual(ctx.exception.code, "disarmed")

    def test_speak_status_is_passive_but_speaking_is_not(self):
        echo = lambda action, args: {"action": action}
        # Asking whether the phone *can* speak is harmless.
        self.assertIn("device.voice.status", L0_PASSIVE)
        broker = Broker(device=MockDevice(armed=False, handler=echo))
        self.assertEqual(broker.dispatch("device.voice.status")["action"], "device.voice.status")
        for action in ("device.voice.speak", "device.voice.stop"):
            self.assertNotIn(action, L0_PASSIVE, action)

    def test_voice_needs_no_a11y(self):
        echo = lambda action, args: {"action": action}
        for action in ("device.voice.speak", "device.voice.stop", "device.voice.status"):
            self.assertIn(action, L0_NO_A11Y, action)
            broker = Broker(device=MockDevice(armed=True, a11y_bound=False, handler=echo))
            self.assertEqual(broker.dispatch(action)["action"], action)

    def test_tts_hint_points_at_the_status_tool(self):
        self.assertIn("mobile_speak_status", error_hint("tts_unavailable"))

    def test_intent_lane_and_reads_require_their_args(self):
        handlers = make_handlers(Broker(device=MockDevice(armed=True)))
        cases = [
            ("mobile_alarm_set", {}), ("mobile_alarm_set", {"hour": 7}),
            ("mobile_timer_set", {}), ("mobile_media_play_search", {}),
            ("mobile_calendar_add", {"title": "x"}), ("mobile_share", {}),
            ("mobile_settings_open", {}), ("mobile_app_uninstall", {}),
            ("mobile_dnd_set", {}),
        ]
        for name, args in cases:
            payload = json.loads(handlers[name](args))
            self.assertFalse(payload["ok"], (name, args))
            self.assertEqual(payload["error"]["code"], "missing_args", (name, args))

    def test_phone_health_is_passive_but_personal_data_is_not(self):
        echo = lambda action, args: {"action": action}
        # Telemetry about the phone itself is fine on an unarmed phone.
        for action in ("device.battery", "device.power.state", "device.thermal", "device.audio.dnd"):
            self.assertIn(action, L0_PASSIVE, action)
            broker = Broker(device=MockDevice(armed=False, handler=echo))
            self.assertEqual(broker.dispatch(action)["action"], action)
        # Calendar, location and app usage are personal: arming is the consent gate.
        for action in ("device.calendar.list", "device.location.last", "device.apps.usage"):
            self.assertNotIn(action, L0_PASSIVE, action)
            with self.assertRaises(BrokerError) as ctx:
                Broker(device=MockDevice(armed=False)).dispatch(action)
            self.assertEqual(ctx.exception.code, "disarmed", action)

    def test_intent_lane_and_reads_need_no_a11y(self):
        echo = lambda action, args: {"action": action}
        for action in (
            "device.alarm.set", "device.timer.set", "device.media.play_search",
            "device.calendar.add", "device.share", "device.settings.open",
            "device.apps.uninstall", "device.calendar.list", "device.location.last",
            "device.battery", "device.power.state", "device.thermal",
            "device.apps.usage", "device.audio.dnd", "device.audio.dnd.set",
        ):
            self.assertIn(action, L0_NO_A11Y, action)
            broker = Broker(device=MockDevice(armed=True, a11y_bound=False, handler=echo))
            self.assertEqual(broker.dispatch(action)["action"], action)

    def test_unavailable_hint_explains_itself(self):
        self.assertIn("feature off", error_hint("unavailable"))

    def test_l0_sets_are_consistent(self):
        # These sets are hand-mirrored from DeviceLanePolicy.kt; keep the containment
        # invariants the phone side also asserts.
        self.assertTrue(L0_PASSIVE <= L0_NO_A11Y)
        self.assertTrue(L0_NO_A11Y <= ALLOWLIST)

    def test_clipboard_read_blocked_has_hint(self):
        # The phone reports clipboard_read_blocked when Android refuses a background read;
        # the agent must be steered to the a11y fallback rather than trusting an empty clip.
        hint = error_hint("clipboard_read_blocked")
        self.assertIn("focused field", hint)

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
