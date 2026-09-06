#!/usr/bin/env python3
"""Fallback entry for Hermes Agent builds that omit register_cli_command (e.g. 0.21).

Usage (any of):
  python ~/.hermes/plugins/hermes-companion/cli_main.py lanes
  python -m hermes_companion_cli   # if console script installed
  hermes-companion lanes          # console_script alias when packaged

Exposes: list, approve, revoke, lanes, default, rename, relay.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

# Ensure sibling imports work when invoked as a script path.
_HERE = Path(__file__).resolve().parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

try:
    from .cli import handle, setup
except ImportError:
    from cli import handle, setup


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="hermes-companion", description="Hermes Companion device CLI")
    setup(parser)
    args = parser.parse_args(argv)
    handle(args)


if __name__ == "__main__":
    main()
