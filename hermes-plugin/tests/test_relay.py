from __future__ import annotations

import json
import os
import sys
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from pairing import PairingStore
from relay import CompanionHandler, RelayState, make_server, parse_bind, parse_upstream
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
            with patch.dict(os.environ, {"HERMES_COMPANION_STANDALONE": "0"}, clear=False):
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

    def test_proxy_sends_fin_so_pooled_connections_are_not_reused(self):
        """Regression: a second request on the same client connection used to vanish for the
        client's whole read timeout because the relay never sent FIN after the first response."""
        up = ThreadingHTTPServer(("127.0.0.1", 0), SlowUpstream)
        threading.Thread(target=up.serve_forever, daemon=True).start()
        relay = None
        try:
            host, port = up.server_address
            with patch.dict(os.environ, {"HERMES_COMPANION_STANDALONE": "0"}, clear=False):
                relay = make_server("127.0.0.1:0", f"http://{host}:{port}")
                threading.Thread(target=relay.serve_forever, daemon=True).start()
                rhost, rport = relay.server_address
                import socket as _s

                conn = _s.create_connection((rhost, rport), timeout=5)
                conn.sendall(b"GET /api/status HTTP/1.1\r\nHost: x\r\nConnection: Keep-Alive\r\n\r\n")
                buf = b""
                deadline = time.monotonic() + 5
                while b'"ok":true' not in buf and time.monotonic() < deadline:
                    chunk = conn.recv(65536)
                    if not chunk:
                        break
                    buf += chunk
                self.assertIn(b'"ok":true', buf)
                # The relay must close its side promptly (EOF), well under the 1s join it used to wait.
                t0 = time.monotonic()
                tail = conn.recv(65536)
                self.assertEqual(tail, b"", "expected EOF after the proxied response")
                self.assertLess(time.monotonic() - t0, 0.9)
                conn.close()
        finally:
            if relay is not None:
                relay.shutdown()
                relay.server_close()
            up.shutdown()

    def test_proxy_closed_upstream_returns_502(self):
        with patch.dict(os.environ, {"HERMES_COMPANION_STANDALONE": "0"}, clear=False):
            relay = make_server("127.0.0.1:0", "http://127.0.0.1:1")
            threading.Thread(target=relay.serve_forever, daemon=True).start()
            try:
                host, port = relay.server_address
                try:
                    urllib.request.urlopen(f"http://{host}:{port}/api/status", timeout=5)
                    self.fail("expected 502")
                except urllib.error.HTTPError as exc:
                    self.assertEqual(exc.code, 502)
                    payload = json.loads(exc.read().decode())
                    self.assertEqual(payload["error"], "dashboard_unreachable")
                    self.assertIn("127.0.0.1:1", payload["upstream"])
            finally:
                relay.shutdown()
                relay.server_close()

    def test_companion_health_no_auth(self):
        relay = make_server("127.0.0.1:0", "http://127.0.0.1:1")
        threading.Thread(target=relay.serve_forever, daemon=True).start()
        try:
            host, port = relay.server_address
            with urllib.request.urlopen(f"http://{host}:{port}/companion/health", timeout=5) as resp:
                payload = json.loads(resp.read().decode())
            self.assertEqual(payload["relay"], "ok")
            self.assertIn(payload["mode"], ("standalone", "proxy"))
            self.assertIn(payload["upstream"], ("reachable", "refused", "unused"))
        finally:
            relay.shutdown()
            relay.server_close()

    def test_host_metrics_json(self):
        relay = make_server("127.0.0.1:0", "http://127.0.0.1:1")
        threading.Thread(target=relay.serve_forever, daemon=True).start()
        try:
            host, port = relay.server_address
            with urllib.request.urlopen(f"http://{host}:{port}/companion/host/metrics", timeout=5) as resp:
                payload = json.loads(resp.read().decode())
            self.assertTrue(payload["ok"])
            self.assertIn("cpu", payload["metrics"])
            self.assertIn("percent", payload["metrics"]["cpu"])
            self.assertIn("platform", payload["metrics"]["system"])
        finally:
            relay.shutdown()
            relay.server_close()

    def test_host_metrics_collect_failure_is_json(self):
        relay = make_server("127.0.0.1:0", "http://127.0.0.1:1")
        threading.Thread(target=relay.serve_forever, daemon=True).start()
        try:
            host, port = relay.server_address
            with patch("relay.collect_metrics", side_effect=RuntimeError("boom")):
                with urllib.request.urlopen(f"http://{host}:{port}/companion/host/metrics", timeout=5) as resp:
                    payload = json.loads(resp.read().decode())
            self.assertFalse(payload["ok"])
            self.assertEqual(payload["error"], "metrics_failed")
            self.assertEqual(payload["metrics"], {})
        finally:
            relay.shutdown()
            relay.server_close()

    def test_host_metrics_non_loopback_ok_without_basic_auth(self):
        relay = make_server("127.0.0.1:0", "http://127.0.0.1:1")
        threading.Thread(target=relay.serve_forever, daemon=True).start()
        try:
            host, port = relay.server_address
            with patch.dict(os.environ, {"HERMES_DASHBOARD_BASIC_AUTH_USER": ""}, clear=False):
                with patch.object(CompanionHandler, "_is_loopback", return_value=False):
                    with urllib.request.urlopen(f"http://{host}:{port}/companion/host/metrics", timeout=5) as resp:
                        payload = json.loads(resp.read().decode())
            self.assertTrue(payload["ok"])
            self.assertIn("cpu", payload["metrics"])
        finally:
            relay.shutdown()
            relay.server_close()

    def test_host_metrics_non_loopback_401_when_basic_auth_required(self):
        relay = make_server("127.0.0.1:0", "http://127.0.0.1:1")
        threading.Thread(target=relay.serve_forever, daemon=True).start()
        try:
            host, port = relay.server_address
            with patch.dict(os.environ, {"HERMES_DASHBOARD_BASIC_AUTH_USER": "ops"}, clear=False):
                with patch.object(CompanionHandler, "_is_loopback", return_value=False):
                    try:
                        urllib.request.urlopen(f"http://{host}:{port}/companion/host/metrics", timeout=5)
                        self.fail("expected 401")
                    except urllib.error.HTTPError as exc:
                        self.assertEqual(exc.code, 401)
                        payload = json.loads(exc.read().decode())
                        self.assertEqual(payload["error"], "unauthorized")
        finally:
            relay.shutdown()
            relay.server_close()

    def test_status_and_arm_result_update_lanes(self):
        """GET /companion/device/lanes must reflect armed + last_seen after WS frames."""
        clock = {"t": 1000.0}
        pairing = PairingStore(now=lambda: clock["t"])
        device = pairing.approve(pairing.issue("ops"))
        pairing.rename(device.device_id, "S22")
        state = RelayState(pairing=pairing)
        class FakeWfile:
            def write(self, data):
                del data
            def flush(self):
                return None
        state.lanes[device.device_id] = FakeWfile()

        relay = make_server("127.0.0.1:0", "http://127.0.0.1:9", state)
        threading.Thread(target=relay.serve_forever, daemon=True).start()
        try:
            host, port = relay.server_address
            origin = f"http://{host}:{port}"
            with urllib.request.urlopen(f"{origin}/companion/device/lanes", timeout=5) as resp:
                before = json.loads(resp.read().decode())
            row = next(r for r in before["devices"] if r["device_id"] == device.device_id)
            self.assertFalse(row.get("armed"))

            clock["t"] = 1100.0
            state.on_frame(
                json.dumps({
                    "type": "mobile.controller.status",
                    "device_id": device.device_id,
                    "armed": True,
                    "foreground_app": "com.example.app",
                    "a11y_bound": True,
                }),
                device_id=device.device_id,
            )
            with urllib.request.urlopen(f"{origin}/companion/device/lanes", timeout=5) as resp:
                after_status = json.loads(resp.read().decode())
            row = next(r for r in after_status["devices"] if r["device_id"] == device.device_id)
            self.assertTrue(row["armed"])
            self.assertEqual(row["foreground_app"], "com.example.app")
            self.assertGreaterEqual(row["last_seen"], 1100.0)

            # Disarm via result frame (phone omits top-level device_id; lane id from WS).
            clock["t"] = 1200.0
            state.on_frame(
                json.dumps({
                    "type": "mobile.controller.result",
                    "command_id": "c-arm-1",
                    "ok": True,
                    "result": {"armed": False},
                }),
                device_id=device.device_id,
            )
            with urllib.request.urlopen(f"{origin}/companion/device/lanes", timeout=5) as resp:
                after_result = json.loads(resp.read().decode())
            row = next(r for r in after_result["devices"] if r["device_id"] == device.device_id)
            self.assertFalse(row["armed"])
            self.assertGreaterEqual(row["last_seen"], 1200.0)
        finally:
            relay.shutdown()
            relay.server_close()

    def test_device_command_resolves_default_and_name(self):
        """POST /companion/device/command uses same device resolution as mobile_*/attach_http."""
        pairing = PairingStore(now=lambda: 50.0)
        a = pairing.approve(pairing.issue("ops"))
        b = pairing.approve(pairing.issue("ops"))
        pairing.rename(a.device_id, "S22")
        pairing.set_default(a.device_id)
        state = RelayState(pairing=pairing)
        written = []

        class FakeWfile:
            def write(self, data):
                written.append(data)
            def flush(self):
                return None

        state.lanes[a.device_id] = FakeWfile()
        state.lanes[b.device_id] = FakeWfile()
        relay = make_server("127.0.0.1:0", "http://127.0.0.1:9", state)
        threading.Thread(target=relay.serve_forever, daemon=True).start()
        try:
            host, port = relay.server_address
            origin = f"http://{host}:{port}"

            def post(payload, wait=False):
                body = dict(payload)
                body["wait"] = wait
                req = urllib.request.Request(
                    f"{origin}/companion/device/command",
                    data=json.dumps(body).encode(),
                    headers={"Content-Type": "application/json"},
                    method="POST",
                )
                with urllib.request.urlopen(req, timeout=5) as resp:
                    return json.loads(resp.read().decode())

            # Empty device_id → default live lane (was lane_down before).
            out = post({"action": "device.noop"})
            self.assertEqual(out["device_id"], a.device_id)
            self.assertIn("command_id", out)

            # Friendly name resolves.
            out = post({"device": "S22", "action": "device.noop"})
            self.assertEqual(out["device_id"], a.device_id)

            # Unknown / offline → structured error, not opaque lane_down on "".
            try:
                post({"device_id": "nope", "action": "device.noop"})
                self.fail("expected error")
            except urllib.error.HTTPError as exc:
                payload = json.loads(exc.read().decode())
                self.assertIn(payload.get("error"), ("unknown_device", "lane_down"))
        finally:
            relay.shutdown()
            relay.server_close()

    def test_notification_event_ring(self):
        state = RelayState()
        state.on_frame(
            json.dumps(
                {
                    "type": "mobile.controller.event",
                    "event": "notification",
                    "device_id": "dev_n1",
                    "profile": "coder",
                    "ts_ms": 1_700_000_000_000,
                    "notification": {
                        "key": "abc",
                        "package": "com.telegram.messenger",
                        "title": "hi",
                        "text": "there",
                        "category": "msg",
                        "ongoing": False,
                        "clearable": True,
                    },
                }
            ),
            device_id="dev_n1",
        )
        rows = state.list_notifications("dev_n1", limit=10)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["notification"]["package"], "com.telegram.messenger")
        self.assertTrue(state.live_meta["dev_n1"].get("notifications_stream"))

    def test_notification_status_meta(self):
        state = RelayState()
        state.on_frame(
            json.dumps(
                {
                    "type": "mobile.controller.status",
                    "device_id": "dev_n2",
                    "notifications_stream": True,
                    "notifications_listener_bound": True,
                }
            )
        )
        self.assertTrue(state.live_meta["dev_n2"]["notifications_stream"])
        self.assertTrue(state.live_meta["dev_n2"]["notifications_listener_bound"])

    def test_notifications_http_get(self):
        state = RelayState()
        state.push_notification(
            "dev_http",
            {
                "device_id": "dev_http",
                "ts_ms": 100,
                "notification": {"key": "k1", "package": "com.example", "title": "t", "text": "x"},
            },
        )
        relay = make_server("127.0.0.1:0", "http://127.0.0.1:9", state)
        threading.Thread(target=relay.serve_forever, daemon=True).start()
        try:
            host, port = relay.server_address
            with urllib.request.urlopen(
                f"http://{host}:{port}/companion/device/notifications?device_id=dev_http&limit=5",
                timeout=5,
            ) as resp:
                payload = json.loads(resp.read().decode())
            self.assertTrue(payload["ok"])
            self.assertEqual(payload["count"], 1)
            self.assertEqual(payload["notifications"][0]["notification"]["package"], "com.example")
        finally:
            relay.shutdown()
            relay.server_close()


if __name__ == "__main__":
    unittest.main()
