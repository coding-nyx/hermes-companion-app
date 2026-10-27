"""Native Hermes audio settings: a credential-free projection of profile configuration.

Mounted by Hermes's enabled-plugin API loader, beneath native dashboard authentication.
No provider request is made by discovery.
"""
import asyncio
import math
from typing import Any, Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, ConfigDict

router = APIRouter()
_LEAVES = frozenset({"model", "model_id", "voice", "voice_id", "speed", "language", "language_code"})
_VOICE = {"voice.voice_chat_mode", "voice.gpt_live.model", "voice.gpt_live.voice"}


def _allowed(key: str) -> bool:
    parts = key.split(".")
    return key in _VOICE or (parts[0] in {"stt", "tts"} and (
        len(parts) == 2 and parts[1] in {"provider", "language"} or
        len(parts) == 3 and parts[2] in _LEAVES and parts[1].replace("_", "").replace("-", "").isalnum() or
        len(parts) == 4 and parts[1] == "providers" and parts[3] in _LEAVES and
        parts[2].replace("_", "").replace("-", "").isalnum()))


def _flatten(value: dict, prefix: str = "") -> dict:
    result = {}
    for name, item in value.items():
        key = f"{prefix}.{name}" if prefix else name
        if isinstance(item, dict):
            result.update(_flatten(item, key))
        elif _allowed(key) and isinstance(item, (str, float, int)) and not isinstance(item, bool):
            result[key] = item
    return result


def _project(config: dict, schema: dict, live: dict) -> dict:
    flat = _flatten(config)
    flat.setdefault("stt.provider", "auto")
    fields = []
    for key, value in sorted(flat.items()):
        definition = schema.get(key, {})
        if key == "stt.provider" and not definition:
            from hermes_cli.web_server_config import _SCHEMA_OVERRIDES, _custom_provider_options
            definition = dict(_SCHEMA_OVERRIDES.get(key, {}))
            definition["options"] = _custom_provider_options("stt", list(definition.get("options", [])), config)
        options = definition.get("options", [])
        if key == "voice.voice_chat_mode":
            options = ["chained", "gpt-live"]
        if key == "stt.provider":
            options = ["auto", *[v for v in options if v != "auto"]]
        if key == "voice.gpt_live.voice":
            from tools.voice_live import GPT_LIVE_VOICES
            options = list(GPT_LIVE_VOICES)
        if key == "voice.gpt_live.model":
            from tools.voice_live import DEFAULT_LIVE_MODEL
            options = [DEFAULT_LIVE_MODEL]
        fields.append({"key": key, "value": value,
                       "options": [v for v in options if isinstance(v, str)],
                       "type": "number" if isinstance(value, (int, float)) else "string"})
    return {"ok": True, "version": 1, "api_base": "/api/plugins/hermes-companion", "fields": fields,
            "live": {k: live.get(k) for k in ("mode", "available", "reason", "model", "voice")},
            "streaming": True, "snapshots": True}


def _read_scoped() -> dict:
    from hermes_cli.config import load_config
    from hermes_cli.web_server_config import _schema_with_dynamic_provider_options
    from tools.voice_live import resolve_gpt_live_status
    from tools.transcription_tools import _get_provider as stt_provider
    from tools.tts_tool import _get_provider as tts_provider, check_tts_requirements
    config = load_config()
    result = _project(config, _schema_with_dynamic_provider_options(), resolve_gpt_live_status())
    stt = stt_provider(config.get("stt", {}))
    tts = tts_provider(config.get("tts", {}))
    ready = stt != "none" and check_tts_requirements()
    result["speech"] = {"available": ready, "stt_provider": stt, "tts_provider": tts,
                        "reason": "" if ready else "Configure an available recognition and speech provider in the Hermes profile"}
    return result


def _validate(changes: dict, current: dict) -> None:
    permitted = {field["key"]: field for field in current["fields"]}
    if not changes or len(changes) > 30:
        raise HTTPException(400, "Supply between 1 and 30 speech settings")
    for key, value in changes.items():
        field = permitted.get(key)
        if not _allowed(key) or field is None:
            raise HTTPException(400, "Unsupported speech setting")
        if field["type"] == "number":
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not 0.1 <= value <= 4:
                raise HTTPException(400, "Speech speed must be between 0.1 and 4")
        elif not isinstance(value, str) or len(value) > 256 or any(ord(c) < 32 for c in value):
            raise HTTPException(400, "Invalid speech setting value")
        if field["options"] and value not in field["options"] and value != field["value"]:
            raise HTTPException(400, "Choose an advertised speech option")


class AudioSettingsPatch(BaseModel):
    model_config = ConfigDict(extra="forbid")
    changes: dict[str, Any]


@router.get("/api/audio/settings")
async def get_audio_settings(profile: Optional[str] = None):
    from hermes_cli.web_server_profiles import _config_profile_scope
    def run():
        with _config_profile_scope(profile):
            return _read_scoped()
    return await asyncio.to_thread(run)


@router.get("/api/audio/settings/voices")
async def audio_voice_catalog(provider: str, profile: Optional[str] = None):
    # Discovery is explicit: opening settings never calls an upstream provider.
    if provider == "edge":
        from tools.tts_tool import _import_edge_tts
        edge_tts = _import_edge_tts()
        voices = await edge_tts.list_voices()
        return {"available": True, "voices": [v["ShortName"] for v in voices if isinstance(v.get("ShortName"), str)]}
    if provider == "elevenlabs":
        from hermes_cli.web_routers.audio import get_elevenlabs_voices
        catalog = await get_elevenlabs_voices(profile)
        return {"available": catalog.get("available", False), "voices": [v["voice_id"] for v in catalog.get("voices", [])]}
    return {"available": False, "voices": []}


@router.post("/api/audio/settings/snapshot")
async def begin_audio_snapshot(profile: Optional[str] = None):
    from hermes_cli.web_server_profiles import _config_profile_scope
    from .voice_snapshot import create_snapshot
    def run():
        with _config_profile_scope(profile):
            return create_snapshot()
    return await asyncio.to_thread(run)


@router.delete("/api/audio/settings/snapshot/{token}")
async def end_audio_snapshot(token: str, profile: Optional[str] = None):
    from hermes_cli.web_server_profiles import _config_profile_scope
    from .voice_snapshot import release_snapshot
    def run():
        with _config_profile_scope(profile):
            return release_snapshot(token)
    return await asyncio.to_thread(run)


@router.patch("/api/audio/settings")
async def patch_audio_settings(body: AudioSettingsPatch, profile: Optional[str] = None):
    from hermes_cli import web_server
    from hermes_cli.config import require_readable_config_before_write, save_config
    from hermes_cli.web_server_profiles import _profile_scope
    def run():
        with _profile_scope(profile):
            with web_server._CONFIG_MUTATION_LOCK:
                _validate(body.changes, _read_scoped())
                config = require_readable_config_before_write()
                for key, value in body.changes.items():
                    node = config
                    parts = key.split(".")
                    for part in parts[:-1]:
                        child = node.get(part)
                        if not isinstance(child, dict):
                            child = {}; node[part] = child
                        node = child
                    if key == "stt.provider" and value == "auto":
                        node.pop("provider", None)
                    else:
                        node[parts[-1]] = value
                save_config(config)
                return _read_scoped()
    return await asyncio.to_thread(run)
