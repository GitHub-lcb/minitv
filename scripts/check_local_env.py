#!/usr/bin/env python3
"""Verify the local MiniTV stack (Java service + MySQL + Redis).

Standard library only, so it runs with any Python 3.8+ interpreter without the
project virtualenv:

    python3 scripts/check_local_env.py

Stage-0 checks (MySQL, Redis, API /health) fail the result. Flutter / Android
tooling is reported separately and never fails stage 0.
"""

from __future__ import annotations

import json
import os
import shutil
import socket
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]


def load_env(path: Path) -> None:
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key, value)


def safe(text: str) -> str:
    """Keep console output printable on a non-UTF-8 Windows code page."""
    return text.encode("ascii", "replace").decode("ascii")


def check(name: str, ok: bool, detail: str) -> bool:
    print(f"[{'OK' if ok else 'MISSING'}] {safe(name)}: {safe(detail)}")
    return ok


def gate(name: str, ok: bool, detail: str) -> bool:
    print(f"[{'OK' if ok else 'DEFER'}] {safe(name)} (stage gate): {safe(detail)}")
    return ok


def port_open(host: str, port: int) -> bool:
    try:
        with socket.create_connection((host, port), timeout=3):
            return True
    except OSError:
        return False


def main() -> int:
    load_env(ROOT / ".env")
    failures = 0

    database_url = os.environ.get("DATABASE_URL", "")
    redis_url = os.environ.get("REDIS_URL", "")
    api_url = f"http://{os.environ.get('API_HOST', '127.0.0.1')}:{os.environ.get('API_PORT', '18110')}"

    if database_url:
        default_db = urlparse(database_url.replace("mysql+pymysql://", "mysql://"))
        host = default_db.hostname or "127.0.0.1"
        port = default_db.port or 3306
        failures += not check("MySQL", port_open(host, port), f"{host}:{port}")
    else:
        failures += not check("DATABASE_URL", False, "unset")

    if redis_url:
        default_redis = urlparse(redis_url)
        host = default_redis.hostname or "127.0.0.1"
        port = default_redis.port or 6379
        failures += not check("Redis", port_open(host, port), f"{host}:{port}")
    else:
        failures += not check("REDIS_URL", False, "unset")

    try:
        with urllib.request.urlopen(f"{api_url}/health", timeout=5) as response:
            payload = json.loads(response.read().decode())
        ok = payload.get("status") == "ok"
        failures += not check("API /health", ok, json.dumps(payload, ensure_ascii=False))
    except (urllib.error.URLError, ValueError) as exc:
        failures += not check("API /health", False, f"{api_url} unreachable ({exc})")

    print("--- stage 8 environment gates (do not fail stage 0) ---")
    flutter = shutil.which("flutter") or shutil.which("flutter.bat")
    if flutter:
        try:
            proc = subprocess.run([flutter, "--version"], capture_output=True, timeout=20)
            output = (proc.stdout or proc.stderr or b"").decode("utf-8", "replace")
            lines = [line for line in output.splitlines() if line.strip()]
            gate("Flutter", proc.returncode == 0, lines[0] if lines else flutter)
        except (subprocess.TimeoutExpired, OSError) as exc:
            gate("Flutter", False, f"version probe failed ({exc}) - only needed for the App")
    else:
        gate("Flutter", False, "not installed - only needed for the App")

    android_home = os.environ.get("ANDROID_HOME", "")
    android_ok = bool(android_home) and os.path.isabs(android_home) and Path(android_home).exists()
    adb = shutil.which("adb") or (str(Path(android_home) / "platform-tools" / "adb") if android_ok else "")
    gate("adb", bool(adb) and Path(adb).exists(), adb or "missing - only needed for the App")

    print("RESULT", "PASS" if failures == 0 else f"FAIL {failures}")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
