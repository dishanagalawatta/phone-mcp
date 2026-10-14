"""TLS helpers: TOFU fingerprint pinning + verified CA-file mode.

Canonical logic shared by the client CLI and (by parity test) the
standalone ``bridges/`` twins. Modes:

- ``SHA256`` pin — accept the socket cert, hash-compare against the
  dashboard fingerprint (both legs authenticated via in-memory anchor).
- ``CAFILE`` — PEM from ``phone-mcp export-ca``; real chain verification
  against the pinned leaf; hostname check skipped (DHCP churn breaks SAN
  matching; the trust anchor IS the phone identity).
- pin + cafile combined (strongest).
- ``INSECURE=1`` — explicit trusted-LAN opt-out only.
- default — stock chain verification (fails closed on self-signed).
"""

from __future__ import annotations

import hashlib
import os
import ssl

ALPN_PROTOCOLS = ["http/1.1"]


def normalize_fingerprint(value: str | None) -> str:
    return "".join(c for c in (str(value or "")).lower() if c in "0123456789abcdef")


def grouped_fingerprint(value: str | None) -> str:
    h = normalize_fingerprint(value)
    return ":".join(h[i : i + 2] for i in range(0, len(h), 2)).upper() if h else ""


def pin_matches(der: bytes | None, pin: str) -> bool:
    if not der:
        return False
    return hashlib.sha256(der).hexdigest() == normalize_fingerprint(pin)


def tls_options(pin: str, insecure: bool, cafile: str = "") -> dict:
    """Pure TLS-mode resolution (unit-testable; bridges mirror it)."""
    has_pin = bool(normalize_fingerprint(pin))
    has_ca = bool(cafile)
    if insecure:
        return {"verify_mode": ssl.CERT_NONE, "check_hostname": False}
    if has_ca:
        return {
            "verify_mode": ssl.CERT_REQUIRED,
            "check_hostname": False,
            "cafile": cafile,
        }
    if has_pin:
        return {"verify_mode": ssl.CERT_NONE, "check_hostname": False}
    return {"verify_mode": ssl.CERT_REQUIRED, "check_hostname": True}


def ssl_context(pin: str, insecure: bool, cafile: str = "") -> ssl.SSLContext:
    """Build an SSLContext for the data leg (always offers ALPN http/1.1).

    The phone's Netty engine drops ALPN-less handshakes on first bytes (curl
    works only because it offers h2,http/1.1) — every TLS path must offer
    plain HTTP/1.1. Harmless against servers that ignore ALPN.
    """
    opts = tls_options(pin, insecure, cafile)
    ctx = ssl.create_default_context()
    ctx.check_hostname = opts["check_hostname"]
    ctx.verify_mode = opts["verify_mode"]
    ctx.set_alpn_protocols(list(ALPN_PROTOCOLS))
    if opts.get("cafile"):
        ctx.load_verify_locations(cafile=opts["cafile"])
    return ctx


def preflight_der(host: str, port: int, timeout_s: float) -> bytes | None:
    """Capture the presented leaf cert via a throwaway TLS handshake.

    CERT_NONE here is safe: the caller hash-compares the DER against the
    dashboard pin before trusting it (fail-closed TOFU).
    """
    import socket as _socket

    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    ctx.set_alpn_protocols(list(ALPN_PROTOCOLS))
    with _socket.create_connection((host, port), timeout=timeout_s) as sock:
        with ctx.wrap_socket(sock, server_hostname=host) as tls:
            return tls.getpeercert(binary_form=True)
    return None


def verify_pin_against_host(
    host: str, port: int, pin: str, timeout_s: float
) -> tuple[bytes | None, str]:
    """TOFU capture + hash-compare. Returns (der, actual_hex).

    Raises RuntimeError with an actionable message on mismatch so callers
    surface ``fingerprint mismatch`` (bridges map it to -32603).
    """
    der = preflight_der(host, port, timeout_s)
    actual = hashlib.sha256(der).hexdigest() if der else ""
    if not pin_matches(der or b"", pin):
        raise RuntimeError(
            "TLS fingerprint mismatch: expected "
            f"{grouped_fingerprint(pin)}, got {grouped_fingerprint(actual) or '(none)'}"
            " — re-verify against the dashboard (phone may have rotated its identity)"
        )
    return der, actual


def anchored_context(
    der: bytes, cafile: str = ""
) -> ssl.SSLContext:
    """Chain-verify the data leg against the TOFU-captured leaf (in-memory anchor)."""
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_REQUIRED
    ctx.set_alpn_protocols(list(ALPN_PROTOCOLS))
    if cafile:
        ctx.load_verify_locations(cafile=cafile)
    else:
        ctx.load_verify_locations(cadata=ssl.DER_cert_to_PEM_cert(der))
    return ctx


def export_ca_pem(
    host: str, port: int, pin: str, out_path: str, timeout_s: float = 15.0
) -> str:
    """Fail-closed leaf-cert export for verified direct-remote mode.

    Writes NOTHING unless the presented cert hashes to the dashboard pin.
    Returns the output path. Mirrors ``scripts/export-ca.sh``.
    """
    from . import tls as _self  # local import avoids cycles in docs

    _ = _self  # silence linters about unused alias
    der, _actual = verify_pin_against_host(host, port, pin, timeout_s)
    assert der, "unreachable: verify_pin_against_host raises on empty DER"
    pem = ssl.DER_cert_to_PEM_cert(der)
    parent = os.path.dirname(os.path.abspath(out_path))
    if parent:
        os.makedirs(parent, exist_ok=True)
    tmp = out_path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        fh.write(pem)
        if not pem.endswith("\n"):
            fh.write("\n")
    os.replace(tmp, out_path)
    return out_path
