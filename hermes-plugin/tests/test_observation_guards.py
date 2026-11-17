import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from broker import Broker, BrokerError, MockDevice


class ObservationGuardTests(unittest.TestCase):
    def test_resolved_window_is_protected_even_when_foreground_status_is_old(self):
        for action in ("device.snapshot", "device.screenshot"):
            with self.subTest(action=action):
                broker = Broker(
                    device=MockDevice(armed=True, foreground_app="com.example.allowed",
                                      handler=lambda action, args: {"app": "com.example.private", "nodes": []}),
                    extra_protected=("com.example.private",),
                )
                with self.assertRaises(BrokerError) as caught:
                    broker.dispatch(action)
                self.assertEqual("protected_package", caught.exception.code)

    def test_observation_errors_are_preserved_without_retry_or_arming(self):
        calls = []
        def capture(action, args):
            calls.append(action)
            raise BrokerError("screenshot_secure_window", "Android blocked capture")
        broker = Broker(device=MockDevice(armed=True, handler=capture))
        with self.assertRaises(BrokerError) as caught:
            broker.dispatch("device.screenshot")
        self.assertEqual("screenshot_secure_window", caught.exception.code)
        self.assertEqual(["device.screenshot"], calls)


if __name__ == "__main__":
    unittest.main()
