"""Unit tests for companion agent wake (P1.5)."""

from __future__ import annotations

import json
import os
import tempfile
import threading
import time
import unittest
import urllib.request
from pathlib import Path
from unittest import mock

from agent_wake import (
    _reset_wake_state_for_tests,
    format_telegram_nudge,
    format_wake_message,
    maybe_wake_for_notification,
    wake_enabled,
)
from relay import RelayState, make_server


class AgentWakeUnitTests(unittest.TestCase):
    def setUp(self):
        _reset_wake_state_for_tests()
        self._env = os.environ.copy()

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self._env)
        _reset_wake_state_for_tests()

    def test_format_wake_message(self):
        msg = format_wake_message("com.example.app", "Hello")
        self.assertIn("package: com.example.app", msg)
        self.assertIn("title: Hello", msg)
        self.assertIn("mobile_notifications", msg)
        self.assertIn("Do NOT echo", msg)
        self.assertIn("Call mobile_notifications", msg)
        self.assertIn("Ring event payload", msg)
        self.assertIn("text:", format_wake_message("com.ex", "T", "Body text here"))
        self.assertIn("Never invent notification bodies", msg)
        self.assertIn("default is do NOT inject", msg)
        # Must not look like a parrot-able one-liner alone
        self.assertNotEqual(msg.strip(), "mobile notif: com.example.app · Hello")

    def test_format_telegram_nudge(self):
        nudge = format_telegram_nudge("com.example.app", "Hello")
        self.assertIn("Hello", nudge)
        self.assertIn("com.example.app", nudge)
        self.assertNotIn("never auto-inject", nudge.lower())
        self.assertNotIn("OPERATING", nudge)

    def test_wake_disabled(self):
        os.environ["HERMES_COMPANION_NOTIF_WAKE"] = "0"
        self.assertFalse(wake_enabled())
        self.assertFalse(
            maybe_wake_for_notification(
                {"profile": "ash", "notification": {"package": "com.ex", "title": "t"}}
            )
        )

    def test_skip_telegram_packages(self):
        os.environ["HERMES_COMPANION_NOTIF_WAKE"] = "1"
        self.assertFalse(
            maybe_wake_for_notification(
                {
                    "profile": "ash",
                    "notification": {
                        "package": "org.telegram.messenger.web",
                        "title": "ash",
                        "text": "hi",
                    },
                }
            )
        )

    def test_requires_profile(self):
        os.environ["HERMES_COMPANION_NOTIF_WAKE"] = "1"
        self.assertFalse(
            maybe_wake_for_notification(
                {"notification": {"package": "com.android.shell", "title": "t"}}
            )
        )

    def test_debounce(self):
        os.environ["HERMES_COMPANION_NOTIF_WAKE"] = "1"
        os.environ["HERMES_COMPANION_NOTIF_WAKE_DEBOUNCE_SEC"] = "60"
        os.environ["HERMES_COMPANION_NOTIF_WAKE_MODE"] = "cli"
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "ash").mkdir()
            os.environ["HERMES_COMPANION_PROFILES_ROOT"] = str(root)
            calls = []

            def fake_cli(profile, message, profile_home):
                calls.append((profile, message, str(profile_home)))
                # release inflight like real wake would
                from agent_wake import _lock, _inflight

                with _lock:
                    _inflight.discard(profile)

            with mock.patch("agent_wake._wake_cli", side_effect=fake_cli):
                ev = {
                    "profile": "ash",
                    "notification": {"package": "com.android.shell", "title": "Smoke"},
                }
                self.assertTrue(maybe_wake_for_notification(ev, now=lambda: 1000.0))
                self.assertFalse(maybe_wake_for_notification(ev, now=lambda: 1010.0))
                self.assertTrue(maybe_wake_for_notification(ev, now=lambda: 1070.0))
            self.assertEqual(len(calls), 2)
            self.assertIn("package: com.android.shell", calls[0][1])
            self.assertIn("title: Smoke", calls[0][1])
            self.assertIn("Call mobile_notifications", calls[0][1])
            self.assertIn("Ring event payload", calls[0][1])


class RelayWakeIntegrationTests(unittest.TestCase):
    def setUp(self):
        _reset_wake_state_for_tests()
        self._env = os.environ.copy()
        os.environ["HERMES_COMPANION_NOTIF_WAKE"] = "1"
        os.environ["HERMES_COMPANION_NOTIF_WAKE_MODE"] = "cli"
        os.environ["HERMES_COMPANION_NOTIF_WAKE_DEBOUNCE_SEC"] = "0"

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self._env)
        _reset_wake_state_for_tests()

    def test_push_notification_triggers_wake(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "ash").mkdir()
            os.environ["HERMES_COMPANION_PROFILES_ROOT"] = str(root)
            woke = threading.Event()

            def fake_cli(profile, message, profile_home):
                woke.set()
                from agent_wake import _lock, _inflight

                with _lock:
                    _inflight.discard(profile)

            with mock.patch("agent_wake._wake_cli", side_effect=fake_cli):
                state = RelayState()
                state.push_notification(
                    "dev1",
                    {
                        "device_id": "dev1",
                        "profile": "ash",
                        "ts_ms": 1,
                        "notification": {
                            "key": "k",
                            "package": "com.android.shell",
                            "title": "WakeMe",
                            "text": "x",
                        },
                    },
                )
                self.assertTrue(woke.wait(2.0))
                rows = state.list_notifications(profile="ash")
                self.assertEqual(len(rows), 1)

    def test_notifications_http_profile_filter(self):
        state = RelayState()
        # disable wake for this pure HTTP test
        os.environ["HERMES_COMPANION_NOTIF_WAKE"] = "0"
        state.push_notification(
            "dev_a",
            {
                "device_id": "dev_a",
                "profile": "ash",
                "ts_ms": 10,
                "notification": {"key": "1", "package": "com.ex", "title": "a"},
            },
        )
        state.push_notification(
            "dev_a",
            {
                "device_id": "dev_a",
                "profile": "coder",
                "ts_ms": 11,
                "notification": {"key": "2", "package": "com.ex", "title": "c"},
            },
        )
        relay = make_server("127.0.0.1:0", "http://127.0.0.1:9", state)
        threading.Thread(target=relay.serve_forever, daemon=True).start()
        try:
            host, port = relay.server_address
            with urllib.request.urlopen(
                f"http://{host}:{port}/companion/device/notifications?profile=ash&limit=10",
                timeout=5,
            ) as resp:
                payload = json.loads(resp.read().decode())
            self.assertEqual(payload["count"], 1)
            self.assertEqual(payload["profile"], "ash")
            self.assertEqual(payload["notifications"][0]["profile"], "ash")
        finally:
            relay.shutdown()
            relay.server_close()


if __name__ == "__main__":
    unittest.main()
