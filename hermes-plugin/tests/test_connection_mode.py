from __future__ import annotations

import json
import os
import sys
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.request
import urllib.error
from unittest.mock import patch
import _isolate  # noqa: F401
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from connection_mode import ConnectionMode
from relay import RelayState, make_server, CompanionHandler
from standalone import OperatorError


class NativeProbe:
    def __init__(self):
        self.calls = []
        self.closed = False
    def connect(self, token=None):
        return {"instance_id": "native"}
    def request(self, method, fields, timeout=None):
        self.calls.append((method, fields))
        return {"profiles": [{"name": "default"}]}
    def close(self):
        self.closed = True


class ConnectionModeTests(unittest.TestCase):
    def test_verified_switch_persists_over_startup_default_and_closes_only_operator_sockets(self):
        with tempfile.TemporaryDirectory() as d, patch.dict(os.environ, HERMES_COMPANION_STANDALONE="1"):
            path = Path(d) / "connection.json"
            state = RelayState(connection=ConnectionMode(path))
            probe = NativeProbe()
            state.room_controller.ws_factory = lambda *args: probe
            state._dashboard_token = lambda: None
            class Sock:
                closed = False
                def shutdown(self, *args): self.closed = True
                def close(self): self.closed = True
            operator, device = Sock(), Sock()
            state.operator_connections.add(operator)
            state.lanes["device"] = device
            self.assertEqual(state.set_connection_mode("proxy", "standalone"), {"ok": True, "mode": "proxy"})
            self.assertEqual(probe.calls, [("profiles.list", {"profile": "default"})])
            self.assertTrue(probe.closed)
            self.assertTrue(operator.closed)
            self.assertFalse(device.closed)
            self.assertEqual(ConnectionMode(path).mode, "proxy")
            self.assertEqual(ConnectionMode(path).source, "saved")
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)

    def test_failed_native_verification_preserves_the_previous_mode_and_sockets(self):
        state = RelayState(connection=ConnectionMode())
        state.connection.save("standalone")
        probe = NativeProbe()
        probe.request = lambda *a, **k: {"unexpected": []}
        state.room_controller.ws_factory = lambda *a: probe
        state._dashboard_token = lambda: None
        socket = object()
        state.operator_connections.add(socket)
        with self.assertRaises(OperatorError): state.set_connection_mode("proxy", "standalone")
        self.assertTrue(state.is_standalone())
        self.assertIn(socket, state.operator_connections)
        self.assertTrue(probe.closed)

    def test_conflict_never_overwrites_another_devices_choice(self):
        state = RelayState()
        state.connection.save("proxy")
        with self.assertRaises(OperatorError) as error: state.set_connection_mode("standalone", "standalone")
        self.assertEqual(error.exception.status, 409)
        self.assertFalse(state.is_standalone())
        self.assertEqual(state.set_connection_mode("proxy", "standalone")["mode"], "proxy")

    def test_remote_mode_changes_require_a_paired_credential_and_health_advertises_support(self):
        state = RelayState()
        state.connection.save("standalone")
        state.pairing.offer("K7M2QX", "default")
        device = state.pairing.approve("K7M2QX")
        server = make_server("127.0.0.1:0", "http://127.0.0.1:1", state)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        base = "http://%s:%s" % server.server_address
        try:
            health = json.load(urllib.request.urlopen(base + "/companion/health"))
            self.assertTrue(health["connection_settings"])
            with patch.object(CompanionHandler, "_is_loopback", return_value=False):
                def request(auth=None):
                    return urllib.request.urlopen(urllib.request.Request(base + "/companion/connection/mode",
                        data=b'{"mode":"standalone","expected_mode":"standalone"}',
                        headers={"Content-Type": "application/json", **({"Authorization": auth} if auth else {})}))
                with self.assertRaises(urllib.error.HTTPError) as error: request()
                self.assertEqual(error.exception.code, 401)
                self.assertTrue(json.load(request("Companion %s:%s" % (device.device_id, device.credential)))["ok"])
        finally:
            server.shutdown(); server.server_close()
