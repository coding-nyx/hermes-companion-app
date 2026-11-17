"""Shared-storage tools and bounded binary transfers; file bytes stay out of agent output."""
from __future__ import annotations

import base64
import hashlib
import json
import os
from pathlib import Path
import tempfile
import time
import uuid

try:
    from .broker import BrokerError
except ImportError:
    from broker import BrokerError

CHUNK_BYTES = 192 * 1024


def make_file_handlers(broker, guard, error):
    def call(action, params):
        try:
            guard()
            return json.dumps({"ok": True, "result": broker.dispatch(action, dict(params or {}))})
        except BrokerError as exc:
            return error(exc)

    def transfer(params, *, push):
        params = dict(params or {})
        stage_path = None
        remote_stage = None
        next_call = 0.0

        def remote(action, **args):
            nonlocal next_call
            for attempt in range(6):
                # Only retry refusals which guarantee the operation never began.
                delay = next_call - time.monotonic()
                if delay > 0:
                    time.sleep(delay)
                next_call = time.monotonic() + 0.125
                try:
                    return broker.dispatch("device.files", {"device": params["device"], "action": action, **args})
                except BrokerError as exc:
                    if exc.code not in {"file_busy", "rate_limited"} or attempt == 5:
                        raise
                    time.sleep(0.2)

        def failed(exc):
            nonlocal remote_stage
            if remote_stage:
                try:
                    remote("delete", path=remote_stage)
                except Exception as cleanup:
                    if not isinstance(cleanup, BrokerError) or cleanup.code != "not_found":
                        exc = BrokerError(exc.code, f"{exc.message}; temporary phone file may remain: {remote_stage}")
                remote_stage = None
            payload = json.loads(error(exc))
            if exc.code == "disarmed":
                hint = "Transfer stopped. Respect DISARM; re-arm only for a new user-authorized task, never just to clean up a temporary file."
                payload["hint"] = hint
                payload["error"]["hint"] = hint
            return json.dumps(payload)

        try:
            guard()
            for field in ("device", "path", "local_path"):
                if not isinstance(params.get(field), str) or not params[field].strip():
                    raise BrokerError("missing_args", f"{field} required; use a stable device id for transfers")
            local = Path(params["local_path"]).expanduser().absolute()
            overwrite = params.get("overwrite", False)
            if not isinstance(overwrite, bool):
                raise BrokerError("invalid_args", "overwrite must be boolean")
            if push:
                if not local.is_file():
                    raise BrokerError("not_a_file", "host source must be a regular file")
                try:
                    target = remote("stat", path=params["path"])
                except BrokerError as exc:
                    if exc.code != "not_found":
                        raise
                else:
                    if target.get("directory"):
                        raise BrokerError("not_a_file", "phone destination is a directory")
                    if not overwrite:
                        raise BrokerError("already_exists", "phone destination exists; use overwrite=true explicitly")
                digest = hashlib.sha256()
                size = 0
                with local.open("rb") as source:
                    before = os.fstat(source.fileno())
                    remote_stage = params["path"] + f".hermes-upload-{uuid.uuid4().hex}.part"
                    while True:
                        data = source.read(CHUNK_BYTES)
                        if not data and size:
                            break
                        digest.update(data)
                        if size == 0:
                            remote("write", path=remote_stage, data_base64=base64.b64encode(data).decode(), overwrite=False)
                        else:
                            remote("append", path=remote_stage, data_base64=base64.b64encode(data).decode(), offset=size)
                        size += len(data)
                        if not data:
                            break
                    after = os.fstat(source.fileno())
                    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns) or size != before.st_size:
                        raise BrokerError("file_changed", "host source changed during upload")
                checked = remote("hash", path=remote_stage)
                if checked.get("sha256") != digest.hexdigest() or checked.get("size") != size:
                    raise BrokerError("file_changed", "upload checksum mismatch")
                result = remote("move", path=remote_stage, destination=params["path"], overwrite=overwrite)
                remote_stage = None
                return json.dumps({"ok": True, "result": {"path": result["path"], "local_path": str(local),
                    "bytes": size, "sha256": digest.hexdigest()}})
            if local.exists() and not overwrite:
                raise BrokerError("already_exists", "host destination exists; use overwrite=true explicitly")
            source = remote("stat", path=params["path"])
            if source.get("directory"):
                raise BrokerError("not_a_file", "pull accepts a file, not a directory")
            digest = hashlib.sha256()
            size = 0
            fd, stage_path = tempfile.mkstemp(prefix=".hermes-download-", dir=local.parent)
            with os.fdopen(fd, "wb") as output:
                while size < source["size"]:
                    chunk = remote("read", path=params["path"], encoding="base64", offset=size,
                        max_bytes=CHUNK_BYTES, version=source["version"])
                    data = base64.b64decode(chunk["data_base64"], validate=True)
                    if not data or len(data) > CHUNK_BYTES or chunk.get("offset") != size or chunk.get("version") != source["version"]:
                        raise BrokerError("file_changed", "invalid or changed download chunk")
                    digest.update(data)
                    output.write(data)
                    size += len(data)
                output.flush()
                os.fsync(output.fileno())
            checked = remote("hash", path=params["path"])
            if checked.get("sha256") != digest.hexdigest() or checked.get("version") != source["version"] or size != source["size"]:
                raise BrokerError("file_changed", "source changed or download checksum mismatch")
            if overwrite:
                os.replace(stage_path, local)
            else:
                # Same-directory hard link gives atomic no-clobber publication on the host.
                os.link(stage_path, local)
                os.unlink(stage_path)
            stage_path = None
            return json.dumps({"ok": True, "result": {"path": source["path"], "local_path": str(local),
                "bytes": size, "sha256": digest.hexdigest()}})
        except BrokerError as exc:
            return failed(exc)
        except FileExistsError:
            return failed(BrokerError("already_exists", "destination exists; use overwrite=true explicitly"))
        except (OSError, ValueError, KeyError, TypeError) as exc:
            return failed(BrokerError("file_transfer_failed", str(exc)))
        finally:
            if stage_path:
                try:
                    os.unlink(stage_path)
                except OSError:
                    pass
            if remote_stage:
                # A disarm/revoked grant may prevent cleanup. Never re-arm to remove a stage.
                try:
                    remote("delete", path=remote_stage)
                except Exception:
                    pass

    return {
        "mobile_files_access": lambda params, **kw: call("device.files.access", params),
        "mobile_files_enable": lambda params, **kw: call("device.files.enable", params),
        "mobile_files": lambda params, **kw: call("device.files", params),
        "mobile_file_pull": lambda params, **kw: transfer(params, push=False),
        "mobile_file_push": lambda params, **kw: transfer(params, push=True),
    }
