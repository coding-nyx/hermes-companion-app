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


def resolve_device_id(state, hint: str | None = None) -> str:
    """Pick a live lane device_id. Fail closed on ambiguity (A6.7)."""
    with state.lock:
        ids = list(state.lanes)
    if not ids:
        raise BrokerError("no_device", "nothing paired")
    pairing = getattr(state, "pairing", None)
    raw = (hint or "").strip()
    if raw:
        if raw in ids:
            return raw
        if pairing is not None:
            device = pairing.resolve(raw)
            if device is not None and device.device_id in ids:
                return device.device_id
            if device is not None:
                raise BrokerError("lane_down", f"{pairing.display_name(device)} lane is down")
        raise BrokerError("unknown_device", f"no live device matching {raw!r}")
    if len(ids) == 1:
        return ids[0]
    default_id = getattr(pairing, "default_device_id", None) if pairing is not None else None
    if default_id and default_id in ids:
        return default_id
    labels = []
    for device_id in ids:
        if pairing is not None and device_id in pairing.devices:
            labels.append(pairing.display_name(pairing.devices[device_id]))
        else:
            labels.append(device_id)
    raise BrokerError(
        "ambiguous_device",
        "multiple devices connected: " + ", ".join(labels) + "; specify device or set default",
    )


def attach_inprocess(broker: Broker, state) -> None:
    def send(action: str, arguments: dict[str, Any]) -> dict[str, Any]:
        args = dict(arguments or {})
        hint = args.pop("_device", None)
        device_id = resolve_device_id(state, hint)
        command_id = state.send_command(device_id, action, args)
        try:
            return _raise_payload(state.wait_result(command_id))
        except TicketError as extra:
            raise BrokerError(extra.code, extra.code) from extra

    broker.device = LiveDevice(send=send)


def attach_http(broker: Broker, origin: str | None = None) -> None:
    base = (origin or os.environ.get("HERMES_COMPANION_RELAY_URL") or "http://127.0.0.1:9120").rstrip("/")

    def send(action: str, arguments: dict[str, Any]) -> dict[str, Any]:
        args = dict(arguments or {})
        hint = args.pop("_device", None)
        try:
            with urllib.request.urlopen(f"{base}/companion/device/lanes", timeout=5) as resp:
                lanes = json.loads(resp.read().decode())
        except urllib.error.URLError as exc:
            raise BrokerError("no_device", str(exc.reason) if hasattr(exc, "reason") else "relay down") from exc
        rows = lanes.get("devices") or []
        # Rich descriptors or legacy id list.
        live_ids = []
        for row in rows:
            if isinstance(row, str):
                live_ids.append(row)
            elif isinstance(row, dict) and row.get("lane") and row.get("device_id"):
                live_ids.append(str(row["device_id"]))
            elif isinstance(row, dict) and row.get("device_id") and "lane" not in row:
                # Older clients: only live ids were returned.
                live_ids.append(str(row["device_id"]))
        if not live_ids and rows and all(isinstance(r, str) for r in rows):
            live_ids = list(rows)
        # Filter to lane-open when rich.
        rich_live = [
            str(r["device_id"])
            for r in rows
            if isinstance(r, dict) and r.get("device_id") and r.get("lane")
        ]
        if rich_live:
            live_ids = rich_live
        if not live_ids:
            raise BrokerError("no_device", "nothing paired")

        device_id = None
        raw = (hint or "").strip()
        if raw:
            if raw in live_ids:
                device_id = raw
            else:
                for row in rows:
                    if not isinstance(row, dict):
                        continue
                    if str(row.get("device_id")) == raw or str(row.get("name") or "").lower() == raw.lower():
                        if row.get("lane") or str(row.get("device_id")) in live_ids:
                            device_id = str(row["device_id"])
                            break
                if device_id is None:
                    raise BrokerError("unknown_device", f"no live device matching {raw!r}")
                if device_id not in live_ids:
                    raise BrokerError("lane_down", f"{raw} lane is down")
        elif len(live_ids) == 1:
            device_id = live_ids[0]
        else:
            default_id = None
            for row in rows:
                if isinstance(row, dict) and row.get("is_default") and row.get("device_id"):
                    default_id = str(row["device_id"])
                    break
            if default_id and default_id in live_ids:
                device_id = default_id
            else:
                labels = []
                for did in live_ids:
                    label = did
                    for row in rows:
                        if isinstance(row, dict) and str(row.get("device_id")) == did:
                            label = str(row.get("name") or did)
                            break
                    labels.append(label)
                raise BrokerError(
                    "ambiguous_device",
                    "multiple devices connected: " + ", ".join(labels) + "; specify device or set default",
                )

        req = urllib.request.Request(
            f"{base}/companion/device/command",
            data=json.dumps({"device_id": device_id, "action": action, "arguments": args}).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=25) as resp:
                return _raise_payload(json.loads(resp.read().decode()))
        except urllib.error.HTTPError as extra:
            raw_body = extra.read().decode()
            try:
                payload = json.loads(raw_body)
            except json.JSONDecodeError:
                raise BrokerError("device_error", raw_body or extra.reason) from extra
            code = str(payload.get("error") or "device_error")
            raise BrokerError(code, code) from extra

    broker.device = LiveDevice(send=send)
