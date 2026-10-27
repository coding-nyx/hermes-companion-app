"""Plugin-owned adapters over native audio handlers; no on-disk core patches.

Native handlers retain provider resolution, text normalization, decoding and auth.
Per-request function globals bind the immutable speech snapshot through each native
worker scope, including its raw streaming producer thread. No request mutates the
native audio module's globals. Config reads are overlaid only within a contextvar.
"""
import asyncio
from contextlib import contextmanager
from functools import wraps
import inspect
from types import FunctionType

from fastapi import APIRouter, HTTPException, WebSocket
from hermes_cli import config
from hermes_cli.web_routers import audio
from hermes_cli.web_models import AudioTranscriptionRequest, TTSSpeakRequest, VoiceLiveSessionRequest
from hermes_cli.web_server_profiles import _config_profile_scope

from .voice_snapshot import apply_snapshot, snapshot_scope
from . import edge_compat


def install_config_overlay():
    for name in ("load_config", "load_config_readonly"):
        original = getattr(config, name)
        if getattr(original, "_companion_snapshot", False):
            continue
        def wrap(fn):
            @wraps(fn)
            def read(*args, **kwargs):
                return apply_snapshot(fn(*args, **kwargs))
            read._companion_snapshot = True
            return read
        setattr(config, name, wrap(original))


install_config_overlay()
edge_compat.install()
from .voice_settings import router as settings_router

router = APIRouter()
for route in settings_router.routes:
    router.add_api_route(route.path.removeprefix("/api"), route.endpoint, methods=route.methods)


def _handler(name, profile, token):
    fn = getattr(audio, name, None)
    if fn is None or not inspect.iscoroutinefunction(fn):
        raise HTTPException(503, "Companion voice is incompatible with this Hermes version; update the Companion plugin")
    # Reject the old source overlay as well as an incompatible future signature.
    signature = inspect.signature(fn)
    expected = {"speak_stream_ws": {"ws"}, "transcribe_audio_upload": {"payload", "profile"},
                "speak_text": {"payload", "profile"}, "create_voice_live_session": {"payload", "profile"}}
    if name in expected and set(signature.parameters) != expected[name]:
        raise HTTPException(503, "Companion voice requires clean native audio handlers; remove the obsolete source overlay")

    @contextmanager
    def scope(selected):
        with snapshot_scope(selected, token), _config_profile_scope(selected):
            yield

    async def worker(selected, operation):
        def run():
            with scope(selected):
                return operation()
        return await asyncio.to_thread(run)

    namespace = dict(fn.__globals__)
    namespace.update(_run_config_scoped=worker, _config_profile_scope=scope)
    bound = FunctionType(fn.__code__, namespace, fn.__name__, fn.__defaults__, fn.__closure__)
    bound.__kwdefaults__ = fn.__kwdefaults__
    return bound


@router.post("/audio/transcribe")
async def transcribe(payload: AudioTranscriptionRequest, profile: str | None = None, voice_snapshot: str | None = None):
    return await _handler("transcribe_audio_upload", profile, voice_snapshot)(payload, profile)


@router.post("/audio/speak")
async def speak(payload: TTSSpeakRequest, profile: str | None = None, voice_snapshot: str | None = None):
    return await _handler("speak_text", profile, voice_snapshot)(payload, profile)


@router.get("/audio/voice-live/status")
async def live_status(profile: str | None = None):
    return await _handler("get_voice_live_status", profile, None)(profile)


@router.post("/audio/voice-live/session")
async def live_session(payload: VoiceLiveSessionRequest, profile: str | None = None, voice_snapshot: str | None = None):
    return await _handler("create_voice_live_session", profile, voice_snapshot)(payload, profile)


@router.websocket("/audio/speak-stream")
async def stream(ws: WebSocket):
    profile = ws.query_params.get("profile") or None
    token = ws.query_params.get("voice_snapshot")
    handler = _handler("speak_stream_ws", profile, token)
    await handler(ws)
