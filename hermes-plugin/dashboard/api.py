"""Entry point loaded by Hermes's authenticated dashboard plugin API loader."""
import importlib.util
from pathlib import Path
import sys

_name = "hermes_companion_audio"
if _name not in sys.modules:
    _dir = Path(__file__).resolve().parent
    _spec = importlib.util.spec_from_file_location(_name, _dir / "__init__.py", submodule_search_locations=[str(_dir)])
    _package = importlib.util.module_from_spec(_spec)
    sys.modules[_name] = _package
    _spec.loader.exec_module(_package)

from hermes_companion_audio.voice_api import router
