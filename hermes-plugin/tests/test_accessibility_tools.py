from __future__ import annotations

import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import _isolate  # noqa: F401,E402
from broker import Broker, BrokerError, LiveDevice, MockDevice
from schemas import ACCESSIBILITY_STATUS, ACCESSIBILITY_ENABLE, ACCESSIBILITY_DISABLE
from tickets import ALLOWLIST
from tools import make_handlers


class AccessibilityToolsTests(unittest.TestCase):
    def test_schemas_handlers_manifest_and_tickets_match(self):
        handlers = make_handlers(Broker())
        manifest = (Path(__file__).resolve().parents[1] / 'plugin.yaml').read_text()
        for schema in (ACCESSIBILITY_STATUS, ACCESSIBILITY_ENABLE, ACCESSIBILITY_DISABLE):
            self.assertIn(schema['name'], handlers)
            self.assertIn('  - ' + schema['name'] + '\n', manifest)
        for suffix in ('status', 'enable', 'disable'):
            self.assertIn('device.accessibility.' + suffix, ALLOWLIST)

    def test_status_and_off_work_while_disarmed_but_on_cannot_auto_arm(self):
        calls = []
        def send(action, args):
            calls.append((action, args))
            return {'a11y_bound': False, 'armed': False, 'explicitly_off': True}
        broker = Broker(device=MockDevice(armed=False, a11y_bound=False, handler=send))
        handlers = make_handlers(broker)
        self.assertTrue(json.loads(handlers['mobile_accessibility_status']({}))['ok'])
        refused = json.loads(handlers['mobile_accessibility_enable']({}))
        self.assertEqual(refused['error']['code'], 'disarmed')
        self.assertTrue(json.loads(handlers['mobile_accessibility_disable']({}))['ok'])
        self.assertEqual([action for action, _ in calls], ['device.accessibility.status', 'device.accessibility.disable'])

    def test_on_routes_device_and_needs_no_accessibility_or_foreground_access(self):
        calls = []
        device = MockDevice(armed=True, a11y_bound=False, foreground_app='com.protected.app',
            handler=lambda action, args: calls.append((action, args)) or {'requested': True, 'a11y_bound': False})
        handlers = make_handlers(Broker(device=device, extra_protected=('com.protected.app',)))
        result = json.loads(handlers['mobile_accessibility_enable']({'device': 'phone-id'}))
        self.assertTrue(result['ok'])
        self.assertFalse(result['result']['a11y_bound'])
        self.assertEqual(calls, [('device.accessibility.enable', {'_device': 'phone-id'})])

    def test_off_and_disarm_bypass_rate_limits_and_protected_foreground(self):
        for action in ('device.accessibility.disable', 'device.disarm'):
            broker = Broker(device=MockDevice(armed=False, foreground_app='com.protected.app', handler=lambda a, p: {}),
                extra_protected=('com.protected.app',), clock=lambda: 10)
            broker.hits = [10] * 10
            broker.dispatch(action)
            with self.assertRaises(BrokerError) as ctx:
                broker.dispatch('device.accessibility.status')
            self.assertEqual(ctx.exception.code, 'rate_limited')

    def test_phone_permission_failure_is_reported_without_retry_or_auto_arm(self):
        calls = []
        def send(action, args):
            calls.append(action)
            raise BrokerError('permission_denied', 'missing WRITE_SECURE_SETTINGS')
        result = json.loads(make_handlers(Broker(device=LiveDevice(send, armed=True)))['mobile_accessibility_enable']({}))
        self.assertEqual(result['error']['code'], 'permission_denied')
        self.assertEqual(calls, ['device.accessibility.enable'])

    def test_new_tools_obey_room_guard(self):
        calls = []
        handlers = make_handlers(Broker(device=MockDevice(handler=lambda a, p: calls.append(a) or {})))
        with patch('tools._room_guard', side_effect=BrokerError('room_control_denied', 'room turn')):
            for schema in (ACCESSIBILITY_STATUS, ACCESSIBILITY_ENABLE, ACCESSIBILITY_DISABLE):
                result = json.loads(handlers[schema['name']]({}))
                self.assertEqual(result['error']['code'], 'room_control_denied')
        self.assertEqual(calls, [])


if __name__ == '__main__':
    unittest.main()
