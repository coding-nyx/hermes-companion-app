#!/usr/bin/env python3
"""Opt-in device acceptance through the real Hands MCP stdio executable.

Root/operator prepares the phone, fixture APK, local consent, private token file
and ADB forwarding. This driver never runs ADB, grants permission, reads the
primary display or sends a global stop. Nothing contacts a device until main().
"""

from __future__ import annotations

import argparse
import base64
import binascii
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import struct
import subprocess
import sys
import threading
import time
from typing import Any, Callable


FIXTURE_PACKAGE = "app.hermes.hands.test"
FIXTURE_HEADER = "HANDS ACCEPTANCE FIXTURE"
INPUT_LABEL = "Agent test input"
ASCII_VALUE = "HANDS_ASCII_123"
MAX_STDIO_BYTES = 16 * 1024 * 1024
MAX_PNG_BYTES = 8 * 1024 * 1024
SAFE_TOOL_ERRORS = frozenset({
    "disarmed", "lease_mismatch", "lease_expired", "invalid_lease", "authentication_failed",
    "local_consent_required", "need_local_consent", "controller_busy", "shizuku_unavailable",
    "target_mismatch", "agent_screen_unavailable", "screen_locked", "screen_off",
    "observation_unavailable", "screen_unavailable", "screen_unstable", "stale_ref", "privacy_active",
    "invalid_response", "invalid_screenshot", "screenshot_failed", "screenshot_secure_window",
    "screenshot_timeout", "unsupported_action", "bad_args", "invalid_arguments", "connection_failed",
    "http_error", "action_failed", "permission_denied", "service_unavailable", "invalid_display",
    "text_unsupported", "unsupported_text", "capability_denied", "controller_unavailable",
})


class SmokeFailure(Exception):
    """A fixed diagnostic code, never an arbitrary response or credential."""
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


@dataclass
class ToolReply:
    data: dict[str, Any]
    content: list[dict[str, Any]]


class StdioMcp:
    def __init__(self, endpoint: str, token_file: Path, timeout: float, server_fingerprint: str | None = None):
        bridge = Path(__file__).resolve().parents[1] / "hands_mcp.py"
        env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
        command = [sys.executable, str(bridge), "--endpoint", endpoint, "--token-file", str(token_file),
            "--timeout", str(min(30.0, timeout - 2))]
        if server_fingerprint is not None:
            command.extend(["--server-fingerprint", server_fingerprint])
        self.process = subprocess.Popen(
            command,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env,
        )
        self.timeout = timeout
        self.next_id = 1
        self.messages: queue.Queue[tuple[str, Any]] = queue.Queue(maxsize=4)
        self.stderr_bytes = 0
        self._closed = False
        self._reader = threading.Thread(target=self._read_stdout, name="smoke-mcp-output", daemon=True)
        self._errors = threading.Thread(target=self._drain_stderr, name="smoke-mcp-errors", daemon=True)
        self._reader.start()
        self._errors.start()

    def _read_stdout(self):
        try:
            while True:
                raw = self.process.stdout.readline(MAX_STDIO_BYTES + 1)
                if not raw:
                    self.messages.put(("error", "bridge_stdout_closed"))
                    return
                if len(raw) > MAX_STDIO_BYTES:
                    self.messages.put(("error", "bridge_response_too_large"))
                    return
                try:
                    value = json.loads(raw)
                except (ValueError, UnicodeDecodeError, RecursionError):
                    self.messages.put(("error", "bridge_invalid_json"))
                    return
                self.messages.put(("message", value))
        except (OSError, ValueError):
            self.messages.put(("error", "bridge_stdout_failed"))

    def _drain_stderr(self):
        # Never retain or print arbitrary child diagnostics: they may contain request data.
        try:
            while True:
                raw = self.process.stderr.read(4096)
                if not raw:
                    return
                self.stderr_bytes += len(raw)
        except (OSError, ValueError):
            return

    def _send(self, message: dict[str, Any]):
        try:
            self.process.stdin.write(json.dumps(message, separators=(",", ":"), allow_nan=False).encode() + b"\n")
            self.process.stdin.flush()
        except (BrokenPipeError, OSError, ValueError):
            raise SmokeFailure("bridge_stdin_closed") from None

    def notify(self, method: str):
        self._send({"jsonrpc": "2.0", "method": method})

    def request(self, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        request_id = self.next_id
        self.next_id += 1
        self._send({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params or {}})
        deadline = time.monotonic() + self.timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise SmokeFailure("bridge_response_timeout")
            try:
                kind, message = self.messages.get(timeout=remaining)
            except queue.Empty:
                raise SmokeFailure("bridge_response_timeout") from None
            if kind == "error":
                raise SmokeFailure(message)
            if not isinstance(message, dict) or message.get("jsonrpc") != "2.0":
                raise SmokeFailure("bridge_invalid_rpc_response")
            if "id" not in message and isinstance(message.get("method"), str):
                continue
            if type(message.get("id")) is not int or message["id"] != request_id:
                raise SmokeFailure("bridge_response_id_mismatch")
            if "error" in message:
                raise SmokeFailure("mcp_protocol_error")
            if not isinstance(message.get("result"), dict):
                raise SmokeFailure("bridge_result_missing")
            return message["result"]

    def tool(self, name: str, arguments: dict[str, Any] | None = None) -> ToolReply:
        result = self.request("tools/call", {"name": name, "arguments": arguments or {}})
        payload = result.get("structuredContent")
        content = result.get("content", [])
        if not isinstance(content, list):
            raise SmokeFailure("tool_content_invalid")
        if not isinstance(payload, dict):
            for item in content:
                if isinstance(item, dict) and item.get("type") == "text" and isinstance(item.get("text"), str):
                    try:
                        payload = json.loads(item["text"])
                    except (ValueError, RecursionError):
                        continue
                    if isinstance(payload, dict):
                        break
        if not isinstance(payload, dict):
            raise SmokeFailure("tool_envelope_missing")
        if result.get("isError") is True or payload.get("ok") is not True:
            code = payload.get("error", {}).get("code") if isinstance(payload.get("error"), dict) else None
            raise SmokeFailure(code if code in SAFE_TOOL_ERRORS else "tool_refused")
        if not isinstance(payload.get("result"), dict):
            raise SmokeFailure("tool_result_missing")
        return ToolReply(payload["result"], content)

    def close(self) -> int:
        if self._closed:
            return self.process.returncode if self.process.returncode is not None else -1
        self._closed = True
        try:
            self.process.stdin.close()  # Real bridge EOF cleanup releases its own issued holders.
        except (OSError, ValueError):
            pass
        try:
            self.process.wait(timeout=12)
        except subprocess.TimeoutExpired:
            self.process.terminate()
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=3)
        self._reader.join(timeout=1)
        self._errors.join(timeout=1)
        self.process.stdout.close()
        self.process.stderr.close()
        return self.process.returncode


class Acceptance:
    def __init__(self, bridge: StdioMcp, report: dict[str, Any], artifacts: Path):
        self.bridge, self.report, self.artifacts = bridge, report, artifacts
        self.stage = "initialize"
        self.display_id: int | None = None
        self.owns_holder = False

    def step(self, name: str, operation: Callable[[], Any]):
        self.stage = name
        started = time.monotonic()
        result = operation()
        self.report["checks"].append({"name": name, "ok": True,
            "duration_ms": round((time.monotonic() - started) * 1000)})
        return result

    def guard(self, *, require_armed=False) -> dict[str, Any]:
        status = self.bridge.tool("mobile_status").data
        if status.get("target") != "agent":
            raise SmokeFailure("agent_target_required")
        if status.get("privacy_active") is True:
            raise SmokeFailure("privacy_active")
        if require_armed and status.get("armed") is not True:
            raise SmokeFailure("disarmed")
        if self.display_id is not None:
            screen = status.get("agent_screen")
            if (not isinstance(screen, dict) or screen.get("active") is not True
                    or type(screen.get("display_id")) is not int or screen["display_id"] != self.display_id):
                raise SmokeFailure("agent_display_identity_changed")
        return status

    @staticmethod
    def texts(tree: dict[str, Any]) -> list[str]:
        # Called only after snapshot identity and exact synthetic package are checked.
        return [node["text"] for node in tree["nodes"]
                if isinstance(node, dict) and isinstance(node.get("text"), str)]

    @staticmethod
    def node(tree: dict[str, Any], label: str, *, editable=False) -> dict[str, Any]:
        matches = [node for node in tree["nodes"] if isinstance(node, dict)
            and (node.get("text") == label or node.get("description") == label)
            and node.get("editable" if editable else "clickable") is True]
        if len(matches) != 1 or not isinstance(matches[0].get("ref"), str):
            raise SmokeFailure("fixture_node_not_unique")
        return matches[0]

    def snapshot(self, *, allow_startup=False) -> dict[str, Any] | None:
        self.guard(require_armed=True)
        tree = self.bridge.tool("mobile_snapshot").data
        # Do not inspect any node content until the non-primary display and package match.
        if type(tree.get("display_id")) is not int or tree["display_id"] != self.display_id or self.display_id <= 0:
            raise SmokeFailure("snapshot_wrong_display")
        if tree.get("app") != FIXTURE_PACKAGE:
            if allow_startup:
                return None
            raise SmokeFailure("snapshot_wrong_fixture_package")
        if (not isinstance(tree.get("snapshot_id"), str) or not tree["snapshot_id"]
                or not isinstance(tree.get("nodes"), list) or not tree["nodes"]):
            raise SmokeFailure("fixture_snapshot_invalid")
        return tree

    def until(self, predicate: Callable[[dict[str, Any]], bool], *, allow_startup=False, timeout=15.0):
        deadline = time.monotonic() + timeout
        last_code = "fixture_verification_timeout"
        while time.monotonic() < deadline:
            try:
                tree = self.snapshot(allow_startup=allow_startup)
                if tree is not None and predicate(tree):
                    return tree
            except SmokeFailure as exc:
                if exc.code not in ("observation_unavailable", "screen_unavailable", "screen_unstable"):
                    raise
                last_code = exc.code
            # Only repeat read-only observations; never repeat a gesture on timeout.
            time.sleep(0.35)
        raise SmokeFailure(last_code)

    def click(self, tree: dict[str, Any], label: str, *, editable=False):
        current = tree
        for attempt in range(3):
            node = self.node(current, label, editable=editable)
            self.guard(require_armed=True)
            try:
                result = self.bridge.tool("mobile_click", {
                    "ref": node["ref"], "snapshot_id": current["snapshot_id"]}).data
            except SmokeFailure as exc:
                # stale_ref is an explicit pre-input rejection. A timeout, unknown
                # delivery state or submitted input must never replay a gesture.
                if exc.code != "stale_ref" or attempt == 2:
                    raise
                self.report["stale_ref_reselections"] = self.report.get("stale_ref_reselections", 0) + 1
                time.sleep(0.2)
                current = self.until(lambda _: True)
                continue
            if type(result.get("display_id")) is not int or result["display_id"] != self.display_id:
                raise SmokeFailure("action_wrong_display")
            return

    def execute(self, hold_seconds: int, verify_scroll: bool):
        def initialize():
            result = self.bridge.request("initialize", {"protocolVersion": "2025-11-25", "capabilities": {},
                "clientInfo": {"name": "hands-device-smoke", "version": "1"}})
            if result.get("protocolVersion") != "2025-11-25":
                raise SmokeFailure("protocol_negotiation_failed")
            self.bridge.notify("notifications/initialized")
            tools = self.bridge.request("tools/list").get("tools")
            if not isinstance(tools, list):
                raise SmokeFailure("tool_inventory_missing")
            names = {tool.get("name") for tool in tools if isinstance(tool, dict)}
            required = {"mobile_status", "mobile_arm", "mobile_disarm", "mobile_agent_screen_start",
                "mobile_open_app", "mobile_snapshot", "mobile_click", "mobile_type", "mobile_screenshot"}
            if not required.issubset(names) or verify_scroll and "mobile_scroll" not in names:
                raise SmokeFailure("required_tools_missing")
            self.report["tool_count"] = len(names)
        self.step("mcp_initialize_and_list", initialize)

        def preflight():
            status = self.guard()
            if status.get("consent_approved") is not True:
                raise SmokeFailure("local_consent_required")
            engine = status.get("shizuku")
            if not isinstance(engine, dict) or engine.get("bound") is not True:
                raise SmokeFailure("shizuku_unavailable")
        self.step("agent_target_and_local_consent", preflight)

        def arm():
            result = self.bridge.tool("mobile_arm", {"duration_seconds": 30, "target": "agent"}).data
            self.owns_holder = True
            if result.get("target") != "agent" or result.get("armed") is not True:
                raise SmokeFailure("arm_target_not_confirmed")
            if result.get("working_lease_managed") is not True:
                raise SmokeFailure("background_renewal_unavailable")
            self.report["idle_lease_seconds"] = 30
        self.step("acquire_own_30_second_holder", arm)

        def start_screen():
            self.guard(require_armed=True)
            result = self.bridge.tool("mobile_agent_screen_start", {"width": 720, "height": 1280, "density": 240}).data
            display_id = result.get("display_id")
            if type(display_id) is not int or display_id <= 0 or result.get("target") != "agent" or result.get("active") is not True:
                raise SmokeFailure("non_primary_display_not_confirmed")
            self.display_id = display_id
            self.report["display_id"] = display_id
        self.step("create_separate_display", start_screen)

        def open_fixture():
            self.guard(require_armed=True)
            result = self.bridge.tool("mobile_open_app", {"package": FIXTURE_PACKAGE}).data
            if result.get("package") != FIXTURE_PACKAGE or result.get("display_id") != self.display_id:
                raise SmokeFailure("fixture_launch_wrong_display")
            time.sleep(1.0)  # Let the synthetic Activity finish its initial layout/animation.
            return self.until(lambda tree: FIXTURE_HEADER in self.texts(tree), allow_startup=True)
        tree = self.step("launch_and_read_synthetic_fixture", open_fixture)

        def verify_tap():
            counts = [int(match[1]) for text in self.texts(tree)
                if (match := re.fullmatch(r"Isolated taps: (\d+)", text))]
            if len(counts) != 1:
                raise SmokeFailure("fixture_tap_counter_missing")
            self.click(tree, "Verify isolated tap")
            expected = "Isolated taps: " + str(counts[0] + 1)
            verified = self.until(lambda current: expected in self.texts(current))
            self.report["isolated_tap_verified"] = True
            return verified
        tree = self.step("ref_click_and_verify_delivery", verify_tap)

        def verify_typing():
            input_node = self.node(tree, INPUT_LABEL, editable=True)
            # Android may expose an empty EditText's hint as its accessible text.
            if input_node.get("text") not in ("", None, INPUT_LABEL):
                raise SmokeFailure("fixture_input_must_start_empty")
            self.click(tree, INPUT_LABEL, editable=True)
            focused = self.until(lambda current: self.node(current, INPUT_LABEL, editable=True).get("focused") is True)
            self.guard(require_armed=True)
            typed = self.bridge.tool("mobile_type", {"text": ASCII_VALUE}).data
            if typed.get("display_id") != self.display_id:
                raise SmokeFailure("typing_wrong_display")
            verified = self.until(lambda current: self.node(current, INPUT_LABEL, editable=True).get("text") == ASCII_VALUE)
            if verified["snapshot_id"] == focused["snapshot_id"]:
                raise SmokeFailure("typing_snapshot_not_fresh")
            self.click(verified, "Verify typed value")
            checked = self.until(lambda current: "Typed value verified" in self.texts(current))
            self.report["ascii_typing_verified"] = True
            return checked
        tree = self.step("ascii_input_and_fresh_snapshot", verify_typing)

        if verify_scroll:
            def scroll_fixture():
                current = tree
                for _ in range(8):
                    if "Verify isolated scroll" in self.texts(current):
                        self.click(current, "Verify isolated scroll")
                        self.until(lambda after: "Scroll verified" in self.texts(after))
                        self.report["isolated_scroll_verified"] = True
                        return
                    self.guard(require_armed=True)
                    result = self.bridge.tool("mobile_scroll", {"direction": "down"}).data
                    if result.get("display_id") != self.display_id:
                        raise SmokeFailure("scroll_wrong_display")
                    current = self.until(lambda _: True)
                raise SmokeFailure("fixture_scroll_footer_not_reached")
            self.step("scroll_and_verify_footer", scroll_fixture)

        def screenshot():
            self.until(lambda _: True)  # Confirm exact fixture package immediately before capture.
            self.guard(require_armed=True)
            reply = self.bridge.tool("mobile_screenshot")
            metadata = reply.data
            if metadata.get("display_id") != self.display_id:
                raise SmokeFailure("screenshot_wrong_display")
            images = [item for item in reply.content if isinstance(item, dict) and item.get("type") == "image"]
            if len(images) != 1 or images[0].get("mimeType") != "image/png" or not isinstance(images[0].get("data"), str):
                raise SmokeFailure("native_mcp_image_missing")
            try:
                png = base64.b64decode(images[0]["data"], validate=True)
            except (ValueError, binascii.Error):
                raise SmokeFailure("screenshot_base64_invalid") from None
            width, height = png_dimensions(png)
            if metadata.get("w") != width or metadata.get("h") != height:
                raise SmokeFailure("screenshot_dimensions_mismatch")
            path = self.artifacts / "agent-fixture.png"
            write_private(path, png)
            self.report["screenshot"] = {"file": path.name, "mime": "image/png", "width": width,
                "height": height, "bytes": len(png), "sha256": hashlib.sha256(png).hexdigest(),
                "display_id": self.display_id}
        self.step("native_mcp_screenshot", screenshot)

        if hold_seconds:
            def idle():
                self.guard(require_armed=True)
                print(json.dumps({"phase": "holding", "seconds": hold_seconds, "display_id": self.display_id}), file=sys.stderr, flush=True)
                deadline = time.monotonic() + hold_seconds
                # Deliberately issue no MCP tools here: only the real bridge's background
                # renewal runs while the operator exercises the phone's primary UI.
                while time.monotonic() < deadline:
                    time.sleep(min(0.5, max(0, deadline - time.monotonic())))
                status = self.guard(require_armed=True)
                if status.get("working_lease_managed") is not True:
                    raise SmokeFailure("idle_renewal_not_active")
                self.until(lambda _: True)
                self.report["idle_hold_seconds"] = hold_seconds
                self.report["still_armed_after_idle"] = True
            self.step("idle_process_heartbeat", idle)


def png_dimensions(png: bytes) -> tuple[int, int]:
    if not 57 <= len(png) <= MAX_PNG_BYTES or png[:8] != b"\x89PNG\r\n\x1a\n":
        raise SmokeFailure("screenshot_png_invalid")
    offset, width, height = 8, 0, 0
    saw_data = False
    while offset + 12 <= len(png):
        length = struct.unpack(">I", png[offset:offset + 4])[0]
        kind = png[offset + 4:offset + 8]
        end = offset + length + 12
        if end > len(png):
            raise SmokeFailure("screenshot_png_truncated")
        data = png[offset + 8:offset + 8 + length]
        if binascii.crc32(kind + data) & 0xFFFFFFFF != struct.unpack(">I", png[end - 4:end])[0]:
            raise SmokeFailure("screenshot_png_corrupt")
        if offset == 8:
            if kind != b"IHDR" or length != 13:
                raise SmokeFailure("screenshot_png_header_invalid")
            width, height = struct.unpack(">II", data[:8])
            if not 0 < width <= 16384 or not 0 < height <= 16384 or width * height > 16_777_216:
                raise SmokeFailure("screenshot_png_dimensions_invalid")
        if kind == b"IDAT":
            saw_data = True
        if kind == b"IEND":
            if length != 0 or end != len(png) or not saw_data:
                raise SmokeFailure("screenshot_png_ending_invalid")
            return width, height
        offset = end
    raise SmokeFailure("screenshot_png_ending_missing")


def write_private(path: Path, data: bytes):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as output:
        output.write(data)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--endpoint", default="http://127.0.0.1:18765")
    parser.add_argument("--server-fingerprint", help="Optional locally verified SHA256 pin passed to the real MCP CLI for HTTPS")
    parser.add_argument("--token-file", type=Path, required=True, help="Prepared private token file; contents are never read or printed by this driver")
    parser.add_argument("--artifacts-dir", type=Path, required=True, help="Fresh directory for report.json and the synthetic fixture PNG")
    parser.add_argument("--hold-seconds", type=int, default=0, help="Optional tool-idle heartbeat window, 0..120 (65 tests beyond a 30-second lease)")
    parser.add_argument("--verify-scroll", action="store_true")
    parser.add_argument("--request-timeout", type=float, default=45.0, help="Bounded stdio response timeout, 5..60 seconds")
    args = parser.parse_args(argv)
    if not 0 <= args.hold_seconds <= 120 or not 5 <= args.request_timeout <= 60:
        parser.error("hold-seconds must be 0..120 and request-timeout must be 5..60")
    started = time.monotonic()
    report: dict[str, Any] = {"ok": False, "fixture_package": FIXTURE_PACKAGE,
        "started_at": datetime.now(timezone.utc).isoformat(), "checks": [],
        "primary_display_contents_observed": False, "global_stop_sent": False}
    bridge = None
    acceptance = None
    try:
        artifacts = args.artifacts_dir.expanduser().resolve()
        artifacts.mkdir(parents=True, exist_ok=True, mode=0o700)
        if (artifacts / "report.json").exists() or (artifacts / "agent-fixture.png").exists():
            raise SmokeFailure("artifacts_directory_must_be_fresh")
        if not args.token_file.expanduser().is_file():
            raise SmokeFailure("prepared_token_file_missing")
        bridge = StdioMcp(args.endpoint, args.token_file.expanduser().resolve(), args.request_timeout,
            server_fingerprint=args.server_fingerprint)
        acceptance = Acceptance(bridge, report, artifacts)
        acceptance.execute(args.hold_seconds, args.verify_scroll)
        report["ok"] = True
    except SmokeFailure as exc:
        report["error"] = {"stage": acceptance.stage if acceptance else "setup", "code": exc.code}
    except KeyboardInterrupt:
        report["error"] = {"stage": acceptance.stage if acceptance else "setup", "code": "interrupted"}
    except Exception:
        # Never include arbitrary response text, command argv, exception strings or tokens.
        report["error"] = {"stage": acceptance.stage if acceptance else "setup", "code": "unexpected_driver_failure"}
    finally:
        cleanup: dict[str, Any] = {"global_stop_sent": False}
        if bridge:
            if acceptance and acceptance.owns_holder:
                try:
                    bridge.tool("mobile_disarm")
                    cleanup["owned_holder_release_acknowledged"] = True
                except SmokeFailure as exc:
                    cleanup["owned_holder_release_acknowledged"] = False
                    cleanup["release_error"] = exc.code
                    report["ok"] = False
            else:
                cleanup["owned_holder_release_requested"] = False
            try:
                cleanup["bridge_exit_code"] = bridge.close()
                cleanup["bridge_stderr_bytes"] = bridge.stderr_bytes
                if cleanup["bridge_exit_code"] != 0:
                    report["ok"] = False
            except Exception:
                cleanup["bridge_close_failed"] = True
                report["ok"] = False
        report["cleanup"] = cleanup
        report["duration_seconds"] = round(time.monotonic() - started, 3)
        # Refuse to overwrite existing evidence, even if setup failed for that reason.
        try:
            if 'artifacts' in locals() and not (artifacts / "report.json").exists():
                write_private(artifacts / "report.json", (json.dumps(report, indent=2, sort_keys=True) + "\n").encode())
        except OSError:
            report["ok"] = False
            report["artifact_write_failed"] = True
        print(json.dumps(report, separators=(",", ":"), allow_nan=False), flush=True)
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
