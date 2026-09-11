"""Point every plugin store at a throwaway directory for the duration of a test run.

Import this first from each test module — before importing any plugin module.

Why this exists: the plugin's store paths fall back to the *real* `~/.hermes` when
`HERMES_HOME` is unset, so simply running the suite on a developer machine wrote into
the developer's own state. One run grew `~/.hermes/companion-operator.json` from 9971 to
10240 bytes; the accumulated "sessions" in a dev machine's operator store were largely
test residue. It also made four tests read live host state, which is why they pass on a
clean machine and fail on lab and hub-11 (a relay listening on :9120, a populated
profiles dir, real git repos).

`tests` deliberately is not a package: `unittest discover -s hermes-plugin/tests` would
then walk up looking for a top-level package and find `hermes-plugin`, whose hyphen makes
it an invalid module name. So this is a plain module that discovery puts on `sys.path`,
imported explicitly rather than via `__init__.py`.

An externally supplied `HERMES_HOME` is respected and left alone, so a deliberate
integration run against a real home is still possible.
"""

from __future__ import annotations

import atexit
import os
import shutil
import tempfile

#: Set when this module created the sandbox, so tests can assert on isolation.
SANDBOX: str | None = None

_VARS_FROM_HOME = {
    # name -> path under the sandbox, or None for the sandbox root itself
    "HERMES_HOME": None,
    "HERMES_COMPANION_STATE": "companion-operator.json",
    "HERMES_COMPANION_MEDIA": "companion-media",
    "HERMES_COMPANION_NOTIF_POLICY": "companion-notif-policy.json",
    "HERMES_PROFILES_DIR": "profiles",
}

#: Every HTTP fallback in the plugin defaults to ``http://127.0.0.1:9120``. On a machine that
#: is actually running a relay — lab, hub-11 — a test asserting "no server, so this must
#: fail" instead reaches the live relay and succeeds. Point the suite at a closed port so
#: reaching a relay has to be deliberate; tests that want one set this themselves.
_CLOSED_RELAY = "http://127.0.0.1:1"


def _install() -> None:
    global SANDBOX
    if os.environ.get("HERMES_HOME"):
        # Caller wants a real home; don't second-guess it.
        return
    SANDBOX = tempfile.mkdtemp(prefix="hermes-companion-tests-")
    os.makedirs(os.path.join(SANDBOX, "profiles"), exist_ok=True)
    for var, leaf in _VARS_FROM_HOME.items():
        if os.environ.get(var):
            continue  # a more specific override was set on purpose
        os.environ[var] = SANDBOX if leaf is None else os.path.join(SANDBOX, leaf)
    os.environ.setdefault("HERMES_COMPANION_RELAY_URL", _CLOSED_RELAY)
    atexit.register(shutil.rmtree, SANDBOX, True)


_install()
