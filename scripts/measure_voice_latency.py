#!/usr/bin/env python3
"""Synthetic native voice benchmark. Run over SSH on a loopback dashboard.

Creates and deletes only its own temporary chat. No microphone, private history,
credential output, provider changes or mutation retries. Prints timings only.
"""
import argparse
import base64
import hashlib
import json
import os
import re
import socket
import struct
import time
import urllib.request
from urllib.parse import quote, urlsplit


class WebSocket:
    def __init__(self, origin, path, token):
        address = urlsplit(origin)
        self.sock = socket.create_connection((address.hostname, address.port or 80), timeout=60)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((f"GET {path} HTTP/1.1\r\nHost: {address.netloc}\r\n"
            f"Authorization: Bearer {token}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            f"Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: {key}\r\n\r\n").encode())
        headers = bytearray()
        while not headers.endswith(b"\r\n\r\n"):
            headers.extend(self.read(1))
            if len(headers) > 16384:
                raise ValueError("invalid_upgrade")
        expected = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest())
        if b" 101 " not in headers.split(b"\r\n")[0] or expected not in headers:
            self.sock.close()
            raise ValueError("upgrade_refused")

    def read(self, size):
        data = bytearray()
        while len(data) < size:
            chunk = self.sock.recv(size - len(data))
            if not chunk:
                raise EOFError()
            data.extend(chunk)
        return bytes(data)

    def send(self, value, opcode=1):
        data = json.dumps(value).encode() if isinstance(value, dict) else value
        size = len(data)
        length = bytes([size | 128]) if size < 126 else (
            b"\xfe" + struct.pack("!H", size) if size < 65536 else b"\xff" + struct.pack("!Q", size))
        mask = os.urandom(4)
        self.sock.sendall(bytes([128 | opcode]) + length + mask + bytes(v ^ mask[i % 4] for i, v in enumerate(data)))

    def receive(self, timeout=30):
        deadline = time.monotonic() + timeout
        chunks = bytearray()
        kind = None
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("frame_deadline")
            self.sock.settimeout(remaining)
            first, second = self.read(2)
            size = second & 127
            if size == 126:
                size = struct.unpack("!H", self.read(2))[0]
            elif size == 127:
                size = struct.unpack("!Q", self.read(8))[0]
            if size > 8_000_000 or second & 128:
                raise ValueError("invalid_frame")
            payload = self.read(size)
            opcode = first & 15
            if opcode == 8:
                raise EOFError()
            if opcode == 9:
                self.send(payload, 10)
                continue
            if opcode == 10:
                continue
            if opcode in (1, 2):
                kind = opcode
            elif opcode != 0:
                raise ValueError("invalid_opcode")
            chunks.extend(payload)
            if len(chunks) > 8_000_000:
                raise ValueError("oversize_message")
            if first & 128:
                return bytes(chunks) if kind == 2 else json.loads(chunks)

    def close(self):
        self.sock.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin", default="http://127.0.0.1:9119")
    parser.add_argument("--profile", required=True)
    parser.add_argument("--samples", type=int, default=2, choices=range(1, 6))
    parser.add_argument("--reasoning", choices=("high", "low"), help="Override ONLY the new synthetic session")
    parser.add_argument("--chat-only", action="store_true", help="Skip speech-provider benchmarks")
    args = parser.parse_args()
    address = urlsplit(args.origin)
    if address.scheme != "http" or address.hostname not in ("127.0.0.1", "localhost", "::1"):
        parser.error("Run on the host against its loopback dashboard")
    report = {"profile": args.profile, "clock": "monotonic", "microphone_used": False, "http": [], "speech": [], "chat": []}
    token = re.search(r'__HERMES_SESSION_TOKEN__\s*=\s*"([^"]+)"', urllib.request.urlopen(args.origin, timeout=10).read().decode()).group(1)

    def elapsed(start):
        return round((time.monotonic() - start) * 1000, 1)

    def http(path, data=None, method=None):
        req = urllib.request.Request(args.origin + path, data=json.dumps(data).encode() if data is not None else None,
            method=method, headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
        return json.load(urllib.request.urlopen(req, timeout=65))

    def path(route):
        return route + "?profile=" + quote(args.profile) + "&token=" + quote(token)

    report["live_status"] = {k: v for k, v in http(path("/api/audio/voice-live/status")).items() if k in ("mode", "available", "reason", "model")}
    for _ in range(5):
        start = time.monotonic()
        http("/api/system/stats")
        report["http"].append(elapsed(start))
    phrase = "Synthetic voice latency test. One two three."
    for sample in range(0 if args.chat_only else args.samples):
        item = {"sample": sample + 1}
        try:
            start = time.monotonic()
            result = http(path("/api/audio/speak"), {"text": phrase})
            item["whole_file_tts_ms"] = elapsed(start)
            item["tts_provider"] = result.get("provider")
            item["audio_bytes"] = len(base64.b64decode(result["data_url"].split(",", 1)[1]))
            start = time.monotonic()
            transcript = http(path("/api/audio/transcribe"), {"data_url": result["data_url"], "mime_type": result.get("mime_type", "audio/mpeg")})
            item["synthetic_file_stt_ms"] = elapsed(start)
            item["transcript_nonempty"] = bool(transcript.get("transcript") or transcript.get("text"))
            item["stt_provider"] = transcript.get("provider")
        except Exception as error:
            item["whole_file_error"] = type(error).__name__
        ws = None
        try:
            start = time.monotonic()
            ws = WebSocket(args.origin, path("/api/audio/speak-stream"), token)
            item["speech_upgrade_ms"] = elapsed(start)
            ws.send({"text": phrase, "done": True})
            size = 0
            while True:
                event = ws.receive()
                if isinstance(event, bytes):
                    if event and not size:
                        item["stream_first_pcm_ms"] = elapsed(start)
                    size += len(event)
                elif event.get("type") in ("end", "fallback", "error"):
                    item["stream_terminal"] = event["type"]
                    item["stream_complete_ms"] = elapsed(start)
                    item["pcm_bytes"] = size
                    break
        except Exception as error:
            item["stream_error"] = type(error).__name__
        finally:
            if ws:
                ws.close()
        report["speech"].append(item)
        print(json.dumps({"speech_sample": item}), flush=True)

    ws = None
    stored = None
    live = None
    sequence = 0

    def rpc(method, params):
        nonlocal sequence
        sequence += 1
        request_id = str(sequence)
        ws.send({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            data = ws.receive(max(0.001, deadline - time.monotonic()))
            if str(data.get("id")) == request_id:
                if "error" in data:
                    raise ValueError("rpc_" + str(data["error"].get("code")))
                return data.get("result", {})
        raise TimeoutError("rpc_deadline")

    try:
        start = time.monotonic()
        ws = WebSocket(args.origin, path("/api/ws"), token)
        report["rpc_upgrade_ms"] = elapsed(start)
        print(json.dumps({"stage": "rpc_connected", "ms": report["rpc_upgrade_ms"]}), flush=True)
        created = rpc("session.create", {"profile": args.profile, "title": "Synthetic voice latency benchmark"})
        report["session_create_ms"] = elapsed(start)
        live = created.get("session_id") or created.get("id")
        stored = created.get("db_session_id") or created.get("stored_session_id") or live
        report["session_model"] = created.get("model") or created.get("info", {}).get("model")
        print(json.dumps({"stage": "session_created", "ms": report["session_create_ms"], "fields": sorted(created)}), flush=True)
        if args.reasoning:
            result = rpc("config.set", {"session_id": live, "profile": args.profile,
                "key": "reasoning", "value": args.reasoning, "scope": "session"})
            report["reasoning_override"] = {k: result.get(k) for k in ("key", "value", "scope")}
        for sample in range(args.samples):
            sequence += 1
            request_id = str(sequence)
            item = {"sample": sample + 1, "tool_events": 0}
            start = time.monotonic()
            ws.send({"jsonrpc": "2.0", "id": request_id, "method": "prompt.submit", "params": {
                "session_id": live, "profile": args.profile,
                "text": "Synthetic latency benchmark. Do not use tools or access anything. Reply with exactly two short sentences: Hello there. Ready for the next question."}})
            text = ""
            while time.monotonic() - start < 60:
                event = ws.receive(max(0.001, 60 - (time.monotonic() - start)))
                if str(event.get("id")) == request_id:
                    if "error" in event:
                        raise ValueError("prompt_rejected")
                    item["submit_ack_ms"] = elapsed(start)
                    continue
                params = event.get("params") or event
                kind = params.get("type") or event.get("method") or ""
                payload = params.get("payload") or params
                sid = params.get("session_id") or payload.get("session_id")
                if sid not in (live, stored):
                    continue
                if kind.startswith("tool.") or "request" in kind:
                    item["tool_events"] += 1
                    rpc("session.interrupt", {"session_id": live})
                    item["aborted_for_tool_request"] = True
                    break
                if kind in ("message.delta", "assistant.delta", "token"):
                    delta = payload.get("text") or payload.get("delta") or payload.get("content") or ""
                    if delta and not text:
                        item["first_text_ms"] = elapsed(start)
                    text += delta
                    if "first_system_clause_ms" not in item and re.search(r"[?!\n:]|\.\s", text):
                        item["first_system_clause_ms"] = elapsed(start)
                if kind in ("message.complete", "message.final", "turn.complete", "turn.completed", "turn.end", "session.idle"):
                    item["terminal_ms"] = elapsed(start)
                    item["text_chars"] = len(text)
                    break
            report["chat"].append(item)
            print(json.dumps({"chat_sample": item}), flush=True)
            if item.get("aborted_for_tool_request"):
                break
    except Exception as error:
        report["chat_error"] = type(error).__name__
    finally:
        if stored:
            try:
                http("/api/sessions/" + quote(str(stored), safe="") + "?profile=" + quote(args.profile), method="DELETE")
                report["synthetic_session_deleted"] = True
            except Exception as error:
                report["synthetic_session_deleted"] = False
                report["cleanup_error"] = type(error).__name__
                report["cleanup_session_id"] = stored
        if ws:
            ws.close()
    print(json.dumps({"report": report}), flush=True)


if __name__ == "__main__":
    main()
