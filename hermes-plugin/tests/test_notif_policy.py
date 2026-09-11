from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import _isolate  # noqa: F401,E402  # must precede plugin imports

import notif_policy
from broker import Broker, MockDevice
from tools import make_handlers


class NotifPolicyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self._env = {
            k: os.environ.get(k)
            for k in ("HERMES_COMPANION_NOTIF_POLICY", "HERMES_COMPANION_NOTIF_WAKE_SKIP_PACKAGES")
        }
        os.environ["HERMES_COMPANION_NOTIF_POLICY"] = str(Path(self.tmp.name) / "policy.json")
        os.environ.pop("HERMES_COMPANION_NOTIF_WAKE_SKIP_PACKAGES", None)
        notif_policy.load(force=True)
        self.addCleanup(self._restore)

    def _restore(self):
        for k, v in self._env.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
        notif_policy.load(force=True)

    # ---- resolution ----

    def test_apps_are_enabled_by_default(self):
        d = notif_policy.describe("com.example.newapp")
        self.assertEqual(d["effective"], notif_policy.RESPOND)
        self.assertEqual(d["source"], "default")
        self.assertTrue(notif_policy.should_ring(d["effective"]))
        self.assertTrue(notif_policy.should_wake(d["effective"]))

    def test_silent_rings_but_never_wakes(self):
        notif_policy.set_mode("com.android.systemui", "silent")
        mode = notif_policy.effective_mode("com.android.systemui")
        self.assertEqual(mode, notif_policy.SILENT)
        self.assertTrue(notif_policy.should_ring(mode), "silent must stay readable")
        self.assertFalse(notif_policy.should_wake(mode), "silent must not wake")

    def test_off_never_reaches_the_ring(self):
        notif_policy.set_mode("com.noisy.app", "off")
        mode = notif_policy.effective_mode("com.noisy.app")
        self.assertFalse(notif_policy.should_ring(mode))
        self.assertFalse(notif_policy.should_wake(mode))

    def test_profile_rule_beats_host_wide_rule(self):
        notif_policy.set_mode("com.zoho.chat", "silent")
        notif_policy.set_mode("com.zoho.chat", "respond", profile="ash")
        self.assertEqual(notif_policy.effective_mode("com.zoho.chat"), notif_policy.SILENT)
        self.assertEqual(
            notif_policy.effective_mode("com.zoho.chat", profile="ash"), notif_policy.RESPOND
        )

    def test_host_override_beats_legacy_env_skip(self):
        os.environ["HERMES_COMPANION_NOTIF_WAKE_SKIP_PACKAGES"] = "com.legacy.app"
        self.assertEqual(notif_policy.effective_mode("com.legacy.app"), notif_policy.SILENT)
        notif_policy.set_mode("com.legacy.app", "respond")
        self.assertEqual(notif_policy.effective_mode("com.legacy.app"), notif_policy.RESPOND)

    def test_phone_suppression_cannot_be_loosened_from_the_host(self):
        # The notification never leaves the phone, so a host `respond` is unreachable and
        # must be reported as such rather than silently claimed.
        notif_policy.set_mode("com.secret.app", "respond")
        d = notif_policy.describe("com.secret.app", phone_off=("com.secret.app",))
        self.assertEqual(d["effective"], notif_policy.OFF)
        self.assertTrue(d["phone_suppressed"])
        self.assertTrue(d["unreachable_override"])

    def test_clearing_an_override_returns_to_default(self):
        notif_policy.set_mode("com.example.app", "off")
        self.assertEqual(notif_policy.effective_mode("com.example.app"), notif_policy.OFF)
        notif_policy.set_mode("com.example.app", None)
        self.assertEqual(notif_policy.effective_mode("com.example.app"), notif_policy.RESPOND)

    def test_bad_input_is_rejected(self):
        with self.assertRaises(ValueError):
            notif_policy.set_mode("com.example.app", "loud")
        with self.assertRaises(ValueError):
            notif_policy.set_mode("", "off")

    def test_garbage_file_falls_back_to_defaults(self):
        Path(os.environ["HERMES_COMPANION_NOTIF_POLICY"]).write_text("{not json")
        notif_policy.load(force=True)
        self.assertEqual(notif_policy.effective_mode("com.example.app"), notif_policy.RESPOND)

    def test_unknown_modes_in_the_file_are_dropped(self):
        Path(os.environ["HERMES_COMPANION_NOTIF_POLICY"]).write_text(
            json.dumps({"packages": {"com.a": "loud", "com.b": "silent"}})
        )
        notif_policy.load(force=True)
        self.assertEqual(notif_policy.effective_mode("com.a"), notif_policy.RESPOND)
        self.assertEqual(notif_policy.effective_mode("com.b"), notif_policy.SILENT)

    # ---- tools ----

    def test_policy_tools_round_trip(self):
        handlers = make_handlers(Broker(device=MockDevice(armed=False)))
        payload = json.loads(
            handlers["mobile_notification_policy_set"](
                {"package": "com.android.systemui", "mode": "silent"}
            )
        )
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["result"]["mode"], "silent")

        read = json.loads(handlers["mobile_notification_policy"]({}))
        self.assertTrue(read["ok"])
        self.assertEqual(read["result"]["default_mode"], "respond")
        rules = {r["package"]: r["mode"] for r in read["result"]["rules"]}
        self.assertEqual(rules["com.android.systemui"], "silent")

    def test_policy_set_requires_package_and_mode(self):
        handlers = make_handlers(Broker(device=MockDevice(armed=False)))
        for args in ({}, {"package": "com.a"}, {"mode": "silent"}):
            payload = json.loads(handlers["mobile_notification_policy_set"](args))
            self.assertFalse(payload["ok"], args)
            self.assertEqual(payload["error"]["code"], "missing_args", args)

    def test_policy_tools_work_while_disarmed(self):
        # These write host config, not the device, so arming is irrelevant.
        handlers = make_handlers(Broker(device=MockDevice(armed=False)))
        self.assertTrue(json.loads(handlers["mobile_notification_policy"]({}))["ok"])


if __name__ == "__main__":
    unittest.main()
