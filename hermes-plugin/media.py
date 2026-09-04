"""Hashed media store for chat attachments. Auth is the relay session, not the dashboard."""

from __future__ import annotations

import hashlib
import json
import os
import threading
from pathlib import Path

MAX_BYTES = 25 * 1024 * 1024
ALLOWED = {
    "image/jpeg",
    "image/png",
    "image/gif",
    "image/webp",
    "video/mp4",
    "video/webm",
    "video/quicktime",
    "application/pdf",
    "text/plain",
    "text/markdown",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
}


def default_media_root() -> Path:
    override = os.environ.get("HERMES_COMPANION_MEDIA")
    if override:
        return Path(override)
    return Path.home() / ".hermes" / "companion-media"


class MediaError(Exception):
    def __init__(self, code: str, status: int = 400):
        super().__init__(code)
        self.code = code
        self.status = status


class MediaStore:
    def __init__(self, root: Path | None = None):
        self.root = Path(root) if root else default_media_root()
        self.root.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()

    def put(self, data: bytes, filename: str, mime: str) -> dict:
        if not data:
            raise MediaError("empty")
        if len(data) > MAX_BYTES:
            raise MediaError("too_large", 413)
        kind = (mime or "").split(";")[0].strip().lower() or "application/octet-stream"
        if kind not in ALLOWED:
            raise MediaError("unsupported_type", 415)
        digest = hashlib.sha256(data).hexdigest()[:24]
        blob = self.root / digest
        meta_path = self.root / f"{digest}.json"
        name = Path(filename or "file").name or "file"
        rec = {
            "id": digest,
            "filename": name,
            "mime": kind,
            "size": len(data),
            "url": f"/companion/media/{digest}",
        }
        with self.lock:
            if not blob.exists():
                blob.write_bytes(data)
            meta_path.write_text(json.dumps(rec), encoding="utf-8")
        return rec

    def get(self, media_id: str) -> tuple[bytes, dict] | None:
        ident = "".join(ch for ch in (media_id or "") if ch.isalnum())
        if not ident:
            return None
        blob = self.root / ident
        meta_path = self.root / f"{ident}.json"
        if not blob.is_file():
            return None
        meta = {"id": ident, "filename": ident, "mime": "application/octet-stream", "size": blob.stat().st_size}
        if meta_path.is_file():
            try:
                meta.update(json.loads(meta_path.read_text(encoding="utf-8")))
            except json.JSONDecodeError:
                pass
        return blob.read_bytes(), meta
