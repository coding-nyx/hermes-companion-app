from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

PLUGIN = Path(__file__).resolve().parents[1]


class HostServicesTests(unittest.TestCase):
    def test_bash_syntax(self):
        for name in ("install.sh", "install-services.sh", "host_services.sh"):
            subprocess.check_call(["bash", "-n", str(PLUGIN / name)])

    def test_write_launchd_plist(self):
        with tempfile.TemporaryDirectory() as tmp:
            plist = Path(tmp) / "ai.hermes.companion-relay.plist"
            log = Path(tmp) / "relay.log"
            script = f"""
set -euo pipefail
. "{PLUGIN / "host_services.sh"}"
hc_write_launchd_plist "{plist}" "ai.hermes.companion-relay" "{tmp}" "{log}" \\
  HERMES_HOME=/tmp/hermes HERMES_COMPANION_STANDALONE=1 -- /usr/bin/python3 -u -m relay
"""
            subprocess.check_call(["bash", "-c", script])
            text = plist.read_text(encoding="utf-8")
            self.assertIn("<string>ai.hermes.companion-relay</string>", text)
            self.assertIn("<string>-m</string>", text)
            self.assertIn("<string>relay</string>", text)
            self.assertIn("<key>KeepAlive</key>", text)
            self.assertIn("<string>/tmp/hermes</string>", text)

    def test_os_kind_matches_uname(self):
        out = subprocess.check_output(
            ["bash", "-c", f'. "{PLUGIN / "host_services.sh"}"; hc_os'],
            text=True,
        ).strip()
        uname = os.uname().sysname
        expected = {"Darwin": "darwin", "Linux": "linux"}.get(uname, "other")
        self.assertEqual(out, expected)


if __name__ == "__main__":
    unittest.main()
