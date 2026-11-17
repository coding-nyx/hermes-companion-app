from __future__ import annotations

import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import _isolate  # noqa: F401,E402
from broker import BrokerError
from working_lease import WorkingLeaseManager

LEASE = "a2efc623-9d20-4bb1-9256-3340eb07712c"
OTHER = "d2a4a076-da1a-4828-8cec-1ecc5f9d893c"


class Hooks:
    def __init__(self):
        self.callbacks = {}

    def register_hook(self, name, callback):
        self.callbacks[name] = callback

    def fire(self, name, **payload):
        self.callbacks[name](**payload)


class FakeBroker:
    def __init__(self):
        self.calls = []
        self.reply = None
        self.error = None

    def dispatch(self, action, args):
        self.calls.append((action, args))
        if self.error is not None:
            raise self.error
        return self.reply or {"armed": action != "device.disarm", "lease_id": args["lease_id"]}


class WorkingLeaseTests(unittest.TestCase):
    def setUp(self):
        self.now = 0.0
        self.broker = FakeBroker()
        self.manager = WorkingLeaseManager(self.broker, clock=lambda: self.now, autostart=False)
        self.hooks = Hooks()
        self.assertTrue(self.manager.register(self.hooks))
        self.addCleanup(self.manager.close, release=False)

    def start(self, session="s1", turn="t1", task="task1", session_key="gateway1"):
        context = dict(session_id=session, turn_id=turn, task_id=task, session_key=session_key)
        self.hooks.fire("pre_llm_call", **context)
        return context

    def arm(self, context, lease=LEASE, device="phone", timeout=3600):
        return self.manager.observe_arm({"device": device},
            {"armed": True, "lease_id": lease, "idle_timeout_seconds": timeout}, context)

    def advance(self, seconds=30):
        self.now += seconds
        self.manager.tick()

    def test_long_reasoning_renews_without_any_model_or_tool_calls(self):
        context = self.start()
        self.assertTrue(self.arm(context))
        for _ in range(150):
            self.advance()
        self.assertEqual(len(self.broker.calls), 150)
        self.assertEqual({action for action, _ in self.broker.calls}, {"device.arm.renew"})
        self.assertEqual(self.broker.calls[-1][1], {"device": "phone", "lease_id": LEASE})

    def test_finishing_disarms_only_own_generation_and_stops_renewal(self):
        context = self.start()
        self.arm(context)
        self.hooks.fire("post_llm_call", **context)
        self.assertEqual(self.broker.calls, [("device.disarm", {"device": "phone", "lease_id": LEASE})])
        self.advance(600)
        self.hooks.fire("on_session_end", **context)
        self.assertEqual(len(self.broker.calls), 1)

    def test_multiple_turns_hold_same_lease_until_last_finishes(self):
        first = self.start()
        second = self.start("s2", "t2", "task2", "gateway2")
        self.arm(first)
        self.arm(second)
        self.hooks.fire("post_llm_call", **first)
        self.assertEqual(self.broker.calls, [])
        self.advance()
        self.assertEqual(self.broker.calls[-1][0], "device.arm.renew")
        self.hooks.fire("post_llm_call", **second)
        self.assertEqual(self.broker.calls[-1][0], "device.disarm")

    def test_disarmed_and_mismatched_leases_never_rearm_or_release_new_owner(self):
        for code in ("disarmed", "lease_mismatch", "privacy_active", "room_hands"):
            with self.subTest(code=code):
                manager = WorkingLeaseManager(self.broker, clock=lambda: self.now, autostart=False)
                hooks = Hooks()
                manager.register(hooks)
                context = dict(session_id="s", turn_id=code)
                hooks.fire("pre_llm_call", **context)
                manager.observe_arm({}, {"armed": True, "lease_id": LEASE}, context)
                self.broker.calls.clear()
                self.broker.error = BrokerError(code, code)
                self.now += 30
                manager.tick()
                self.now += 300
                manager.tick()
                hooks.fire("post_llm_call", **context)
                self.assertEqual([action for action, _ in self.broker.calls], ["device.arm.renew"])
                manager.close(release=False)
        self.broker.error = None

    def test_wrong_phone_reply_generation_is_terminal(self):
        context = self.start()
        self.arm(context)
        self.broker.reply = {"armed": True, "lease_id": OTHER}
        self.advance()
        self.advance()
        self.hooks.fire("post_llm_call", **context)
        self.assertEqual(len(self.broker.calls), 1)

    def test_lost_room_permission_stops_renewing_without_sending_commands(self):
        def reject_room():
            raise BrokerError("room_hands", "another participant has Hands")
        self.manager.guard = reject_room
        context = self.start()
        self.arm(context)
        self.advance()
        self.advance()
        self.hooks.fire("post_llm_call", **context)
        self.assertEqual(self.broker.calls, [])

    def test_transient_retries_are_bounded(self):
        context = self.start()
        self.arm(context)
        self.broker.error = TimeoutError()
        for _ in range(8):
            self.advance()
        self.assertEqual(len(self.broker.calls), 3)
        self.hooks.fire("post_llm_call", **context)
        self.assertEqual(len(self.broker.calls), 3)

    def test_success_resets_transient_error_count(self):
        context = self.start()
        self.arm(context)
        self.broker.error = TimeoutError()
        self.advance()
        self.advance()
        self.broker.error = None
        self.advance()
        self.broker.error = TimeoutError()
        self.advance()
        self.advance()
        self.broker.error = None
        self.advance()
        self.assertEqual(len(self.broker.calls), 6)

    def test_handler_context_uses_unique_session_and_task_never_other_turn(self):
        first = self.start()
        self.start("s2", "t2", "task2")
        self.assertFalse(self.arm({}))
        self.assertFalse(self.arm({"session_id": "unknown"}))
        self.assertTrue(self.arm({"session_id": "s1", "task_id": "task1"}))
        self.hooks.fire("post_llm_call", session_id="s2", turn_id="t2")
        self.assertEqual(self.broker.calls, [])
        self.hooks.fire("post_llm_call", **first)
        self.assertEqual(self.broker.calls[-1][0], "device.disarm")

    def test_ambiguous_handler_context_waits_for_exact_post_tool_identity(self):
        self.start()
        second = self.start("s1", "t2", "task1")
        self.assertFalse(self.arm({"session_id": "s1", "task_id": "task1"}))
        self.hooks.fire("post_tool_call", tool_name="mobile_arm", args={"device": "phone"},
            result=json.dumps({"ok": True, "result": {"armed": True, "lease_id": LEASE}}), **second)
        self.advance()
        self.assertEqual(self.broker.calls[-1][0], "device.arm.renew")

    def test_old_finish_does_not_stop_new_turn_same_session(self):
        old = self.start()
        current = self.start("s1", "t2")
        self.arm(current)
        self.hooks.fire("post_llm_call", **old)
        self.advance()
        self.assertEqual(self.broker.calls[-1][0], "device.arm.renew")

    def test_interrupt_targets_gateway_session_key_only(self):
        first = self.start()
        second = self.start("s2", "t2", "task2", "gateway2")
        self.arm(first)
        self.arm(second, OTHER, "other-phone")
        self.hooks.fire("agent_loop_stopped", session_key="gateway1", reason="stop")
        self.assertEqual(self.broker.calls, [("device.disarm", {"device": "phone", "lease_id": LEASE})])
        self.advance()
        self.assertEqual(self.broker.calls[-1], ("device.arm.renew", {"device": "other-phone", "lease_id": OTHER}))

    def test_short_leases_refresh_before_expiry(self):
        self.arm(self.start(), timeout=30)
        self.advance(9)
        self.assertEqual(self.broker.calls, [])
        self.advance(1)
        self.assertEqual(self.broker.calls[-1][0], "device.arm.renew")

    def test_canonical_phone_id_wins_over_default_device_alias(self):
        self.manager.observe_arm({}, {"armed": True, "lease_id": LEASE, "device_id": "stable-id"}, self.start())
        self.advance()
        self.assertEqual(self.broker.calls[-1][1]["device"], "stable-id")

    def test_no_hooks_or_unobserved_turn_cannot_claim_lifecycle_management(self):
        manager = WorkingLeaseManager(self.broker, autostart=False)
        self.assertFalse(manager.register(object()))
        self.assertFalse(manager.observe_arm({}, {"armed": True, "lease_id": LEASE}, {"session_id": "s1"}))
        self.assertFalse(self.arm({"session_id": "s1", "turn_id": "t1"}))
        manager.close(release=False)

    def test_invalid_or_failed_arm_results_are_not_managed(self):
        context = self.start()
        for result in ({"armed": False, "lease_id": LEASE}, {"armed": True},
                       {"armed": True, "lease_id": "invalid"},
                       {"armed": True, "lease_id": LEASE, "idle_timeout_seconds": 0}):
            self.assertFalse(self.manager.observe_arm({}, result, context))
        self.hooks.fire("post_tool_call", tool_name="mobile_arm", args={}, result="not json", **context)
        self.advance()
        self.assertEqual(self.broker.calls, [])

    def test_late_arm_result_after_release_cannot_reattach_old_generation(self):
        old = self.start()
        self.arm(old)
        self.hooks.fire("post_llm_call", **old)
        current = self.start("s2", "t2")
        self.assertFalse(self.arm(current))
        self.assertTrue(self.arm(current, OTHER))

    def test_close_releases_owned_lease_and_disables_callbacks(self):
        self.arm(self.start())
        self.manager.close()
        self.assertFalse(self.manager.available)
        self.assertEqual(self.broker.calls[-1][0], "device.disarm")
        self.advance()
        self.assertEqual(len(self.broker.calls), 1)


if __name__ == "__main__":
    unittest.main()
