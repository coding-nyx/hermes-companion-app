from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import _isolate  # noqa: F401,E402

from broker import Broker, BrokerError, MockDevice
from tools import error_hint, make_handlers


class MobileObservationTests(unittest.TestCase):
    def test_screenshot_attaches_pixels_in_hermes_image_contract(self):
        fixture = MockDevice().execute("device.screenshot", {})
        fixture.update(captured_at_ms=123456, window_id=7, generation=12, app="com.example.fixture")
        handlers = make_handlers(Broker(device=MockDevice(armed=True, handler=lambda *_: fixture)))

        result = handlers["mobile_screenshot"]({})

        # Hermes only treats dictionaries with this envelope as image content. JSON
        # containing png_b64 is ordinary text, even when the main model has vision.
        self.assertIs(result["_multimodal"], True)
        text, image = result["content"]
        self.assertEqual(image["type"], "image_url")
        self.assertEqual(image["image_url"]["url"], "data:image/png;base64," + fixture["png_b64"])
        metadata = json.loads(text["text"])
        self.assertTrue(metadata["ok"])
        self.assertEqual(metadata["result"]["captured_at_ms"], 123456)
        self.assertEqual(metadata["result"]["window_id"], 7)
        self.assertEqual(metadata["result"]["generation"], 12)
        self.assertNotIn("png_b64", text["text"])
        self.assertNotIn(fixture["png_b64"], result["text_summary"])
        self.assertFalse(json.loads(result["text_summary"])["ok"])
        self.assertEqual(json.loads(result["text_summary"])["error"]["code"], "image_input_unavailable")
        self.assertIn("do not claim", result["text_summary"])

    def test_screenshot_failure_remains_explicit_error_without_image(self):
        def reject(*_):
            raise BrokerError("screenshot_secure_window", "Android refused the secure window")

        handlers = make_handlers(Broker(device=MockDevice(armed=True, handler=reject)))
        result = json.loads(handlers["mobile_screenshot"]({}))
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"]["code"], "screenshot_secure_window")
        self.assertIn("do not bypass", result["hint"])
        self.assertNotIn("content", result)

    def test_screenshot_preserves_canvas_metadata_for_scaled_coordinates(self):
        fixture = MockDevice().execute("device.screenshot", {})
        for space, bounds in (("window", [0, 104, 1080, 2332]), ("display", [0, 0, 1080, 2400])):
            with self.subTest(coordinate_space=space):
                canvas = {"coordinate_space": space, "window_bounds": bounds, "screen_size": {"w": 1080, "h": 2400}}
                handlers = make_handlers(Broker(device=MockDevice(armed=True, handler=lambda *_: {**fixture, **canvas})))
                result = handlers["mobile_screenshot"]({})
                for text in (result["content"][0]["text"], result["text_summary"]):
                    metadata = json.loads(text)["result"]
                    for key, value in canvas.items():
                        self.assertEqual(metadata[key], value)
                    self.assertNotIn("png_b64", metadata)

    def test_invalid_capture_cannot_be_advertised_as_visible_image(self):
        fixture = MockDevice().execute("device.screenshot", {})
        for override in ({"png_b64": "not base64"}, {"w": 3}, {"w": True}, {"mime": "text/plain"}, {"png_b64": ""}):
            with self.subTest(override=override):
                handlers = make_handlers(Broker(device=MockDevice(armed=True, handler=lambda *_: {**fixture, **override})))
                result = json.loads(handlers["mobile_screenshot"]({}))
                self.assertFalse(result["ok"])
                self.assertEqual(result["error"]["code"], "invalid_screenshot")
                self.assertNotIn("content", result)

    def test_completed_action_reports_observation_failure_without_retry(self):
        calls = []

        def execute(action, _args):
            calls.append(action)
            if action == "device.click":
                return {"clicked": "e1", "snapshot": {"nodes": [{"ref": "old"}]}}
            raise BrokerError("screen_unstable", "window changed while reading the tree")

        handlers = make_handlers(Broker(device=MockDevice(armed=True, handler=execute)))
        result = json.loads(handlers["mobile_click"]({"ref": "e1"}))
        self.assertTrue(result["ok"])
        self.assertEqual(result["result"]["clicked"], "e1")
        self.assertFalse(result["result"]["observation"]["ok"])
        self.assertEqual(result["result"]["observation"]["error"]["code"], "screen_unstable")
        self.assertNotIn("snapshot", result["result"])
        self.assertIn("Do not repeat", result["result"]["next"])
        self.assertEqual(calls, ["device.click", "device.snapshot"])

    def test_empty_followup_tree_is_unavailable_not_a_fresh_snapshot(self):
        def execute(action, _args):
            return {"nodes": []} if action == "device.snapshot" else {"typed": True}

        handlers = make_handlers(Broker(device=MockDevice(armed=True, handler=execute)))
        result = json.loads(handlers["mobile_type"]({"text": "hello"}))["result"]
        self.assertTrue(result["typed"])
        self.assertEqual(result["observation"]["error"]["code"], "observation_unavailable")
        self.assertNotIn("snapshot", result)

    def test_unreadable_screen_cannot_resolve_a_text_click(self):
        calls = []

        def execute(action, _args):
            calls.append(action)
            return {"nodes": []}

        handlers = make_handlers(Broker(device=MockDevice(armed=True, handler=execute)))
        for name, args in (("mobile_snapshot", {}), ("mobile_click", {"text": "Continue"})):
            result = json.loads(handlers[name](args))
            self.assertFalse(result["ok"])
            self.assertEqual(result["error"]["code"], "observation_unavailable")
        self.assertEqual(calls, ["device.snapshot", "device.snapshot"])

    def test_followup_preserves_phone_observation_identity(self):
        tree = {"nodes": [{"ref": "e1"}], "captured_at_ms": 123456, "window_id": 7, "generation": 12}

        def execute(action, _args):
            return tree if action == "device.snapshot" else {"clicked": "e1"}

        handlers = make_handlers(Broker(device=MockDevice(armed=True, handler=execute)))
        result = json.loads(handlers["mobile_click"]({"ref": "e1"}))["result"]
        self.assertEqual(result["snapshot"], tree)
        self.assertNotIn("observation", result)

    def test_phone_observation_errors_have_actionable_recovery(self):
        for code in (
            "screen_off", "screen_locked", "screen_unavailable", "screen_unstable", "observation_cancelled",
            "gesture_timeout", "gesture_cancelled", "screenshot_blank", "screenshot_secure_window",
            "screenshot_rate_limited", "screenshot_invalid_display", "screenshot_invalid_window",
            "screenshot_failed", "screenshot_timeout",
        ):
            with self.subTest(code=code):
                self.assertTrue(error_hint(code))
        self.assertIn("operator", error_hint("screen_locked"))
        self.assertIn("do not repeat", error_hint("gesture_timeout"))
        self.assertIn("do not repeat", error_hint("gesture_cancelled"))


if __name__ == "__main__":
    unittest.main()
