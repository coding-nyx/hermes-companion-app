"""Context-local speech snapshots. No snapshot or credential is written to disk."""
from contextvars import ContextVar
from contextlib import contextmanager
from copy import deepcopy
from functools import wraps
import secrets
import threading
import time

_active = ContextVar("audio_config_snapshot", default=None)
_snapshots = {}
_lock = threading.Lock()
_TTL = 6 * 3600


def create_snapshot():
    from fastapi import HTTPException
    from hermes_constants import get_hermes_home
    from hermes_cli.config import load_config
    cfg = load_config()
    token = secrets.token_urlsafe(32)
    now = time.monotonic()
    with _lock:
        for key in list(_snapshots):
            if _snapshots[key][0] < now:
                del _snapshots[key]
        if len(_snapshots) >= 64:
            raise HTTPException(429, "Too many active voice snapshots")
        _snapshots[token] = (now + _TTL, str(get_hermes_home()),
                             {k: deepcopy(cfg.get(k, {})) for k in ("stt", "tts", "voice")})
    return {"ok": True, "snapshot": token}


def release_snapshot(token):
    from hermes_constants import get_hermes_home
    with _lock:
        item = _snapshots.get(token)
        if item is not None and item[1] == str(get_hermes_home()):
            del _snapshots[token]
    return {"ok": True}


@contextmanager
def snapshot_scope(profile, token):
    from fastapi import HTTPException
    from hermes_cli.web_server_profiles import _config_profile_scope
    from hermes_constants import get_hermes_home
    if not token:
        yield
        return
    with _config_profile_scope(profile):
        home = str(get_hermes_home())
    with _lock:
        item = _snapshots.get(token)
        if item is None or item[0] < time.monotonic() or item[1] != home:
            raise HTTPException(409, "Voice settings snapshot expired; resume the call")
        value = item[2]
    binding = _active.set((home, value))
    try:
        yield
    finally:
        _active.reset(binding)


def apply_snapshot(config):
    from hermes_constants import get_hermes_home
    item = _active.get()
    if item is None or item[0] != str(get_hermes_home()):
        return config
    return {**config, **deepcopy(item[1])}


def with_voice_snapshot(fn):
    @wraps(fn)
    async def wrapped(*args, **kwargs):
        with snapshot_scope(kwargs.get("profile"), kwargs.get("voice_snapshot")):
            return await fn(*args, **kwargs)
    return wrapped
