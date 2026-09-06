"""python -m style entry when the plugin is importable as a package."""
from __future__ import annotations

try:
    from .cli_main import main
except ImportError:
    from cli_main import main

if __name__ == "__main__":
    main()
