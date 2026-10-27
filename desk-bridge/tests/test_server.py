import importlib.util
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

import httpx

spec = importlib.util.spec_from_file_location("desk_bridge", Path(__file__).parents[1] / "server.py")
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


class RequestPolicyTest(unittest.TestCase):
    def body(self):
        return {"model": "grok-fixture", "store": False, "stream": True,
                "input": [{"role": "user", "content": "hello"}], "instructions": "Be concise"}

    def test_text_and_inline_image_allowed(self):
        bridge.validate_response_request(self.body())
        body = self.body()
        body["input"][0]["content"] = [{"type": "input_text", "text": "look"},
                                      {"type": "input_image", "image_url": "data:image/jpeg;base64,aGVsbG8="}]
        bridge.validate_response_request(body)

    def test_rejects_tools_and_arbitrary_request_fields(self):
        for key, value in (("tools", [{"type": "computer"}]), ("previous_response_id", "old"),
                           ("model", "other-provider"), ("stream", False), ("store", True)):
            with self.subTest(key=key), self.assertRaises(ValueError):
                body = self.body()
                body[key] = value
                bridge.validate_response_request(body)

    def test_external_images_and_tool_messages_rejected(self):
        for message in ({"role": "tool", "content": "execute"},
                        {"role": "user", "content": [{"type": "input_image", "image_url": "http://127.0.0.1/private"}]},
                        {"role": "user", "content": [{"type": "function_call", "name": "shell"}]}):
            with self.subTest(message=message), self.assertRaises(ValueError):
                body = self.body()
                body["input"] = [message]
                bridge.validate_response_request(body)


class AuthenticationTest(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), bridge.DeskHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = "http://127.0.0.1:" + str(self.server.server_port)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def test_missing_credentials_fail_before_loading_provider(self):
        with patch.object(bridge.httpx, "get") as dashboard:
            response = httpx.Client(trust_env=False).get(self.url + "/desk-grok/v1/models")
            self.assertEqual(401, response.status_code)
            dashboard.assert_not_called()

    def test_dashboard_rejection_fails_closed_and_never_forwards(self):
        with patch.object(bridge.httpx, "get", return_value=httpx.Response(401)) as dashboard:
            response = httpx.Client(trust_env=False).request("GET", self.url + "/desk-grok/v1/models",
                headers={"Authorization": "Bearer fixture-invalid-dashboard-session"})
            self.assertEqual(401, response.status_code)
            self.assertEqual("http://127.0.0.1:9119/api/profiles", dashboard.call_args.args[0])

    def test_only_model_and_response_routes_exist(self):
        response = httpx.get(self.url + "/desk-grok/v1/files", trust_env=False)
        self.assertEqual(404, response.status_code)

    def test_valid_dashboard_identity_still_cannot_enable_tools(self):
        with patch.object(bridge.httpx, "get", return_value=httpx.Response(200)):
            response = httpx.Client(trust_env=False).post(self.url + "/desk-grok/v1/responses",
                headers={"Authorization": "Bearer fixture-valid-dashboard-session"},
                json={"model": "grok-fixture", "stream": True, "store": False, "input": [], "tools": []})
            self.assertEqual(400, response.status_code)


if __name__ == "__main__":
    unittest.main()
