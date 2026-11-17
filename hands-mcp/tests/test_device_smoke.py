"""Verify acceptance-driver retry safety without starting a process or contacting a phone."""

from pathlib import Path
import io
import unittest
from unittest.mock import patch

import device_smoke as smoke


def fixture_tree(snapshot_id, ref, *, display_id=7):
    return {'app': smoke.FIXTURE_PACKAGE, 'display_id': display_id, 'snapshot_id': snapshot_id,
        'nodes': [{'ref': ref, 'text': 'Verify isolated tap', 'clickable': True}]}


class ScriptedBridge:
    def __init__(self, outcomes, snapshots=(), targets=()):
        self.outcomes = list(outcomes)
        self.snapshots = list(snapshots)
        self.targets = list(targets)
        self.calls = []

    def tool(self, name, arguments=None):
        self.calls.append((name, arguments))
        if name == 'mobile_status':
            target = self.targets.pop(0) if self.targets else 'agent'
            return smoke.ToolReply({'target': target, 'armed': True,
                'agent_screen': {'active': True, 'display_id': 7}}, [])
        if name == 'mobile_snapshot':
            return smoke.ToolReply(self.snapshots.pop(0), [])
        if name == 'mobile_click':
            outcome = self.outcomes.pop(0)
            if isinstance(outcome, Exception):
                raise outcome
            return smoke.ToolReply(outcome, [])
        raise AssertionError('Unexpected tool name')


class DriverClickSafetyTests(unittest.TestCase):
    def acceptance(self, bridge):
        acceptance = smoke.Acceptance(bridge, {'checks': []}, Path('/unused-synthetic-artifacts'))
        acceptance.display_id = 7
        return acceptance

    @patch('device_smoke.time.sleep')
    def test_explicit_stale_rejection_reobserves_and_reselects_before_retry(self, _sleep):
        bridge = ScriptedBridge([smoke.SmokeFailure('stale_ref'), {'display_id': 7}],
            [fixture_tree('fresh-snapshot', 'new-ref')])
        acceptance = self.acceptance(bridge)
        acceptance.click(fixture_tree('old-snapshot', 'old-ref'), 'Verify isolated tap')
        clicks = [arguments for name, arguments in bridge.calls if name == 'mobile_click']
        self.assertEqual(clicks, [
            {'ref': 'old-ref', 'snapshot_id': 'old-snapshot'},
            {'ref': 'new-ref', 'snapshot_id': 'fresh-snapshot'},
        ])
        self.assertEqual(sum(name == 'mobile_snapshot' for name, _ in bridge.calls), 1)
        self.assertEqual(acceptance.report['stale_ref_reselections'], 1)

    @patch('device_smoke.time.sleep')
    def test_stale_rejections_are_capped_at_three_total_click_attempts(self, _sleep):
        bridge = ScriptedBridge([smoke.SmokeFailure('stale_ref') for _ in range(3)],
            [fixture_tree('snapshot-2', 'e2'), fixture_tree('snapshot-3', 'e3')])
        acceptance = self.acceptance(bridge)
        with self.assertRaises(smoke.SmokeFailure) as error:
            acceptance.click(fixture_tree('snapshot-1', 'e1'), 'Verify isolated tap')
        self.assertEqual(error.exception.code, 'stale_ref')
        self.assertEqual(sum(name == 'mobile_click' for name, _ in bridge.calls), 3)
        self.assertEqual(sum(name == 'mobile_snapshot' for name, _ in bridge.calls), 2)

    def test_unknown_or_ambiguous_delivery_errors_never_replay_input(self):
        for code in ('bridge_response_timeout', 'connection_failed', 'action_failed', 'controller_busy',
            'gesture_timeout', 'gesture_cancelled', 'screen_unstable', 'observation_unavailable'):
            with self.subTest(code=code):
                bridge = ScriptedBridge([smoke.SmokeFailure(code)])
                with self.assertRaises(smoke.SmokeFailure) as error:
                    self.acceptance(bridge).click(fixture_tree('snapshot-1', 'e1'), 'Verify isolated tap')
                self.assertEqual(error.exception.code, code)
                self.assertEqual(sum(name == 'mobile_click' for name, _ in bridge.calls), 1)
                self.assertEqual(sum(name == 'mobile_snapshot' for name, _ in bridge.calls), 0)

    def test_submitted_but_unverified_input_is_not_replayed(self):
        bridge = ScriptedBridge([{'display_id': 7, 'input_submitted': True, 'delivery_verified': False}])
        self.acceptance(bridge).click(fixture_tree('snapshot-1', 'e1'), 'Verify isolated tap')
        self.assertEqual(sum(name == 'mobile_click' for name, _ in bridge.calls), 1)
        self.assertEqual(sum(name == 'mobile_snapshot' for name, _ in bridge.calls), 0)

    def test_wrong_display_success_never_triggers_another_click(self):
        bridge = ScriptedBridge([{'display_id': 0}])
        with self.assertRaises(smoke.SmokeFailure) as error:
            self.acceptance(bridge).click(fixture_tree('snapshot-1', 'e1'), 'Verify isolated tap')
        self.assertEqual(error.exception.code, 'action_wrong_display')
        self.assertEqual(sum(name == 'mobile_click' for name, _ in bridge.calls), 1)

    @patch('device_smoke.time.sleep')
    def test_reselection_aborts_if_target_changes_to_primary(self, _sleep):
        bridge = ScriptedBridge([smoke.SmokeFailure('stale_ref')], targets=['agent', 'primary'])
        with self.assertRaises(smoke.SmokeFailure) as error:
            self.acceptance(bridge).click(fixture_tree('snapshot-1', 'e1'), 'Verify isolated tap')
        self.assertEqual(error.exception.code, 'agent_target_required')
        self.assertEqual(sum(name == 'mobile_click' for name, _ in bridge.calls), 1)
        self.assertEqual(sum(name == 'mobile_snapshot' for name, _ in bridge.calls), 0)

    @patch('device_smoke.time.sleep')
    def test_reselection_rejects_primary_snapshot_before_another_click(self, _sleep):
        bridge = ScriptedBridge([smoke.SmokeFailure('stale_ref')],
            [fixture_tree('snapshot-2', 'e2', display_id=0)])
        with self.assertRaises(smoke.SmokeFailure) as error:
            self.acceptance(bridge).click(fixture_tree('snapshot-1', 'e1'), 'Verify isolated tap')
        self.assertEqual(error.exception.code, 'snapshot_wrong_display')
        self.assertEqual(sum(name == 'mobile_click' for name, _ in bridge.calls), 1)


class DriverTransportArgumentTests(unittest.TestCase):
    @staticmethod
    def process():
        class Process:
            stdin = io.BytesIO()
            stdout = io.BytesIO()
            stderr = io.BytesIO()
            returncode = 0

            def wait(self, timeout=None):
                return 0
        return Process()

    def test_verified_pin_is_passed_to_actual_stdio_executable(self):
        pin = '12' * 32
        endpoint = 'https://100.100.100.100:8766'
        with patch('device_smoke.subprocess.Popen', return_value=self.process()) as start:
            client = smoke.StdioMcp(endpoint, Path('/private/unused-token'), 45, server_fingerprint=pin)
            self.addCleanup(client.close)
            command = start.call_args.args[0]
            self.assertEqual(command[command.index('--server-fingerprint') + 1], pin)
            self.assertEqual(command[command.index('--endpoint') + 1], endpoint)
            self.assertEqual(Path(command[1]).name, 'hands_mcp.py')
            self.assertNotIn('shell', start.call_args.kwargs)

    def test_loopback_mode_omits_pin_but_explicit_empty_pin_is_not_silently_dropped(self):
        for pin in (None, ''):
            with self.subTest(pin=pin), patch('device_smoke.subprocess.Popen', return_value=self.process()) as start:
                client = smoke.StdioMcp('http://127.0.0.1:18765', Path('/private/unused-token'), 45,
                    server_fingerprint=pin)
                self.addCleanup(client.close)
                command = start.call_args.args[0]
                if pin is None:
                    self.assertNotIn('--server-fingerprint', command)
                else:
                    self.assertEqual(command[command.index('--server-fingerprint') + 1], '')


if __name__ == '__main__':
    unittest.main()
