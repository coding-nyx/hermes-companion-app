from __future__ import annotations

import json
import sys
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from pairing import PairingStore
from relay import RelayState, make_server, parse_bind, parse_upstream
from tickets import TicketError


class SlowUpstream(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        del format, args

    def do_GET(self):
        time.sleep(0.15)
        body = b'{"ok":true,"path":"%s"}' % self.path.encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class RelayTests(unittest.TestCase):
    def test_parse_bind_and_upstream(self):
        self.assertEqual(parse_bind("0.0.0.0:9120"), ("0.0.0.0", 9120))
        self.assertEqual(parse_bind("http://127.0.0.1:9120"), ("127.0.0.1", 9120))
        self.assertEqual(parse_upstream("http://127.0.0.1:9119"), ("127.0.0.1", 9119))

    def test_proxy_get_without_half_close(self):
        up = ThreadingHTTPServer(("127.0.0.1", 0), SlowUpstream)
        threading.Thread(target=up.serve_forever, daemon=True).start()
        relay = None
        try:
            host, port = up.server_address
            relay = make_server(f"127.0.0.1:0", f"http://{host}:{port}")
            threading.Thread(target=relay.serve_forever, daemon=True).start()
            rhost, rport = relay.server_address
            with urllib.request.urlopen(f"http://{rhost}:{rport}/api/status", timeout=5) as resp:
                payload = json.loads(resp.read().decode())
            self.assertTrue(payload["ok"])
            self.assertEqual(payload["path"], "/api/status")
        finally:
            if relay is not None:
                relay.shutdown()
                relay.server_close()
            up.shutdown()
            up.server_close()

    def test_local_pair_and_register(self):
        state = RelayState(pairing=PairingStore(now=lambda: 50.0))
        relay = make_server("127.0.0.1:0", "http://127.0.0.1:9", state)
        threading.Thread(target=relay.serve_forever, daemon=True).start()
        try:
            host, port = relay.server_address
            origin = f"http://{host}:{port}"
            req = urllib.request.Request(
                f"{origin}/companion/device/pair",
                data=json.dumps({"code": "K7M2QX", "profile": "coder"}).encode(),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=5) as resp:
                offered = json.loads(resp.read().decode())
            self.assertEqual(offered["code"], "K7M2QX")
            self.assertEqual(offered["status"], "pending")
            device = state.pairing.approve("K7M2QX")
            with urllib.request.urlopen(f"{origin}/companion/device/pair/K7M2QX", timeout=5) as resp:
                status = json.loads(resp.read().decode())
            self.assertEqual(status["status"], "approved")
            self.assertEqual(status["device_id"], device.device_id)
            reg = urllib.request.Request(
                f"{origin}/companion/device/register",
                data=json.dumps(
                    {
                        "device_id": device.device_id,
                        "credential": device.credential,
                        "profile": "coder",
                        "capabilities": ["device.snapshot"],
                    }
                ).encode(),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with urllib.request.urlopen(reg, timeout=5) as resp:
                minted = json.loads(resp.read().decode())
            self.assertTrue(minted["ticket"].startswith("dt-"))
            with urllib.request.urlopen(f"{origin}/companion/device/list", timeout=5) as resp:
                listed = json.loads(resp.read().decode())
            self.assertEqual(listed["devices"][0]["device_id"], device.device_id)
            self.assertNotIn("credential", listed["devices"][0])
            try:
                urllib.request.urlopen(f"{origin}/companion/device/ws", timeout=5)
                self.fail("ws should 426")
            except urllib.error.HTTPError as exc:
                self.assertEqual(exc.code, 426)
        finally:
            relay.shutdown()
            relay.server_close()

    def test_wait_result_from_phone_frame(self):
        class FakeWfile:
            def write(self, data):
                del data

            def flush(self):
                return None

        state = RelayState()
        state.lanes["dev_1"] = FakeWfile()
        command_id = state.send_command("dev_1", "device.noop", {})

        def later():
            time.sleep(0.05)
            state.on_frame(
                json.dumps(
                    {
                        "type": "mobile.controller.result",
                        "command_id": command_id,
                        "ok": True,
                        "result": {"ok": True},
                    }
                )
            )

        threading.Thread(target=later, daemon=True).start()
        payload = state.wait_result(command_id, timeout=2)
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["result"]["ok"], True)

    def test_wait_result_timeout(self):
        class FakeWfile:
            def write(self, data):
                del data

            def flush(self):
                return None

        state = RelayState()
        state.lanes["dev_1"] = FakeWfile()
        command_id = state.send_command("dev_1", "device.noop", {})
        with self.assertRaises(TicketError) as ctx:
            state.wait_result(command_id, timeout=0.05)
        self.assertEqual(ctx.exception.code, "timeout")


if __name__ == "__main__":
    unittest.main()
