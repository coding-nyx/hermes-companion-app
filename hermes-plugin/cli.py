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
    create = rsub.add_parser("create", help="Create a room: create TITLE PROFILE [PROFILE…] (remote: profile@peer)")
    create.add_argument("title")
    create.add_argument("profiles", nargs="+")
    create.add_argument("--mode", choices=["converse", "moderated", "bounded"], default="converse")
    create.add_argument("--turns", type=int, default=12, help="Turn budget per operator message before the room pauses (converse/moderated)")
    create.add_argument("--rounds", type=int, default=2, help="Max agent rounds per operator message (bounded mode, 1–4)")
    create.add_argument("--moderator", default="", help="Chair profile (moderated mode)")
    create.add_argument("--hands", default="", help="The only participant allowed to drive the phone")
    rename = rsub.add_parser("rename", help="Rename a room")
    rename.add_argument("room_id")
    rename.add_argument("title", nargs="+")
    mode = rsub.add_parser("mode", help="Change the turn policy of a room")
    mode.add_argument("room_id")
    mode.add_argument("mode", choices=["converse", "moderated", "bounded"])
    mode.add_argument("--turns", type=int)
    mode.add_argument("--rounds", type=int)
    mode.add_argument("--moderator")
    mode.add_argument("--hands")
    parts = rsub.add_parser("participants", help="Add / remove participants: --add PROFILE[@peer] --remove PROFILE")
    parts.add_argument("room_id")
    parts.add_argument("--add", action="append", default=[])
    parts.add_argument("--remove", action="append", default=[])
    pause = rsub.add_parser("pause", help="Soft stop: finish the current turn, schedule nothing else")
    pause.add_argument("room_id")
    cont = rsub.add_parser("continue", help="Resume a paused/quiet room with extra turns")
    cont.add_argument("room_id")
    cont.add_argument("--turns", type=int, default=6)
    summ = rsub.add_parser("summarize", help="Ask a participant for a 5-line summary (pinned)")
    summ.add_argument("room_id")
    summ.add_argument("--by", default="")
    appr = rsub.add_parser("approve", help="Answer a pending approval in a room: approve ROOM REQUEST_ID DECISION")
    appr.add_argument("room_id")
    appr.add_argument("request_id")
    appr.add_argument("decision")
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
    agent = sub.add_parser("agent", help="Coding-agent sessions (Claude Code / Codex / shell in tmux)")
    asub = agent.add_subparsers(dest="agent_cmd", required=True)
    asub.add_parser("tools", help="Which agent CLIs this host has")
    asub.add_parser("list", help="List sessions")
    astart = asub.add_parser("start", help="Start a session: start TOOL [--cwd DIR] [--title T] [--structured] [PROMPT…]")
    astart.add_argument("tool")
    astart.add_argument("prompt", nargs="*")
    astart.add_argument("--cwd", default="")
    astart.add_argument("--title", default="")
    astart.add_argument("--structured", action="store_true", help="stream-json session (Claude Code): prompts + approvals instead of a pane")
    aprompt = asub.add_parser("prompt", help="Send a prompt to a structured session and print its events: prompt ID TEXT…")
    aprompt.add_argument("session_id")
    aprompt.add_argument("text", nargs="+")
    aprompt.add_argument("--no-wait", action="store_true")
    aappr = asub.add_parser("approve", help="Answer a pending approval: approve ID REQUEST_ID once|deny")
    aappr.add_argument("session_id")
    aappr.add_argument("request_id")
    aappr.add_argument("decision")
    atr = asub.add_parser("transcript", help="Print a structured session's events")
    atr.add_argument("session_id")
    apane = asub.add_parser("pane", help="Print the current screen of a session")
    apane.add_argument("session_id")
    akeys = asub.add_parser("keys", help="Send text (and Enter) to a session: keys ID TEXT…")
    akeys.add_argument("session_id")
    akeys.add_argument("text", nargs="*")
    akeys.add_argument("--key", action="append", default=[], help="special key: enter, esc, tab, up, down, c-c …")
    akill = asub.add_parser("kill", help="Kill a session")
    akill.add_argument("session_id")
    peer = sub.add_parser("peer", help="Peer links between relays (cross-host rooms)")
    psub = peer.add_subparsers(dest="peer_cmd", required=True)
    psub.add_parser("list", help="List peers this host can call and grants it issued")
    grant = psub.add_parser("grant", help="Mint a credential another relay may use to run turns here: grant NAME")
    grant.add_argument("name", help="Name of the relay that will use it (e.g. lab)")
    padd = psub.add_parser("add", help="Store a peer credential minted on the other host: add NAME ORIGIN HOST_ID SECRET")
    padd.add_argument("name")
    padd.add_argument("origin")
    padd.add_argument("host_id")
    padd.add_argument("secret")
    prm = psub.add_parser("remove", help="Forget a peer")
    prm.add_argument("name")
    prev = psub.add_parser("revoke", help="Revoke a grant this host issued: revoke HOST_ID")
    prev.add_argument("host_id")
    pchk = psub.add_parser("check", help="Probe a peer's health")
    pchk.add_argument("name")


def handle(args) -> None:
    cmd = getattr(args, "companion_cmd", None)
    if not cmd:
        parser = getattr(args, "_companion_parser", None)
        if parser is not None:
            parser.print_help()
        else:
            print("usage: hermes companion {list,approve,revoke,lanes,default,rename,relay,room,peer,agent} ...")
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
    if cmd == "peer":
        _handle_peer(args)
        return
    if cmd == "agent":
        _handle_agent(args)
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


def _print_agent_event(ev: dict) -> None:
    t = str(ev.get("type") or "")
    if t == "agent.user":
        print(f"YOU  {ev.get('text')}")
    elif t == "agent.delta":
        print(ev.get("text") or "", end="", flush=True)
    elif t == "agent.tool.start":
        print(f"\n[{ev.get('name')} · {str(ev.get('detail') or '')[:100]}]")
    elif t == "agent.tool.complete":
        print(f"[{ev.get('name')} done · {str(ev.get('detail') or '')[:100]}]")
    elif t == "agent.approval":
        print(f"\n!! APPROVAL {ev.get('request_id')}  {ev.get('tool')} · {str(ev.get('command') or '')[:120]}  → hermes companion agent approve <id> {ev.get('request_id')} once|deny")
    elif t == "agent.approval.resolved":
        print(f"[approval {ev.get('request_id')}: {ev.get('decision')}]")
    elif t == "agent.turn.end":
        print(f"\n-- turn end{' (error)' if ev.get('is_error') else ''}  cost=${ev.get('cost_usd')}")
    elif t == "agent.exit":
        print(f"-- exited {ev.get('exit_code')} {ev.get('stderr') or ''}")
    elif t == "agent.ready":
        print(f"-- ready  claude session {ev.get('claude_session_id')}  model {ev.get('model')}")


def _handle_agent(args) -> None:
    sub = getattr(args, "agent_cmd", None)
    if sub == "tools":
        data = _json("GET", "/companion/agents/tools?refresh=1")
        print(f"tmux: {'yes' if data.get('tmux') else 'MISSING'}   default cwd: {data.get('default_cwd')}")
        for t in data.get("tools") or []:
            mark = "+" if t.get("installed") else "-"
            extra = t.get("version") or (t.get("install_hint") if not t.get("installed") else "")
            print(f"{mark} {t.get('glyph')}  {t.get('id'):9} {extra}")
        return
    if sub == "list":
        rows = _json("GET", "/companion/agents/sessions").get("sessions") or []
        if not rows:
            print("no sessions")
            return
        for s in rows:
            print(f"{s.get('id')}  {s.get('tool'):7} {s.get('status'):8} {str(s.get('title') or '')[:30]:30} {s.get('cwd')}   {s.get('attach')}")
        return
    if sub == "start":
        body = {"tool": args.tool, "prompt": " ".join(args.prompt), "cwd": args.cwd, "title": args.title,
                "mode": "structured" if args.structured else "pty"}
        s = _json("POST", "/companion/agents/sessions", body).get("session") or {}
        print(f"started {s.get('id')}  {s.get('tool')}  {s.get('mode')}  {s.get('cwd')}   {s.get('attach')}")
        return
    if sub == "prompt":
        import time as _time
        text = " ".join(args.text)
        before = len(_json("GET", f"/companion/agents/sessions/{args.session_id}/transcript").get("events") or [])
        _json("POST", f"/companion/agents/sessions/{args.session_id}/prompt", {"text": text})
        if args.no_wait:
            return
        deadline = _time.monotonic() + 900
        seen = before
        while _time.monotonic() < deadline:
            data = _json("GET", f"/companion/agents/sessions/{args.session_id}/transcript?after={seen}")
            for ev in data.get("events") or []:
                seen += 1
                _print_agent_event(ev)
                if ev.get("type") in ("agent.turn.end", "agent.exit"):
                    return
            _time.sleep(0.5)
        return
    if sub == "approve":
        _json("POST", f"/companion/agents/sessions/{args.session_id}/approval", {"request_id": args.request_id, "decision": args.decision})
        print(f"answered {args.request_id}: {args.decision}")
        return
    if sub == "transcript":
        for ev in _json("GET", f"/companion/agents/sessions/{args.session_id}/transcript").get("events") or []:
            _print_agent_event(ev)
        return
    if sub == "pane":
        data = _json("GET", f"/companion/agents/sessions/{args.session_id}/pane")
        print(data.get("ansi") or "")
        return
    if sub == "keys":
        body = {"text": " ".join(args.text), "keys": args.key or (["enter"] if args.text else [])}
        _json("POST", f"/companion/agents/sessions/{args.session_id}/keys", body)
        print("sent")
        return
    if sub == "kill":
        _json("DELETE", f"/companion/agents/sessions/{args.session_id}")
        print(f"killed {args.session_id}")
        return


def _handle_peer(args) -> None:
    sub = getattr(args, "peer_cmd", None)
    if sub == "list":
        data = _json("GET", "/companion/peers")
        peers = data.get("peers") or []
        grants = data.get("grants") or []
        if not peers and not grants:
            print("no peers, no grants")
            return
        for p in peers:
            print(f"peer   {p.get('name'):12} {p.get('origin')}")
        for g in grants:
            print(f"grant  {g.get('name'):12} {g.get('host_id')}")
        return
    if sub == "grant":
        g = _json("POST", "/companion/peers/grant", {"name": args.name}).get("grant") or {}
        print("run on the other host:")
        print(f"  hermes companion peer add <this-host-name> http://<this-host>:9120 {g.get('host_id')} {g.get('secret')}")
        return
    if sub == "add":
        p = _json("POST", "/companion/peers", {"name": args.name, "origin": args.origin, "host_id": args.host_id, "secret": args.secret}).get("peer") or {}
        print(f"peer {p.get('name')} → {p.get('origin')}")
        return
    if sub == "remove":
        _json("DELETE", f"/companion/peers/{args.name}")
        print(f"removed peer {args.name}")
        return
    if sub == "revoke":
        _json("DELETE", f"/companion/peers/grants/{args.host_id}")
        print(f"revoked grant {args.host_id}")
        return
    if sub == "check":
        data = _json("GET", f"/companion/peers/{args.name}")
        health = data.get("health") or {}
        print(f"{args.name}  reachable={data.get('reachable')}  rooms={health.get('rooms')}  mode={health.get('mode')}  {data.get('error') or ''}")
        return


def _handle_room(args) -> None:
    sub = getattr(args, "room_cmd", None)
    if sub == "list":
        rows = _json("GET", "/companion/rooms").get("rooms") or []
        if not rows:
            print("no rooms")
            return
        for r in rows:
            glyphs = " ".join(p.get("glyph") or p.get("profile") for p in r.get("participants") or [])
            if r.get("busy"):
                state = f"live {r.get('speaking')}"
            else:
                state = str(r.get("state") or "idle") + (f" ({r.get('pause_reason')})" if r.get("pause_reason") else "")
            print(f"{r.get('id')}  {str(r.get('title') or '')[:28]:28} {glyphs:16} {r.get('message_count', 0):>3} msgs  {r.get('mode', '')}  {state}")
        return
    if sub == "create":
        policy = {"mode": args.mode, "max_turns": args.turns, "max_rounds": args.rounds}
        if args.moderator:
            policy["moderator"] = args.moderator
        if args.hands:
            policy["hands"] = args.hands
        result = _json("POST", "/companion/rooms", {"title": args.title, "participants": args.profiles, "policy": policy})
        room = result.get("room") or {}
        print(f"created {room.get('id')}  {room.get('title')}  {room.get('mode')}  " + " ".join(p.get("glyph") for p in room.get("participants") or []))
        return
    if sub == "rename":
        result = _json("PATCH", f"/companion/rooms/{args.room_id}", {"title": " ".join(args.title)})
        print(f"renamed {args.room_id}  {result.get('room', {}).get('title')}")
        return
    if sub == "mode":
        policy = {"mode": args.mode}
        for key, val in (("max_turns", args.turns), ("max_rounds", args.rounds), ("moderator", args.moderator), ("hands", args.hands)):
            if val is not None:
                policy[key] = val
        result = _json("PATCH", f"/companion/rooms/{args.room_id}", {"policy": policy})
        room = result.get("room") or {}
        print(f"{args.room_id}  mode={room.get('mode')} turns={room.get('policy', {}).get('max_turns')} moderator={room.get('moderator') or '-'} hands={room.get('hands') or '-'}")
        return
    if sub == "participants":
        result = _json("POST", f"/companion/rooms/{args.room_id}/participants", {"add": args.add, "remove": args.remove})
        print(" ".join(p.get("glyph") for p in result.get("room", {}).get("participants") or []))
        return
    if sub == "pause":
        result = _json("POST", f"/companion/rooms/{args.room_id}/pause", {})
        print("pausing after the current turn" if result.get("pausing") else "nothing running")
        return
    if sub == "continue":
        result = _json("POST", f"/companion/rooms/{args.room_id}/continue", {"turns": args.turns})
        print(f"{args.room_id}  {result.get('room', {}).get('state')}  budget {result.get('room', {}).get('budget')}")
        return
    if sub == "summarize":
        result = _json("POST", f"/companion/rooms/{args.room_id}/summarize", {"by": args.by})
        print(f"summary requested from {args.by or 'the first participant'}  ({result.get('room', {}).get('state')})")
        return
    if sub == "approve":
        _json("POST", f"/companion/rooms/{args.room_id}/approval", {"request_id": args.request_id, "decision": args.decision})
        print(f"answered {args.request_id}: {args.decision}")
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
