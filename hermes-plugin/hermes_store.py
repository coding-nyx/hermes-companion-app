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


def hermes_root() -> Path:
    """
    The install root that owns ``profiles/``. Mirrors ``hermes_cli.profiles``: a gateway or relay
    started with ``HERMES_HOME=<root>/profiles/<name>`` still belongs to ``<root>`` — the root is the
    ``default`` profile and every sibling under ``profiles/`` is a named one.
    """
    home = hermes_home()
    return home.parent.parent if home.parent.name == "profiles" else home


def is_profile_dir(path: Path) -> bool:
    return path.is_dir() and any((path / name).exists() for name in ("config.yaml", "state.db", "profile.yaml"))


# Hermes calls the root profile (HERMES_HOME itself) "default"; named ones live in HERMES_HOME/profiles/<name>.
DEFAULT_PROFILE = "default"


def _profile_id(path: Path) -> str:
    if path.resolve() == hermes_root().resolve():
        return DEFAULT_PROFILE
    return path.name


def discover_profile_dirs() -> list[Path]:
    """
    Root profile first (as ``default``), then ``<root>/profiles/<name>`` alphabetically.
    Before 2026-09-08 any nested profile hid the root one, so a host with ``profiles/coder``
    listed only ``coder`` and the default profile's threads were unreachable from the phone;
    a relay running as ``HERMES_HOME=…/profiles/bishop`` saw only itself.
    An explicit ``profiles/default`` directory wins over the root when both exist.
    """
    root = hermes_root()
    nested = root / "profiles"
    found: list[Path] = []
    if nested.is_dir():
        found = [child for child in sorted(nested.iterdir()) if is_profile_dir(child)]
    has_named_default = any(child.name == DEFAULT_PROFILE for child in found)
    if is_profile_dir(root) and not has_named_default:
        return [root] + found
    return found


def profile_dir(profile_id: str) -> Path | None:
    wanted = (profile_id or "").strip()
    if not wanted:
        return None
    for path in discover_profile_dirs():
        if _profile_id(path) == wanted:
            return path
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
    "source",
)

# Rows the phone can ask for in one page. Matches the app's SESSION_PAGE / Hermes' history cap.
SESSION_PAGE = 500


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


def _session_where(names: set[str], include_archived: bool) -> str:
    where = []
    if "archived" in names and not include_archived:
        where.append("COALESCE(archived, 0) = 0")
    if "hidden" in names:
        where.append("COALESCE(hidden, 0) = 0")
    return f" WHERE {' AND '.join(where)}" if where else ""


def list_sessions(profile_id: str, limit: int = SESSION_PAGE, offset: int = 0, include_archived: bool = False) -> list[dict]:
    """Newest-started first. Hidden (and, by default, archived) rows are excluded *before* LIMIT/OFFSET so a page is never short."""
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
        if "started_at" in names and "last_activity_at" in names:
            order = "COALESCE(started_at, last_activity_at) DESC, COALESCE(last_activity_at, started_at) DESC, id DESC"
        elif "started_at" in names:
            order = "started_at DESC, id DESC"
        elif "last_activity_at" in names:
            order = "last_activity_at DESC, id DESC"
        where_sql = _session_where(names, include_archived)
        try:
            n = int(limit)
        except (TypeError, ValueError):
            n = SESSION_PAGE
        try:
            skip = max(0, int(offset or 0))
        except (TypeError, ValueError):
            skip = 0
        rows = con.execute(
            f"SELECT {_select_existing(names, _SESSION_COLS)} FROM sessions{where_sql} ORDER BY {order} LIMIT ? OFFSET ?",
            (max(1, n), skip),
        ).fetchall()
    except sqlite3.Error:
        return []
    finally:
        con.close()
    out = []
    for row in rows:
        archived = bool(int(row["archived"] or 0))
        if (archived and not include_archived) or int(row["hidden"] or 0):
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
            "source": str(row["source"] or ""),
            "archived": archived,
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


def count_sessions(profile_id: str, include_archived: bool = False) -> int:
    """Non-hidden (and, by default, non-archived) rows — the `total` a paging client expects."""
    path = profile_dir(profile_id)
    if path is None:
        return 0
    con = _connect(path / "state.db")
    if con is None:
        return 0
    try:
        names = _table_columns(con, "sessions")
        if "id" not in names:
            return 0
        where_sql = _session_where(names, include_archived)
        return int(con.execute(f"SELECT COUNT(*) FROM sessions{where_sql}").fetchone()[0] or 0)
    except sqlite3.Error:
        return 0
    finally:
        con.close()


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
