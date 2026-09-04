"""Pin Hermes internals the standalone operator may touch. Fail open to local store."""

from __future__ import annotations

import importlib
import os
from pathlib import Path

PINNED_MODULES = (
    "hermes",
    "hermes_agent",
    "agent",
)


def profiles_dir() -> str:
    override = os.environ.get("HERMES_PROFILES_DIR")
    if override:
        return override
    return str(Path.home() / ".hermes")


def self_test() -> dict:
    warnings: list[str] = []
    version = ""
    found = ""
    for name in PINNED_MODULES:
        try:
            mod = importlib.import_module(name)
        except Exception:
            continue
        found = name
        version = str(getattr(mod, "__version__", "") or "")
        break
    if not found:
        warnings.append("hermes_internals_missing")
    return {
        "ok": True,
        "hermes_module": found,
        "hermes_version": version,
        "profiles_dir": profiles_dir(),
        "warnings": warnings,
        "fallback": "local_store" if warnings else "internals",
    }
