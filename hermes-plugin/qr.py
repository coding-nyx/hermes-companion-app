"""QR code generation and terminal rendering for Hermes Companion pairing.

Zero external dependencies: uses pure Python qrcodegen module.
"""

from __future__ import annotations

import os
import socket
import subprocess
from typing import Optional
from urllib.parse import quote

try:
    from .qrcodegen import QrCode
except ImportError:
    try:
        from qrcodegen import QrCode
    except ImportError:
        QrCode = None


def detect_host_ip() -> str:
    """Best effort detection of reachable IP (Tailscale IPv4 first, then LAN IP)."""
    env = os.environ.get("HERMES_COMPANION_HOST") or os.environ.get("HERMES_HOST")
    if env:
        return env.strip()

    # 1. Tailscale IPv4
    try:
        out = subprocess.check_output(
            ["tailscale", "ip", "-4"],
            timeout=1.5,
            stderr=subprocess.DEVNULL,
        ).decode().strip()
        if out:
            candidate = out.splitlines()[0].strip()
            if candidate:
                return candidate
    except Exception:
        pass

    # 2. Local LAN interface IP via routing socket
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("1.1.1.1", 80))
            return s.getsockname()[0]
    except Exception:
        pass

    # 3. Hostname fallback
    try:
        h = socket.gethostname()
        if h and not h.startswith("localhost"):
            return socket.gethostbyname(h)
    except Exception:
        pass

    return "127.0.0.1"


def detect_host_origin(default_port: int = 9120) -> str:
    """Detect public/accessible relay origin for pairing."""
    env = os.environ.get("HERMES_COMPANION_ORIGIN")
    if env:
        return env.rstrip("/")

    relay_env = os.environ.get("HERMES_COMPANION_RELAY_URL")
    if relay_env and not any(loc in relay_env for loc in ("127.0.0.1", "localhost", "0.0.0.0")):
        return relay_env.rstrip("/")

    port = default_port
    if relay_env:
        try:
            from urllib.parse import urlparse
            p = urlparse(relay_env).port
            if p:
                port = p
        except Exception:
            pass

    ip = detect_host_ip()
    return f"http://{ip}:{port}"


def render_terminal_qr(text: str, border: int = 2) -> str:
    """Renders a QR code to a high-contrast ANSI string using unicode half-blocks (▀/▄)."""
    if QrCode is None:
        return ""

    qr = QrCode.encode_text(text, QrCode.Ecc.MEDIUM)
    size = qr.get_size()
    lines = []

    for y in range(-border, size + border, 2):
        row = []
        for x in range(-border, size + border):
            top_dark = qr.get_module(x, y) if (0 <= x < size and 0 <= y < size) else False
            y2 = y + 1
            bot_dark = qr.get_module(x, y2) if (0 <= x < size and 0 <= y2 < size) else False

            if top_dark and bot_dark:
                row.append("\033[30;40m \033[0m")
            elif top_dark and not bot_dark:
                row.append("\033[30;47m▀\033[0m")
            elif not top_dark and bot_dark:
                row.append("\033[30;47m▄\033[0m")
            else:
                row.append("\033[37;47m█\033[0m")
        lines.append("".join(row))

    return "\n".join(lines)


def pair_deep_link(origin: str, code: str, profile: str = "default") -> str:
    """`hermes-companion://pair` link, encoded exactly like the app's WakePolicy.pairDeepLink.

    Each value is percent-encoded so an origin carrying `&`, `#` or `?` survives the phone's
    query split instead of pairing against a truncated host.
    """

    def enc(v: str) -> str:
        return quote(v.strip(), safe="")

    return f"hermes-companion://pair?origin={enc(origin)}&code={enc(code.upper())}&profile={enc(profile)}"


def format_pair_card(origin: str, code: str, profile: str = "default", pre_approved: bool = True) -> str:
    """Formats a printable pairing card with terminal QR code and deep-link."""
    deep_link = pair_deep_link(origin, code, profile)
    qr_text = render_terminal_qr(deep_link)

    status_str = (
        "Pre-approved (Instant connect)"
        if pre_approved
        else f"Pending approval (run: hermes companion approve {code})"
    )

    parts = []
    parts.append("━" * 58)
    parts.append("  ⚡ HERMES COMPANION — INSTANT DEVICE PAIRING")
    parts.append("━" * 58)
    if qr_text:
        parts.append("")
        for ql in qr_text.splitlines():
            parts.append(f"  {ql}")
        parts.append("")
        parts.append("  ▲ Scan with phone camera, Google Lens, or Companion app")
        parts.append("")
    parts.append(f"  PAIRING CODE : \033[1;32m{code}\033[0m")
    parts.append(f"  HOST ORIGIN  : {origin}")
    parts.append(f"  PROFILE      : {profile}")
    parts.append(f"  STATUS       : {status_str}")
    parts.append("─" * 58)
    parts.append("  DEEP LINK (Tap or copy on phone):")
    parts.append(f"  \033[1;34m{deep_link}\033[0m")
    parts.append("━" * 58)

    return "\n".join(parts)
