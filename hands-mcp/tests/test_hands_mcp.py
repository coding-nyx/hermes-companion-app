import base64
import binascii
import io
import json
import os
from pathlib import Path
import struct
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import unittest
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import hands_mcp as hands


TOKEN = "test-pairing-token-private-to-this-test"


def png_image(width=2, height=3):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", binascii.crc32(kind + data) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" +
            chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) +
            chunk(b"IDAT", zlib.compress((b"\0" + b"\xff\0\0" * width) * height)) +
            chunk(b"IEND", b""))


class Phone:
    def __init__(self):
        self.requests = []
        self.override = None
        phone = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                self.respond(None)

            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                self.respond(json.loads(body))

            def respond(self, body):
                phone.requests.append({"method": self.command, "path": self.path,
                                       "authorization": self.headers.get("Authorization"), "body": body})
                if phone.override:
                    code, response, headers = phone.override(phone.requests[-1])
                elif self.headers.get("Authorization") != "Bearer " + TOKEN:
                    code, response, headers = 401, {"ok": False, "error": {"code": "unauthorized", "message": "No"}}, {}
                elif self.path == "/v1/status":
                    code, response, headers = 200, {"ok": True, "result": {"armed": True, "target": "agent"}}, {}
                elif body["action"] == "device.arm":
                    code, response, headers = 200, {"ok": True, "result": {"armed": True, "lease_id": "test-lease"}}, {}
                elif body["action"] == "device.arm.renew":
                    code, response, headers = 200, {"ok": True, "result": {"armed": True, "lease_id": body['lease_id']}}, {}
                elif body["action"] == "device.screenshot":
                    code, response, headers = 200, {"ok": True, "result": {
                        "mime": "image/png", "png_b64": base64.b64encode(png_image()).decode(),
                        "w": 2, "h": 3, "display_id": 7, "coordinate_space": "display"}}, {}
                else:
                    code, response, headers = 200, {"ok": True, "result": {"executed": body["action"]}}, {}
                raw = response if isinstance(response, bytes) else json.dumps(response).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                for key, value in headers.items():
                    self.send_header(key, value)
                self.end_headers()
                self.wfile.write(raw)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.endpoint = "http://127.0.0.1:" + str(self.server.server_port)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)


class TransportTests(unittest.TestCase):
    def setUp(self):
        self.phone = Phone()
        self.addCleanup(self.phone.close)
        self.client = hands.HandsClient(self.phone.endpoint, TOKEN)
        self.addCleanup(self.client.close)

    def test_status_uses_bearer_and_no_lease(self):
        result = self.client.call("mobile_status", {})
        self.assertEqual(result["result"]["target"], "agent")
        self.assertEqual(self.phone.requests, [{"method": "GET", "path": "/v1/status",
                                              "authorization": "Bearer " + TOKEN, "body": None}])

    def test_disarmed_actions_never_reach_phone(self):
        for tool, arguments in (("mobile_snapshot", {}), ("mobile_click", {"x": 1, "y": 2}),
                                ("mobile_agent_screen_start", {}), ("mobile_disarm", {})):
            with self.subTest(tool=tool), self.assertRaises(hands.BridgeError) as error:
                self.client.call(tool, arguments)
            self.assertEqual(error.exception.code, "disarmed")
        self.assertEqual(self.phone.requests, [])

    def test_arm_binds_followup_commands_to_same_controller_and_lease(self):
        self.client.call("mobile_arm", {"target": "agent", "backend": "shizuku"})
        self.client.call("mobile_click", {"x": 12, "y": 24})
        arm, click = [request["body"] for request in self.phone.requests]
        self.assertEqual(arm["action"], "device.arm")
        self.assertEqual(arm["arguments"], {"target": "agent", "backend": "shizuku"})
        self.assertNotIn("lease_id", arm)
        self.assertEqual(click["lease_id"], "test-lease")
        self.assertEqual(click["controller_id"], arm["controller_id"])
        self.assertEqual(click["action"], "device.click")

    def test_arm_omits_selection_by_default(self):
        self.client.call("mobile_arm", {})
        self.assertEqual(self.phone.requests[0]["body"]["arguments"], {})

    def test_selection_mismatch_gives_actionable_hint_without_retry(self):
        for code in ("target_mismatch", "backend_mismatch"):
            with self.subTest(code=code):
                self.phone.requests.clear()
                self.phone.override = lambda _, c=code: (409, {"ok": False, "error": {
                    "code": c, "message": "The requested selection does not match Hands."}}, {})
                result = self.client.call("mobile_arm", {"target": "agent"})
                self.assertEqual(result["error"]["code"], code)
                self.assertIn("mobile_status", result["error"]["hint"])
                self.assertIn("Omit both fields", result["error"]["hint"])
                self.assertIn("No separate session approval", result["error"]["hint"])
                self.assertEqual(len(self.phone.requests), 1)
                self.assertIsNone(self.client.lease_id)

    def test_legacy_selection_mismatch_does_not_rewrite_other_approval_errors(self):
        self.phone.override = lambda _: (409, {"ok": False, "error": {
            "code": "local_consent_required", "message": "Use the screen and backend selected in Hands."}}, {})
        result = self.client.call("mobile_arm", {"backend": "shizuku"})
        self.assertIn("mobile_status", result["error"]["hint"])
        self.phone.override = lambda _: (409, {"ok": False, "error": {
            "code": "local_consent_required", "message": "Local approval required"}}, {})
        result = self.client.call("mobile_arm", {})
        self.assertNotIn("hint", result["error"])

    def test_clients_do_not_share_a_controller_or_local_lease(self):
        other = hands.HandsClient(self.phone.endpoint, TOKEN)
        self.assertNotEqual(self.client.controller_id, other.controller_id)
        self.client.call("mobile_arm", {})
        with self.assertRaises(hands.BridgeError):
            other.call("mobile_snapshot", {})

    def test_failed_arm_does_not_create_lease(self):
        self.phone.override = lambda _: (409, {"ok": False, "error": {
            "code": "need_local_consent", "message": "Arm Hands on the phone"}}, {})
        result = self.client.call("mobile_arm", {})
        self.assertEqual(result["error"]["code"], "need_local_consent")
        self.assertIsNone(self.client.lease_id)

    def test_invalid_lease_clears_local_lease_without_retry(self):
        self.client.call("mobile_arm", {})
        self.phone.override = lambda _: (409, {"ok": False, "error": {
            "code": "lease_expired", "message": "Lease expired"}}, {})
        self.client.call("mobile_click", {"x": 4, "y": 5})
        self.assertIsNone(self.client.lease_id)
        self.assertEqual(len(self.phone.requests), 2)

    def test_control_policy_forbidden_does_not_revoke_valid_credentials(self):
        self.client.call("mobile_arm", {})
        self.phone.override = lambda _: (403, {"ok": False, "error": {
            "code": "protected_package", "message": "This app is protected"}}, {})
        result = self.client.call("mobile_click", {"x": 4, "y": 5})
        self.assertEqual(result["error"]["code"], "protected_package")
        self.assertEqual(self.client.lease_id, "test-lease")

    def test_close_disarms_only_owned_lease(self):
        unarmed = hands.HandsClient(self.phone.endpoint, TOKEN)
        unarmed.close()
        self.assertEqual(self.phone.requests, [])
        self.client.call("mobile_arm", {})
        self.client.close()
        self.client.close()
        self.assertEqual(len(self.phone.requests), 2)
        self.assertEqual(self.phone.requests[-1]["body"]["action"], "device.disarm")
        self.assertEqual(self.phone.requests[-1]["body"]["lease_id"], "test-lease")

    def test_bad_credentials_are_sanitized(self):
        client = hands.HandsClient(self.phone.endpoint, "do-not-echo-this-secret")
        with self.assertRaises(hands.BridgeError) as error:
            client.call("mobile_status", {})
        self.assertEqual(error.exception.code, "authentication_failed")
        self.assertNotIn("do-not-echo-this-secret", str(error.exception))

    def test_redirect_is_not_followed(self):
        self.phone.override = lambda _: (302, b"", {"Location": self.phone.endpoint + "/steal"})
        with self.assertRaises(hands.BridgeError) as error:
            self.client.call("mobile_status", {})
        self.assertEqual(error.exception.code, "redirect_rejected")
        self.assertEqual(len(self.phone.requests), 1)

    def test_http_failure_is_not_retried_or_echoed(self):
        self.phone.override = lambda _: (503, b"<html>secret internal diagnostic</html>", {})
        with self.assertRaises(hands.BridgeError) as error:
            self.client.call("mobile_status", {})
        self.assertEqual(error.exception.code, "http_error")
        self.assertNotIn("secret", str(error.exception))
        self.assertEqual(len(self.phone.requests), 1)

    def test_success_must_use_valid_result_envelope(self):
        for body in (b"nope", b'{"ok":true}', b'{"ok":1,"result":{}}',
                     b'{"ok":false,"error":{}}', b'{"ok":true,"result":{"x":1e999}}'):
            with self.subTest(body=body):
                self.phone.override = lambda _, body=body: (200, body, {})
                with self.assertRaises(hands.BridgeError) as error:
                    self.client.call("mobile_status", {})
                self.assertEqual(error.exception.code, "invalid_response")

    def test_arm_response_requires_lease(self):
        self.phone.override = lambda _: (200, {"ok": True, "result": {"armed": True}}, {})
        with self.assertRaises(hands.BridgeError) as error:
            self.client.call("mobile_arm", {})
        self.assertEqual(error.exception.code, "invalid_response")
        self.assertIsNone(self.client.lease_id)

    def test_screenshot_is_native_image_without_base64_text_duplication(self):
        self.client.call("mobile_arm", {})
        result = hands.tool_result(self.client.call("mobile_screenshot", {}), screenshot=True)
        self.assertFalse(result["isError"])
        self.assertEqual([block["type"] for block in result["content"]], ["text", "image"])
        self.assertEqual(result["content"][1]["mimeType"], "image/png")
        self.assertEqual(base64.b64decode(result["content"][1]["data"]), png_image())
        self.assertNotIn("png_b64", result["structuredContent"]["result"])
        self.assertNotIn("png_b64", result["content"][0]["text"])
        self.assertEqual(result["structuredContent"]["result"]["display_id"], 7)

    def test_agent_screen_actions_use_explicit_names(self):
        self.client.call("mobile_agent_screen_status", {})
        self.client.call("mobile_arm", {})
        self.client.call("mobile_agent_screen_start", {"width": 720, "height": 1280, "density": 240})
        self.client.call("mobile_agent_screen_stop", {})
        actions = [request["body"]["action"] for request in self.phone.requests]
        self.assertEqual(actions, ["device.agent_screen.status", "device.arm", "device.agent_screen.start", "device.agent_screen.stop"])

    def test_keep_awake_is_separate_from_arming(self):
        for arguments in ({}, {'action': 'set', 'enabled': True, 'duration_seconds': 600},
                          {'action': 'set', 'enabled': False}):
            self.assertTrue(self.client.call('mobile_keep_awake', arguments)['ok'])
            self.assertIsNone(self.client.lease_id)
        self.assertEqual([request['body']['action'] for request in self.phone.requests],
                         ['device.display.keep_awake'] * 3)

    def test_brightness_read_is_passive_and_write_requires_own_holder(self):
        self.assertTrue(self.client.call('mobile_brightness_get', {})['ok'])
        with self.assertRaises(hands.BridgeError) as error:
            self.client.call('mobile_brightness_set', {'level': 40})
        self.assertEqual(error.exception.code, 'disarmed')
        self.client.call('mobile_arm', {})
        self.client.call('mobile_brightness_set', {'level': 40, 'auto': False})
        self.assertEqual(self.phone.requests[-1]['body']['action'], 'device.display.brightness.set')
        self.assertEqual(self.phone.requests[-1]['body']['lease_id'], 'test-lease')

    def test_keep_awake_and_brightness_reject_ambiguous_arguments_before_io(self):
        for name, arguments in (
            ('mobile_keep_awake', {'enabled': True}),
            ('mobile_keep_awake', {'action': 'status', 'duration_seconds': 600}),
            ('mobile_keep_awake', {'action': 'set'}),
            ('mobile_keep_awake', {'action': 'set', 'enabled': 1}),
            ('mobile_keep_awake', {'action': 'set', 'enabled': False, 'duration_seconds': 600}),
            ('mobile_keep_awake', {'action': 'set', 'enabled': True, 'duration_seconds': 7201}),
            ('mobile_brightness_set', {}),
            ('mobile_brightness_set', {'level': 0}),
            ('mobile_brightness_set', {'level': True}),
            ('mobile_brightness_set', {'auto': 'false'}),
            ('mobile_brightness_set', {'level': 50, 'auto': True}),
        ):
            with self.subTest(name=name, arguments=arguments), self.assertRaises(hands.BridgeError) as error:
                self.client.call(name, arguments)
            self.assertEqual(error.exception.code, 'invalid_arguments')
        self.assertEqual(self.phone.requests, [])

    def test_explicit_screen_stop_is_available_without_a_holder(self):
        self.assertTrue(self.client.call('mobile_agent_screen_stop', {})['ok'])
        self.assertNotIn('lease_id', self.phone.requests[-1]['body'])


class HeartbeatTests(unittest.TestCase):
    def setUp(self):
        self.phone = Phone()
        self.addCleanup(self.phone.close)
        self.now = [0.0]
        self.client = hands.HandsClient(self.phone.endpoint, TOKEN, heartbeat=False, clock=lambda: self.now[0])
        self.addCleanup(self.client.close)

    def test_tick_renews_only_issued_holder_and_never_rearms(self):
        self.now[0] = 100
        self.client.tick()
        self.assertEqual(self.phone.requests, [])
        self.client.call('mobile_arm', {'duration_seconds': 30})
        self.now[0] = 109
        self.client.tick()
        self.assertEqual(len(self.phone.requests), 1)
        self.now[0] = 110
        self.client.tick()
        renewal = self.phone.requests[-1]['body']
        self.assertEqual(renewal['action'], 'device.arm.renew')
        self.assertEqual(renewal['lease_id'], 'test-lease')
        self.assertEqual(renewal['arguments'], {'duration_seconds': 30})
        self.assertEqual(renewal['controller_id'], self.phone.requests[0]['body']['controller_id'])
        self.assertEqual(sum(request['body']['action'] == 'device.arm' for request in self.phone.requests), 1)

    def test_local_expiry_stops_renewal_and_cannot_silently_rearm(self):
        self.client.call('mobile_arm', {})
        self.phone.override = lambda _: (409, {'ok': False, 'error': {
            'code': 'local_consent_required', 'message': 'Local approval expired'}}, {})
        self.now[0] = 30
        self.client.tick()
        self.assertIsNone(self.client.lease_id)
        self.now[0] = 1000
        self.client.tick()
        with self.assertRaises(hands.BridgeError) as error:
            self.client.call('mobile_snapshot', {})
        self.assertEqual(error.exception.code, 'disarmed')
        self.assertEqual([request['body']['action'] for request in self.phone.requests], ['device.arm', 'device.arm.renew'])

    def test_failed_renewal_is_not_replayed_and_keeps_only_conditional_cleanup(self):
        self.client.call('mobile_arm', {})
        self.phone.override = lambda _: (503, b'unavailable', {})
        self.now[0] = 30
        self.client.tick()
        self.now[0] = 1000
        self.client.tick()
        self.assertEqual(len(self.phone.requests), 2)
        self.assertEqual(self.client.lease_id, 'test-lease')
        self.assertEqual(self.client._working_status()['working_lease_error'], 'http_error')
        self.phone.override = None
        self.client.close()
        self.assertEqual(self.phone.requests[-1]['body']['action'], 'device.disarm')
        self.assertEqual(self.phone.requests[-1]['body']['lease_id'], 'test-lease')

    def test_each_arm_holder_is_released_without_a_global_stop(self):
        counter = [0]
        def reply(request):
            action = request['body']['action']
            if action == 'device.arm':
                counter[0] += 1
                return 200, {'ok': True, 'result': {'armed': True, 'lease_id': 'holder-' + str(counter[0])}}, {}
            return 200, {'ok': True, 'result': {'armed': counter[0] > 0}}, {}
        self.phone.override = reply
        self.client.call('mobile_arm', {})
        self.client.call('mobile_arm', {})
        self.assertEqual(self.client.lease_id, 'holder-2')
        self.client.call('mobile_disarm', {})
        releases = [request['body'] for request in self.phone.requests if request['body']['action'] == 'device.disarm']
        self.assertEqual([release['lease_id'] for release in releases], ['holder-1', 'holder-2'])
        self.assertTrue(all(release['controller_id'] == self.client.controller_id for release in releases))
        self.assertIsNone(self.client.lease_id)
        self.now[0] = 1000
        self.client.tick()
        self.assertEqual(len(self.phone.requests), 4)

    def test_background_worker_runs_between_tools_and_stops_on_close(self):
        renewed = threading.Event()
        def reply(request):
            if request['body']['action'] == 'device.arm':
                return 200, {'ok': True, 'result': {'armed': True, 'lease_id': 'background-holder'}}, {}
            if request['body']['action'] == 'device.arm.renew':
                renewed.set()
            return 200, {'ok': True, 'result': {'armed': True}}, {}
        self.phone.override = reply
        client = hands.HandsClient(self.phone.endpoint, TOKEN, renewal_interval=0.01)
        self.addCleanup(client.close)
        result = client.call('mobile_arm', {'duration_seconds': 30})
        self.assertTrue(result['result']['working_lease_managed'])
        self.assertTrue(renewed.wait(2), 'No renewal while the MCP client was idle between tools')
        client.close()
        self.assertFalse(client._worker.is_alive())
        self.assertEqual(self.phone.requests[-1]['body']['action'], 'device.disarm')

    def test_live_disarmed_status_invalidates_issued_holders(self):
        self.client.call('mobile_arm', {})
        self.phone.override = lambda _: (200, {'ok': True, 'result': {'armed': False}}, {})
        self.client.call('mobile_status', {})
        self.assertIsNone(self.client.lease_id)
        self.now[0] = 1000
        self.client.tick()
        self.assertEqual(len(self.phone.requests), 2)


class ValidationTests(unittest.TestCase):
    def test_click_accepts_only_one_complete_target(self):
        schema = hands.TOOLS_BY_NAME["mobile_click"]["inputSchema"]
        for arguments in ({}, {"x": 1}, {"y": 2}, {"ref": "e1"}, {"ref": "e1", "snapshot_id": "s1", "x": 1, "y": 2}, {"x": True, "y": 3}):
            with self.subTest(arguments=arguments), self.assertRaises(hands.BridgeError):
                hands.validate(arguments, schema)
        hands.validate({"ref": "e1", "snapshot_id": "snapshot-1"}, schema)
        hands.validate({"x": 1, "y": 2}, schema)

    def test_partial_coordinate_and_reference_targets_cannot_be_mixed(self):
        client = hands.HandsClient("http://127.0.0.1:1", TOKEN)
        for arguments in ({"ref": "e1", "snapshot_id": "s1", "x": 1},
                          {"snapshot_id": "s1", "x": 1, "y": 2},
                          {"ref": "e1", "x": 1, "y": 2}):
            with self.subTest(arguments=arguments), self.assertRaises(hands.BridgeError) as error:
                client.call("mobile_click", arguments)
            self.assertEqual(error.exception.code, "invalid_arguments")

    def test_arguments_are_strict_and_bounded(self):
        cases = [("mobile_status", {"token": TOKEN}), ("mobile_type", {"text": "a" * 16385}),
                 ("mobile_swipe", {"x1": 1, "y1": 2, "x2": 3, "y2": 4, "duration_ms": 0}),
                 ("mobile_arm", {"duration_seconds": 7201}), ("mobile_arm", {"target": "any"}),
                 ("mobile_open_app", {"package": "com.example; rm -rf /"}),
                 ("mobile_agent_screen_start", {"width": 99999}), ("mobile_press", {"key": "power"}),
                 ("mobile_snapshot", []), ("mobile_type", {"text": 123})]
        for name, arguments in cases:
            with self.subTest(name=name, arguments=arguments), self.assertRaises(hands.BridgeError):
                hands.validate(arguments, hands.TOOLS_BY_NAME[name]["inputSchema"])

    def test_remote_plaintext_and_endpoint_credentials_are_rejected(self):
        for endpoint in ("http://example.org:8765", "http://192.168.0.10:8765", "http://127.0.0.1:0",
                         "https://user:secret@example.org", "http://127.0.0.1/path", "http://127.0.0.1?token=secret"):
            with self.subTest(endpoint=endpoint), self.assertRaises(ValueError):
                hands.validate_endpoint(endpoint)
        for endpoint in ("http://127.0.0.1:8765", "http://localhost:8765", "http://[::1]:8765", "https://hands.example.org"):
            self.assertEqual(hands.validate_endpoint(endpoint), endpoint)

    def test_token_file_requires_private_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "token"
            path.write_text(TOKEN + "\n")
            path.chmod(0o600)
            self.assertEqual(hands.read_token(str(path), {}), TOKEN)
            if os.name == "posix":
                path.chmod(0o644)
                with self.assertRaises(ValueError):
                    hands.read_token(str(path), {})
        self.assertEqual(hands.read_token(None, {"HANDS_TOKEN": TOKEN}), TOKEN)
        self.assertEqual(hands.read_token(None, {"HERMES_HANDS_TOKEN": TOKEN}), TOKEN)
        self.assertEqual(hands.read_token(None, {"HANDS_TOKEN": TOKEN, "HERMES_HANDS_TOKEN": "old-token"}), TOKEN)
        for token in ("", "too-short", "a" * 32 + "\r\nInjected: yes"):
            with self.assertRaises(ValueError):
                hands.read_token(None, {"HANDS_TOKEN": token})

    def test_corrupt_or_mismatched_images_fail_without_image_content(self):
        valid = base64.b64encode(png_image()).decode()
        corrupt = bytearray(png_image())
        corrupt[-1] ^= 1
        for capture in ({"png_b64": "invalid"}, {"png_b64": valid, "w": 99},
                        {"png_b64": valid, "mime": "text/html"},
                        {"png_b64": base64.b64encode(corrupt).decode()},
                        {"png_b64": base64.b64encode(png_image()[:24]).decode()}):
            result = hands.tool_result({"ok": True, "result": capture}, screenshot=True)
            self.assertTrue(result["isError"])
            self.assertEqual(result["structuredContent"]["error"]["code"], "invalid_screenshot")
            self.assertEqual([block["type"] for block in result["content"]], ["text"])


class ProtocolTests(unittest.TestCase):
    def setUp(self):
        self.phone = Phone()
        self.addCleanup(self.phone.close)
        self.client = hands.HandsClient(self.phone.endpoint, TOKEN)
        self.addCleanup(self.client.close)
        self.server = hands.McpServer(self.client)

    def initialize(self, version="2025-11-25"):
        response = self.server.handle({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
            "protocolVersion": version, "clientInfo": {"name": "test-client", "version": "1"}, "capabilities": {}}})
        self.assertIsNone(self.server.handle({"jsonrpc": "2.0", "method": "notifications/initialized"}))
        return response

    def call(self, name, arguments=None):
        return self.server.handle({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                                   "params": {"name": name, "arguments": arguments or {}}})

    def test_initialize_negotiates_supported_versions_without_contacting_phone(self):
        for version in hands.PROTOCOL_VERSIONS + ("unknown-version",):
            with self.subTest(version=version):
                self.server = hands.McpServer(self.client)
                result = self.initialize(version)["result"]
                self.assertEqual(result["protocolVersion"], version if version in hands.PROTOCOL_VERSIONS else hands.PROTOCOL_VERSIONS[0])
                self.assertIn("tools", result["capabilities"])
                self.assertEqual(result['serverInfo']['name'], 'hands')
                self.assertIn("Hands must be running", result["instructions"])
                self.assertIn("omit both", result["instructions"])
                self.assertIn("No separate session approval", result["instructions"])
        self.assertEqual(self.phone.requests, [])

    def test_tools_require_completed_initialization(self):
        response = self.server.handle({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
        self.assertEqual(response["error"]["code"], -32002)
        self.initialize()
        tools = self.server.handle({"jsonrpc": "2.0", "id": "list", "method": "tools/list"})
        self.assertEqual(len(tools["result"]["tools"]), 85)

    def test_protocol_errors_and_tool_execution_errors_are_distinct(self):
        self.initialize()
        self.assertEqual(self.call("unknown")["error"]["code"], -32602)
        result = self.call("mobile_arm", {"duration_seconds": -1})["result"]
        self.assertTrue(result["isError"])
        self.assertEqual(result["structuredContent"]["error"]["code"], "invalid_arguments")
        self.assertEqual(self.phone.requests, [])

    def test_invalid_request_ids_params_and_batch(self):
        for request in ([], {"jsonrpc": "1.0", "id": 1, "method": "ping"},
                        {"jsonrpc": "2.0", "id": True, "method": "ping"},
                        {"jsonrpc": "2.0", "id": 1, "method": 3}):
            self.assertEqual(self.server.handle(request)["error"]["code"], -32600)
        self.assertEqual(self.server.handle({"jsonrpc": "2.0", "id": 1, "method": "ping", "params": []})["error"]["code"], -32602)

    def test_notifications_have_no_output_or_phone_effect(self):
        for method in ("ping", "tools/call", "notifications/cancelled", "notifications/initialized"):
            self.assertIsNone(self.server.handle({"jsonrpc": "2.0", "method": method}))
        self.assertEqual(self.phone.requests, [])

    def test_stdio_is_newline_json_and_eof_releases_lease(self):
        lines = [
            {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-11-25", "capabilities": {}, "clientInfo": {"name": "test", "version": "1"}}},
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
            {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "mobile_arm", "arguments": {}}},
            {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "mobile_screenshot", "arguments": {}}},
        ]
        output = io.StringIO()
        self.server.run(io.BytesIO(("\n".join(json.dumps(line) for line in lines) + "\n").encode()), output)
        responses = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual([response["id"] for response in responses], [1, 2, 3])
        self.assertEqual(responses[-1]["result"]["content"][1]["type"], "image")
        self.assertEqual(self.phone.requests[-1]["body"]["action"], "device.disarm")
        self.assertNotIn(TOKEN, output.getvalue())

    def test_parse_errors_do_not_break_later_messages(self):
        output = io.StringIO()
        self.server.run(io.BytesIO(b'not-json\n{"jsonrpc":"2.0","method":"ping","id":5}\n'), output)
        responses = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual(responses[0]["error"]["code"], -32700)
        self.assertEqual(responses[1], {"jsonrpc": "2.0", "id": 5, "result": {}})

    def test_oversized_message_stops_without_processing_trailing_commands(self):
        output = io.StringIO()
        raw = b"a" * (hands.MAX_MESSAGE_BYTES + 1) + b'\n{"jsonrpc":"2.0","id":1,"method":"ping"}\n'
        self.server.run(io.BytesIO(raw), output)
        responses = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual(len(responses), 1)
        self.assertEqual(responses[0]["error"]["code"], -32700)


if __name__ == "__main__":
    unittest.main()
