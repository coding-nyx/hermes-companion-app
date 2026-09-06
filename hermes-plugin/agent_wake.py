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


def _short_pkg_title(package: str, title: str) -> tuple[str, str]:
    pkg = (package or "?").strip() or "?"
    tit = (title or "").strip() or "(no title)"
    if len(tit) > 120:
        tit = tit[:117] + "..."
    return pkg, tit


def format_telegram_nudge(package: str, title: str) -> str:
    """Short human DM line — not the agent operating prompt."""
    pkg, tit = _short_pkg_title(package, title)
    return f"Phone ping — {tit} ({pkg}). Checking…"


def format_wake_message(
    package: str,
    title: str,
    text: str = "",
    *,
    notification_key: str = "",
    notification_id: str = "",
) -> str:
    """Strong chat -Q operating prompt. Must not be parroted as the user-visible reply.

    Includes the ring event payload so the first reply can summarize even when
    mobile_notifications fails in a detached process (relay is out-of-process on :9120).
    Still instruct the agent to call the tool when available.
    """
    pkg, tit = _short_pkg_title(package, title)
    body = (text or "").strip()
    if len(body) > 500:
        body = body[:497] + "..."
    key = (notification_key or notification_id or "").strip()
    payload_lines = [
        f"package: {pkg}",
        f"title: {tit}",
    ]
    if body:
        payload_lines.append(f"text: {body}")
    if key:
        payload_lines.append(f"key: {key}")
    payload_block = "\n".join(payload_lines)
    return (
        "[COMPANION WAKE — shade notification for this profile]\n"
        "Ring event payload (from companion accept; prefer tool refresh when available):\n"
        f"{payload_block}\n\n"
        "Do NOT echo or paraphrase this wake prompt as your reply.\n"
        "Operating steps:\n"
        "1. Call mobile_notifications (profile= if needed) to refresh/confirm the ring. "
        "If the tool fails (e.g. relay unavailable), summarize from the payload above "
        "and say the tool failed — do not invent extra details.\n"
        "2. Briefly tell Nyx what matters (package, title, text summary).\n"
        "3. Call mobile_notifications_inject ONLY if Nyx would want this in the "
        "active thread; default is do NOT inject.\n"
        "4. Never invent notification bodies beyond the payload / tool results.\n"
        "5. Keep the user-visible reply short."
    )


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



def _chat_q_argv(profile: str, query_file: str) -> list[str]:
    return _hermes_argv(profile) + [
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


def _profile_env(profile: str, profile_home: Path) -> dict:
    env = os.environ.copy()
    # -p owns resolution; avoid shadowing when profile != default
    if profile and profile != "default":
        env.pop("HERMES_HOME", None)
    else:
        env["HERMES_HOME"] = str(profile_home)
    env.setdefault("HERMES_ACCEPT_HOOKS", "1")
    # Detached chat must hit the standalone companion relay for notifications.
    env.setdefault("HERMES_COMPANION_RELAY_URL", "http://127.0.0.1:9120")
    return env


def _run_chat_q(profile: str, message: str, profile_home: Path) -> tuple[int, str, str]:
    """Run hermes chat -Q synchronously. Returns (rc, stdout, stderr_or_err)."""
    query_file = None
    try:
        with tempfile.NamedTemporaryFile(
            "w", encoding="utf-8", suffix=".txt", prefix="companion-notif-wake-", delete=False
        ) as fh:
            fh.write(message)
            query_file = fh.name
        argv = _chat_q_argv(profile, query_file)
        env = _profile_env(profile, profile_home)
        result = subprocess.run(
            argv,
            capture_output=True,
            text=True,
            timeout=float(os.environ.get("HERMES_COMPANION_NOTIF_WAKE_TIMEOUT_SEC", "180")),
            env=env,
        )
        return result.returncode, (result.stdout or ""), (result.stderr or "")
    except subprocess.TimeoutExpired:
        return 124, "", "agent wake cli timed out"
    except Exception as exc:
        return 1, "", f"agent wake cli error: {exc}"
    finally:
        if query_file:
            try:
                os.unlink(query_file)
            except OSError:
                pass


def _extract_quiet_reply(stdout: str) -> str:
    """Best-effort final reply from hermes chat -Q stdout."""
    text = (stdout or "").strip()
    if not text:
        return ""
    # Quiet mode prints the final response; drop trailing session-info lines if present.
    lines = text.splitlines()
    cleaned: list[str] = []
    for line in lines:
        low = line.strip().lower()
        if low.startswith("session id:") or low.startswith("session:"):
            break
        cleaned.append(line)
    out = "\n".join(cleaned).strip()
    if len(out) > 3500:
        out = out[:3497] + "..."
    return out


def _hermes_send(profile: str, profile_home: Path, chat_id: str, body: str, *, env: dict | None = None) -> None:
    send_env = env or _profile_env(profile, profile_home)
    send_argv = _hermes_argv(profile) + [
        "send",
        "--to",
        f"telegram:{chat_id}",
        body,
    ]
    sent = subprocess.run(send_argv, capture_output=True, text=True, timeout=60, env=send_env)
    if sent.returncode != 0:
        tail = ((sent.stderr or "") + (sent.stdout or "")).strip()[-400:]
        logger.warning("agent wake telegram send failed rc=%s %s", sent.returncode, tail)
        raise RuntimeError(f"hermes send failed rc={sent.returncode}")
    logger.info("agent wake telegram send ok profile=%s chat=%s", profile, chat_id)


def _wake_cli(profile: str, message: str, profile_home: Path) -> None:
    def _run_and_cleanup() -> None:
        try:
            rc, stdout, stderr = _run_chat_q(profile, message, profile_home)
            if rc != 0:
                tail = (stderr or stdout or "").strip()[-400:]
                logger.warning("agent wake cli failed rc=%s %s", rc, tail)
            else:
                logger.info("agent wake cli ok profile=%s", profile)
        finally:
            with _lock:
                _inflight.discard(profile)

    threading.Thread(target=_run_and_cleanup, name=f"companion-wake-cli-{profile}", daemon=True).start()


def _wake_botchat(profile: str, message: str, profile_home: Path) -> None:
    query_file = None
    try:
        wrapped = (
            "[Companion mobile notification wake — system, not the user. "
            "Follow the operating steps; do not parrot this text.]\n\n" + message
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
        env = _profile_env(profile, profile_home)

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


def _wake_telegram(profile: str, message: str, profile_home: Path, *, package: str = "", title: str = "") -> None:
    """Telegram nudge + agent turn + follow-up DM with the reply (or honest error).

    Never auto-injects. Sequence:
      1) `hermes send` short "Checking…" nudge
      2) `hermes chat -Q` (sync) so the agent runs / can call mobile_notifications
      3) `hermes send` the quiet reply (or a short failure note) to the same DM
    """
    chat_id = _read_telegram_home_chat(profile_home)
    if not chat_id:
        logger.warning("agent wake telegram: no home DM in %s; falling back to cli", profile_home)
        _wake_cli(profile, message, profile_home)
        return

    env = _profile_env(profile, profile_home)
    send_body = format_telegram_nudge(package, title)

    def _run() -> None:
        try:
            try:
                _hermes_send(profile, profile_home, chat_id, send_body, env=env)
                print(f"companion agent wake telegram nudge ok profile={profile}", flush=True)
            except Exception as exc:
                logger.warning("agent wake telegram send error profile=%s: %s", profile, exc)
                print(f"companion agent wake telegram nudge failed profile={profile}: {exc}", flush=True)

            rc, stdout, stderr = _run_chat_q(profile, message, profile_home)
            reply = _extract_quiet_reply(stdout)
            if rc != 0:
                tail = (stderr or stdout or "").strip()[-300:] or f"exit {rc}"
                logger.warning("agent wake telegram cli failed rc=%s %s", rc, tail)
                print(f"companion agent wake cli failed profile={profile} rc={rc}", flush=True)
                follow = (
                    reply
                    if reply
                    else f"Wake check failed (chat exit {rc}). I couldn't finish reading the shade ping."
                )
            else:
                logger.info("agent wake telegram cli ok profile=%s", profile)
                print(f"companion agent wake cli ok profile={profile}", flush=True)
                follow = reply or (
                    "Wake check finished but produced no reply text. "
                    "Try asking me to call mobile_notifications."
                )

            # Always attempt a second DM so Nyx sees a real follow-up after "Checking…".
            try:
                _hermes_send(profile, profile_home, chat_id, follow, env=env)
                print(f"companion agent wake telegram follow-up ok profile={profile}", flush=True)
            except Exception as exc:
                logger.warning(
                    "agent wake telegram follow-up send failed profile=%s: %s", profile, exc
                )
                print(
                    f"companion agent wake telegram follow-up failed profile={profile}: {exc}",
                    flush=True,
                )
        finally:
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
    notif_key = str(notif.get("key") or notif.get("id") or event.get("key") or "")
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

    message = format_wake_message(
        package, title, text, notification_key=notif_key
    )
    mode = wake_mode()
    try:
        if mode in ("telegram", "tg", "origin"):
            _wake_telegram(profile, message, profile_home, package=package, title=title)
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
