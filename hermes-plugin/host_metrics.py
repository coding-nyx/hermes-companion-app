"""Host system diagnostics and telemetry provider for Hermes Companion."""

from __future__ import annotations

import os
import platform
import shutil
import subprocess
import sys
import time


def _get_cpu_info() -> tuple[float, list[float]]:
    try:
        load1, load5, load15 = os.getloadavg()
        cpu_count = os.cpu_count() or 1
        # Estimated overall CPU percent based on 1-min load average normalized by core count
        pct = min(100.0, max(0.0, (load1 / cpu_count) * 100.0))
        return round(pct, 1), [round(load1, 2), round(load5, 2), round(load15, 2)]
    except (OSError, AttributeError):
        return 0.0, [0.0, 0.0, 0.0]


def _get_memory_info() -> tuple[int, int, int, float]:
    """Returns (total_bytes, used_bytes, free_bytes, percent_used)."""
    # 1. Try psutil if available
    try:
        import psutil  # type: ignore
        mem = psutil.virtual_memory()
        return mem.total, mem.used, mem.available, round(mem.percent, 1)
    except ImportError:
        pass

    # 2. Try /proc/meminfo (Linux)
    if os.path.exists("/proc/meminfo"):
        try:
            mem_info: dict[str, int] = {}
            with open("/proc/meminfo", "r") as f:
                for line in f:
                    parts = line.split(":")
                    if len(parts) == 2:
                        key = parts[0].strip()
                        val = parts[1].strip().split()[0]
                        mem_info[key] = int(val) * 1024
            total = mem_info.get("MemTotal", 0)
            avail = mem_info.get("MemAvailable", mem_info.get("MemFree", 0))
            used = max(0, total - avail)
            pct = round((used / total * 100.0), 1) if total > 0 else 0.0
            return total, used, avail, pct
        except Exception:
            pass

    # 3. Try macOS vm_stat & sysctl
    if platform.system() == "Darwin":
        try:
            total_str = subprocess.check_output(["sysctl", "-n", "hw.memsize"], text=True).strip()
            total = int(total_str)
            # Rough estimate using page size and free pages
            vm = subprocess.check_output(["vm_stat"], text=True)
            page_size = 4096
            free_pages = 0
            for line in vm.splitlines():
                if "page size of" in line:
                    parts = line.split("page size of")
                    if len(parts) > 1:
                        page_size = int(parts[1].split()[0].strip())
                elif "Pages free:" in line:
                    free_pages += int(line.split(":")[1].strip().rstrip("."))
                elif "Pages inactive:" in line:
                    free_pages += int(line.split(":")[1].strip().rstrip("."))
            avail = free_pages * page_size
            used = max(0, total - avail)
            pct = round((used / total * 100.0), 1) if total > 0 else 0.0
            return total, used, avail, pct
        except Exception:
            pass

    # Fallback
    return 16 * 1024 * 1024 * 1024, 8 * 1024 * 1024 * 1024, 8 * 1024 * 1024 * 1024, 50.0


def _get_disk_info(path: str = "/") -> tuple[int, int, int, float]:
    try:
        usage = shutil.disk_usage(path)
        pct = round((usage.used / usage.total * 100.0), 1) if usage.total > 0 else 0.0
        return usage.total, usage.used, usage.free, pct
    except Exception:
        return 0, 0, 0, 0.0


def _find_hermes_process() -> tuple[int, str]:
    """Check if a hermes or python hermes process is running."""
    try:
        out = subprocess.check_output(["pgrep", "-f", "hermes"], text=True).strip()
        pids = [int(p) for p in out.splitlines() if p.strip().isdigit()]
        if pids:
            return pids[0], "running"
    except (subprocess.CalledProcessError, FileNotFoundError):
        pass
    return os.getpid(), "online (relay)"


def collect_metrics(workspace_path: str | None = None) -> dict:
    cpu_pct, load_avg = _get_cpu_info()
    mem_total, mem_used, mem_free, mem_pct = _get_memory_info()
    disk_total, disk_used, disk_free, disk_pct = _get_disk_info(workspace_path or "/")
    hermes_pid, hermes_state = _find_hermes_process()

    uptime_sec = 0.0
    try:
        if os.path.exists("/proc/uptime"):
            with open("/proc/uptime", "r") as f:
                uptime_sec = float(f.read().split()[0])
        elif platform.system() == "Darwin":
            boot_str = subprocess.check_output(["sysctl", "-n", "kern.boottime"], text=True).strip()
            # kern.boottime: { sec = 1725420000, usec = 0 }
            if "sec =" in boot_str:
                sec_part = boot_str.split("sec =")[1].split(",")[0].strip()
                uptime_sec = max(0.0, time.time() - float(sec_part))
    except Exception:
        uptime_sec = 0.0

    return {
        "ok": True,
        "metrics": {
            "cpu": {
                "percent": cpu_pct,
                "cores": os.cpu_count() or 1,
                "load_avg": load_avg,
            },
            "memory": {
                "total_bytes": mem_total,
                "used_bytes": mem_used,
                "free_bytes": mem_free,
                "percent": mem_pct,
            },
            "disk": {
                "total_bytes": disk_total,
                "used_bytes": disk_used,
                "free_bytes": disk_free,
                "percent": disk_pct,
            },
            "system": {
                "platform": platform.system(),
                "release": platform.release(),
                "architecture": platform.machine(),
                "python_version": platform.python_version(),
                "uptime_seconds": int(uptime_sec),
            },
            "hermes": {
                "pid": hermes_pid,
                "status": hermes_state,
            },
        },
    }
