from __future__ import annotations

import argparse
import os
import sys
import threading
import unittest
from argparse import Namespace
from io import StringIO
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import _isolate  # noqa: F401,E402  # must precede plugin imports

from pairing import PairingStore
from relay import RelayState, make_server


class CliTests(unittest.TestCase):
    def test_help_lists_subcommands(self):
        from cli import handle, setup

        parser = argparse.ArgumentParser(prog="hermes companion")
        setup(parser)
        text = parser.format_help()
        for name in ("list", "approve", "revoke", "lanes", "default", "rename", "relay", "room"):
            self.assertIn(name, text)
        buf = StringIO()
        with patch("sys.stdout", buf):
            handle(parser.parse_args([]))
        self.assertIn("revoke", buf.getvalue())

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

    def test_pair_qr_generation(self):
        state = RelayState(pairing=PairingStore(now=lambda: 50.0))
        relay = make_server("127.0.0.1:0", "http://127.0.0.1:9", state)
        threading.Thread(target=relay.serve_forever, daemon=True).start()
        try:
            host, port = relay.server_address
            env = {"HERMES_COMPANION_RELAY_URL": f"http://{host}:{port}"}
            with patch.dict(os.environ, env, clear=False):
                from cli import handle

                buf = StringIO()
                with patch("sys.stdout", buf):
                    handle(Namespace(companion_cmd="qr", profile="coder", host="http://100.83.141.111:9120", approve=True))
                out = buf.getvalue()
                self.assertIn("HERMES COMPANION — INSTANT DEVICE PAIRING", out)
                # Same encoding as the app's WakePolicy.pairDeepLink (WakePolicyTest pins it).
                self.assertIn("hermes-companion://pair?origin=http%3A%2F%2F100.83.141.111%3A9120&code=", out)
                self.assertIn("&profile=coder", out)
                self.assertIn("coder", out)
                self.assertIn("Pre-approved", out)
        finally:
            relay.shutdown()
            relay.server_close()

    def test_pair_deep_link_encodes_awkward_origins(self):
        from urllib.parse import parse_qs, urlsplit

        from qr import pair_deep_link

        origin = "https://hermes.example.ts.net/hub?x=1&y=2#frag"
        link = pair_deep_link(origin, "x7k2m9", "ops team")
        self.assertEqual(
            link,
            "hermes-companion://pair?origin=https%3A%2F%2Fhermes.example.ts.net%2Fhub%3Fx%3D1%26y%3D2%23frag"
            "&code=X7K2M9&profile=ops%20team",
        )
        q = parse_qs(urlsplit(link).query)
        self.assertEqual(q["origin"], [origin])
        self.assertEqual(q["code"], ["X7K2M9"])
        self.assertEqual(q["profile"], ["ops team"])

    def test_pair_qr_no_approve_leaves_code_pending(self):
        state = RelayState(pairing=PairingStore(now=lambda: 50.0))
        relay = make_server("127.0.0.1:0", "http://127.0.0.1:9", state)
        threading.Thread(target=relay.serve_forever, daemon=True).start()
        try:
            host, port = relay.server_address
            with patch.dict(os.environ, {"HERMES_COMPANION_RELAY_URL": f"http://{host}:{port}"}, clear=False):
                from cli import handle

                buf = StringIO()
                with patch("sys.stdout", buf):
                    handle(Namespace(companion_cmd="qr", profile="default", host="http://lab:9120", approve=False))
            code = next(iter(state.pairing.pending))
            self.assertIn(f"hermes companion approve {code}", buf.getvalue())
            # The phone polls first and must see pending, not unknown, so it skips the re-offer.
            self.assertEqual(state.pairing.status(code)["status"], "pending")
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
