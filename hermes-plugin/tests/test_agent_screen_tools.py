from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import _isolate  # noqa: F401,E402
from broker import ALLOWLIST, L0_NO_A11Y, L0_PASSIVE, META, Broker, BrokerError, LiveDevice, MockDevice
from schemas import AGENT_SCREEN_START, AGENT_SCREEN_STATUS, AGENT_SCREEN_STOP
from tickets import ALLOWLIST as TICKET_ALLOWLIST
from tools import ERROR_HINTS, armed_hint, make_handlers


SCHEMAS = (AGENT_SCREEN_START, AGENT_SCREEN_STATUS, AGENT_SCREEN_STOP)


def status(*, active=False, armed=False, target='agent'):
    return {'service': 'hermes-hands', 'target': target, 'armed': armed,
        'consent_approved': armed, 'agent_screen': {'active': active, 'display_id': 7 if active else -1}}


class AgentScreenToolsTests(unittest.TestCase):
    def test_mobile_opt_out_preserves_companion_cli_without_registering_mobile(self):
        root = Path(__file__).resolve().parents[1]
        spec = importlib.util.spec_from_file_location('mobile_opt_out_plugin', root / '__init__.py',
            submodule_search_locations=[str(root)])
        plugin = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = plugin
        try:
            spec.loader.exec_module(plugin)
            calls = []
            class Context:
                def register_tool(self, **kwargs): calls.append('tool')
                def register_skill(self, *args, **kwargs): calls.append('skill')
                def register_system_prompt_section(self, *args, **kwargs): calls.append('prompt')
                def register_command(self, *args, **kwargs): calls.append('command')
                def register_cli_command(self, **kwargs): calls.append('cli')
            with patch.dict(os.environ, {'HERMES_COMPANION_RELAY': '0', 'HERMES_COMPANION_MOBILE_ENABLED': '0'}), \
                    patch.object(plugin.working_lease, 'register') as lease:
                plugin.register(Context())
            self.assertEqual(calls, ['cli'])
            lease.assert_not_called()
            with tempfile.TemporaryDirectory() as directory, \
                    patch.object(plugin, '_MCP_ONLY_MARKER', Path(directory) / '.hands-mcp-only'), \
                    patch.dict(os.environ, {}, clear=True):
                self.assertTrue(plugin._mobile_enabled())
                plugin._MCP_ONLY_MARKER.touch()
                self.assertFalse(plugin._mobile_enabled())
                with patch.dict(os.environ, {'HERMES_COMPANION_MOBILE_ENABLED': '1'}):
                    self.assertTrue(plugin._mobile_enabled())
                with patch.dict(os.environ, {'HERMES_COMPANION_MOBILE_ENABLED': '0'}):
                    self.assertFalse(plugin._mobile_enabled())
        finally:
            for name in list(sys.modules):
                if name == spec.name or name.startswith(spec.name + '.'):
                    del sys.modules[name]

    def handlers(self, *, armed=False, response=None):
        calls = []
        def send(action, args):
            calls.append((action, args))
            if response is not None:
                return response
            if action == 'device.agent_screen.start':
                return {'target': 'agent', 'active': True, 'display_id': 7,
                    'width': args['width'], 'height': args['height'], 'density': args['density']}
            return status(armed=armed if action != 'device.agent_screen.stop' else False)
        broker = Broker(device=MockDevice(armed=armed, a11y_bound=False,
            foreground_app='com.protected.primary', handler=send),
            extra_protected=('com.protected.primary',))
        return make_handlers(broker), calls, broker

    def test_tool_schema_registration_manifest_and_ticket_inventory_match(self):
        handlers, _, _ = self.handlers()
        root = Path(__file__).resolve().parents[1]
        manifest = (root / 'plugin.yaml').read_text()
        for schema in SCHEMAS:
            self.assertIn(schema['name'], handlers)
            self.assertIn('  - ' + schema['name'] + '\n', manifest)
        for suffix in ('start', 'status', 'stop'):
            action = 'device.agent_screen.' + suffix
            self.assertIn(action, ALLOWLIST)
            self.assertIn(action, TICKET_ALLOWLIST)
            if suffix != 'stop':
                self.assertIn(action, L0_NO_A11Y)
        self.assertIn('device.agent_screen.status', L0_PASSIVE)
        self.assertNotIn('device.agent_screen.start', META | L0_PASSIVE)
        self.assertIn('device.agent_screen.stop', META)
        spec = importlib.util.spec_from_file_location('agent_screen_test_plugin', root / '__init__.py',
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
            for schema in SCHEMAS:
                self.assertEqual(registered[schema['name']]['schema'], schema)
                self.assertTrue(callable(registered[schema['name']]['handler']))
            prompt = plugin._prompt()
            for phrase in ('mobile_agent_screen_start/status/stop', 'start the service', 'primary display', 'ASCII'):
                self.assertIn(phrase, prompt)
        finally:
            for name in list(sys.modules):
                if name == spec.name or name.startswith(spec.name + '.'):
                    del sys.modules[name]

    def test_status_is_passive_but_creation_cannot_auto_arm(self):
        handlers, calls, broker = self.handlers()
        result = json.loads(handlers['mobile_agent_screen_status']({'device': 'phone'}))
        self.assertTrue(result['ok'])
        self.assertFalse(result['result']['agent_screen']['active'])
        result = json.loads(handlers['mobile_agent_screen_start']({}))
        self.assertEqual(result['error']['code'], 'disarmed')
        self.assertFalse(broker.device.armed)
        self.assertEqual(calls, [('device.agent_screen.status', {'_device': 'phone'})])

    def test_start_needs_no_companion_accessibility_or_primary_foreground_access(self):
        handlers, calls, _ = self.handlers(armed=True)
        result = json.loads(handlers['mobile_agent_screen_start']({'device': 'phone'}))
        self.assertTrue(result['ok'])
        self.assertEqual(result['result']['target'], 'agent')
        self.assertGreater(result['result']['display_id'], 0)
        self.assertEqual(calls, [('device.agent_screen.start', {
            '_device': 'phone', 'width': 720, 'height': 1280, 'density': 240})])

    def test_start_accepts_status_style_payload(self):
        handlers, _, _ = self.handlers(armed=True, response=status(active=True, armed=True))
        self.assertTrue(json.loads(handlers['mobile_agent_screen_start']({}))['ok'])

    def test_start_rejects_invalid_bounds_types_and_unknown_args_before_dispatch(self):
        handlers, calls, _ = self.handlers(armed=True)
        for args in ({'width': 319}, {'height': 2561}, {'density': 119}, {'density': 641},
            {'width': True}, {'height': '720'}, {'density': 240.0}, {'device': ''},
            {'device': 7}, {'target': 'primary'}, {'command': 'input tap 1 2'}, []):
            with self.subTest(args=args):
                result = json.loads(handlers['mobile_agent_screen_start'](args))
                self.assertEqual(result['error']['code'], 'invalid_args')
        self.assertEqual(calls, [])

    def test_stop_globally_disarms_even_when_disarmed_or_rate_limited(self):
        handlers, calls, broker = self.handlers()
        broker.clock = lambda: 10
        broker.hits = [10] * 10
        result = json.loads(handlers['mobile_agent_screen_stop']({}))
        self.assertTrue(result['ok'])
        self.assertFalse(result['result']['armed'])
        self.assertFalse(result['result']['agent_screen']['active'])
        self.assertEqual(calls, [('device.agent_screen.stop', {})])
        result = json.loads(handlers['mobile_agent_screen_status']({}))
        self.assertEqual(result['error']['code'], 'rate_limited')

    def test_stop_cannot_report_success_until_screen_closed_and_disarmed(self):
        for response in (status(active=True), status(armed=True), {'ok': True},
            {'target': 'agent', 'armed': False, 'agent_screen': {'active': False, 'display_id': 7}}):
            handlers, _, _ = self.handlers(response=response)
            result = json.loads(handlers['mobile_agent_screen_stop']({}))
            self.assertEqual(result['error']['code'], 'invalid_agent_screen_result')

    def test_primary_status_does_not_claim_agent_display_is_selected(self):
        handlers, _, _ = self.handlers(response=status(active=True, target='primary'))
        result = json.loads(handlers['mobile_agent_screen_status']({}))
        self.assertTrue(result['ok'])
        self.assertEqual(result['result']['target'], 'primary')
        handlers, _, _ = self.handlers(armed=True, response=status(active=True, target='primary'))
        result = json.loads(handlers['mobile_agent_screen_start']({}))
        self.assertEqual(result['error']['code'], 'invalid_agent_screen_result')

    def test_generic_or_primary_display_success_is_refused(self):
        for response in ({'ok': True, 'action': 'device.agent_screen.start'},
            {'target': 'agent', 'active': True, 'display_id': 0},
            {'target': 'agent', 'active': True, 'display_id': True},
            {'target': 'agent', 'active': False, 'display_id': -1}, {'target': []}):
            handlers, calls, _ = self.handlers(armed=True, response=response)
            result = json.loads(handlers['mobile_agent_screen_start']({}))
            self.assertEqual(result['error']['code'], 'invalid_agent_screen_result')
            self.assertEqual(len(calls), 1)
            self.assertIn('primary display', result['error']['hint'])

    def test_phone_refusal_is_not_retried_or_replaced_with_primary_control(self):
        for code in ('capability_denied', 'local_consent_required', 'target_mismatch', 'backend_mismatch', 'shizuku_unavailable'):
            calls = []
            def send(action, args):
                calls.append(action)
                raise BrokerError(code, 'Phone refused this request')
            handlers = make_handlers(Broker(device=LiveDevice(send=send, armed=True)))
            result = json.loads(handlers['mobile_agent_screen_start']({}))
            self.assertEqual(result['error']['code'], code)
            self.assertEqual(calls, ['device.agent_screen.start'])

    def test_selection_mismatch_guidance_uses_status_not_session_approval(self):
        for code in ('target_mismatch', 'backend_mismatch', 'local_consent_required'):
            hint = ERROR_HINTS[code]
            self.assertIn('mobile_status', hint)
            self.assertNotIn('approve a new session', hint.lower())
            self.assertNotIn('allow a new session', hint.lower())

    def test_live_phone_enforces_consent_even_if_host_arm_cache_is_stale(self):
        calls = []
        def send(action, args):
            calls.append(action)
            raise BrokerError('disarmed', 'Hands session ended locally')
        device = LiveDevice(send=send, armed=True)
        result = json.loads(make_handlers(Broker(device=device))['mobile_agent_screen_start']({}))
        self.assertEqual(result['error']['code'], 'disarmed')
        self.assertFalse(device.armed)
        self.assertEqual(calls, ['device.agent_screen.start'])

    def test_all_agent_screen_tools_obey_room_guard(self):
        handlers, calls, _ = self.handlers(armed=True)
        with patch('tools._room_guard', side_effect=BrokerError('room_control_denied', 'room turn')):
            for schema in SCHEMAS:
                result = json.loads(handlers[schema['name']]({}))
                self.assertEqual(result['error']['code'], 'room_control_denied')
        self.assertEqual(calls, [])

    def test_armed_hints_include_local_hands_consent_and_no_primary_fallback(self):
        for armed in (True, False):
            hint = armed_hint(True, armed)
            self.assertIn('mobile_agent_screen_status', hint)
            self.assertIn('service', hint)
            self.assertIn('primary display', hint)


if __name__ == '__main__':
    unittest.main()
