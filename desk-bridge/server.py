"""Private Desk Grok transport. Uses host OAuth; never returns provider credentials.

Runs separately from the Companion plugin. Incoming credentials are validated against
the existing loopback dashboard on EVERY request. Only model discovery and stateless
Responses inference are exposed; no Hermes agent loop, filesystem or device tools.
"""
from __future__ import annotations

import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpx

MAX_BODY = 1_500_000
UPSTREAM = "https://api.x.ai/v1"


def validate_response_request(body: dict) -> None:
    allowed = {"model", "input", "instructions", "store", "stream"}
    if set(body) - allowed or body.get("store") is not False or body.get("stream") is not True:
        raise ValueError("unsupported_request")
    if not isinstance(body.get("model"), str) or not body["model"].startswith("grok"):
        raise ValueError("invalid_model")
    if not isinstance(body.get("instructions", ""), str):
        raise ValueError("invalid_instructions")
    items = body.get("input")
    if not isinstance(items, list) or not 1 <= len(items) <= 1000:
        raise ValueError("invalid_input")
    for item in items:
        if not isinstance(item, dict) or set(item) != {"role", "content"} or item["role"] not in {"user", "assistant"}:
            raise ValueError("invalid_message")
        content = item["content"]
        if isinstance(content, str):
            continue
        if not isinstance(content, list) or len(content) > 2:
            raise ValueError("invalid_content")
        for part in content:
            if not isinstance(part, dict):
                raise ValueError("invalid_content")
            kind = part.get("type")
            if kind == "input_text" and set(part) == {"type", "text"} and isinstance(part["text"], str):
                continue
            if (kind == "input_image" and set(part) == {"type", "image_url"} and
                    isinstance(part["image_url"], str) and part["image_url"].startswith("data:image/jpeg;base64,")):
                continue
            raise ValueError("invalid_content")


class DeskHandler(BaseHTTPRequestHandler):
    server_version = "DeskBridge/0.3"
    def log_message(self, format, *args):
        # Do not log authorization, prompts, images, response bodies or URL queries.
        pass

    def error(self, code: int, name: str):
        body = json.dumps({"error": {"code": name}}).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def authorized(self) -> bool:
        header = self.headers.get("Authorization", "")
        if not header.startswith("Bearer ") or not 16 <= len(header) <= 4096:
            return False
        try:
            response = httpx.get("http://127.0.0.1:9119/api/profiles", headers={
                "Authorization": header, "X-Hermes-Session-Token": header[7:]}, timeout=5, follow_redirects=False, trust_env=False)
            return response.status_code == 200
        except httpx.HTTPError:
            return False

    def do_GET(self):
        self.dispatch()

    def do_POST(self):
        self.dispatch()

    def dispatch(self):
        if self.path not in {"/desk-grok/v1/models", "/desk-grok/v1/responses"}:
            return self.error(404, "unknown_route")
        if not self.authorized():
            return self.error(401, "dashboard_session_required")
        expected = "GET" if self.path.endswith("/models") else "POST"
        if self.command != expected:
            return self.error(405, "method_not_allowed")
        body = None
        if self.command == "POST":
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 1 <= length <= MAX_BODY or self.headers.get("Transfer-Encoding"):
                    return self.error(413, "request_size")
                body = json.loads(self.rfile.read(length))
                validate_response_request(body)
            except (ValueError, TypeError, AttributeError):
                return self.error(400, "invalid_request")
        # Only this host owns and renews the provider token. Incoming caller tokens
        # are never sent to xAI. Use the installed official Hermes auth implementation.
        try:
            from hermes_cli.proxy.adapters.xai import XAIGrokAdapter
            credential = XAIGrokAdapter().get_credential()
            token = credential.bearer
            if not token:
                return self.error(503, "grok_account_not_connected")
        except Exception:
            return self.error(503, "grok_account_not_connected")
        started = False
        try:
            with httpx.Client(timeout=httpx.Timeout(180, connect=15), follow_redirects=False) as client:
                with client.stream(self.command, UPSTREAM + ("/models" if self.command == "GET" else "/responses"),
                                   headers={"Authorization": "Bearer " + token, "Accept": "text/event-stream" if body else "application/json"}, json=body) as response:
                    if response.status_code != 200:
                        return self.error(response.status_code if response.status_code in {400, 401, 403, 429} else 502, "grok_upstream_request_failed")
                    self.send_response(200)
                    self.send_header("Content-Type", "text/event-stream" if body else "application/json")
                    self.send_header("Cache-Control", "no-store")
                    self.send_header("Connection", "close")
                    self.end_headers()
                    started = True
                    count = 0
                    for chunk in response.iter_bytes():
                        count += len(chunk)
                        if count > 2_000_000:
                            break
                        self.wfile.write(chunk)
                        self.wfile.flush()
        except (httpx.HTTPError, OSError):
            if not started:
                self.error(502, "grok_connection_failed")
        finally:
            self.close_connection = True


if __name__ == "__main__":
    # Explicit private bind; no public-interface default.
    bind = os.environ.get("DESK_BRIDGE_BIND", "127.0.0.1:9121")
    host, port = bind.rsplit(":", 1)
    server = ThreadingHTTPServer((host, int(port)), DeskHandler)
    server.daemon_threads = True
    print("Desk Grok bridge ready", flush=True)
    server.serve_forever()
