from __future__ import annotations

import os
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import _isolate  # noqa: F401,E402  # must precede plugin imports

import media  # noqa: E402
import notif_policy  # noqa: E402
import pairing  # noqa: E402
import standalone  # noqa: E402


class IsolationTests(unittest.TestCase):
    """Guard the sandbox itself.

    Without this, a store that quietly stops honouring HERMES_HOME would go unnoticed until
    it had written into someone's real ~/.hermes again — which is exactly how the operator
    store came to accumulate test residue.
    """

    def test_sandbox_is_active(self):
        self.assertIsNotNone(
            _isolate.SANDBOX,
            "tests must run against a sandbox; HERMES_HOME was already set externally",
        )
        self.assertTrue(Path(_isolate.SANDBOX).is_dir())

    def test_no_store_resolves_into_the_real_home(self):
        real = Path.home() / ".hermes"
        paths = {
            "standalone operator store": standalone.default_state_path(),
            "pairing store": pairing.default_store_path(),
            "notification policy": notif_policy.policy_path(),
        }
        media_dir = getattr(media, "default_media_dir", None) or getattr(media, "media_dir", None)
        if callable(media_dir):
            paths["media dir"] = Path(media_dir())
        for label, path in paths.items():
            with self.subTest(store=label):
                self.assertFalse(
                    str(Path(path).resolve()).startswith(str(real.resolve())),
                    f"{label} resolves inside the real home: {path}",
                )
                self.assertTrue(
                    str(Path(path)).startswith(_isolate.SANDBOX),
                    f"{label} is outside the sandbox: {path}",
                )

    def test_profiles_dir_is_sandboxed(self):
        # agent_wake and drift root profile lookups here; pointing at the real dir is what
        # made the discovery tests read live host state.
        self.assertTrue(os.environ["HERMES_PROFILES_DIR"].startswith(_isolate.SANDBOX))


if __name__ == "__main__":
    unittest.main()
