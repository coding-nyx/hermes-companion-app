"""hermes companion … — talks to the live relay, not a second in-memory store.

Subcommands: list approve revoke lanes default rename relay room{list,create,post,history,interrupt,delete}."""

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
    parser.set_defaults(_companion_parser=parser)
    sub = parser.add_subparsers(
        dest="companion_cmd",
        required=False,
        metavar="COMMAND",
        title="commands",
    )
    sub.add_parser("list", help="List paired Android devices")
    approve = sub.add_parser("approve", help="Approve the 6-char code shown on the phone")
    approve.add_argument("code")
    revoke = sub.add_parser("revoke", help="Revoke a paired device")
    revoke.add_argument("device_id")
    sub.add_parser("lanes", help="Show live device-control lanes")
    default = sub.add_parser("default", help="Set the default target device")
    default.add_argument("device", help="Device id or friendly name")
    rename = sub.add_parser("rename", help="Assign a friendly label to a paired device")
    rename.add_argument("device_id")
    rename.add_argument("name")
    check = sub.add_parser("relay", help="Relay preflight (`--check` upstream reachability)")
    check.add_argument("--check", action="store_true", help="Print /companion/health equivalent and exit")
    room = sub.add_parser("room", help="Agent rooms: group chat between profiles")
    rsub = room.add_subparsers(dest="room_cmd", required=True)
    rsub.add_parser("list", help="List rooms")
    create = rsub.add_parser("create", help="Create a room: create TITLE PROFILE [PROFILE…]")
    create.add_argument("title")
    create.add_argument("profiles", nargs="+")
    create.add_argument("--rounds", type=int, default=2, help="Max agent rounds per operator message (1–4)")
    post = rsub.add_parser("post", help="Post as the operator and run the agents' turns")
    post.add_argument("room_id")
    post.add_argument("text", nargs="+")
    post.add_argument("--no-wait", action="store_true", help="Return immediately instead of printing the turns")
    hist = rsub.add_parser("history", help="Print a room transcript")
    hist.add_argument("room_id")
    hist.add_argument("--after", type=int, default=0)
    stop = rsub.add_parser("interrupt", help="Stop all in-flight turns in a room")
    stop.add_argument("room_id")
    rm = rsub.add_parser("delete", help="Delete a room (backing sessions stay in their profiles)")
    rm.add_argument("room_id")


def handle(args) -> None:
    cmd = getattr(args, "companion_cmd", None)
    if not cmd:
        parser = getattr(args, "_companion_parser", None)
        if parser is not None:
            parser.print_help()
        else:
            print("usage: hermes companion {list,approve,revoke,lanes,default,rename,relay,room} ...")
        return
    if cmd == "list":
        rows = _json("GET", "/companion/device/list").get("devices") or []
        if not rows:
            print("no paired devices")
            return
        for row in rows:
            mark = "*" if row.get("is_default") else " "
            name = row.get("name") or row.get("device_id")
            print(f"{mark} {row.get('device_id')}  {name}  {row.get('profile')}")
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
        live = [r for r in rows if (isinstance(r, dict) and r.get("lane")) or isinstance(r, str)]
        if not live:
            print("no live lanes")
            return
        print(f"{'D':1} {'DEVICE ID':22} {'NAME':18} {'MODEL':14} {'ARMED':5} {'LANE'}")
        for row in rows:
            if isinstance(row, str):
                print(f"  {row:22}")
                continue
            if not row.get("lane"):
                continue
            mark = "*" if row.get("is_default") else " "
            armed = "yes" if row.get("armed") else "no"
            print(
                f"{mark} {str(row.get('device_id') or ''):22} "
                f"{str(row.get('name') or '')[:18]:18} "
                f"{str(row.get('model') or '')[:14]:14} "
                f"{armed:5} live"
            )
        return
    if cmd == "default":
        result = _json("POST", "/companion/device/default", {"device": args.device})
        print(f"default {result.get('device_id')}  {result.get('name')}")
        return
    if cmd == "rename":
        result = _json("POST", "/companion/device/rename", {"device_id": args.device_id, "name": args.name})
        print(f"renamed {result.get('device_id')}  {result.get('name')}")
        return
    if cmd == "relay":
        if getattr(args, "check", False):
            try:
                from .relay import check_upstream
            except ImportError:
                from relay import check_upstream

            report = check_upstream()
            print(json.dumps(report, indent=2))
            if report["status"] != "reachable" and report["mode"] != "standalone":
                raise SystemExit(2)
            return
        health = _json("GET", "/companion/health")
        print(json.dumps(health, indent=2))
        return
    if cmd == "room":
        _handle_room(args)
        return
    raise SystemExit(f"unknown companion command: {cmd}")


def _print_room_msg(m: dict) -> None:
    tag = f"{m.get('glyph') or m.get('speaker'):>3}"
    if m.get("passed"):
        print(f"{tag}  (passed)")
        return
    for t in m.get("tools") or []:
        print(f"{tag}  [ran {t.get('name')} · {str(t.get('detail') or '')[:80]}]")
    if m.get("error"):
        print(f"{tag}  !{m.get('error')}")
    text = str(m.get("text") or "")
    if text:
        print(f"{tag}  {text}")


def _handle_room(args) -> None:
    sub = getattr(args, "room_cmd", None)
    if sub == "list":
        rows = _json("GET", "/companion/rooms").get("rooms") or []
        if not rows:
            print("no rooms")
            return
        for r in rows:
            glyphs = " ".join(p.get("glyph") or p.get("profile") for p in r.get("participants") or [])
            state = f"live {r.get('speaking')}" if r.get("busy") else "idle"
            print(f"{r.get('id')}  {str(r.get('title') or '')[:28]:28} {glyphs:16} {r.get('message_count', 0):>3} msgs  {state}")
        return
    if sub == "create":
        result = _json("POST", "/companion/rooms", {
            "title": args.title, "participants": args.profiles, "policy": {"max_rounds": args.rounds},
        })
        room = result.get("room") or {}
        print(f"created {room.get('id')}  {room.get('title')}  " + " ".join(p.get("glyph") for p in room.get("participants") or []))
        return
    if sub == "history":
        result = _json("GET", f"/companion/rooms/{args.room_id}/history?after={int(args.after)}")
        for m in result.get("messages") or []:
            _print_room_msg(m)
        return
    if sub == "interrupt":
        result = _json("POST", f"/companion/rooms/{args.room_id}/interrupt", {})
        print("interrupted" if result.get("interrupted") else "nothing running")
        return
    if sub == "delete":
        _json("DELETE", f"/companion/rooms/{args.room_id}")
        print(f"deleted {args.room_id}")
        return
    if sub == "post":
        import time as _time

        text = " ".join(args.text)
        result = _json("POST", f"/companion/rooms/{args.room_id}/post", {"text": text})
        _print_room_msg(result.get("message") or {})
        if args.no_wait:
            return
        seq = int(result.get("seq") or 0)
        deadline = _time.monotonic() + 900
        while _time.monotonic() < deadline:
            _time.sleep(1.0)
            page = _json("GET", f"/companion/rooms/{args.room_id}/history?after={seq}")
            for m in page.get("messages") or []:
                _print_room_msg(m)
                seq = max(seq, int(m.get("seq") or 0))
            if not (page.get("room") or {}).get("busy"):
                break
        return
    raise SystemExit(f"unknown room command: {sub}")
