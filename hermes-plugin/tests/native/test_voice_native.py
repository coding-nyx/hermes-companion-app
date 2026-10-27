"""Run with Hermes scripts/run_tests.sh /path/to/plugin/tests/native/test_voice_native.py.

Real native routes, configuration writer and profile scopes; no speech provider requests.
"""
from pathlib import Path
import asyncio
import pytest
from starlette.testclient import TestClient


@pytest.fixture
def homes(tmp_path, monkeypatch):
    root = tmp_path / ".hermes"
    b = root / "profiles" / "b"
    b.mkdir(parents=True)
    plugins = root / "plugins"
    plugins.mkdir()
    (plugins / "hermes-companion").symlink_to(Path(__file__).resolve().parents[2], target_is_directory=True)
    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    monkeypatch.setenv("HERMES_HOME", str(root))
    from agent import secret_scope
    from tui_gateway import launch_profile_policy
    monkeypatch.setattr(secret_scope, "_MULTIPLEX_ACTIVE", False)
    monkeypatch.setattr(launch_profile_policy, "_snapshot", None)
    for home, voice in ((root, "en-US-GuyNeural"), (b, "en-US-JennyNeural")):
        (home / "config.yaml").write_text(
            "# keep this comment\nplugins:\n  enabled: [hermes-companion]\nmodel:\n  default: test-model\n  api_key: fake-private-model-key\n"
            f"tts:\n  provider: edge\n  edge:\n    voice: {voice}\n"
            "voice:\n  voice_chat_mode: chained\n  gpt_live:\n    api_key: fake-private-voice-key\n"
            "unrelated:\n  preserved: true\n")
    return root, b


def client():
    from hermes_cli.web_server import _SESSION_HEADER_NAME, _SESSION_TOKEN, app
    result = TestClient(app)
    result.headers[_SESSION_HEADER_NAME] = _SESSION_TOKEN
    return result


def test_routes_isolate_profiles_hide_credentials_and_merge_only_speech(homes):
    c = client()
    def voice(profile=None):
        response = c.get("/api/plugins/hermes-companion/audio/settings", params={"profile": profile} if profile else {})
        assert response.status_code == 200, response.text
        assert "fake-private" not in response.text
        values = {f["key"]: f["value"] for f in response.json()["fields"]}
        assert all("api_key" not in key and "command" not in key and "base_url" not in key for key in values)
        return values["tts.edge.voice"]
    a = voice()
    b = voice("b")
    assert a != b and voice() == a
    result = c.patch("/api/plugins/hermes-companion/audio/settings?profile=b", json={"changes": {"tts.edge.voice": "en-US-AriaNeural"}})
    assert result.status_code == 200, result.text
    assert voice("b") == "en-US-AriaNeural" and voice() == a
    raw = homes[1].joinpath("config.yaml").read_text()
    assert "keep this comment" in raw and "fake-private-model-key" in raw and "preserved" in raw
    assert c.patch("/api/plugins/hermes-companion/audio/settings?profile=b", json={"changes": {"voice.gpt_live.api_key": "replace"}}).status_code == 400
    assert c.patch("/api/plugins/hermes-companion/audio/settings?profile=b", json={"changes": {"model": "replace"}}).status_code == 400


def test_plugin_api_requires_native_auth_and_runtime_enablement(homes):
    c = client()
    from hermes_cli.web_server import app
    unauthenticated = TestClient(app, client=("203.0.113.10", 50000))
    assert unauthenticated.get("/api/plugins/hermes-companion/audio/settings").status_code in (401, 403)
    from hermes_cli.config import require_readable_config_before_write, save_config
    cfg = require_readable_config_before_write()
    cfg["plugins"]["disabled"] = ["hermes-companion"]
    save_config(cfg)
    assert c.get("/api/plugins/hermes-companion/audio/settings").status_code == 404


def test_edge_v1_compatibility_uses_the_pinned_upstream_v2_protocol(homes, monkeypatch):
    client()  # Load via the supported native plugin API discovery path.
    from tools import tts_tool
    from hermes_companion_audio import edge_compat
    from types import ModuleType
    import sys
    package = ModuleType("companion_fixture_edge")
    modules = {name: ModuleType(package.__name__ + "." + name) for name in ("constants", "communicate", "voices")}
    constants = modules["constants"]
    constants.SEC_MS_GEC_VERSION = "1-fixture"
    for module in modules.values():
        monkeypatch.setitem(sys.modules, module.__name__, module)
    monkeypatch.setattr(tts_tool, "_import_edge_tts", lambda: package)
    edge_compat.install()
    assert tts_tool._import_edge_tts() is package
    communicate = modules["communicate"]
    import re
    assert constants.SEC_MS_GEC_VERSION.startswith("2-")
    assert re.fullmatch(r"[A-F0-9]{16}-[A-F0-9]{64}", communicate.DRM.generate_sec_ms_gec())
    assert modules["voices"].DRM is communicate.DRM


def test_incompatible_native_handler_fails_before_provider_work(homes, monkeypatch):
    c = client()
    async def changed(payload, profile=None, incompatible=None):
        raise AssertionError("An incompatible handler must not execute")
    monkeypatch.setattr("hermes_cli.web_routers.audio.speak_text", changed)
    response = c.post("/api/plugins/hermes-companion/audio/speak", json={"text": "Fixture"})
    assert response.status_code == 503


def test_call_snapshot_survives_settings_save_and_cannot_cross_profiles(homes, monkeypatch, tmp_path):
    c = client()
    from hermes_companion_audio.voice_snapshot import snapshot_scope
    from hermes_cli.web_server_profiles import _config_profile_scope
    from hermes_cli.config import load_config, load_config_readonly
    from fastapi import HTTPException
    c = client()
    token = c.post("/api/plugins/hermes-companion/audio/settings/snapshot?profile=b", json={}).json()["snapshot"]
    assert c.patch("/api/plugins/hermes-companion/audio/settings?profile=b", json={"changes": {"tts.edge.voice": "en-US-AriaNeural", "voice.gpt_live.voice": "cedar"}}).status_code == 200
    async def read_bound():
        with snapshot_scope("b", token):
            def worker():
                with _config_profile_scope("b"):
                    return load_config()["tts"]["edge"]["voice"], load_config_readonly()["tts"]["edge"]["voice"]
            return await asyncio.to_thread(worker)
    old, readonly = asyncio.run(read_bound())
    assert old == readonly == "en-US-JennyNeural"
    with _config_profile_scope("b"):
        assert load_config()["tts"]["edge"]["voice"] == "en-US-AriaNeural"
    # Exercise the real route, executor hop and config loader against the frozen home.
    def synthesize(text, **kwargs):
        assert load_config()["tts"]["edge"]["voice"] == old
        path = tmp_path / "test-speech.mp3"
        path.write_bytes(b"synthetic-audio")
        return {"success": True, "file_path": str(path), "provider": "fixture"}
    monkeypatch.setattr("tools.tts_tool.text_to_speech_tool", synthesize)
    response = c.post(f"/api/plugins/hermes-companion/audio/speak?profile=b&voice_snapshot={token}", json={"text": "Fixture"})
    assert response.status_code == 200, response.text
    assert response.json()["provider"] == "fixture"
    def create_live(sdp, history):
        assert load_config()["voice"]["gpt_live"]["voice"] != "cedar"
        return {"session": {"id": "fixture"}, "transport": {"type": "webrtc", "sdp": sdp}}
    monkeypatch.setattr("tools.voice_live.create_webrtc_session", create_live)
    response = c.post(f"/api/plugins/hermes-companion/audio/voice-live/session?profile=b&voice_snapshot={token}", json={"sdp": "v=0\r\nm=audio\r\n"})
    assert response.status_code == 200, response.text
    class Stream:
        sample_rate = 24000
        channels = 1
        def stream(self, text):
            assert load_config()["tts"]["edge"]["voice"] == old
            yield b"\x00\x01" * 320
    monkeypatch.setattr("tools.tts_streaming.resolve_streaming_provider", lambda cfg: Stream())
    from hermes_cli.web_server import _SESSION_TOKEN
    with c.websocket_connect(f"/api/plugins/hermes-companion/audio/speak-stream?profile=b&voice_snapshot={token}&token={_SESSION_TOKEN}") as socket:
        socket.send_json({"text": "Fixture sentence. ", "done": True})
        messages = []
        while True:
            message = socket.receive()
            messages.append(message)
            if '"end"' in message.get("text", ""):
                break
        assert any(message.get("bytes") for message in messages)
    with pytest.raises(HTTPException):
        with snapshot_scope(None, token):
            pass
    assert c.delete(f"/api/plugins/hermes-companion/audio/settings/snapshot/{token}?profile=b").status_code == 200
    with pytest.raises(HTTPException):
        with snapshot_scope("b", token):
            pass
