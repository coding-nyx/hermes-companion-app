from __future__ import annotations

import os
import sys
import threading
import unittest
from argparse import Namespace
from io import StringIO
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from pairing import PairingStore
from relay import RelayState, make_server


class CliTests(unittest.TestCase):
    def test_list_approve_via_http(self):
        state = RelayState(pairing=PairingStore(now=lambda: 50.0))
        code = state.pairing.offer("K7M2QX", "coder")
        relay = make_server("127.0.0.1:0", "http://127.0.0.1:9", state)
        threading.Thread(target=relay.serve_forever, daemon=True).start()
        try:
            host, port = relay.server_address
            env = {"HERMES_COMPANION_RELAY_URL": f"http://{host}:{port}"}
            with patch.dict(os.environ, env, clear=False):
                from cli import handle

                buf = StringIO()
                with patch("sys.stdout", buf):
                    handle(Namespace(companion_cmd="approve", code=code))
                self.assertIn("dev_", buf.getvalue())
                listed = StringIO()
                with patch("sys.stdout", listed):
                    handle(Namespace(companion_cmd="list"))
                self.assertIn("coder", listed.getvalue())
                lanes = StringIO()
                with patch("sys.stdout", lanes):
                    handle(Namespace(companion_cmd="lanes"))
                self.assertIn("no live lanes", lanes.getvalue())
        finally:
            relay.shutdown()
            relay.server_close()


    def test_cli_main_fallback(self):
        state = RelayState(pairing=PairingStore(now=lambda: 50.0))
        device = state.pairing.approve(state.pairing.issue("ops"))
        state.pairing.rename(device.device_id, "Pixel")
        relay = make_server("127.0.0.1:0", "http://127.0.0.1:9", state)
        threading.Thread(target=relay.serve_forever, daemon=True).start()
        try:
            host, port = relay.server_address
            env = {"HERMES_COMPANION_RELAY_URL": f"http://{host}:{port}"}
            with patch.dict(os.environ, env, clear=False):
                from cli_main import main
                buf = StringIO()
                with patch("sys.stdout", buf):
                    main(["list"])
                self.assertIn(device.device_id, buf.getvalue())
                lanes = StringIO()
                with patch("sys.stdout", lanes):
                    main(["lanes"])
                self.assertIn("no live lanes", lanes.getvalue())
                renamed = StringIO()
                with patch("sys.stdout", renamed):
                    main(["rename", device.device_id, "LabPhone"])
                self.assertIn("LabPhone", renamed.getvalue())
                defaulted = StringIO()
                with patch("sys.stdout", defaulted):
                    main(["default", "LabPhone"])
                self.assertIn(device.device_id, defaulted.getvalue())
        finally:
            relay.shutdown()
            relay.server_close()


if __name__ == "__main__":
    unittest.main()
