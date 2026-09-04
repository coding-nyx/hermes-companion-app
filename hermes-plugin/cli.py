"""hermes companion … — talks to the live relay, not a second in-memory store."""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request

def relay_url() -> str:
    return (os.environ.get("HERMES_COMPANION_RELAY_URL") or "http://127.0.0.1:9120").rstrip("/")


def _json(method: str, path: str, payload: dict | None = None) -> dict:
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(
        relay_url() + path,
        data=data,
        headers={"Content-Type": "application/json"} if data else {},
        method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=8) as resp:
            return json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as extra:
        raw = extra.read().decode()
        try:
            body = json.loads(raw)
        except json.JSONDecodeError:
            body = {"error": raw or extra.reason}
        raise SystemExit(f"companion: {body.get('error') or extra}") from extra
    except urllib.error.URLError as extra:
        raise SystemExit(
            f"companion: relay down at {relay_url()} ({extra.reason}). "
            "Enable hermes-companion and wait for :9120."
        ) from extra


def setup(parser) -> None:
    sub = parser.add_subparsers(dest="companion_cmd", required=True)
    sub.add_parser("list", help="List paired Android devices")
    approve = sub.add_parser("approve", help="Approve the 6-char code shown on the phone")
    approve.add_argument("code")
    revoke = sub.add_parser("revoke", help="Revoke a paired device")
    revoke.add_argument("device_id")
    sub.add_parser("lanes", help="Show live device-control lanes")
    check = sub.add_parser("relay", help="Relay preflight (`--check` upstream reachability)")
    check.add_argument("--check", action="store_true", help="Print /companion/health equivalent and exit")


def handle(args) -> None:
    cmd = getattr(args, "companion_cmd", None)
    if cmd == "list":
        rows = _json("GET", "/companion/device/list").get("devices") or []
        if not rows:
            print("no paired devices")
            return
        for row in rows:
            print(f"{row.get('device_id')}  {row.get('profile')}")
        return
    if cmd == "approve":
        result = _json("POST", f"/companion/device/pair/{args.code}/approve", {})
        print(f"approved {result.get('device_id')}  {result.get('profile')}")
        return
    if cmd == "revoke":
        _json("POST", "/companion/device/revoke", {"device_id": args.device_id})
        print(f"revoked {args.device_id}")
        return
    if cmd == "lanes":
        rows = _json("GET", "/companion/device/lanes").get("devices") or []
        if not rows:
            print("no live lanes")
            return
        for device_id in rows:
            print(device_id)
        return
    if cmd == "relay":
        if getattr(args, "check", False):
            from relay import check_upstream

            report = check_upstream()
            print(json.dumps(report, indent=2))
            if report["status"] != "reachable" and report["mode"] != "standalone":
                raise SystemExit(2)
            return
        health = _json("GET", "/companion/health")
        print(json.dumps(health, indent=2))
        return
    raise SystemExit(f"unknown companion command: {cmd}")
