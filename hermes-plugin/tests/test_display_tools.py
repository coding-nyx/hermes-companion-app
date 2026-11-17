from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import _isolate  # noqa: F401,E402
from broker import ALLOWLIST, META, Broker, BrokerError, LiveDevice, MockDevice
from schemas import ARM, KEEP_AWAKE, BRIGHTNESS_GET, BRIGHTNESS_SET
from tickets import ALLOWLIST as TICKET_ALLOWLIST
from tools import error_hint, make_handlers


class DisplayToolsTests(unittest.TestCase):
    def handlers(self, *, armed=False):
        calls = []
        broker = Broker(device=MockDevice(armed=armed, a11y_bound=False,
            foreground_app='com.protected.app',
            handler=lambda action, args: calls.append((action, args)) or {'armed': armed}),
            extra_protected=('com.protected.app',))
        return make_handlers(broker), calls, broker

    def test_tools_are_registered_and_ticketed(self):
        handlers, _, _ = self.handlers()
        root = Path(__file__).resolve().parents[1]
        manifest = (root / 'plugin.yaml').read_text()
        for schema in (KEEP_AWAKE, BRIGHTNESS_GET, BRIGHTNESS_SET):
            self.assertIn(schema['name'], handlers)
            self.assertIn('  - ' + schema['name'] + '\n', manifest)
        for action in ('device.display.keep_awake', 'device.display.brightness.get', 'device.display.brightness.set'):
            self.assertIn(action, ALLOWLIST)
            self.assertIn(action, TICKET_ALLOWLIST)
        spec = importlib.util.spec_from_file_location('display_test_plugin', root / '__init__.py',
            submodule_search_locations=[str(root)])
        plugin = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = plugin
        try:
            spec.loader.exec_module(plugin)
            registered = {}
            class Context:
                def register_tool(self, **kwargs):
                    registered[kwargs['name']] = kwargs
            with patch.dict(os.environ, {'HERMES_COMPANION_RELAY': '0'}):
                plugin.register(Context())
            for schema in (KEEP_AWAKE, BRIGHTNESS_GET, BRIGHTNESS_SET):
                self.assertEqual(registered[schema['name']]['schema'], schema)
                self.assertTrue(callable(registered[schema['name']]['handler']))
        finally:
            for name in list(sys.modules):
                if name == spec.name or name.startswith(spec.name + '.'):
                    del sys.modules[name]

    def test_standalone_hold_can_be_enabled_and_read_without_arming_or_accessibility(self):
        handlers, calls, broker = self.handlers()
        for args in ({}, {'action': 'set', 'enabled': True, 'duration_seconds': 600, 'device': 'phone'},
            {'action': 'set', 'enabled': False}):
            self.assertTrue(json.loads(handlers['mobile_keep_awake'](args))['ok'])
            self.assertFalse(broker.device.armed)
        self.assertEqual(calls, [
            ('device.display.keep_awake', {'action': 'status'}),
            ('device.display.keep_awake', {'action': 'set', 'enabled': True, 'duration_seconds': 600, '_device': 'phone'}),
            ('device.display.keep_awake', {'action': 'set', 'enabled': False}),
        ])
        self.assertIn('device.display.keep_awake', META)

    def test_standalone_hold_rejects_invalid_or_ambiguous_arguments(self):
        handlers, calls, _ = self.handlers()
        cases = [
            {'action': []}, {'action': 'renew'}, {'enabled': True},
            {'action': 'set'}, {'action': 'set', 'enabled': 1},
            {'action': 'set', 'enabled': 'false'},
            {'action': 'set', 'enabled': False, 'duration_seconds': 60},
            *({'action': 'set', 'enabled': True, 'duration_seconds': value}
                for value in (None, True, 29, 7201, 60.5, '60')),
        ]
        for args in cases:
            result = json.loads(handlers['mobile_keep_awake'](args))
            self.assertEqual(result['error']['code'], 'invalid_args', args)
        self.assertEqual(calls, [])

    def test_standalone_release_bypasses_rate_limit_but_enabling_does_not(self):
        handlers, calls, broker = self.handlers()
        broker.clock = lambda: 10
        broker.hits = [10] * 10
        self.assertTrue(json.loads(handlers['mobile_keep_awake']({'action': 'set', 'enabled': False}))['ok'])
        result = json.loads(handlers['mobile_keep_awake']({'action': 'set', 'enabled': True}))
        self.assertEqual(result['error']['code'], 'rate_limited')
        self.assertEqual(calls, [('device.display.keep_awake', {'action': 'set', 'enabled': False})])

    def test_brightness_read_is_passive_but_writes_require_arm(self):
        handlers, calls, _ = self.handlers()
        self.assertTrue(json.loads(handlers['mobile_brightness_get']({'device': 'phone'}))['ok'])
        result = json.loads(handlers['mobile_brightness_set']({'level': 45}))
        self.assertEqual(result['error']['code'], 'disarmed')
        self.assertEqual(calls, [('device.display.brightness.get', {'_device': 'phone'})])

    def test_brightness_changes_need_no_accessibility_or_foreground_access(self):
        handlers, calls, _ = self.handlers(armed=True)
        for args in ({'level': 1}, {'level': 100, 'auto': False}, {'auto': True}, {'auto': False}):
            self.assertTrue(json.loads(handlers['mobile_brightness_set'](args))['ok'])
            self.assertEqual(calls[-1], ('device.display.brightness.set', args))

    def test_brightness_rejects_invalid_values_before_dispatch(self):
        handlers, calls, _ = self.handlers(armed=True)
        for args in ({}, {'level': 0}, {'level': 101}, {'level': True}, {'level': 5.5},
            {'level': '50'}, {'auto': 1}, {'auto': 'false'}, {'auto': None}, {'level': 50, 'auto': True}):
            result = json.loads(handlers['mobile_brightness_set'](args))
            self.assertEqual(result['error']['code'], 'invalid_args', args)
        self.assertEqual(calls, [])

    def test_arm_lease_rejects_invalid_duration_without_auto_arming(self):
        handlers, calls, broker = self.handlers()
        for duration in (None, False, 29, 7201, '600', 60.5):
            result = json.loads(handlers['mobile_arm']({'duration_seconds': duration}))
            self.assertEqual(result['error']['code'], 'invalid_args')
        self.assertFalse(broker.device.armed)
        self.assertEqual(calls, [])
        self.assertEqual(ARM['parameters']['properties']['duration_seconds']['default'], 3600)

    def test_new_tools_obey_room_guard(self):
        handlers, calls, _ = self.handlers(armed=True)
        with patch('tools._room_guard', side_effect=BrokerError('room_control_denied', 'room turn')):
            for name, args in (
                ('mobile_keep_awake', {}), ('mobile_keep_awake', {'action': 'set', 'enabled': False}),
                ('mobile_brightness_get', {}), ('mobile_brightness_set', {'level': 50}),
            ):
                result = json.loads(handlers[name](args))
                self.assertEqual(result['error']['code'], 'room_control_denied')
        self.assertEqual(calls, [])

    def test_brightness_permission_hint_opens_the_correct_special_access_page(self):
        hint = error_hint('permission_denied', 'missing WRITE_SETTINGS')
        self.assertIn("mobile_settings_open(page='write_settings')", hint)
        self.assertIn('operator', hint)

    def test_status_queries_actual_phone_after_local_disarm_and_never_uses_cached_arm(self):
        calls = []
        device = LiveDevice(armed=True, send=lambda action, args:
            calls.append((action, args)) or {'armed': False, 'a11y_bound': False,
                'keep_awake': {'enabled': False}, 'foreground_app': ''})
        result = json.loads(make_handlers(Broker(device=device))['mobile_status']({'device': 'phone'}))
        self.assertTrue(result['ok'])
        self.assertFalse(result['armed'])
        self.assertFalse(device.armed)
        self.assertEqual(result['keep_awake'], {'enabled': False})
        self.assertIn('DISARMED', result['hint'])
        self.assertEqual(calls, [('device.status', {'_device': 'phone'})])

    def test_status_rejects_missing_or_malformed_phone_arm_state(self):
        for response in ({}, {'armed': 'true'}, {'armed': 1}):
            device = LiveDevice(armed=True, send=lambda action, args: response)
            result = json.loads(make_handlers(Broker(device=device))['mobile_status']({}))
            self.assertEqual(result['error']['code'], 'invalid_status')

    def test_live_cache_is_informational_and_phone_enforces_arm(self):
        calls = []
        def send(action, args):
            calls.append(action)
            raise BrokerError('disarmed', 'phone is disarmed')
        device = LiveDevice(send=send)
        self.assertFalse(device.armed)
        response = json.loads(make_handlers(Broker(device=device))['mobile_brightness_set']({'level': 50}))
        self.assertEqual(response['error']['code'], 'disarmed')
        self.assertFalse(device.armed)
        self.assertEqual(calls, ['device.display.brightness.set'])

    def test_arm_attaches_to_the_active_turn_without_dropping_tool_context(self):
        class Manager:
            def observe_arm(self, params, result, context):
                self.seen = (params, result, context)
                return True
        manager = Manager()
        broker = Broker(device=MockDevice(handler=lambda action, args: {'armed': True, 'lease_id': 'lease'}))
        result = json.loads(make_handlers(broker, working_lease=manager)['mobile_arm'](
            {'duration_seconds': 600}, session_id='session', task_id='task'))
        self.assertTrue(result['result']['working_lease_managed'])
        self.assertEqual(manager.seen[0], {'duration_seconds': 600})
        self.assertEqual(manager.seen[2], {'session_id': 'session', 'task_id': 'task'})


if __name__ == '__main__':
    unittest.main()
