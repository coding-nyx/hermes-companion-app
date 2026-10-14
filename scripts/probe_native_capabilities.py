#!/usr/bin/env python3
"""Read-only host probe. Prints response shapes/capabilities, never tokens or chat contents.

Run locally over an SSH/ADB test forward, or pipe this file to the host's python3.
"""
import json
import os
import re
import base64
import hashlib
import socket
import struct
from urllib.parse import urlsplit, quote
import urllib.error
import urllib.request

origin = os.environ.get("COMPANION_PROBE_ORIGIN", "http://127.0.0.1:9119")
profile = os.environ.get("COMPANION_PROBE_PROFILE", "knight")
report = {"profile": profile, "http": {}, "rpc": {}}

def rpc_socket():
    """Minimal stdlib WebSocket for read-only, loopback-host probes; no package installation."""
    address = urlsplit(origin)
    if address.scheme != "http" or address.hostname not in ("127.0.0.1", "localhost", "::1"):
        raise ValueError("RPC probe requires a loopback HTTP test connection")
    sock = socket.create_connection((address.hostname, address.port or 80), timeout=10)
    key = base64.b64encode(os.urandom(16)).decode()
    path = address.path.rstrip("/") + "/api/ws?profile=" + quote(profile) + "&token=" + quote(token)
    sock.sendall(("GET " + path + " HTTP/1.1\r\nHost: " + address.netloc +
        "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: " + key + "\r\n\r\n").encode())
    headers = bytearray()
    while not headers.endswith(b"\r\n\r\n"):
        chunk = sock.recv(1)
        if not chunk or len(headers) > 16_384: raise ValueError("invalid upgrade")
        headers.extend(chunk)
    expected = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest())
    if b" 101 " not in headers.split(b"\r\n")[0] or expected not in headers: raise ValueError("upgrade refused")
    return sock

def send(sock, payload, opcode=1):
    data = payload.encode() if isinstance(payload, str) else payload
    mask = os.urandom(4)
    size = len(data)
    length = bytes([size | 128]) if size < 126 else b"\xfe" + struct.pack("!H", size)
    sock.sendall(bytes([128 | opcode]) + length + mask + bytes(v ^ mask[i % 4] for i, v in enumerate(data)))

def receive(sock):
    def read(size):
        data = bytearray()
        while len(data) < size:
            chunk = sock.recv(size - len(data))
            if not chunk: raise EOFError()
            data.extend(chunk)
        return bytes(data)
    chunks = bytearray()
    while True:
        first, second = read(2)
        size = second & 127
        if size == 126: size = struct.unpack("!H", read(2))[0]
        if size == 127: size = struct.unpack("!Q", read(8))[0]
        if size > 2_000_000: raise ValueError("oversize frame")
        if second & 128: raise ValueError("masked server frame")
        payload = read(size)
        opcode = first & 15
        if opcode == 8: raise EOFError()
        if opcode == 9: send(sock, payload, 10); continue
        if opcode == 10: continue
        if opcode not in (0, 1): raise ValueError("unexpected frame")
        chunks.extend(payload)
        if len(chunks) > 2_000_000: raise ValueError("oversize message")
        if first & 128: return chunks.decode()
try:
    html = urllib.request.urlopen(origin + "/", timeout=5).read().decode()
    token = re.search(r'__HERMES_SESSION_TOKEN__\s*=\s*"([^"]+)"', html).group(1)
except Exception:
    print(json.dumps({"state": "unknown", "reason": "native dashboard or loopback token unavailable"}))
    raise SystemExit(1)

for path in ["/api/auth/me", "/api/auth/providers", "/api/profiles", "/api/system/stats", "/api/audio/voice-live/status?profile=" + quote(profile),
             "/api/git/status?profile=" + profile, "/openapi.json"]:
    try:
        req = urllib.request.Request(origin + path, headers={"Authorization": "Bearer " + token})
        with urllib.request.urlopen(req, timeout=10) as response:
            body = response.read()
            content = response.headers.get("Content-Type", "")
            if "json" not in content:
                report["http"][path] = {"state": "unsupported", "reason": "HTML fallback"}
                continue
            data = json.loads(body)
            item = {"state": "supported", "fields": sorted(data) if isinstance(data, dict) else ["array"]}
            if path == "/api/system/stats":
                item["hermes_version"] = data.get("hermes_version")
            if "/voice-live/status" in path:
                item.update({key: data.get(key) for key in ("mode", "available", "reason", "model", "voice")})
            if path == "/openapi.json":
                item["paths"] = sorted(data.get("paths", {}))
                def scrub(value):
                    if isinstance(value, dict): return {k: scrub(v) for k, v in value.items() if k not in ("default", "example", "examples")}
                    if isinstance(value, list): return [scrub(v) for v in value]
                    return value
                item["contracts"] = scrub({
                    "paths": {p: v for p, v in data.get("paths", {}).items() if p.startswith(("/api/audio/", "/api/files", "/api/git/", "/auth/native/", "/api/auth/")) and "voice-config" not in p},
                    "components": data.get("components", {})})
            report["http"][path] = item
    except urllib.error.HTTPError as error:
        report["http"][path] = {"state": "unknown", "http_status": error.code}
    except Exception:
        report["http"][path] = {"state": "unknown", "reason": "request failed"}

try:
    ws = rpc_socket()
    for method in ("groups.capabilities", "commands.catalog"):
        send(ws, json.dumps({"jsonrpc": "2.0", "id": method, "method": method, "params": {}}))
        for _ in range(30):
            data = json.loads(receive(ws))
            if data.get("id") != method:
                continue
            if "result" in data:
                result = data["result"]
                report["rpc"][method] = {"state": "supported", "fields": sorted(result) if isinstance(result, dict) else ["array"]}
                if isinstance(result, dict):
                    report["rpc"][method]["flags"] = {k: v for k, v in result.items() if isinstance(v, bool)}
            else:
                code = data.get("error", {}).get("code")
                report["rpc"][method] = {"state": "unsupported" if code == -32601 else "unknown", "rpc_code": code}
            break
    ws.close()
except Exception:
    report["rpc"]["probe"] = {"state": "unknown", "reason": "RPC probe unavailable"}

print(json.dumps(report, indent=2))
