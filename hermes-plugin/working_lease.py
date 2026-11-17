"""Keep an explicitly armed phone awake for the lifetime of its owning agent turn.

Turn hooks, rather than model/tool traffic, establish whether work is still running.
The worker only renews an existing phone-issued lease; it never calls device.arm.
Older Hermes runtimes without these hooks retain the phone's bounded arm timeout.
"""

from __future__ import annotations

import atexit
from dataclasses import dataclass, field
import json
import logging
import math
import threading
import time
from typing import Any, Callable
from uuid import UUID

logger = logging.getLogger(__name__)
Turn = tuple[str, str]


@dataclass
class _Turn:
    task_id: str = ""
    session_key: str = ""


@dataclass
class _Lease:
    lease_id: str
    device: str | None
    timeout: float
    next_renewal: float
    holders: set[Turn] = field(default_factory=set)
    failures: int = 0
    in_flight: bool = False


class WorkingLeaseManager:
    """One manager per plugin instance/profile; safe across concurrent session turns."""

    TERMINAL_ERRORS = frozenset({
        "disarmed", "lease_mismatch", "no_device", "unknown_device", "lane_down",
        "capability_denied", "privacy_active", "room_hands", "room_control_denied",
        "permission_denied", "invalid_args",
    })

    def __init__(
        self, broker, *, clock: Callable[[], float] = time.monotonic,
        interval: float = 30.0, max_failures: int = 3, autostart: bool = True,
        guard: Callable[[], None] | None = None,
    ):
        self.broker = broker
        self.clock = clock
        self.interval = max(0.01, float(interval))
        self.max_failures = max(1, int(max_failures))
        self.autostart = autostart
        self.guard = guard
        self._lock = threading.RLock()
        self._wake = threading.Event()
        self._worker: threading.Thread | None = None
        self._turns: dict[Turn, _Turn] = {}
        self._leases: dict[str, _Lease] = {}
        # Reject a late successful ARM result after its generation was already released.
        self._ended: dict[str, None] = {}
        self._registered = False
        self._closed = False
        atexit.register(self.close, release=False)

    @property
    def available(self) -> bool:
        return self._registered and not self._closed

    def register(self, ctx) -> bool:
        """Install real turn observers, or leave lifecycle management unavailable."""
        if self._registered:
            return True
        register = getattr(ctx, "register_hook", None)
        if not callable(register) or self._closed:
            return False
        try:
            for name, callback in (
                ("pre_llm_call", self._start),
                ("post_llm_call", self._finish),
                ("on_session_end", self._session_end),
                ("agent_loop_stopped", self._interrupted),
                ("post_tool_call", self._tool_finished),
            ):
                register(name, callback)
        except Exception:
            # Partially installed callbacks stay inert without this flag.
            logger.warning("Companion turn lease hooks unavailable")
            return False
        self._registered = True
        return True

    @staticmethod
    def _identity(context: dict[str, Any]) -> Turn | None:
        session = str(context.get("session_id") or "").strip()
        turn = str(context.get("turn_id") or "").strip()
        return (session, turn) if session and turn else None

    @staticmethod
    def _session_key() -> str:
        # Hooks run with Hermes' copied context. Do not use a process-global env var:
        # multiple sessions can be running in the same gateway process.
        try:
            from tools.approval_context import get_current_session_key
            return str(get_current_session_key(default="") or "")
        except (ImportError, AttributeError):
            return ""

    def _start(self, **context) -> None:
        identity = self._identity(context)
        if identity is None or not self.available:
            return
        with self._lock:
            self._turns[identity] = _Turn(
                task_id=str(context.get("task_id") or ""),
                session_key=str(context.get("session_key") or self._session_key()),
            )

    def _find_turn(self, context: dict[str, Any]) -> Turn | None:
        identity = self._identity(context)
        if identity is not None:
            return identity if identity in self._turns else None
        # Current Hermes handlers get session_id/task_id, while post_tool_call also
        # carries turn_id. Attach immediately only when these identify one live turn.
        session = str(context.get("session_id") or "")
        task = str(context.get("task_id") or "")
        if not session:
            return None
        matches = [key for key, turn in self._turns.items()
                   if key[0] == session and (not task or turn.task_id == task)]
        return matches[0] if len(matches) == 1 else None

    def observe_arm(self, params, result, context=None) -> bool:
        """Associate successful explicit ARM with its actual active turn, if known."""
        if not self.available or not isinstance(result, dict) or result.get("armed") is not True:
            return False
        raw_id = result.get("lease_id")
        try:
            lease_id = str(UUID(str(raw_id)))
            timeout = float(result.get("idle_timeout_seconds", 3600))
        except (ValueError, TypeError, AttributeError):
            return False
        if not math.isfinite(timeout) or timeout <= 0:
            return False
        params = params if isinstance(params, dict) else {}
        context = context if isinstance(context, dict) else {}
        # The phone's ID is stable even if the operator selects another default device.
        device = str(result.get("device_id") or params.get("device") or "").strip() or None
        with self._lock:
            identity = self._find_turn(context)
            if identity is None or lease_id in self._ended or self._closed:
                return False
            lease = self._leases.get(lease_id)
            if lease is None:
                lease = _Lease(lease_id, device, timeout, self.clock() + self._period(timeout))
                self._leases[lease_id] = lease
            elif device is not None:
                lease.device = device
            lease.holders.add(identity)
            lease.timeout = timeout
            self._ensure_worker()
            self._wake.set()
        return True

    def _tool_finished(self, tool_name=None, args=None, result=None, **context) -> None:
        if tool_name != "mobile_arm":
            return
        try:
            payload = json.loads(result) if isinstance(result, str) else result
            if not isinstance(payload, dict) or payload.get("ok") is False:
                return
            phone_result = payload.get("result", payload)
            self.observe_arm(args, phone_result, context)
        except (ValueError, TypeError):
            return

    def _period(self, timeout: float) -> float:
        # Even a short explicit lease must be refreshed well before OS/phone expiry.
        return min(self.interval, timeout / 3)

    def _ensure_worker(self) -> None:
        if not self.autostart or self._closed or self._worker is not None:
            return
        self._worker = threading.Thread(target=self._run, name="companion-working-lease", daemon=True)
        self._worker.start()

    def _run(self) -> None:
        while True:
            with self._lock:
                if self._closed or not self._leases:
                    self._worker = None
                    return
                delay = max(0.01, min(item.next_renewal for item in self._leases.values()) - self.clock())
                self._wake.clear()
            self._wake.wait(min(delay, self.interval))
            self.tick()

    @staticmethod
    def _args(lease: _Lease) -> dict[str, Any]:
        args: dict[str, Any] = {"lease_id": lease.lease_id}
        if lease.device is not None:
            args["device"] = lease.device
        return args

    def _remember_ended(self, lease_id: str) -> None:
        self._ended[lease_id] = None
        while len(self._ended) > 1024:
            self._ended.pop(next(iter(self._ended)))

    def tick(self) -> None:
        """Renew due leases; exposed so tests can advance a fake clock without sleeps."""
        with self._lock:
            if self._closed:
                return
            due = [lease for lease in self._leases.values()
                   if lease.holders and not lease.in_flight and lease.next_renewal <= self.clock()]
            for lease in due:
                lease.in_flight = True
        for lease in due:
            error = ""
            try:
                if self.guard is not None:
                    self.guard()
                result = self.broker.dispatch("device.arm.renew", self._args(lease))
                if not isinstance(result, dict) or result.get("armed") is not True:
                    error = "disarmed"
                elif result.get("lease_id") != lease.lease_id:
                    error = "lease_mismatch"
            except Exception as exc:
                error = str(getattr(exc, "code", "renew_failed"))
            with self._lock:
                if self._leases.get(lease.lease_id) is not lease:
                    continue
                lease.in_flight = False
                if error:
                    lease.failures += 1
                    if error in self.TERMINAL_ERRORS or lease.failures >= self.max_failures:
                        self._leases.pop(lease.lease_id, None)
                        self._remember_ended(lease.lease_id)
                        logger.info("Companion work lease stopped: %s", error)
                        continue
                else:
                    lease.failures = 0
                lease.next_renewal = self.clock() + self._period(lease.timeout)

    def _finish(self, **context) -> None:
        identity = self._identity(context)
        if identity is not None:
            self._finish_turns({identity})

    def _session_end(self, **context) -> None:
        identity = self._identity(context)
        if identity is not None:
            self._finish_turns({identity})
            return
        session = str(context.get("session_id") or "")
        if session:
            with self._lock:
                identities = {key for key in self._turns if key[0] == session}
            self._finish_turns(identities)

    def _interrupted(self, session_key=None, **_context) -> None:
        if not session_key:
            return
        with self._lock:
            identities = {key for key, turn in self._turns.items() if turn.session_key == session_key}
        self._finish_turns(identities)

    def _finish_turns(self, identities: set[Turn]) -> None:
        release = []
        with self._lock:
            for identity in identities:
                self._turns.pop(identity, None)
            for lease_id, lease in list(self._leases.items()):
                lease.holders.difference_update(identities)
                if not lease.holders:
                    self._leases.pop(lease_id)
                    self._remember_ended(lease_id)
                    release.append(lease)
            self._wake.set()
        for lease in release:
            try:
                # Conditional DISARM cannot stop a newer phone-local arm generation.
                self.broker.dispatch("device.disarm", self._args(lease))
            except Exception:
                # The bounded phone lease remains the fallback if its lane disappeared.
                logger.info("Companion work lease release unavailable")

    def close(self, *, release: bool = True) -> None:
        with self._lock:
            if self._closed:
                return
            self._closed = True
            self._wake.set()
            identities = set(self._turns)
            worker = self._worker
        if release:
            self._finish_turns(identities)
        else:
            with self._lock:
                self._turns.clear()
                self._leases.clear()
        if worker is not None and worker is not threading.current_thread():
            worker.join(timeout=1)
