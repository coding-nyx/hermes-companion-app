"""Read Hermes profile directories and state.db so standalone can list real threads."""

from __future__ import annotations

import os
import sqlite3
from pathlib import Path


def hermes_home() -> Path:
    override = os.environ.get("HERMES_HOME")
    if override:
        return Path(override)
    return Path.home() / ".hermes"


def is_profile_dir(path: Path) -> bool:
    return path.is_dir() and any((path / name).exists() for name in ("config.yaml", "state.db", "profile.yaml"))


def _profile_id(path: Path) -> str:
    return path.name


def discover_profile_dirs() -> list[Path]:
    home = hermes_home()
    nested = home / "profiles"
    if nested.is_dir():
        found = [child for child in sorted(nested.iterdir()) if is_profile_dir(child)]
        if found:
            return found
    if is_profile_dir(home):
        return [home]
    return []


def profile_dir(profile_id: str) -> Path | None:
    wanted = (profile_id or "").strip()
    if not wanted:
        return None
    for path in discover_profile_dirs():
        if _profile_id(path) == wanted:
            return path
    home = hermes_home()
    if is_profile_dir(home) and (not wanted or _profile_id(home) == wanted):
        return home
    return None


def _parse_model(path: Path) -> str:
    cfg = path / "config.yaml"
    try:
        text = cfg.read_text(encoding="utf-8")
    except OSError:
        return ""
    in_model = False
    for raw in text.splitlines():
        line = raw.rstrip()
        if not in_model:
            if line.startswith("model:"):
                rest = line.split(":", 1)[1].strip().strip("'\"")
                if rest and rest not in ("|", ">", "{", "", "[]"):
                    return rest
                in_model = True
            continue
        if line and line[0] not in (" ", "\t", "#"):
            break
        stripped = line.strip()
        if stripped.startswith("default:"):
            return stripped.split(":", 1)[1].strip().strip("'\"")
    return ""


def _epoch_ms(value) -> int:
    if value is None or value == "":
        return 0
    try:
        number = float(value)
    except (TypeError, ValueError):
        return 0
    if number > 10_000_000_000:
        return int(number)
    if number > 1_000_000_000:
        return int(number * 1000)
    return 0


def _connect(db: Path) -> sqlite3.Connection | None:
    if not db.is_file():
        return None
    try:
        con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
        con.row_factory = sqlite3.Row
        return con
    except sqlite3.Error:
        return None


_SESSION_COLS = (
    "id",
    "title",
    "display_name",
    "last_activity_at",
    "started_at",
    "ended_at",
    "end_reason",
    "profile_name",
    "hidden",
    "archived",
    "message_count",
)


def _table_columns(con: sqlite3.Connection, table: str) -> set[str]:
    try:
        return {str(row[1]) for row in con.execute(f"PRAGMA table_info({table})").fetchall()}
    except sqlite3.Error:
        return set()


def _select_existing(names: set[str], wanted: tuple[str, ...]) -> str:
    bits = []
    for col in wanted:
        if col in names:
            bits.append(col)
        else:
            bits.append(f"NULL AS {col}")
    return ", ".join(bits)


def list_sessions(profile_id: str, limit: int = 200) -> list[dict]:
    path = profile_dir(profile_id)
    if path is None:
        return []
    con = _connect(path / "state.db")
    if con is None:
        return []
    try:
        names = _table_columns(con, "sessions")
        if "id" not in names:
            return []
        order = "id DESC"
        if "last_activity_at" in names and "started_at" in names:
            order = "COALESCE(last_activity_at, started_at) DESC"
        elif "last_activity_at" in names:
            order = "last_activity_at DESC"
        elif "started_at" in names:
            order = "started_at DESC"
        rows = con.execute(
            f"SELECT {_select_existing(names, _SESSION_COLS)} FROM sessions ORDER BY {order} LIMIT ?",
            (max(1, int(limit)),),
        ).fetchall()
    except sqlite3.Error:
        return []
    finally:
        con.close()
    out = []
    for row in rows:
        if int(row["archived"] or 0) or int(row["hidden"] or 0):
            continue
        sid = str(row["id"] or "")
        if not sid:
            continue
        title = str(row["title"] or row["display_name"] or sid)
        ended = bool(row["ended_at"] or row["end_reason"])
        out.append({
            "id": sid,
            "profile": profile_id,
            "profile_name": str(row["profile_name"] or profile_id),
            "title": title,
            "updated_at": _epoch_ms(row["last_activity_at"]) or _epoch_ms(row["started_at"]),
            "started_at": _epoch_ms(row["started_at"]),
            "unread": False,
            "ended": ended,
            "end_reason": row["end_reason"] or "",
            "message_count": int(row["message_count"] or 0),
        })
    return out


def list_messages(session_id: str, profile_id: str, limit=None, before=None) -> list[dict] | None:
    path = profile_dir(profile_id)
    if path is None:
        return None
    con = _connect(path / "state.db")
    if con is None:
        return None
    try:
        session_cols = _table_columns(con, "sessions")
        if "id" not in session_cols:
            return None
        exists = con.execute("SELECT 1 FROM sessions WHERE id = ? LIMIT 1", (session_id,)).fetchone()
        if exists is None:
            return None
        names = _table_columns(con, "messages")
        if "id" not in names:
            return []
        where = ["session_id = ?"] if "session_id" in names else ["1 = 0"]
        if "active" in names:
            where.append("IFNULL(active, 1) = 1")
        order = "id ASC"
        if "timestamp" in names:
            order = "timestamp ASC, id ASC"
        wanted = ("id", "role", "content", "timestamp")
        rows = con.execute(
            f"SELECT {_select_existing(names, wanted)} FROM messages WHERE {' AND '.join(where)} ORDER BY {order}",
            (session_id,) if "session_id" in names else (),
        ).fetchall()
    except sqlite3.Error:
        return None
    finally:
        con.close()
    items = []
    for row in rows:
        items.append({
            "id": str(row["id"]),
            "role": str(row["role"] or "assistant"),
            "content": row["content"] or "",
            "timestamp": row["timestamp"],
        })
    if before:
        ids = [str(m.get("id") or "") for m in items]
        try:
            items = items[: ids.index(str(before))]
        except ValueError:
            pass
    try:
        n = int(limit) if limit is not None and str(limit).strip() != "" else None
    except (TypeError, ValueError):
        n = None
    if n and n > 0:
        items = items[-n:]
    return items


def profiles() -> list[dict]:
    rows = []
    for path in discover_profile_dirs():
        pid = _profile_id(path)
        sessions = list_sessions(pid)
        rows.append({
            "id": pid,
            "display_name": pid,
            "model": _parse_model(path) or "default",
            "gateway": "running",
            "session_count": len(sessions),
        })
    return rows
