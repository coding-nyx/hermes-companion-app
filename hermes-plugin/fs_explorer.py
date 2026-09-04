"""Safe workspace file browser and content reader for Hermes Companion."""

from __future__ import annotations

import os
from pathlib import Path


def _resolve_safe(root: str, subpath: str) -> Path | None:
    base = Path(root).resolve()
    target = (base / subpath.lstrip("/")).resolve()
    try:
        target.relative_to(base)
        return target
    except ValueError:
        # Path escaped base directory
        return None


def list_dir_tree(workspace_root: str, subpath: str = "") -> dict:
    target = _resolve_safe(workspace_root, subpath)
    if target is None or not target.exists():
        return {"ok": False, "error": "invalid_or_not_found"}

    if not target.is_dir():
        return {"ok": False, "error": "not_a_directory"}

    items: list[dict] = []
    try:
        with os.scandir(target) as it:
            for entry in it:
                # Ignore git internals and pycache by default
                if entry.name in (".git", "__pycache__", ".gradle"):
                    continue
                stat = entry.stat()
                is_dir = entry.is_dir(follow_symlinks=False)
                rel_path = str(Path(entry.path).resolve().relative_to(Path(workspace_root).resolve()))
                items.append(
                    {
                        "path": rel_path,
                        "name": entry.name,
                        "is_dir": is_dir,
                        "size_bytes": stat.st_size if not is_dir else 0,
                        "modified_ms": int(stat.st_mtime * 1000),
                    }
                )
    except Exception as exc:
        return {"ok": False, "error": str(exc)}

    # Sort directories first, then alphabetical
    items.sort(key=lambda x: (not x["is_dir"], x["name"].lower()))
    return {"ok": True, "items": items}


def read_file_content(workspace_root: str, file_path: str, max_bytes: int = 1_048_576) -> dict:
    target = _resolve_safe(workspace_root, file_path)
    if target is None or not target.exists():
        return {"ok": False, "error": "not_found"}

    if target.is_dir():
        return {"ok": False, "error": "is_a_directory"}

    try:
        size = target.stat().st_size
        truncated = size > max_bytes
        with open(target, "r", encoding="utf-8", errors="replace") as f:
            content = f.read(max_bytes)

        return {
            "ok": True,
            "path": file_path,
            "size_bytes": size,
            "truncated": truncated,
            "content": content,
        }
    except Exception as exc:
        return {"ok": False, "error": str(exc)}
