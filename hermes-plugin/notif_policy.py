"""Per-app notification policy: off / silent / respond.

Two layers, host overrides phone:

* **Phone base** — the per-app rules the operator sets in the Device tab, synced to the host
  at register as ``extra_protected``. These are the ``off`` tier and they are enforced *on the
  phone*, so such a notification never leaves the device.
* **Host override** — this module's JSON file, optionally scoped per profile. It decides what
  the host does with an event it actually receives: drop it before the ring, ring it without
  waking anyone, or ring it and wake the profile.

The asymmetry is real and deliberate: a host override can tighten anything, and can loosen
anything the *host* controls, but it cannot loosen a phone-side ``off`` — that notification is
suppressed at source and never arrives. :func:`describe` reports that case explicitly rather
than claiming a mode that will never take effect.

Everything not mentioned anywhere is ``respond``: apps are enabled by default.
"""

from __future__ import annotations

import json
import os
import threading
from pathlib import Path

RESPOND = "respond"
SILENT = "silent"
OFF = "off"
MODES = (RESPOND, SILENT, OFF)
DEFAULT_MODE = RESPOND

_lock = threading.Lock()
_cache: dict | None = None
_cache_mtime: float | None = None


def policy_path() -> Path:
    """Where host overrides live. ``HERMES_COMPANION_NOTIF_POLICY`` wins if set."""
    raw = os.environ.get("HERMES_COMPANION_NOTIF_POLICY")
    if raw:
        return Path(raw).expanduser()
    home = os.environ.get("HERMES_HOME") or str(Path.home() / ".hermes")
    return Path(home).expanduser() / "companion-notif-policy.json"


def _blank() -> dict:
    return {"packages": {}, "profiles": {}}


def load(*, force: bool = False) -> dict:
    """Read the override file, cached on mtime so the hot notification path stays cheap."""
    global _cache, _cache_mtime
    path = policy_path()
    try:
        mtime = path.stat().st_mtime
    except OSError:
        with _lock:
            _cache, _cache_mtime = _blank(), None
        return _blank()
    with _lock:
        if not force and _cache is not None and _cache_mtime == mtime:
            return json.loads(json.dumps(_cache))
    try:
        data = json.loads(path.read_text() or "{}")
    except (OSError, ValueError):
        data = {}
    if not isinstance(data, dict):
        data = {}
    clean = {
        "packages": _clean_map(data.get("packages")),
        "profiles": {
            str(prof): _clean_map(rules)
            for prof, rules in (data.get("profiles") or {}).items()
            if isinstance(rules, dict)
        },
    }
    with _lock:
        _cache, _cache_mtime = clean, mtime
    return json.loads(json.dumps(clean))


def _clean_map(raw) -> dict[str, str]:
    if not isinstance(raw, dict):
        return {}
    out: dict[str, str] = {}
    for pkg, mode in raw.items():
        mode_s = str(mode).strip().lower()
        pkg_s = str(pkg).strip().lower()
        if pkg_s and mode_s in MODES:
            out[pkg_s] = mode_s
    return out


def save(data: dict) -> Path:
    """Write overrides atomically and drop the cache."""
    global _cache, _cache_mtime
    path = policy_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    clean = {
        "packages": _clean_map(data.get("packages")),
        "profiles": {
            str(p): _clean_map(r) for p, r in (data.get("profiles") or {}).items()
        },
    }
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(clean, indent=2, sort_keys=True) + "\n")
    tmp.replace(path)
    with _lock:
        _cache, _cache_mtime = None, None
    return path


def set_mode(package: str, mode: str | None, *, profile: str | None = None) -> dict:
    """Set (or clear, with ``mode=None``) one package's host override.

    Returns the resulting override file contents.
    """
    pkg = str(package or "").strip().lower()
    if not pkg:
        raise ValueError("package required")
    if mode is not None:
        mode = str(mode).strip().lower()
        if mode not in MODES:
            raise ValueError(f"mode must be one of {', '.join(MODES)}")
    data = load(force=True)
    bucket = (
        data["profiles"].setdefault(str(profile).strip(), {})
        if profile and str(profile).strip()
        else data["packages"]
    )
    if mode is None:
        bucket.pop(pkg, None)
    else:
        bucket[pkg] = mode
    save(data)
    return load(force=True)


def legacy_silent() -> set[str]:
    """Packages the old env-var wake-skip list silenced.

    Kept so an existing deployment does not suddenly start waking on Telegram again. The
    override file is the supported place now; this is a compatibility floor, not a default.
    """
    raw = os.environ.get("HERMES_COMPANION_NOTIF_WAKE_SKIP_PACKAGES")
    if not raw:
        return set()
    return {p.strip().lower() for p in raw.split(",") if p.strip()}


def describe(
    package: str,
    *,
    profile: str | None = None,
    phone_off: tuple[str, ...] | set[str] = (),
) -> dict:
    """Resolve the effective mode for one package, and say where it came from.

    ``phone_off`` is the device's own per-app rule set (its ``extra_protected``). Those are
    enforced on the phone, so they are reported separately from the host decision: a host
    override of ``respond`` on such a package is honest about being unreachable.
    """
    pkg = str(package or "").strip().lower()
    prof = str(profile or "").strip()
    data = load()
    phone_blocked = pkg in {str(p).strip().lower() for p in phone_off}

    mode, source = DEFAULT_MODE, "default"
    if pkg in legacy_silent():
        mode, source = SILENT, "env:HERMES_COMPANION_NOTIF_WAKE_SKIP_PACKAGES"
    if pkg in data["packages"]:
        mode, source = data["packages"][pkg], "host:global"
    if prof and pkg in data["profiles"].get(prof, {}):
        mode, source = data["profiles"][prof][pkg], f"host:profile:{prof}"

    return {
        "package": pkg,
        "mode": mode,
        "source": source,
        "phone_suppressed": phone_blocked,
        # What actually happens end to end. A phone-side block wins by physics: the event
        # never reaches the host, whatever the host override says.
        "effective": OFF if phone_blocked else mode,
        "unreachable_override": bool(phone_blocked and mode != OFF and source != "default"),
    }


def effective_mode(
    package: str,
    *,
    profile: str | None = None,
    phone_off: tuple[str, ...] | set[str] = (),
) -> str:
    """Just the effective mode — the hot path for ring/wake decisions."""
    return describe(package, profile=profile, phone_off=phone_off)["effective"]


def should_ring(mode: str) -> bool:
    """`off` never enters the ring; `silent` and `respond` do."""
    return mode != OFF


def should_wake(mode: str) -> bool:
    """Only `respond` wakes the profile."""
    return mode == RESPOND


def snapshot(
    *, profile: str | None = None, phone_off: tuple[str, ...] | set[str] = ()
) -> dict:
    """Everything currently configured, for the read tool."""
    data = load()
    prof = str(profile or "").strip()
    pkgs = set(data["packages"]) | set(data["profiles"].get(prof, {})) | legacy_silent()
    pkgs |= {str(p).strip().lower() for p in phone_off}
    return {
        "default_mode": DEFAULT_MODE,
        "policy_file": str(policy_path()),
        "profile": prof or None,
        "rules": [describe(p, profile=prof, phone_off=phone_off) for p in sorted(pkgs)],
        "phone_rules": sorted({str(p).strip().lower() for p in phone_off}),
    }
