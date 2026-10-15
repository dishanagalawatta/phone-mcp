"""USB-loopback helpers for Phone MCP (stdlib only, pure + testable).

Live-tested fact: ``adb forward tcp:8080 tcp:8080`` maps host 127.0.0.1:8080
to the phone's :8080 with the same TLS fingerprint + Bearer auth as LAN.
``adb reverse`` is wrong here (bind conflict on device:8080).

Pure functions below never spawn subprocesses (so unit tests stay hermetic);
only :func:`run_adb` shells out, and it is deliberately thin/mockable.
"""

from __future__ import annotations

import re
import subprocess
import urllib.parse

USB_DEFAULT_PORT = 8080
USB_LOOPBACK_HOSTS = ("127.0.0.1", "localhost", "::1")

_CONN_MARKERS = (
    "refused",
    "timed out",
    "timeout",
    "reset by peer",
    "connection reset",
    "closed",
    "unreachable",
    "no route to host",
    "failed to connect",
    "connection aborted",
    "broken pipe",
)

_BEARER_RE = re.compile(r"Bearer\s+[A-Za-z0-9._\-~+/=]+")
_TOKEN_KV_RE = re.compile(
    r"(token\s*[:=]\s*['\"]?)([A-Za-z0-9._\-~+/=]{8,})(['\"]?)", re.IGNORECASE
)


def _redact(text: str) -> str:
    """Strip bearer-looking secrets so hints never echo a token."""
    safe = _BEARER_RE.sub("Bearer <redacted>", str(text or ""))
    return _TOKEN_KV_RE.sub(r"\1<redacted>\3", safe)


def _loopback_host(host: str) -> bool:
    return (host or "").strip().lower().strip("[]") in USB_LOOPBACK_HOSTS


def is_loopback_url(url: str) -> bool:
    """True when *url* targets a loopback host (USB-forwarded or local)."""
    try:
        host = urllib.parse.urlparse(url or "").hostname or ""
    except Exception:
        return False
    return _loopback_host(host)


def port_of_url(url: str, default: int = USB_DEFAULT_PORT) -> int:
    try:
        port = urllib.parse.urlparse(url or "").port
    except ValueError:
        return default
    return port or default


def loopback_url_for_port(port: int | str = USB_DEFAULT_PORT) -> str:
    """Canonical USB endpoint for *port*: ``https://127.0.0.1:PORT/mcp``."""
    return f"https://127.0.0.1:{int(port)}/mcp"


def rewrite_url_to_loopback(url: str, port: int | str | None = None) -> str:
    """Rewrite *url*'s host to 127.0.0.1, keeping scheme/port/path.

    Already-loopback URLs are normalized, not double-rewritten. An empty or
    unparsable *url* falls back to :func:`loopback_url_for_port`.
    """
    try:
        parts = urllib.parse.urlparse(url or "")
    except Exception:
        parts = None
    if parts is None or not parts.hostname:
        return loopback_url_for_port(port or USB_DEFAULT_PORT)
    new_port = int(port) if port else (parts.port or USB_DEFAULT_PORT)
    scheme = parts.scheme if parts.scheme in ("https", "http") else "https"
    path = parts.path or "/mcp"
    rebuilt = parts._replace(scheme=scheme, netloc=f"127.0.0.1:{new_port}", path=path)
    return urllib.parse.urlunparse(rebuilt)


def parse_adb_devices(output: str) -> list[dict]:
    """Parse ``adb devices -l`` output into [{serial, state, detail}]."""
    devices: list[dict] = []
    for line in str(output or "").splitlines():
        stripped = line.strip()
        if not stripped or stripped.lower().startswith("list of devices"):
            continue
        if stripped.startswith("*"):
            continue
        parts = stripped.split()
        if len(parts) < 2:
            continue
        serial = parts[0]
        rest = [p.lower() for p in parts[1:]]
        if rest[:2] == ["no", "device"]:
            state, detail = "no device", " ".join(parts[3:])
        else:
            state, detail = rest[0], " ".join(parts[2:])
        devices.append({"serial": serial, "state": state, "detail": detail})
    return devices


def parse_adb_forward_list(output: str) -> list[dict]:
    """Parse ``adb forward --list`` output into [{serial, local, remote}]."""
    forwards: list[dict] = []
    for line in str(output or "").splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        parts = stripped.split()
        if len(parts) < 3:
            continue
        forwards.append({"serial": parts[0], "local": parts[1], "remote": parts[2]})
    return forwards


def adb_forward_args(serial: str, port: int | str = USB_DEFAULT_PORT) -> list[str]:
    """Argv for ``adb forward tcp:PORT tcp:PORT`` (scoped with -s when known)."""
    spec = f"tcp:{int(port)}"
    if (serial or "").strip():
        return ["adb", "-s", serial.strip(), "forward", spec, spec]
    return ["adb", "forward", spec, spec]


def adb_remove_args(serial: str, port: int | str = USB_DEFAULT_PORT) -> list[str]:
    """Argv to drop one ``adb forward`` rule again."""
    spec = f"tcp:{int(port)}"
    if (serial or "").strip():
        return ["adb", "-s", serial.strip(), "forward", "--remove", spec]
    return ["adb", "forward", "--remove", spec]


def usb_setup_hint(port: int | str = USB_DEFAULT_PORT) -> str:
    """One-line actionable hint for USB-loopback setup."""
    port = int(port)
    return (
        f"adb forward tcp:{port} tcp:{port}  # then "
        f"PHONE_MCP_URL=https://127.0.0.1:{port}/mcp (see scripts/usb-mcp.sh setup)"
    )


def pick_usb_device(
    devices: list[dict], serial: str = ""
) -> tuple[str, str]:
    """Pick the adb target for a USB forward (pure; no subprocess).

    Returns ``(chosen_serial, error)`` — exactly one is non-empty.

    - Explicit *serial* must be attached in ``device`` state, else an
      actionable error (unauthorized/offline/absent).
    - Without *serial*, a single ``device``-state attachment is auto-picked;
      zero usable or several usable both fail with a hint (``adb devices``,
      RSA prompt, ``--serial``).
    """
    wanted = (serial or "").strip()
    by_serial = {str(d.get("serial", "")): d for d in devices or []}
    if wanted:
        dev = by_serial.get(wanted)
        if dev is None:
            known = ", ".join(sorted(by_serial)) or "(none attached)"
            return "", (
                f"device '{wanted}' not attached (known: {known}) — "
                "check the USB cable / `adb devices`"
            )
        state = str(dev.get("state", "")).lower()
        if state == "device":
            return wanted, ""
        if state == "unauthorized":
            return "", (
                f"device '{wanted}' is unauthorized — accept the "
                "USB-debugging RSA prompt on the phone, then retry"
            )
        if state == "offline":
            return "", (
                f"device '{wanted}' is offline — replug USB / run "
                "`adb reconnect`, then retry"
            )
        return "", f"device '{wanted}' in state '{state}' — replug USB and retry"
    usable = [d for d in (devices or []) if str(d.get("state", "")).lower() == "device"]
    if len(usable) == 1:
        return str(usable[0].get("serial", "")), ""
    if not usable:
        if devices:
            rows = "; ".join(
                f"{d.get('serial', '?')} {d.get('state', '?')}" for d in devices
            )
            hint = f"no usable device (attached: {rows})"
            states = {str(d.get("state", "")).lower() for d in devices}
            if "unauthorized" in states:
                hint += " — accept the USB-debugging RSA prompt on the phone"
            if "offline" in states:
                hint += " — replug USB / run `adb reconnect`"
            return "", hint
        return "", "no adb devices attached — plug in USB + enable USB debugging"
    rows = ", ".join(str(d.get("serial", "?")) for d in usable)
    return "", (
        f"{len(usable)} devices in 'device' state ({rows}) — "
        "pass --serial/-s to pick one (see `adb devices -l`)"
    )


def actionable_usb_error(detail: str, url: str = "") -> str:
    """Actionable transport error; USB-aware when *url* is loopback.

    Never includes a token (bearer-looking secrets are redacted).
    """
    safe = _redact(detail)
    lowered = safe.lower()
    loopback = is_loopback_url(url or "")
    port = port_of_url(url) if url else USB_DEFAULT_PORT
    if "fingerprint mismatch" in lowered:
        return safe
    if "unauthorized" in lowered or "offline" in lowered:
        msg = (
            f"{safe} — phone is unauthorized/offline: run `adb devices`, "
            "accept the on-phone RSA authorization prompt, then retry"
        )
        if loopback:
            msg += f" ({usb_setup_hint(port)})"
        return msg
    if "certificate" in lowered or "self-signed" in lowered or "verify failed" in lowered:
        if loopback:
            return (
                f"{safe} — verify the dashboard SHA-256 once, then set "
                f"PHONE_MCP_SHA256 (USB loopback via `adb forward tcp:{port} "
                f"tcp:{port}` uses the same TLS fingerprint + auth as LAN)"
            )
        return (
            f"{safe} — verify the dashboard SHA-256 once, then set "
            "PHONE_MCP_SHA256 (or PHONE_MCP_INSECURE=1 on trusted LAN)"
        )
    if loopback and any(marker in lowered for marker in _CONN_MARKERS):
        return f"{safe} — USB loopback unreachable ({usb_setup_hint(port)})"
    if "timed out" in lowered or "timeout" in lowered:
        return safe + (
            " — tool budget exceeded (raise PHONE_MCP_TIMEOUT_MS/FAST_MS/MARGIN_MS "
            "if legitimate)"
        )
    if safe.lower().startswith("upstream unreachable:"):
        return safe
    return f"Upstream unreachable: {safe}"


def run_adb(args: list[str], timeout: float = 15.0) -> tuple[int, str, str]:
    """Thin subprocess wrapper for adb (mockable by the CLI/tests)."""
    try:
        proc = subprocess.run(  # noqa: S603 — argv comes from adb_*_args helpers
            list(args), capture_output=True, text=True, timeout=timeout
        )
    except FileNotFoundError:
        return 127, "", "adb not found on PATH (install Android platform-tools)"
    except subprocess.TimeoutExpired:
        return 124, "", f"adb timed out after {timeout:g}s"
    except Exception as exc:  # noqa: BLE001
        return 1, "", str(exc)
    return proc.returncode, proc.stdout or "", proc.stderr or ""
