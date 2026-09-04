"""Attach broker.device to the companion relay lane (in-process or HTTP)."""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from typing import Any

from broker import Broker, BrokerError, LiveDevice
from tickets import TicketError


def _raise_payload(payload: dict[str, Any]) -> dict[str, Any]:
    if payload.get("ok", True):
        result = payload.get("result")
        return result if isinstance(result, dict) else payload
    err = payload.get("error") or {}
    if isinstance(err, str):
        raise BrokerError(err, err)
    code = str(err.get("code") or payload.get("error") or "device_error")
    message = str(err.get("message") or code)
    raise BrokerError(code, message)


def attach_inprocess(broker: Broker, state) -> None:
    def send(action: str, arguments: dict[str, Any]) -> dict[str, Any]:
        with state.lock:
            ids = list(state.lanes)
        if not ids:
            raise BrokerError("no_device", "nothing paired")
        command_id = state.send_command(ids[0], action, arguments)
        try:
            return _raise_payload(state.wait_result(command_id))
        except TicketError as extra:
            raise BrokerError(extra.code, extra.code) from extra

    broker.device = LiveDevice(send=send)


def attach_http(broker: Broker, origin: str | None = None) -> None:
    base = (origin or os.environ.get("HERMES_COMPANION_RELAY_URL") or "http://127.0.0.1:9120").rstrip("/")

    def send(action: str, arguments: dict[str, Any]) -> dict[str, Any]:
        try:
            with urllib.request.urlopen(f"{base}/companion/device/lanes", timeout=5) as resp:
                lanes = json.loads(resp.read().decode())
        except urllib.error.URLError as exc:
            raise BrokerError("no_device", str(exc.reason) if hasattr(exc, "reason") else "relay down") from exc
        ids = lanes.get("devices") or []
        if not ids:
            raise BrokerError("no_device", "nothing paired")
        req = urllib.request.Request(
            f"{base}/companion/device/command",
            data=json.dumps({"device_id": ids[0], "action": action, "arguments": arguments}).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=25) as resp:
                return _raise_payload(json.loads(resp.read().decode()))
        except urllib.error.HTTPError as extra:
            raw = extra.read().decode()
            try:
                payload = json.loads(raw)
            except json.JSONDecodeError:
                raise BrokerError("device_error", raw or extra.reason) from extra
            code = str(payload.get("error") or "device_error")
            raise BrokerError(code, code) from extra

    broker.device = LiveDevice(send=send)
