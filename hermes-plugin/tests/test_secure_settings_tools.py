from __future__ import annotations

import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import _isolate  # noqa: F401,E402
import schemas
from broker import Broker, MockDevice
from tickets import ALLOWLIST as TICKET_ACTIONS
from tools import make_handlers


class SecureSettingsToolsTest(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.device = MockDevice(armed=True, a11y_bound=False, foreground_app='example.protected',
            handler=lambda action, args: self.calls.append((action, args)) or {'accepted': True})
        self.handlers = make_handlers(Broker(device=self.device, extra_protected=('example.protected', 'com.blocked.*')))

    def call(self, name, params=None):
        return json.loads(self.handlers[name](params or {}))

    def test_catalog_and_ticket_routes_expose_explicit_typed_tools(self):
        manifest = (Path(__file__).resolve().parents[1] / 'plugin.yaml').read_text()
        mcp = json.loads((Path(__file__).resolve().parents[2] / 'hands-mcp' / 'tool_catalog.json').read_text())
        routes = {row['name']: row.get('action') for row in mcp['tools']}
        for constant, action in (
            ('ACCESSIBILITY_SERVICES', 'device.accessibility.services'),
            ('ACCESSIBILITY_SERVICE_SET', 'device.accessibility.service.set'),
            ('SECURE_SETTINGS', 'device.secure_settings.list'),
            ('SECURE_SETTINGS_SET', 'device.secure_settings.set'),
        ):
            schema = getattr(schemas, constant)
            self.assertIn(schema['name'], self.handlers)
            self.assertIn('  - ' + schema['name'] + '\n', manifest)
            self.assertIn(action, TICKET_ACTIONS)
            self.assertEqual(action, routes[schema['name']])
            self.assertFalse(schema['parameters']['additionalProperties'])
        self.assertEqual({'display_inversion', 'show_keyboard_with_hardware_keyboard'},
            set(schemas.SECURE_SETTINGS_SET['parameters']['properties']['setting']['enum']))

    def test_plugin_register_exposes_the_four_handlers(self):
        import __init__ as plugin

        class Registry:
            def __init__(self): self.tools = {}
            def register_tool(self, *, name, toolset, schema, handler):
                self.tools[name] = (toolset, schema, handler)

        registry = Registry()
        with patch.dict('os.environ', {'HERMES_COMPANION_RELAY': '0'}), patch('live.attach_http'):
            plugin.register(registry)
        for name in ('mobile_accessibility_services', 'mobile_accessibility_service_set',
                     'mobile_secure_settings', 'mobile_secure_settings_set'):
            self.assertIn(name, registry.tools)
            self.assertTrue(callable(registry.tools[name][2]))

    def test_armed_requests_forward_exact_typed_arguments_without_screen_lane(self):
        cases = [
            ('mobile_accessibility_services', {}, 'device.accessibility.services', {}),
            ('mobile_accessibility_service_set', {'component': 'example.reader/.Service', 'enabled': False, 'device': 'phone'},
             'device.accessibility.service.set', {'component': 'example.reader/.Service', 'enabled': False, '_device': 'phone'}),
            ('mobile_accessibility_service_set', {'component': 'android/系统服务', 'enabled': False},
             'device.accessibility.service.set', {'component': 'android/系统服务', 'enabled': False}),
            ('mobile_secure_settings', {}, 'device.secure_settings.list', {}),
            ('mobile_secure_settings_set', {'setting': 'display_inversion', 'enabled': True},
             'device.secure_settings.set', {'setting': 'display_inversion', 'enabled': True}),
        ]
        for name, args, action, forwarded in cases:
            with self.subTest(name=name):
                self.assertTrue(self.call(name, args)['ok'])
                self.assertEqual((action, forwarded), self.calls[-1])

    def test_every_new_tool_requires_arm_and_never_auto_arms(self):
        self.device.armed = False
        cases = [
            ('mobile_accessibility_services', {}),
            ('mobile_accessibility_service_set', {'component': 'example.reader/.Service', 'enabled': True}),
            ('mobile_secure_settings', {}),
            ('mobile_secure_settings_set', {'setting': 'display_inversion', 'enabled': False}),
        ]
        for name, args in cases:
            self.assertEqual('disarmed', self.call(name, args)['error']['code'])
        self.assertEqual([], self.calls)

    def test_invalid_or_arbitrary_keys_and_nonboolean_are_never_forwarded(self):
        cases = [
            ('mobile_secure_settings', {'key': 'arbitrary'}),
            ('mobile_secure_settings_set', {'setting': 'adb_enabled', 'enabled': True}),
            ('mobile_secure_settings_set', {'setting': [], 'enabled': True}),
            ('mobile_secure_settings_set', {'setting': 'display_inversion', 'enabled': 'true'}),
            ('mobile_accessibility_service_set', {'component': 'example.reader/.Service', 'enabled': 0}),
            ('mobile_accessibility_service_set', {'component': 'not/a/service', 'enabled': True}),
            ('mobile_accessibility_service_set', {'component': 'example.reader/.Service', 'enabled': False, 'extra': 1}),
        ]
        for name, args in cases:
            with self.subTest(name=name, args=args):
                self.assertEqual('invalid_args', self.call(name, args)['error']['code'])
        self.assertEqual([], self.calls)

    def test_protected_service_component_is_denied_before_forwarding(self):
        result = self.call('mobile_accessibility_service_set', {'component': 'com.blocked.reader/.Service', 'enabled': False})
        self.assertEqual('protected_package', result['error']['code'])
        self.assertEqual([], self.calls)

    def test_room_turn_cannot_use_new_tools(self):
        from broker import BrokerError
        with patch('tools._room_guard', side_effect=BrokerError('room_control_denied', 'room turn')):
            for name, args in [
                ('mobile_accessibility_services', {}), ('mobile_secure_settings', {}),
                ('mobile_accessibility_service_set', {'component': 'example.reader/.Service', 'enabled': False}),
                ('mobile_secure_settings_set', {'setting': 'display_inversion', 'enabled': False}),
            ]:
                self.assertEqual('room_control_denied', self.call(name, args)['error']['code'])
        self.assertEqual([], self.calls)


if __name__ == '__main__':
    unittest.main()
