"""Explicit-path host file conveniences using the standalone bounded file API."""

from __future__ import annotations

import base64
import binascii
import hashlib
import os
from pathlib import Path
import stat
import tempfile
import time
import uuid


CHUNK_BYTES = 196608


class TransferError(Exception):
    def __init__(self, code, message, hint=None):
        self.code, self.message, self.hint = code, message, hint


def transfer_file(client, args, *, push):
    local = Path(args['local_path']).expanduser()
    if not local.is_absolute() or not args['path'].strip():
        raise TransferError('invalid_arguments', 'Transfer requires explicit absolute local_path and phone path')
    overwrite = args.get('overwrite', False)
    maximum = args.get('max_transfer_bytes', 268435456)
    deadline = time.monotonic() + 300
    original_lease = client.lease_id
    stage = None
    remote_stage = None
    pending_error = None

    def remote(action, **values):
        if client.lease_id != original_lease or not original_lease:
            raise TransferError('disarmed', 'Transfer stopped because its holder is no longer active')
        if time.monotonic() >= deadline:
            raise TransferError('transfer_timeout', 'Transfer exceeded its bounded operation window')
        try:
            reply = client.call('mobile_files', {'action': action, **values})
        except Exception as exc:
            raise TransferError(getattr(exc, 'code', 'file_transfer_failed'), 'Phone file operation failed') from None
        if reply.get('ok') is not True:
            code = reply.get('error', {}).get('code')
            raise TransferError(code if isinstance(code, str) else 'file_transfer_failed', 'Phone file operation was refused')
        return reply['result']

    try:
        if push:
            if local.is_symlink():
                raise TransferError('not_a_file', 'Upload source must be an explicit regular file, not a symlink')
            try:
                target = remote('stat', path=args['path'])
            except TransferError as exc:
                if exc.code != 'not_found':
                    raise
            else:
                if target.get('directory'):
                    raise TransferError('not_a_file', 'Phone destination is a directory')
                if not overwrite:
                    raise TransferError('already_exists', 'Phone destination exists; overwrite must be explicit')
            descriptor = os.open(local, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
            digest, size = hashlib.sha256(), 0
            with os.fdopen(descriptor, 'rb') as source:
                before = os.fstat(source.fileno())
                if not stat.S_ISREG(before.st_mode) or before.st_size > maximum:
                    raise TransferError('transfer_too_large', 'Source must be a regular file within max_transfer_bytes')
                remote_stage = args['path'] + '.hands-upload-' + uuid.uuid4().hex + '.part'
                while True:
                    data = source.read(CHUNK_BYTES)
                    if not data and size:
                        break
                    if size + len(data) > maximum:
                        raise TransferError('transfer_too_large', 'Upload exceeded max_transfer_bytes')
                    digest.update(data)
                    values = {'path': remote_stage, 'data_base64': base64.b64encode(data).decode('ascii')}
                    if size == 0:
                        remote('write', overwrite=False, **values)
                    else:
                        remote('append', offset=size, **values)
                    size += len(data)
                    if not data:
                        break
                after = os.fstat(source.fileno())
                if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns) or size != before.st_size:
                    raise TransferError('file_changed', 'Host source changed during upload')
            checked = remote('hash', path=remote_stage)
            if checked.get('sha256') != digest.hexdigest() or checked.get('size') != size:
                raise TransferError('file_changed', 'Upload checksum did not match')
            result = remote('move', path=remote_stage, destination=args['path'], overwrite=overwrite)
            remote_stage = None
            return {'ok': True, 'result': {'path': result['path'], 'local_path': str(local),
                'bytes': size, 'sha256': digest.hexdigest()}}

        if os.path.lexists(local) and not overwrite:
            raise TransferError('already_exists', 'Host destination exists; overwrite must be explicit')
        if not local.parent.is_dir() or local.is_dir():
            raise TransferError('not_a_file', 'Host destination needs an existing parent directory')
        source = remote('stat', path=args['path'])
        if source.get('directory') or type(source.get('size')) is not int or not 0 <= source['size'] <= maximum:
            raise TransferError('transfer_too_large', 'Phone source must be a regular file within max_transfer_bytes')
        if not isinstance(source.get('version'), str):
            raise TransferError('invalid_response', 'File source did not include its version')
        descriptor, stage = tempfile.mkstemp(prefix='.hands-download-', dir=local.parent)
        digest, size = hashlib.sha256(), 0
        with os.fdopen(descriptor, 'wb') as output:
            while size < source['size']:
                chunk = remote('read', path=args['path'], encoding='base64', offset=size,
                    max_bytes=min(CHUNK_BYTES, source['size'] - size), version=source['version'])
                try:
                    if not isinstance(chunk.get('data_base64'), str) or len(chunk['data_base64']) > (CHUNK_BYTES + 2) // 3 * 4:
                        raise ValueError('Chunk exceeded encoded bounds')
                    data = base64.b64decode(chunk['data_base64'], validate=True)
                except (KeyError, ValueError, binascii.Error):
                    raise TransferError('invalid_response', 'Invalid binary download chunk') from None
                if (not data or len(data) > min(CHUNK_BYTES, source['size'] - size)
                        or chunk.get('offset') != size or chunk.get('version') != source['version']):
                    raise TransferError('file_changed', 'Download chunk changed or exceeded its bounds')
                digest.update(data)
                output.write(data)
                size += len(data)
            output.flush()
            os.fsync(output.fileno())
        checked = remote('hash', path=args['path'])
        if checked.get('sha256') != digest.hexdigest() or checked.get('version') != source['version'] or size != source['size']:
            raise TransferError('file_changed', 'Download checksum or source version changed')
        if overwrite:
            os.replace(stage, local)
        else:
            os.link(stage, local)
            os.unlink(stage)
        stage = None
        return {'ok': True, 'result': {'path': source.get('path', args['path']), 'local_path': str(local),
            'bytes': size, 'sha256': digest.hexdigest()}}
    except TransferError as exc:
        pending_error = exc
        raise
    except FileExistsError:
        pending_error = TransferError('already_exists', 'Destination exists; overwrite must be explicit')
        raise pending_error from None
    except (OSError, ValueError, KeyError, TypeError):
        pending_error = TransferError('file_transfer_failed', 'File transfer failed; no file contents are included in this error')
        raise pending_error from None
    finally:
        if stage:
            try:
                os.unlink(stage)
            except OSError:
                pass
        if remote_stage:
            stop_codes = {'disarmed', 'lease_mismatch', 'lease_expired', 'privacy_active', 'permission_denied', 'authentication_failed'}
            try:
                if client.lease_id != original_lease or pending_error and pending_error.code in stop_codes:
                    raise TransferError('cleanup_blocked', 'Control or permission ended')
                remote('delete', path=remote_stage)
            except TransferError:
                if pending_error:
                    pending_error.hint = 'A temporary .hands-upload-*.part file may remain beside the requested destination. Do not rearm or broaden permission for cleanup.'
