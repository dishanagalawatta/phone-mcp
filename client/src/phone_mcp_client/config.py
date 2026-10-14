"""Client configuration: env/QR/file loading + validation.

Tokens stay in env vars / the local paired config file — never baked into
generated harness configs (harness files only carry ``{env:...}`` refs).
"""

from __future__ import annotations

import dataclasses
import json
import os
import urllib.parse

from . import tls as _tls

DEFAULT_PROTOCOL = "2025-06-18"
DEFAULT_TIMEOUT_MS = 35000
DEFAULT_FAST_MS = 15000
DEFAULT_MARGIN_MS = 10000

CONFIG_ENV = ("PHONE_MCP_URL", "PHONE_MCP_TOKEN", "PHONE_MCP_SHA256")
PAIRED_CONFIG_DEFAULT = os.path.join(
    os.path.expanduser("~"), ".config", "phone-mcp", "config.json"
)


@dataclasses.dataclass
class ClientConfig:
    """Connection + budget configuration for one phone."""

    url: str = ""
    token: str = ""
    sha256: str = ""  # normalized hex (no colons, lowercase)
    cafile: str = ""
    insecure: bool = False
    protocol: str = DEFAULT_PROTOCOL
    timeout_ms: int = DEFAULT_TIMEOUT_MS
    fast_ms: int = DEFAULT_FAST_MS
    margin_ms: int = DEFAULT_MARGIN_MS

    def validate(self) -> list[str]:
        """Return a list of human-readable problems (empty == valid)."""
        problems: list[str] = []
        if not self.url:
            problems.append("missing PHONE_MCP_URL (e.g. https://192.168.1.50:8080/mcp)")
        else:
            try:
                parts = urllib.parse.urlparse(self.url)
            except Exception:
                problems.append(f"bad PHONE_MCP_URL {self.url!r}")
            else:
                if parts.scheme not in ("https", "http"):
                    problems.append("PHONE_MCP_URL must be https:// (http:// loopback only)")
                elif parts.scheme == "http" and (parts.hostname or "").lower() not in (
                    "localhost",
                    "127.0.0.1",
                    "::1",
                ):
                    problems.append("http:// only allowed for loopback (phone serves TLS-only)")
                if parts.scheme == "https" and not parts.hostname:
                    problems.append(f"bad PHONE_MCP_URL {self.url!r}")
        if not self.token:
            problems.append("missing PHONE_MCP_TOKEN (dashboard pairing card / QR)")
        if self.sha256 and len(self.sha256) != 64:
            problems.append(
                "PHONE_MCP_SHA256 must be 64 hex chars (dashboard fingerprint, colons ok)"
            )
        if self.cafile and not os.path.exists(self.cafile):
            problems.append(f"PHONE_MCP_CAFILE not found: {self.cafile}")
        if self.timeout_ms <= 0 or self.fast_ms <= 0 or self.margin_ms < 0:
            problems.append("timeout budgets must be positive (margin may be 0)")
        return problems

    @property
    def host(self) -> str:
        return urllib.parse.urlparse(self.url).hostname or ""

    @property
    def port(self) -> int:
        parts = urllib.parse.urlparse(self.url)
        try:
            return parts.port or (443 if parts.scheme == "https" else 80)
        except ValueError:
            return 443

    def redacted(self) -> dict:
        """Log-safe view (token never included)."""
        pin = self.sha256[:12] + "…" if self.sha256 else "none"
        return {
            "url": self.url,
            "pin": f"sha256:{pin}" if self.sha256 else "none",
            "ca": self.cafile or "none",
            "insecure": self.insecure,
            "proto": self.protocol,
        }


def _ms(env: dict, name: str, default: int) -> int:
    try:
        return int(float(env.get(name, str(default))))
    except (TypeError, ValueError):
        return default


def from_env(env: dict | None = None) -> ClientConfig:
    """Build a config from a mapping (defaults to ``os.environ``)."""
    e = dict(os.environ) if env is None else dict(env)
    return ClientConfig(
        url=e.get("PHONE_MCP_URL", "").strip(),
        token=e.get("PHONE_MCP_TOKEN", ""),
        sha256=_tls.normalize_fingerprint(e.get("PHONE_MCP_SHA256", "")),
        cafile=e.get("PHONE_MCP_CAFILE", "").strip(),
        insecure=e.get("PHONE_MCP_INSECURE", "") == "1",
        protocol=e.get("PHONE_MCP_PROTOCOL", DEFAULT_PROTOCOL).strip() or DEFAULT_PROTOCOL,
        timeout_ms=_ms(e, "PHONE_MCP_TIMEOUT_MS", DEFAULT_TIMEOUT_MS),
        fast_ms=_ms(e, "PHONE_MCP_FAST_MS", DEFAULT_FAST_MS),
        margin_ms=_ms(e, "PHONE_MCP_MARGIN_MS", DEFAULT_MARGIN_MS),
    )


def parse_qr_payload(raw: str) -> dict:
    """Parse the dashboard QR envelope ``{"url","token","sha256"}``.

    Raises ValueError on missing fields / non-TLS URL / bad fingerprint, so
    callers fail closed instead of pinning blind.
    """
    try:
        obj = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ValueError(f"QR payload is not valid JSON: {exc}") from exc
    if not isinstance(obj, dict):
        raise ValueError("QR payload must be a JSON object")
    url = str(obj.get("url", "")).strip()
    token = str(obj.get("token", ""))
    sha = _tls.normalize_fingerprint(str(obj.get("sha256", "")))
    if not url or not token or not sha:
        raise ValueError("QR payload must contain url, token, sha256")
    if not url.startswith("https://"):
        raise ValueError("QR url must be https://")
    if len(sha) != 64:
        raise ValueError("QR sha256 must be 64 hex chars")
    return {"url": url, "token": token, "sha256": sha}


def from_qr(raw: str, env: dict | None = None) -> ClientConfig:
    """Build a config from a QR payload, keeping env budgets/TLS overrides."""
    parsed = parse_qr_payload(raw)
    base = from_env(env)
    base.url = parsed["url"]
    base.token = parsed["token"]
    base.sha256 = parsed["sha256"]
    return base


def load_paired_config(path: str = PAIRED_CONFIG_DEFAULT) -> ClientConfig:
    """Load the local paired config file (written by ``phone-mcp pair``)."""
    with open(path, encoding="utf-8") as fh:
        obj = json.load(fh)
    cfg = from_env({})
    cfg.url = str(obj.get("url", ""))
    cfg.token = str(obj.get("token", ""))
    cfg.sha256 = _tls.normalize_fingerprint(str(obj.get("sha256", "")))
    cfg.cafile = str(obj.get("cafile", ""))
    return cfg


def save_paired_config(cfg: ClientConfig, path: str = PAIRED_CONFIG_DEFAULT) -> str:
    """Persist pairing values with 0600 perms (token lives here, not in harness files)."""
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    payload = {
        "url": cfg.url,
        "token": cfg.token,
        "sha256": cfg.sha256,
        "cafile": cfg.cafile,
    }
    # O_CREAT|O_WRONLY|O_TRUNC with 0600, then write — no world-readable window.
    fd = os.open(path, os.O_CREAT | os.O_WRONLY | os.O_TRUNC, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, indent=2)
            fh.write("\n")
    except BaseException:
        raise
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass
    return path
