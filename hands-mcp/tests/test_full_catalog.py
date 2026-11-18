import base64
import hashlib
import json
import os
from pathlib import Path
import tempfile
import sys
import unittest
from unittest.mock import patch
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import hands_mcp as hands
from test_hands_mcp import Phone, TOKEN


class FullCatalogTests(unittest.TestCase):
    def setUp(self):
        self.phone = Phone()
        self.addCleanup(self.phone.close)
        self.client = hands.HandsClient(self.phone.endpoint, TOKEN, heartbeat=False)
        self.addCleanup(self.client.close)

    def arm(self):
        self.client.call('mobile_arm', {})
        self.phone.requests.clear()

    def test_discovery_covers_all_phone_families_without_host_or_gateway_tools(self):
        self.assertEqual(len(hands.TOOLS), 85)
        expected = '''status devices snapshot click type secure_entry press swipe scroll open_app apps wait arm keep_awake brightness_get brightness_set disarm agent_screen_start agent_screen_status agent_screen_stop accessibility_status accessibility_enable accessibility_disable accessibility_services accessibility_service_set secure_settings secure_settings_set screenshot notifications notifications_ack notifications_clear audio_volume_get audio_volume_set audio_ringer media_sessions media_now_playing media_control clipboard_get clipboard_set device_info notifications_nls notifications_nls_enable notifications_stream notifications_stream_set notification_actions notification_action notification_reply notification_dismiss notification_snooze sms_list sms_send sms_compose calls_log calls_dial calls_place contacts_search speak speak_stop speak_status alarm_set timer_set media_play_search calendar_add share settings_open app_uninstall calendar_list location_last battery power_state thermal app_usage dnd dnd_set files_access files_enable files file_pull file_push telemetry gate_get gate_set shell power_wake keyguard_dismiss'''.split()
        self.assertEqual(set(hands.TOOLS_BY_NAME), {'mobile_' + name for name in expected})
        for excluded in ('mobile_select_device', 'mobile_notifications_inject', 'mobile_notification_policy',
                         'mobile_notification_policy_set', 'mobile_command'):
            self.assertNotIn(excluded, hands.TOOLS_BY_NAME)
        for definition in hands.TOOLS:
            self.assertNotIn('device', definition['inputSchema'].get('properties', {}))
            self.assertNotIn('action', {key for key in definition if key != 'inputSchema'})

    def test_exact_provider_and_mutation_action_mappings_keep_arguments_and_lease(self):
        self.arm()
        cases = [
            ('mobile_accessibility_services', 'device.accessibility.services', {}),
            ('mobile_accessibility_service_set', 'device.accessibility.service.set', {'component':'app.synthetic/.Reader', 'enabled':True}),
            ('mobile_accessibility_service_set', 'device.accessibility.service.set', {'component':'android/系统服务', 'enabled':True}),
            ('mobile_secure_settings', 'device.secure_settings.list', {}),
            ('mobile_secure_settings_set', 'device.secure_settings.set', {'setting':'display_inversion', 'enabled':False}),
            ('mobile_sms_list', 'device.sms.list', {'limit': 2, 'box': 'inbox', 'query': 'synthetic'}),
            ('mobile_sms_send', 'device.sms.send', {'address': '5550100', 'body': 'Synthetic fixture message'}),
            ('mobile_sms_compose', 'device.sms.compose', {'address': '5550100', 'body': 'Synthetic draft'}),
            ('mobile_calls_log', 'device.calls.log', {'type': 'missed', 'limit': 1}),
            ('mobile_calls_dial', 'device.calls.dial', {'address': '5550100'}),
            ('mobile_calls_place', 'device.calls.place', {'address': '5550100'}),
            ('mobile_contacts_search', 'device.contacts.search', {'query': 'Synthetic', 'limit': 1}),
            ('mobile_calendar_list', 'device.calendar.list', {'from_ms': 1000, 'to_ms': 2000}),
            ('mobile_calendar_add', 'device.calendar.add', {'title': 'Synthetic', 'begin_ms': 1000, 'end_ms': 2000}),
            ('mobile_location_last', 'device.location.last', {}),
            ('mobile_app_usage', 'device.apps.usage', {'limit': 2, 'since_ms': 1000}),
            ('mobile_dnd_set', 'device.audio.dnd.set', {'mode': 'priority'}),
            ('mobile_media_control', 'device.media.control', {'session_id': 'app.synthetic', 'action': 'seek', 'position_ms': 500}),
            ('mobile_audio_volume_set', 'device.audio.volume.set', {'stream': 'music', 'step': -1}),
            ('mobile_clipboard_set', 'device.clipboard.set', {'text': 'Synthetic clipboard'}),
            ('mobile_speak', 'device.voice.speak', {'text': 'Synthetic speech', 'rate': .75, 'pitch': 1.25}),
            ('mobile_alarm_set', 'device.alarm.set', {'hour': 0, 'minute': 0, 'skip_ui': False}),
            ('mobile_timer_set', 'device.timer.set', {'seconds': 10}),
            ('mobile_share', 'device.share', {'text': 'Synthetic share', 'subject': 'Fixture'}),
            ('mobile_settings_open', 'device.settings.open', {'page': 'accessibility'}),
            ('mobile_app_uninstall', 'device.apps.uninstall', {'package': 'app.synthetic'}),
            ('mobile_files_enable', 'device.files.enable', {}),
            ('mobile_accessibility_enable', 'device.accessibility.enable', {}),
        ]
        for tool, action, arguments in cases:
            with self.subTest(tool=tool):
                self.assertTrue(self.client.call(tool, arguments)['ok'])
                body = self.phone.requests[-1]['body']
                self.assertEqual(body['action'], action)
                self.assertEqual(body['arguments'], arguments)
                self.assertEqual(body['lease_id'], 'test-lease')
                self.assertEqual(body['controller_id'], self.client.controller_id)

    def test_personal_data_and_mutations_never_implicitly_arm(self):
        for tool, arguments in (
            ('mobile_accessibility_services', {}), ('mobile_accessibility_service_set', {'component':'app.synthetic/.Reader', 'enabled':True}),
            ('mobile_secure_settings', {}), ('mobile_secure_settings_set', {'setting':'display_inversion', 'enabled':True}),
            ('mobile_sms_list', {}), ('mobile_calls_log', {}), ('mobile_contacts_search', {}),
            ('mobile_location_last', {}), ('mobile_calendar_list', {}), ('mobile_clipboard_get', {}),
            ('mobile_notification_reply', {'key': 'fixture', 'text': 'Synthetic reply'}),
            ('mobile_files_enable', {}), ('mobile_files', {'action': 'list', 'path': ''}),
        ):
            with self.subTest(tool=tool), self.assertRaises(hands.BridgeError) as error:
                self.client.call(tool, arguments)
            self.assertEqual(error.exception.code, 'disarmed')
        self.assertEqual(self.phone.requests, [])

    def test_safe_metadata_reads_are_passive_without_permission_auto_grants(self):
        for tool in ('mobile_audio_volume_get', 'mobile_media_sessions', 'mobile_device_info',
                     'mobile_files_access', 'mobile_accessibility_status', 'mobile_speak_status',
                     'mobile_dnd', 'mobile_battery', 'mobile_power_state', 'mobile_thermal'):
            self.assertTrue(self.client.call(tool, {})['ok'])
            self.assertNotIn('lease_id', self.phone.requests[-1]['body'])
        self.assertNotIn('device.arm', [request['body']['action'] for request in self.phone.requests])

    def test_notifications_poll_ack_are_passive_recipient_gated_and_never_inject(self):
        delivery = str(uuid.uuid4())
        events = [{'delivery_id': delivery, 'cursor': 1, 'consent_version': 3,
            'notification': {'key': 'synthetic-key', 'passive': True, 'content_mode': 'title',
                             'title': 'Untrusted synthetic notification'}}]
        def reply(request):
            action = request['body']['action']
            if action == 'device.notifications.list':
                return 200, {'ok': True, 'result': {'events': events, 'next_cursor': 1, 'has_more': False}}, {}
            return 200, {'ok': True, 'result': {'acked': 1}}, {}
        self.phone.override = reply
        result = self.client.call('mobile_notifications', {'after_cursor': 0, 'limit': 2, 'package': 'app.synthetic', 'since_ms': 1})
        self.assertEqual(result['result']['events'], events)
        self.client.call('mobile_notifications_ack', {'delivery_ids': [delivery]})
        self.client.call('mobile_notifications_ack', {'delivery_ids': [delivery]})
        self.assertEqual([request['body']['action'] for request in self.phone.requests],
                         ['device.notifications.list', 'device.notifications.ack', 'device.notifications.ack'])
        self.assertTrue(all('lease_id' not in request['body'] for request in self.phone.requests))
        self.phone.override = lambda _: (200, {'ok': False, 'error': {'code': 'notification_consent_required', 'message': 'Allow locally'}}, {})
        denied = self.client.call('mobile_notifications', {})
        self.assertEqual(denied['error']['code'], 'notification_consent_required')
        self.assertNotIn('device.notifications.stream.set', [request['body']['action'] for request in self.phone.requests])

    def test_stream_cannot_enable_expand_host_routing_or_accept_unbounded_arrays(self):
        self.arm()
        for args in ({'enabled': True}, {'origin': 'https://elsewhere'}, {'profile': 'some-host'},
                     {'packages': ['app.synthetic'] * 101}, {'packages': ['app.*']}, {}):
            with self.subTest(args=args), self.assertRaises(hands.BridgeError):
                self.client.call('mobile_notifications_stream_set', args)
        self.assertEqual(self.phone.requests, [])
        args = {'enabled': False}
        self.client.call('mobile_notifications_stream_set', args)
        self.assertEqual(self.phone.requests[-1]['body']['arguments'], args)

    def test_secure_settings_schemas_refuse_unknown_keys_and_malformed_services(self):
        self.arm()
        before = len(self.phone.requests)
        for tool, args in (
            ('mobile_secure_settings_set', {'setting': 'adb_enabled', 'enabled': True}),
            ('mobile_secure_settings_set', {'setting': 'display_inversion', 'enabled': 'true'}),
            ('mobile_secure_settings_set', {'setting': 'display_inversion', 'enabled': True, 'raw_key': 'adb_enabled'}),
            ('mobile_accessibility_service_set', {'component': 'invalid', 'enabled': True}),
            ('mobile_accessibility_service_set', {'component': 'android/invalid/service', 'enabled': True}),
            ('mobile_accessibility_service_set', {'component': 'app.synthetic/.Reader', 'enabled': 1}),
        ):
            with self.subTest(tool=tool, args=args), self.assertRaises(hands.BridgeError) as err:
                self.client.call(tool, args)
            self.assertEqual(err.exception.code, 'invalid_arguments')
        self.assertEqual(len(self.phone.requests), before)

    def test_private_entry_never_accepts_values_or_returns_phone_content(self):
        self.arm()
        request_id = str(uuid.uuid4())
        snapshot = str(uuid.uuid4())
        arguments = {'action': 'request', 'package': 'app.synthetic', 'ref': '@e3',
                     'snapshot_id': snapshot, 'label': 'Email address'}
        self.phone.override = lambda _: (200, {'ok': True, 'result': {
            'request_id': request_id, 'status': 'pending', 'privacy_active': True,
            'value': 'DO_NOT_PUBLISH', 'screenshot': 'DO_NOT_PUBLISH'}}, {})
        result = self.client.call('mobile_secure_entry', arguments)
        self.assertEqual(result['result'], {'request_id': request_id, 'status': 'pending', 'privacy_active': True})
        self.assertIsNone(self.client.lease_id)
        before = len(self.phone.requests)
        self.client.tick()
        with self.assertRaises(hands.BridgeError) as disarmed:
            self.client.call('mobile_snapshot', {})
        self.assertEqual(disarmed.exception.code, 'disarmed')
        self.assertEqual(before, len(self.phone.requests))
        self.assertNotIn('DO_NOT_PUBLISH', json.dumps(hands.tool_result(result)))
        self.assertEqual(self.phone.requests[-1]['body']['arguments']['ref'], 'e3')
        for forbidden in ('value', 'text', 'content', 'password'):
            with self.subTest(forbidden=forbidden), self.assertRaises(hands.BridgeError):
                self.client.call('mobile_secure_entry', {**arguments, forbidden: 'DO_NOT_PUBLISH'})
        self.phone.override = None
        self.arm()
        self.phone.override = lambda _: (200, {'ok': False, 'error': {'code': 'stale_ref', 'message': 'DO_NOT_PUBLISH'}}, {})
        result = self.client.call('mobile_secure_entry', arguments)
        self.assertEqual(result['error']['code'], 'stale_ref')
        self.assertNotIn('DO_NOT_PUBLISH', json.dumps(result))

    def test_private_status_cancel_work_disarmed_but_remain_value_free(self):
        request_id = str(uuid.uuid4())
        self.phone.override = lambda _: (200, {'ok': True, 'result': {
            'request_id': request_id, 'status': 'cancelled', 'privacy_active': True}}, {})
        for operation in ('status', 'cancel'):
            self.assertTrue(self.client.call('mobile_secure_entry', {'action': operation, 'request_id': request_id})['ok'])
            self.assertNotIn('lease_id', self.phone.requests[-1]['body'])
        self.assertEqual([request['body']['action'] for request in self.phone.requests], ['device.secure_entry'] * 2)

    def test_file_chunks_are_bounded_by_decoded_bytes_and_operation_shape(self):
        self.arm()
        for args in ({'action': 'write', 'path': '/Download/test', 'content': 'x', 'data_base64': 'eA=='},
            {'action': 'write', 'path': '/Download/test', 'content': 'é' * 98305},
            {'action': 'write', 'path': '/Download/test', 'data_base64': '%%%invalid'},
            {'action': 'append', 'path': '/Download/test', 'content': 'x'},
            {'action': 'read', 'path': '/Download/test', 'max_bytes': 196609},
            {'action': 'read', 'path': '/Download/test', 'content': 'unexpected'},
            {'action': 'copy', 'path': '/Download/test'}, {'action': 'read', 'path': ''}):
            with self.subTest(action=args['action']), self.assertRaises(hands.BridgeError):
                self.client.call('mobile_files', args)
        self.assertEqual(self.phone.requests, [])
        data = base64.b64encode(b'x' * hands.FILE_CHUNK_BYTES).decode()
        self.client.call('mobile_files', {'action': 'write', 'path': '/Download/test', 'data_base64': data})
        self.assertEqual(self.phone.requests[-1]['body']['action'], 'device.files')

    def test_long_voice_wait_and_files_get_only_their_bounded_timeout_override(self):
        self.arm()
        with patch.object(self.client.opener, 'open', wraps=self.client.opener.open) as send:
            self.client.call('mobile_speak', {'text': 'Synthetic speech', 'wait_ms': 120000})
            self.assertEqual(send.call_args.kwargs['timeout'], 125)
            self.client.call('mobile_files', {'action': 'stat', 'path': '/Download/test'})
            self.assertEqual(send.call_args.kwargs['timeout'], 125)
            self.client.call('mobile_sms_list', {'limit': 1})
            self.assertEqual(send.call_args.kwargs['timeout'], 30)

    def test_arm_timeout_allows_bounded_recovery_but_does_not_change_other_calls_or_retry(self):
        client = hands.HandsClient(self.phone.endpoint, TOKEN, timeout=1, heartbeat=False)
        self.addCleanup(client.close)
        with patch.object(client.opener, 'open', wraps=client.opener.open) as send:
            self.assertTrue(client.call('mobile_status', {})['ok'])
            self.assertEqual(send.call_args.kwargs['timeout'], 1)
            self.assertTrue(client.call('mobile_arm', {})['ok'])
            self.assertEqual(send.call_args.kwargs['timeout'], 10)
            self.assertTrue(client.call('mobile_sms_list', {'limit': 1})['ok'])
            self.assertEqual(send.call_args.kwargs['timeout'], 1)
        before = len(self.phone.requests)
        with patch.object(client.opener, 'open', side_effect=TimeoutError()) as failed:
            with self.assertRaises(hands.BridgeError) as error:
                client.call('mobile_arm', {})
            self.assertEqual(error.exception.code, 'connection_failed')
            self.assertEqual(failed.call_count, 1)
            self.assertEqual(failed.call_args.kwargs['timeout'], 10)
        self.assertEqual(len(self.phone.requests), before)
        with patch.object(self.client.opener, 'open', wraps=self.client.opener.open) as send:
            self.assertTrue(self.client.call('mobile_arm', {})['ok'])
            self.assertEqual(send.call_args.kwargs['timeout'], 30)

    def test_arm_completes_a_real_delayed_phone_response_with_short_configured_timeout(self):
        import time
        def delayed(request):
            if request['body']['action'] == 'device.arm':
                time.sleep(1.2)
                return 200, {'ok': True, 'result': {'armed': True, 'lease_id': 'delayed-holder'}}, {}
            return 200, {'ok': True, 'result': {'armed': False}}, {}
        self.phone.override = delayed
        client = hands.HandsClient(self.phone.endpoint, TOKEN, timeout=1, heartbeat=False)
        self.addCleanup(client.close)
        self.assertTrue(client.call('mobile_arm', {})['ok'])
        self.assertEqual(client.lease_id, 'delayed-holder')
        self.assertEqual([request['body']['action'] for request in self.phone.requests], ['device.arm'])

    def test_primary_handoff_is_explicit_and_phone_refusal_has_no_fallback(self):
        self.arm()
        self.phone.override = lambda _: (200, {'ok': False, 'error': {
            'code': 'primary_handoff_required', 'message': 'Approve the primary handoff'}}, {})
        result = self.client.call('mobile_sms_compose', {'address': '5550100', 'body': 'Synthetic draft'})
        self.assertEqual(result['error']['code'], 'primary_handoff_required')
        self.assertEqual(len(self.phone.requests), 1)
        self.assertNotIn('allow_primary_handoff', self.phone.requests[-1]['body']['arguments'])
        self.phone.override = None
        self.client.call('mobile_settings_open', {'page': 'wifi', 'allow_primary_handoff': True})
        self.assertTrue(self.phone.requests[-1]['body']['arguments']['allow_primary_handoff'])

    def test_local_permission_request_pauses_holder_without_auto_grant_or_rearm(self):
        self.arm()
        self.phone.override = lambda _: (200, {'ok': True, 'result': {
            'local_approval_required': True, 'armed': False}}, {})
        result = self.client.call('mobile_files_enable', {})
        self.assertTrue(result['result']['local_approval_required'])
        self.assertIsNone(self.client.lease_id)
        self.assertEqual([request['body']['action'] for request in self.phone.requests], ['device.files.enable'])

    def test_notification_clear_requires_own_holder_and_preserves_exact_empty_arguments(self):
        with self.assertRaises(hands.BridgeError) as disarmed:
            self.client.call('mobile_notifications_clear', {})
        self.assertEqual('disarmed', disarmed.exception.code)
        self.assertEqual([], self.phone.requests)
        self.arm()
        self.assertTrue(self.client.call('mobile_notifications_clear', {})['ok'])
        body = self.phone.requests[-1]['body']
        self.assertEqual('device.notifications.clear', body['action'])
        self.assertEqual({}, body['arguments'])
        self.assertEqual('test-lease', body['lease_id'])
        with self.assertRaises(hands.BridgeError):
            self.client.call('mobile_notifications_clear', {'origin': 'https://foreign.invalid'})
        self.assertEqual(1, len(self.phone.requests))

    def test_text_click_resolves_one_fresh_ref_and_does_not_replay_submitted_input(self):
        self.arm()
        snapshot = str(uuid.uuid4())
        def reply(request):
            if request['body']['action'] == 'device.snapshot':
                return 200, {'ok': True, 'result': {'snapshot_id': snapshot, 'nodes': [
                    {'ref': 'e2', 'text': 'Synthetic button', 'clickable': True}]}}, {}
            return 200, {'ok': True, 'result': {'input_submitted': True, 'delivery_verified': False}}, {}
        self.phone.override = reply
        self.client.call('mobile_click', {'text': 'Synthetic button'})
        self.assertEqual([request['body']['action'] for request in self.phone.requests], ['device.snapshot', 'device.click'])
        self.assertEqual(self.phone.requests[-1]['body']['arguments'], {'ref': 'e2', 'snapshot_id': snapshot})

    def test_ambiguous_text_click_never_sends_input(self):
        self.arm()
        self.phone.override = lambda _: (200, {'ok': True, 'result': {'snapshot_id': str(uuid.uuid4()),
            'nodes': [{'ref': 'e1', 'text': 'Same', 'clickable': True}, {'ref': 'e2', 'text': 'Same', 'clickable': True}]}}, {})
        with self.assertRaises(hands.BridgeError) as error:
            self.client.call('mobile_click', {'text': 'Same'})
        self.assertEqual(error.exception.code, 'no_match')
        self.assertEqual([request['body']['action'] for request in self.phone.requests], ['device.snapshot'])

    def test_generic_nested_schema_handles_arrays_numbers_enums_and_nonfinite_failures(self):
        schema = {'type': 'object', 'additionalProperties': False, 'properties': {
            'rows': {'type': 'array', 'minItems': 1, 'maxItems': 2, 'uniqueItems': True, 'items': {
                'type': 'object', 'additionalProperties': False, 'required': ['rate', 'mode'], 'properties': {
                    'rate': {'type': 'number', 'minimum': .5, 'maximum': 2}, 'mode': {'enum': ['one', 'two']}}}}}, 'required': ['rows']}
        hands.validate({'rows': [{'rate': .75, 'mode': 'one'}]}, schema)
        for invalid in ({'rows': [{'rate': True, 'mode': 'one'}]}, {'rows': [{'rate': float('nan'), 'mode': 'one'}]},
            {'rows': [{'rate': .75, 'mode': 'other'}]}, {'rows': [{'rate': .75, 'mode': 'one'}] * 2}):
            with self.assertRaises(hands.BridgeError) as error:
                hands.validate(invalid, schema)
            self.assertEqual(error.exception.code, 'invalid_arguments')


class FileConvenienceTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='hands-transfer-test-')
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name)
        self.phone = Phone()
        self.addCleanup(self.phone.close)
        self.client = hands.HandsClient(self.phone.endpoint, TOKEN, heartbeat=False)
        self.addCleanup(self.client.close)
        self.client.call('mobile_arm', {})
        self.phone.requests.clear()
        self.files = {'/Download/source.bin': b'x' * (hands.FILE_CHUNK_BYTES + 7)}
        self.tamper_hash = False
        self.disarm_append = False
        self.phone.override = self.reply

    def reply(self, request):
        body = request['body']
        if body['action'] == 'device.disarm':
            return 200, {'ok': True, 'result': {'armed': False}}, {}
        args = body['arguments']; action = args['action']; path = args['path']
        def info():
            return {'path': path, 'size': len(self.files[path]), 'version': 'stable-v1', 'directory': False}
        if action in {'stat', 'read', 'hash'} and path not in self.files:
            return 200, {'ok': False, 'error': {'code': 'not_found', 'message': 'Missing fixture'}}, {}
        if action == 'stat': result = info()
        elif action == 'read':
            data = self.files[path][args['offset']:args['offset'] + args['max_bytes']]
            result = {**info(), 'offset': args['offset'], 'data_base64': base64.b64encode(data).decode()}
        elif action == 'hash':
            result = {**info(), 'sha256': 'wrong' if self.tamper_hash else hashlib.sha256(self.files[path]).hexdigest()}
        elif action == 'write':
            self.files[path] = base64.b64decode(args['data_base64']); result = info()
        elif action == 'append':
            if self.disarm_append:
                return 200, {'ok': False, 'error': {'code': 'disarmed', 'message': 'Stopped locally'}}, {}
            self.assertEqual(args['offset'], len(self.files[path]))
            self.files[path] += base64.b64decode(args['data_base64']); result = info()
        elif action == 'move':
            destination = args['destination']; self.files[destination] = self.files.pop(path)
            result = {'path': destination}
        elif action == 'delete':
            self.files.pop(path, None); result = {'deleted': True}
        else: raise AssertionError('Unexpected fixture file operation')
        return 200, {'ok': True, 'result': result}, {}

    def test_pull_uses_explicit_path_bounded_versioned_chunks_and_verified_atomic_publish(self):
        destination = self.path / 'download.bin'
        result = self.client.call('mobile_file_pull', {'path': '/Download/source.bin', 'local_path': str(destination)})
        self.assertEqual(destination.read_bytes(), self.files['/Download/source.bin'])
        self.assertNotIn('data_base64', json.dumps(result))
        reads = [r['body']['arguments'] for r in self.phone.requests if r['body']['arguments'].get('action') == 'read']
        self.assertEqual([r['offset'] for r in reads], [0, hands.FILE_CHUNK_BYTES])
        self.assertEqual([r['max_bytes'] for r in reads], [hands.FILE_CHUNK_BYTES, 7])
        self.assertTrue(all(r['version'] == 'stable-v1' for r in reads))
        self.assertEqual(list(self.path.glob('.hands-download-*')), [])

    def test_push_verifies_hash_before_final_move_and_returns_only_metadata(self):
        source = self.path / 'upload.bin'; source.write_bytes(b'y' * (hands.FILE_CHUNK_BYTES + 3))
        result = self.client.call('mobile_file_push', {'path': '/Download/new.bin', 'local_path': str(source)})
        self.assertEqual(self.files['/Download/new.bin'], source.read_bytes())
        operations = [r['body']['arguments']['action'] for r in self.phone.requests]
        self.assertEqual(operations, ['stat', 'write', 'append', 'hash', 'move'])
        self.assertEqual(result['result']['bytes'], hands.FILE_CHUNK_BYTES + 3)
        self.assertNotIn('data_base64', json.dumps(result))

    def test_existing_host_file_is_not_replaced_without_explicit_overwrite(self):
        destination = self.path / 'existing'; destination.write_bytes(b'keep')
        with self.assertRaises(hands.BridgeError) as error:
            self.client.call('mobile_file_pull', {'path': '/Download/source.bin', 'local_path': str(destination)})
        self.assertEqual(error.exception.code, 'already_exists')
        self.assertEqual(destination.read_bytes(), b'keep')
        self.assertEqual(self.phone.requests, [])

    def test_checksum_failure_does_not_publish_or_leave_host_staging_file(self):
        self.tamper_hash = True; destination = self.path / 'download.bin'
        with self.assertRaises(hands.BridgeError) as error:
            self.client.call('mobile_file_pull', {'path': '/Download/source.bin', 'local_path': str(destination)})
        self.assertEqual(error.exception.code, 'file_changed')
        self.assertFalse(destination.exists())
        self.assertEqual(list(self.path.glob('.hands-download-*')), [])

    def test_disarm_mid_upload_never_rearms_or_requests_cleanup_permissions(self):
        self.disarm_append = True; source = self.path / 'upload'; source.write_bytes(b'z' * (hands.FILE_CHUNK_BYTES + 1))
        with self.assertRaises(hands.BridgeError) as error:
            self.client.call('mobile_file_push', {'path': '/Download/new.bin', 'local_path': str(source)})
        self.assertEqual(error.exception.code, 'disarmed')
        self.assertIn('Do not rearm', error.exception.hint)
        self.assertIsNone(self.client.lease_id)
        self.assertEqual([r['body']['arguments']['action'] for r in self.phone.requests], ['stat', 'write', 'append'])
        self.assertNotIn('/Download/new.bin', self.files)

    def test_relative_host_paths_are_rejected_before_read_or_phone_call(self):
        with self.assertRaises(hands.BridgeError) as error:
            self.client.call('mobile_file_push', {'path': '/Download/new.bin', 'local_path': 'implicit-relative-file'})
        self.assertEqual(error.exception.code, 'invalid_arguments')
        self.assertEqual(self.phone.requests, [])


if __name__ == '__main__':
    unittest.main()
