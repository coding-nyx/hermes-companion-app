"""Wake a Hermes profile when a shade notification lands in the companion ring.

Never auto-calls mobile_notifications_inject. Configurable; default ON.
"""

from __future__ import annotations

import json
import logging
import os
import shutil
import sys
import subprocess
import tempfile
import threading
import time
from pathlib import Path
from typing import Callable

logger = logging.getLogger("hermes.companion.agent_wake")

_lock = threading.Lock()
_last_wake_at: dict[str, float] = {}
_inflight: set[str] = set()


def wake_enabled() -> bool:
    raw = os.environ.get("HERMES_COMPANION_NOTIF_WAKE", "1").strip().lower()
    return raw not in ("0", "false", "no", "off")


def wake_mode() -> str:
    # telegram: one-shot cron deliver to allowlisted home DM (agent turn + origin delivery) — default
    # cli: hermes chat -Q (agent turn; no Telegram delivery)
    # botchat: inject into profile Bot Chat as inbound turn
    return (os.environ.get("HERMES_COMPANION_NOTIF_WAKE_MODE") or "telegram").strip().lower()


def wake_debounce_sec() -> float:
    try:
        return max(0.0, float(os.environ.get("HERMES_COMPANION_NOTIF_WAKE_DEBOUNCE_SEC", "20")))
    except (TypeError, ValueError):
        return 20.0


def wake_skip_packages() -> set[str]:
    """Packages that still enter the ring but must not wake (avoid Telegram self-echo loops)."""
    raw = os.environ.get(
        "HERMES_COMPANION_NOTIF_WAKE_SKIP_PACKAGES",
        "org.telegram.messenger,org.telegram.messenger.web,org.telegram.messenger.beta,"
        "com.telegram.messenger,app.hermes.companion",
    )
    return {p.strip() for p in raw.split(",") if p.strip()}


def profiles_root() -> Path:
    override = os.environ.get("HERMES_COMPANION_PROFILES_ROOT", "").strip()
    if override:
        return Path(override).expanduser()
    home = os.environ.get("HERMES_HOME", "").strip()
    if home:
        p = Path(home).expanduser()
        # When companion runs under default ~/.hermes, profiles live in profiles/.
        cand = p / "profiles"
        if cand.is_dir():
            return cand
        # When companion is wrongly pointed at a profile home, walk up.
        if p.name != "profiles" and (p.parent / "profiles").is_dir() and p.parent.name == ".hermes":
            return p.parent / "profiles"
        if (p.parent / "profiles").is_dir():
            return p.parent / "profiles"
    return Path.home() / ".hermes" / "profiles"


def resolve_profile_home(profile: str) -> Path | None:
    profile = (profile or "").strip()
    if not profile or profile == "default":
        # default profile uses HERMES_HOME root when not using profiles/default
        root = profiles_root()
        direct = root / "default"
        if direct.is_dir():
            return direct
        home = os.environ.get("HERMES_HOME", "").strip()
        if home:
            return Path(home).expanduser()
        return Path.home() / ".hermes"
    path = profiles_root() / profile
    return path if path.is_dir() else None


def format_wake_message(package: str, title: str, text: str = "") -> str:
    pkg = (package or "?").strip() or "?"
    tit = (title or "").strip() or "(no title)"
    if len(tit) > 120:
        tit = tit[:117] + "..."
    hint = (
        "Call mobile_notifications for details. "
        "Only call mobile_notifications_inject if Nyx would want this in the active chat; "
        "never dump every shade event; never auto-inject."
    )
    return f"mobile notif: {pkg} · {tit}\n{hint}"


def _resolve_hermes_bin() -> str | None:
    override = os.environ.get("HERMES_BIN", "").strip()
    if override and Path(override).exists():
        return override
    found = shutil.which("hermes")
    if found:
        return found
    home = Path.home()
    candidates = [
        home / ".local/bin/hermes",
        home / ".hermes/hermes-agent/venv/bin/hermes",
        home / ".hermes/hermes-agent/.venv/bin/hermes",
        Path("/home/nyx/.local/bin/hermes"),
        Path("/home/nyx/.hermes/hermes-agent/venv/bin/hermes"),
        Path("/home/nyx/.hermes/hermes-agent/.venv/bin/hermes"),
    ]
    for cand in candidates:
        if cand.is_file() and os.access(cand, os.X_OK):
            return str(cand)
    return None



def _hermes_argv(profile: str) -> list[str]:
    hermes_bin = _resolve_hermes_bin()
    if hermes_bin:
        argv = [hermes_bin]
    else:
        py = os.environ.get("HERMES_PYTHON", "").strip() or os.environ.get("PYTHON", "").strip()
        if not py:
            for cand in (
                Path.home() / ".hermes/hermes-agent/.venv/bin/python",
                Path.home() / ".hermes/hermes-agent/venv/bin/python",
                Path("/home/nyx/.hermes/hermes-agent/.venv/bin/python"),
            ):
                if cand.is_file():
                    py = str(cand)
                    break
        if not py:
            import sys
            py = sys.executable or "python3"
        argv = [py, "-m", "hermes_cli.main"]
    if profile and profile != "default":
        argv += ["-p", profile]
    return argv

def _read_telegram_home_chat(profile_home: Path) -> str | None:
    path = profile_home / "channel_directory.json"
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    rows = (data.get("platforms") or {}).get("telegram") or []
    for row in rows:
        if not isinstance(row, dict):
            continue
        if str(row.get("type") or "").lower() == "dm" and row.get("id"):
            return str(row["id"])
    for row in rows:
        if isinstance(row, dict) and row.get("id"):
            return str(row["id"])
    return None



def _wake_cli(profile: str, message: str, profile_home: Path) -> None:
    query_file = None
    try:
        with tempfile.NamedTemporaryFile(
            "w", encoding="utf-8", suffix=".txt", prefix="companion-notif-wake-", delete=False
        ) as fh:
            fh.write(message)
            query_file = fh.name
        argv = _hermes_argv(profile) + [
            "chat",
            "-Q",
            "--oneshot",
            "--query-file",
            query_file,
            "--source",
            "companion-notif",
            "--max-turns",
            os.environ.get("HERMES_COMPANION_NOTIF_WAKE_MAX_TURNS", "8"),
            "--run-budget",
            os.environ.get("HERMES_COMPANION_NOTIF_WAKE_RUN_BUDGET", "120"),
            "--accept-hooks",
        ]
        env = os.environ.copy()
        # -p owns resolution; avoid shadowing when profile != default
        if profile and profile != "default":
            env.pop("HERMES_HOME", None)
        else:
            env["HERMES_HOME"] = str(profile_home)
        env.setdefault("HERMES_ACCEPT_HOOKS", "1")

        def _run_and_cleanup() -> None:
            try:
                _spawn_sync = subprocess.run
                result = _spawn_sync(
                    argv,
                    capture_output=True,
                    text=True,
                    timeout=float(os.environ.get("HERMES_COMPANION_NOTIF_WAKE_TIMEOUT_SEC", "180")),
                    env=env,
                )
                if result.returncode != 0:
                    tail = (result.stderr or result.stdout or "").strip()[-400:]
                    logger.warning("agent wake cli failed rc=%s %s", result.returncode, tail)
                else:
                    logger.info("agent wake cli ok profile=%s", profile)
            except subprocess.TimeoutExpired:
                logger.warning("agent wake cli timed out profile=%s", profile)
            except Exception as exc:
                logger.warning("agent wake cli error profile=%s: %s", profile, exc)
            finally:
                if query_file:
                    try:
                        os.unlink(query_file)
                    except OSError:
                        pass
                with _lock:
                    _inflight.discard(profile)

        threading.Thread(target=_run_and_cleanup, name=f"companion-wake-cli-{profile}", daemon=True).start()
    except Exception:
        if query_file:
            try:
                os.unlink(query_file)
            except OSError:
                pass
        raise


def _wake_botchat(profile: str, message: str, profile_home: Path) -> None:
    query_file = None
    try:
        wrapped = (
            "[Companion mobile notification wake — not the user. "
            "Review, call mobile_notifications, inject only if warranted.]\n\n" + message
        )
        with tempfile.NamedTemporaryFile(
            "w", encoding="utf-8", suffix=".txt", prefix="companion-notif-botchat-", delete=False
        ) as fh:
            fh.write(wrapped)
            query_file = fh.name
        argv = _hermes_argv(profile) + [
            "chat",
            "--in",
            "~",
            "-c",
            "Bot Chat",
            "--create-if-missing",
            "-Q",
            "--query-file",
            query_file,
            "--accept-hooks",
            "--max-turns",
            os.environ.get("HERMES_COMPANION_NOTIF_WAKE_MAX_TURNS", "8"),
            "--run-budget",
            os.environ.get("HERMES_COMPANION_NOTIF_WAKE_RUN_BUDGET", "120"),
        ]
        env = os.environ.copy()
        if profile and profile != "default":
            env.pop("HERMES_HOME", None)
        else:
            env["HERMES_HOME"] = str(profile_home)
        env.setdefault("HERMES_ACCEPT_HOOKS", "1")

        def _run_and_cleanup() -> None:
            try:
                result = subprocess.run(
                    argv,
                    capture_output=True,
                    text=True,
                    timeout=float(os.environ.get("HERMES_COMPANION_NOTIF_WAKE_TIMEOUT_SEC", "180")),
                    env=env,
                )
                if result.returncode != 0:
                    tail = (result.stderr or result.stdout or "").strip()[-400:]
                    logger.warning("agent wake botchat failed rc=%s %s", result.returncode, tail)
                else:
                    logger.info("agent wake botchat ok profile=%s", profile)
            except Exception as exc:
                logger.warning("agent wake botchat error profile=%s: %s", profile, exc)
            finally:
                if query_file:
                    try:
                        os.unlink(query_file)
                    except OSError:
                        pass
                with _lock:
                    _inflight.discard(profile)

        threading.Thread(target=_run_and_cleanup, name=f"companion-wake-botchat-{profile}", daemon=True).start()
    except Exception:
        if query_file:
            try:
                os.unlink(query_file)
            except OSError:
                pass
        raise


def _wake_telegram(profile: str, message: str, profile_home: Path) -> None:
    """Immediate Telegram nudge + agent turn (chat -Q). Never auto-injects.

    Cron one-shots only accept minute+ delays (`in 1m`), so we:
      1) `hermes send` a short line to the home DM (visible now)
      2) `hermes chat -Q` so the agent actually runs and can call mobile_notifications
    """
    chat_id = _read_telegram_home_chat(profile_home)
    if not chat_id:
        logger.warning("agent wake telegram: no home DM in %s; falling back to cli", profile_home)
        _wake_cli(profile, message, profile_home)
        return

    env = os.environ.copy()
    if profile and profile != "default":
        env.pop("HERMES_HOME", None)
    else:
        env["HERMES_HOME"] = str(profile_home)
    env.setdefault("HERMES_ACCEPT_HOOKS", "1")

    # Keep telegram text short; full tool hint stays in the chat -Q prompt.
    first_line = (message.splitlines() or [""])[0].strip() or "mobile notif"
    send_body = first_line + "\n(call mobile_notifications — never auto-inject)"

    def _run() -> None:
        try:
            send_argv = _hermes_argv(profile) + [
                "send",
                "--to",
                f"telegram:{chat_id}",
                send_body,
            ]
            sent = subprocess.run(send_argv, capture_output=True, text=True, timeout=60, env=env)
            if sent.returncode != 0:
                tail = ((sent.stderr or "") + (sent.stdout or "")).strip()[-400:]
                logger.warning("agent wake telegram send failed rc=%s %s", sent.returncode, tail)
            else:
                logger.info("agent wake telegram send ok profile=%s chat=%s", profile, chat_id)
        except Exception as exc:
            logger.warning("agent wake telegram send error profile=%s: %s", profile, exc)
        # Agent turn regardless of send outcome (tools still useful).
        try:
            _wake_cli(profile, message, profile_home)
        except Exception as exc:
            logger.warning("agent wake telegram cli follow-up failed profile=%s: %s", profile, exc)
            with _lock:
                _inflight.discard(profile)

    threading.Thread(target=_run, name=f"companion-wake-tg-{profile}", daemon=True).start()



def maybe_wake_for_notification(event: dict, *, now: Callable[[], float] | None = None) -> bool:
    """Schedule a profile wake for a ring-accepted notification event. Returns True if scheduled."""
    if not wake_enabled():
        return False
    if not isinstance(event, dict):
        return False
    profile = str(event.get("profile") or "").strip()
    if not profile:
        return False
    notif = event.get("notification") if isinstance(event.get("notification"), dict) else {}
    package = str(notif.get("package") or event.get("package") or "")
    title = str(notif.get("title") or event.get("title") or "")
    text = str(notif.get("text") or event.get("text") or "")
    if package in wake_skip_packages():
        logger.debug("agent wake skip package=%s profile=%s", package, profile)
        return False
    if not package and not title and not text:
        return False

    clock = now or time.time
    debounce = wake_debounce_sec()
    with _lock:
        last = _last_wake_at.get(profile, 0.0)
        if debounce > 0 and (clock() - last) < debounce:
            return False
        if profile in _inflight:
            return False
        _last_wake_at[profile] = clock()
        _inflight.add(profile)

    profile_home = resolve_profile_home(profile)
    if profile_home is None:
        logger.warning("agent wake: profile home missing for %s", profile)
        with _lock:
            _inflight.discard(profile)
        return False

    message = format_wake_message(package, title, text)
    mode = wake_mode()
    try:
        if mode in ("telegram", "tg", "origin"):
            _wake_telegram(profile, message, profile_home)
        elif mode in ("botchat", "bot-chat", "bot_chat"):
            _wake_botchat(profile, message, profile_home)
        else:
            _wake_cli(profile, message, profile_home)
        print(f"companion agent wake scheduled mode={mode} profile={profile} pkg={package}", flush=True)
        return True
    except Exception as exc:
        logger.warning("agent wake schedule failed profile=%s: %s", profile, exc)
        with _lock:
            _inflight.discard(profile)
        return False


# test helpers
def _reset_wake_state_for_tests() -> None:
    with _lock:
        _last_wake_at.clear()
        _inflight.clear()
