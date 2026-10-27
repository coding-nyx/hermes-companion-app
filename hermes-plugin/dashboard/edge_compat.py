"""In-process compatibility for Edge v1 clients, using pinned upstream v2 code."""
from functools import wraps
import importlib
import threading

_lock = threading.Lock()


def install():
    from tools import tts_tool
    original = tts_tool._import_edge_tts
    if getattr(original, "_companion_edge", False):
        return

    @wraps(original)
    def load():
        package = original()  # Retain Hermes PM's normal dependency ownership.
        with _lock:
            constants = importlib.import_module(package.__name__ + ".constants")
            if constants.SEC_MS_GEC_VERSION.startswith("1-"):
                from .edge_protocol.drm import DRM
                from .edge_protocol.constants import SEC_MS_GEC_VERSION
                # Both modules import these symbols by value. Update the protocol
                # adapter, not provider selection, credentials, endpoints or config.
                for name in ("communicate", "voices"):
                    module = importlib.import_module(package.__name__ + "." + name)
                    module.DRM = DRM
                    module.SEC_MS_GEC_VERSION = SEC_MS_GEC_VERSION
                constants.SEC_MS_GEC_VERSION = SEC_MS_GEC_VERSION
        return package
    load._companion_edge = True
    tts_tool._import_edge_tts = load
