"""`phone-mcp` CLI — one client for every harness.

Subcommands:
  serve       stdio bridge (harness ↔ phone Streamable HTTP)
  smoke       4-probe health check (alias: doctor)
  usb         USB loopback via `adb forward` (setup|status|remove|check)
  pair        validate dashboard QR/values, save ~/.config/phone-mcp/config.json (0600)
  configure   render (+ optionally install) harness configs
  list        tools/list
  call        tools/call <name>
  media       GET /media/{id} → file
  export-ca   fail-closed leaf-cert export for verified direct-remote mode
"""

from __future__ import annotations

import argparse
import json
import os
import sys

from . import __version__, config as _config, harnesses as _h, protocol as _p, tls as _tls
from . import usb as _usb

EXIT_OK, EXIT_FAIL, EXIT_USAGE = 0, 1, 2


def _add_conn_flags(parser: argparse.ArgumentParser) -> None:
    """Accept connection flags after the subcommand too (SUPPRESS keeps globals)."""
    parser.add_argument("--url", default=argparse.SUPPRESS,
                        help="phone endpoint (default: $PHONE_MCP_URL)")
    parser.add_argument("--token", default=argparse.SUPPRESS,
                        help="bearer token (default: $PHONE_MCP_TOKEN)")
    parser.add_argument("--sha256", default=argparse.SUPPRESS,
                        help="pinned fingerprint (default: $PHONE_MCP_SHA256)")
    parser.add_argument("--cafile", default=argparse.SUPPRESS,
                        help="PEM from export-ca (default: $PHONE_MCP_CAFILE)")
    parser.add_argument("--insecure", action="store_true", default=argparse.SUPPRESS,
                        help="skip TLS verify (trusted LAN only)")
    parser.add_argument("--usb", action="store_true", default=argparse.SUPPRESS,
                        help="use USB loopback (adb forward tcp:PORT tcp:PORT → https://127.0.0.1:PORT/mcp)")


def _build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(
        prog="phone-mcp",
        description="Unified MCP client for Phone MCP (opencode, claude, codex, vscode, cursor, gemini, …)",
    )
    ap.add_argument("--version", action="store_true", help="print version and exit")
    ap.add_argument("--url", default="", help="phone endpoint (default: $PHONE_MCP_URL)")
    ap.add_argument("--token", default="", help="bearer token (default: $PHONE_MCP_TOKEN)")
    ap.add_argument("--sha256", default="", help="pinned fingerprint (default: $PHONE_MCP_SHA256)")
    ap.add_argument("--cafile", default="", help="PEM from export-ca (default: $PHONE_MCP_CAFILE)")
    ap.add_argument("--insecure", action="store_true", help="skip TLS verify (trusted LAN only)")
    ap.add_argument("--usb", action="store_true",
                    help="use USB loopback (rewrite URL host to 127.0.0.1, keeping port)")
    ap.add_argument("--timeout-ms", type=float, default=None)
    ap.add_argument("--verbose", "-v", action="store_true")

    sub = ap.add_subparsers(dest="cmd")

    sv = sub.add_parser("serve", help="stdio bridge: harness (stdin/stdout) ↔ phone HTTPS")
    _add_conn_flags(sv)

    sm = sub.add_parser("smoke", help="4-probe health check (initialize/list/battery/device_info)")
    sm.add_argument("--timeout", type=float, default=20.0, help="per-probe seconds")
    _add_conn_flags(sm)
    doc = sub.add_parser("doctor", help="alias for smoke")
    doc.add_argument("--timeout", type=float, default=20.0, dest="timeout")
    _add_conn_flags(doc)

    pr = sub.add_parser("pair", help="validate pairing values and save them (0600)")
    pr.add_argument("--qr", default="", help="dashboard QR JSON {\"url\",\"token\",\"sha256\"}")
    pr.add_argument("--out", default=_config.PAIRED_CONFIG_DEFAULT)
    pr.add_argument("--print-env", action="store_true", help="print export lines (token included)")
    _add_conn_flags(pr)

    cf = sub.add_parser("configure", help="render/install harness configs")
    cf.add_argument("--harness", default="all",
                    help="all or space/comma-separated: opencode claude codex vscode cursor gemini cline windsurf generic")
    cf.add_argument("--bridge", default="phone-mcp", choices=["phone-mcp", "node", "python"])
    cf.add_argument("--out", default="./generated-harness")
    cf.add_argument("--install", action="store_true", help="write into harness config paths")
    cf.add_argument("--print-only", action="store_true")
    cf.add_argument("--cwd", default=".", help="project root for project-scoped installs")
    cf.add_argument("--no-skills", action="store_true",
                    help="skip staging the 6 phone-mcp agent skills (staged by default)")
    cf.add_argument("--skills-dir", default="",
                    help="stage skills here (default: <out>/skills)")
    _add_conn_flags(cf)

    lp = sub.add_parser("list", help="list phone tools")
    lp.add_argument("--json", action="store_true", dest="as_json")
    _add_conn_flags(lp)

    ca = sub.add_parser("call", help="call a phone tool")
    ca.add_argument("name")
    ca.add_argument("--args", default="{}", help="JSON object for arguments")
    ca.add_argument("--args-file", default="", help="read arguments from a JSON file")
    ca.add_argument("--json", action="store_true", dest="as_json")
    _add_conn_flags(ca)

    md = sub.add_parser("media", help="download a /media/{id} blob")
    md.add_argument("media_id")
    md.add_argument("--out", required=True)
    _add_conn_flags(md)

    ec = sub.add_parser("export-ca", help="pin phone leaf cert to a local CA file (fail-closed)")
    ec.add_argument("--host", default="")
    ec.add_argument("--port", type=int, default=0)
    ec.add_argument("--out", required=True)
    ec.add_argument("--timeout", type=float, default=15.0)
    _add_conn_flags(ec)

    ub = sub.add_parser("usb", help="USB loopback via adb forward (setup/status/remove/check)")
    ub.add_argument("action", nargs="?", default="setup",
                    choices=["setup", "status", "remove", "check"],
                    help="setup: adb forward + verify /healthz (default); "
                         "status: list devices + forwards; remove: drop the forward; "
                         "check: verify /healthz only")
    ub.add_argument("--port", type=int, default=_usb.USB_DEFAULT_PORT,
                    help="phone port to forward (default: 8080)")
    ub.add_argument("--serial", default="", help="adb device serial (default: single device)")
    ub.add_argument("--timeout", type=float, default=15.0, help="adb/healthz seconds")
    ub.add_argument("--no-setup", action="store_true",
                    help="skip `adb forward`, only verify /healthz")
    return ap


def _resolve_config(ns: argparse.Namespace) -> _config.ClientConfig:
    cfg = _config.from_env()
    url = getattr(ns, "url", "")
    token = getattr(ns, "token", "")
    sha = getattr(ns, "sha256", "")
    cafile = getattr(ns, "cafile", "")
    if url:
        cfg.url = url.strip()
    if token:
        cfg.token = token
    if sha:
        cfg.sha256 = _tls.normalize_fingerprint(sha)
    if cafile:
        cfg.cafile = cafile.strip()
    if getattr(ns, "insecure", False):
        cfg.insecure = True
    if getattr(ns, "timeout_ms", None):
        try:
            cfg.timeout_ms = int(ns.timeout_ms)
        except (TypeError, ValueError):
            pass
    # Paired-file fallback when env is empty (never overrides explicit values).
    if (not cfg.url or not cfg.token) and os.path.exists(_config.PAIRED_CONFIG_DEFAULT):
        try:
            paired = _config.load_paired_config()
            if not cfg.url:
                cfg.url = paired.url
            if not cfg.token:
                cfg.token = paired.token
            if not cfg.sha256:
                cfg.sha256 = paired.sha256
            if not cfg.cafile:
                cfg.cafile = paired.cafile
        except Exception as exc:  # noqa: BLE001
            _p.log(f"paired config unreadable: {exc}")
    # --usb rewrites the endpoint to the USB loopback (keeps any configured
    # port; defaults to 8080 when no URL is set yet).
    if getattr(ns, "usb", False):
        if cfg.url:
            cfg.url = _usb.rewrite_url_to_loopback(cfg.url)
        else:
            cfg.url = _usb.loopback_url_for_port(_usb.USB_DEFAULT_PORT)
    return cfg


def _fail(msg: str) -> int:
    _p.log(f"error: {msg}")
    return EXIT_FAIL


# -- subcommand handlers -----------------------------------------------------


def cmd_smoke(cfg: _config.ClientConfig, timeout: float) -> int:
    problems = cfg.validate()
    if problems:
        for prob in problems:
            _p.log(f"config: {prob}")
        if not cfg.url or not cfg.token:
            return _fail("set PHONE_MCP_URL + PHONE_MCP_TOKEN (or run `phone-mcp pair`)")
    # Per-probe budget overrides the configured ceiling for smoke only.
    cfg.timeout_ms = int(timeout * 1000)
    _p.log(f"smoke → {cfg.url} (pin={cfg.sha256[:12] + '…' if cfg.sha256 else 'none'})")
    checks = _p.smoke(cfg)
    failed = False
    for name, ok, detail in checks:
        # Redact any accidental token echo in server detail strings.
        safe = str(detail)
        if cfg.token and cfg.token in safe:
            safe = safe.replace(cfg.token, "<redacted>")
        print(f"[{'PASS' if ok else 'FAIL'}] {name}: {safe}")
        failed = failed or not ok
    if failed:
        print("SMOKE FAIL — check URL/token/fingerprint, dashboard server state, LAN/adb forward (scripts/usb-mcp.sh setup).")
        return EXIT_FAIL
    print("SMOKE PASS — harness configs generated by `phone-mcp configure` will connect.")
    return EXIT_OK


def cmd_pair(ns: argparse.Namespace) -> int:
    if ns.qr:
        try:
            cfg = _config.from_qr(ns.qr)
        except ValueError as exc:
            return _fail(str(exc))
    else:
        base = _config.from_env()
        url = getattr(ns, "url", "").strip() or base.url
        token = getattr(ns, "token", "") or base.token
        sha = _tls.normalize_fingerprint(getattr(ns, "sha256", "") or base.sha256)
        cfg = _config.ClientConfig(
            url=url, token=token, sha256=sha,
            cafile=getattr(ns, "cafile", "").strip() or base.cafile,
            insecure=getattr(ns, "insecure", False) or base.insecure, protocol=base.protocol,
            timeout_ms=base.timeout_ms, fast_ms=base.fast_ms, margin_ms=base.margin_ms,
        )
    problems = cfg.validate()
    if problems:
        for prob in problems:
            _p.log(f"config: {prob}")
        return EXIT_USAGE
    path = _config.save_paired_config(cfg, ns.out)
    _p.log(f"paired → {path} (0600)")
    _p.log(f"url={cfg.url} pin={_tls.grouped_fingerprint(cfg.sha256)[:23]}…")
    if ns.print_env:
        print(f'export PHONE_MCP_URL="{cfg.url}"')
        print('export PHONE_MCP_TOKEN="<saved — re-export from your shell history>"')
        print(f"export PHONE_MCP_SHA256=\"{cfg.sha256}\"")
    else:
        print(f"paired {cfg.url} → {path}")
        print("exports: PHONE_MCP_URL / PHONE_MCP_TOKEN / PHONE_MCP_SHA256 (see `pair --print-env`)")
    return EXIT_OK


def _parse_harness_selection(raw: str) -> list[str]:
    parts = [p.strip().lower() for p in raw.replace(",", " ").split()]
    if not parts or parts == ["all"]:
        return list(_h.HARNESS_IDS)
    unknown = [p for p in parts if p not in _h.RENDERERS]
    if unknown:
        raise ValueError(f"unknown harness(s): {', '.join(unknown)} (choose from: all {' '.join(_h.HARNESS_IDS)})")
    # De-dupe, stable order.
    return sorted(set(parts), key=_h.HARNESS_IDS.index)


def cmd_configure(ns: argparse.Namespace, cfg: _config.ClientConfig) -> int:
    if not cfg.url:
        return _fail("missing URL — pass --url or set PHONE_MCP_URL (pair first)")
    try:
        wanted = _parse_harness_selection(ns.harness)
    except ValueError as exc:
        return _fail(str(exc))
    try:
        _h.bridge_command(ns.bridge)
    except ValueError as exc:
        return _fail(str(exc))
    os.makedirs(ns.out, exist_ok=True)
    _p.log(f"configure bridge={ns.bridge} out={ns.out}")
    _p.log(f"url={cfg.url} sha={'set' if cfg.sha256 else '(none — verify dashboard fingerprint once)'}")
    for hid in wanted:
        filename, content = _h.RENDERERS[hid](cfg.url, ns.bridge)
        dest = os.path.join(ns.out, filename)
        with open(dest, "w", encoding="utf-8") as fh:
            fh.write(content)
        print(f"wrote {dest}")
        hint = _h.cli_add_hint(hid, cfg.url, ns.bridge)
        if hint:
            print(f"  {hint}")
        if ns.install:
            target = _h.default_install_path(hid, ns.cwd)
            try:
                _h.install_rendered(hid, content, target)
                print(f"  installed → {target}")
            except Exception as exc:  # noqa: BLE001
                _p.log(f"install {hid} → {target} failed: {exc}")
                return EXIT_FAIL
    print("")
    print("next:")
    print(f'  export PHONE_MCP_URL="{cfg.url}"')
    print("  export PHONE_MCP_TOKEN='<paste-from-pairing-card>'")
    print(f"  export PHONE_MCP_SHA256=\"{cfg.sha256 or '<dashboard-fingerprint>'}\"")
    print(f"  phone-mcp smoke  # expect 4× PASS before touching any harness")
    print(f"  # guide: docs/harness.md · manual probe: {_h.INSPECTOR_GUIDE}")
    if not ns.no_skills:
        from . import skills as _skills
        skills_dir = ns.skills_dir.strip() or os.path.join(ns.out, "skills")
        try:
            staged = _skills.stage_skills(
                skills_dir, forbidden=tuple(s for s in (cfg.token, cfg.sha256) if s))
        except (RuntimeError, ValueError) as exc:
            return _fail(f"skills staging failed: {exc}")
        total = sum(staged.values())
        print(f"skills staged → {skills_dir} "
              f"({len(staged)} skills, {total} files; --no-skills to skip)")
        if ns.install:
            try:
                installed = _skills.stage_skills(
                    _skills.project_skills_dir(ns.cwd),
                    forbidden=tuple(s for s in (cfg.token, cfg.sha256) if s))
            except (RuntimeError, ValueError) as exc:
                return _fail(f"skills install failed: {exc}")
            print(f"  skills installed → {_skills.project_skills_dir(ns.cwd)} "
                  f"({sum(installed.values())} files; agents discover .agents/skills/ automatically)")
    if ns.print_only:
        print("== rendered ==")
        for hid in wanted:
            filename, content = _h.RENDERERS[hid](cfg.url, ns.bridge)
            print(f"--- {filename}")
            print(content, end="" if content.endswith("\n") else "\n")
    return EXIT_OK


def cmd_list(cfg: _config.ClientConfig, as_json: bool) -> int:
    problems = cfg.validate()
    if problems:
        return _fail("; ".join(problems))
    try:
        result = _p.tools_list(cfg)
    except RuntimeError as exc:
        return _fail(str(exc))
    if as_json:
        print(json.dumps(result, indent=2))
        return EXIT_OK
    tools = result.get("tools", [])
    print(f"{len(tools)} tools:")
    for tool in tools:
        name = tool.get("name", "?")
        desc = (tool.get("description", "") or "").split("\n")[0][:100]
        print(f"  {name:22} {desc}")
    return EXIT_OK


def cmd_call(cfg: _config.ClientConfig, name: str, args_raw: str, args_file: str, as_json: bool) -> int:
    problems = cfg.validate()
    if problems:
        return _fail("; ".join(problems))
    if args_file:
        try:
            with open(args_file, encoding="utf-8") as fh:
                arguments = json.load(fh)
        except Exception as exc:  # noqa: BLE001
            return _fail(f"unreadable --args-file: {exc}")
    else:
        try:
            arguments = json.loads(args_raw or "{}")
        except json.JSONDecodeError as exc:
            return _fail(f"bad --args JSON: {exc}")
    if not isinstance(arguments, dict):
        return _fail("--args must be a JSON object")
    _p.log(f"call {name} args={sorted(arguments)}")
    try:
        result = _p.tools_call(cfg, name, arguments)
    except RuntimeError as exc:
        return _fail(str(exc))
    if as_json:
        print(json.dumps(result, indent=2))
        return EXIT_OK
    contents = result.get("content", [])
    for item in contents:
        if isinstance(item, dict) and item.get("type") == "text":
            print(item.get("text", ""))
        else:
            print(json.dumps(item))
    if result.get("isError"):
        return EXIT_FAIL
    # Surface media URLs plainly (blobs fetch via `phone-mcp media`).
    return EXIT_OK


def cmd_media(cfg: _config.ClientConfig, media_id: str, out: str) -> int:
    problems = cfg.validate()
    if problems:
        return _fail("; ".join(problems))
    _p.log(f"media {media_id} → {out}")
    try:
        data, mime = _p.fetch_media(cfg, media_id)
    except RuntimeError as exc:
        return _fail(str(exc))
    except Exception as exc:  # noqa: BLE001
        return _fail(_p._actionable_transport_error(str(exc), cfg.url))
    parent = os.path.dirname(os.path.abspath(out))
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(out, "wb") as fh:
        fh.write(data)
    print(f"wrote {out} ({len(data)} bytes, {mime})")
    return EXIT_OK


def cmd_export_ca(ns: argparse.Namespace, cfg: _config.ClientConfig) -> int:
    host = ns.host or cfg.host
    port = ns.port or cfg.port or 8080
    pin = _tls.normalize_fingerprint(getattr(ns, "sha256", "") or cfg.sha256)
    if not host:
        return _fail("missing host — pass --host or set PHONE_MCP_URL first")
    if not pin:
        return _fail("refusing to pin blind — pass --sha256 (dashboard fingerprint, verified once)")
    if len(pin) != 64:
        return _fail("--sha256 must be 64 hex chars")
    _p.log(f"export-ca {host}:{port} → {ns.out}")
    try:
        path = _tls.export_ca_pem(host, port, pin, ns.out, ns.timeout)
    except RuntimeError as exc:
        return _fail(str(exc))
    except Exception as exc:  # noqa: BLE001
        return _fail(_p._actionable_transport_error(str(exc), cfg.url))
    print(f"wrote {path} (fail-closed: hash matched the dashboard pin)")
    print(f"export PHONE_MCP_CAFILE=\"{path}\"")
    return EXIT_OK


def _usb_health_check(port: int, timeout: float) -> tuple[bool, str]:
    """GET https://127.0.0.1:PORT/healthz (reachability only, no auth/pin)."""
    import ssl
    import urllib.request

    url = f"https://127.0.0.1:{port}/healthz"
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    ctx.set_alpn_protocols(["http/1.1"])
    req = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as res:  # noqa: S310
            body = res.read().decode("utf-8", "replace")[:200]
        return True, f"{url} → HTTP {getattr(res, 'status', 200)} {body.strip()}"
    except Exception as exc:  # noqa: BLE001
        hint = _usb.actionable_usb_error(str(exc), _usb.loopback_url_for_port(port))
        return False, f"{url}: {hint}"


def cmd_usb(ns: argparse.Namespace) -> int:
    action = getattr(ns, "action", "setup") or "setup"
    port = int(getattr(ns, "port", _usb.USB_DEFAULT_PORT) or _usb.USB_DEFAULT_PORT)
    serial = (getattr(ns, "serial", "") or "").strip()
    timeout = float(getattr(ns, "timeout", 15.0) or 15.0)

    if action == "status":
        rc_d, out_d, err_d = _usb.run_adb(["adb", "devices", "-l"], timeout)
        if rc_d != 0:
            return _fail(f"`adb devices -l` failed: {(err_d or out_d).strip()}")
        devices = _usb.parse_adb_devices(out_d)
        rc_f, out_f, err_f = _usb.run_adb(["adb", "forward", "--list"], timeout)
        forwards = _usb.parse_adb_forward_list(out_f) if rc_f == 0 else []
        if not devices:
            print("no adb devices attached (connect USB + accept the RSA prompt)")
        for dev in devices:
            print(f"{dev['serial']}\t{dev['state']}"
                  + (f"\t{dev['detail']}" if dev["detail"] else ""))
        matches = [f for f in forwards if f["local"] == f"tcp:{port}"]
        if matches:
            for fwd in matches:
                print(f"forward: {fwd['serial']} {fwd['local']} -> {fwd['remote']}")
        else:
            print(f"no adb forward for tcp:{port} ({_usb.usb_setup_hint(port)})")
            if rc_f != 0:
                _p.log(f"`adb forward --list` failed: {(err_f or out_f).strip()}")
        return EXIT_OK

    if action == "remove":
        target = serial
        if not target:
            rc_d, out_d, _ = _usb.run_adb(["adb", "devices"], timeout)
            devs = _usb.parse_adb_devices(out_d) if rc_d == 0 else []
            picked, problem = _usb.pick_usb_device(devs, "")
            if problem:
                return _fail(f"{problem} — {_usb.usb_setup_hint(port)}")
            target = picked
        args = _usb.adb_remove_args(target, port)
        _p.log(f"usb remove: {' '.join(args)}")
        rc, out, err = _usb.run_adb(args, timeout)
        if rc != 0:
            detail = (err or out or f"adb exited {rc}").strip()
            if "not found" in detail.lower():
                print(f"no adb forward tcp:{port} to remove — already clean")
                return EXIT_OK
            return _fail(f"adb forward --remove failed: {detail}")
        print(f"removed adb forward tcp:{port}")
        return EXIT_OK

    # setup / check: ensure the forward (unless --no-setup), then verify /healthz.
    if action == "setup" and not getattr(ns, "no_setup", False):
        target = serial
        if not target:
            rc_d, out_d, _ = _usb.run_adb(["adb", "devices"], timeout)
            devs = _usb.parse_adb_devices(out_d) if rc_d == 0 else []
            picked, problem = _usb.pick_usb_device(devs, "")
            if problem:
                if rc_d != 0:
                    return _fail(f"`adb devices` failed — {_usb.usb_setup_hint(port)}")
                return _fail(f"{problem} — {_usb.usb_setup_hint(port)}")
            target = picked
        args = _usb.adb_forward_args(target, port)
        _p.log(f"usb setup: {' '.join(args)}")
        rc, out, err = _usb.run_adb(args, timeout)
        if rc != 0:
            detail = (err or out or f"adb exited {rc}").strip()
            if "unauthorized" in detail.lower() or "offline" in detail.lower():
                detail += " — run `adb devices` and accept the on-phone RSA prompt"
            return _fail(f"adb forward failed: {detail} — {_usb.usb_setup_hint(port)}")
    ok, detail = _usb_health_check(port, timeout)
    print(f"[{'PASS' if ok else 'FAIL'}] usb/{action}: {detail}")
    if not ok:
        return EXIT_FAIL
    print(f"USB ready — export PHONE_MCP_URL=\"{_usb.loopback_url_for_port(port)}\"")
    return EXIT_OK


def main(argv: list[str] | None = None) -> int:
    ap = _build_parser()
    ns = ap.parse_args(argv)
    if getattr(ns, "version", False):
        print(f"phone-mcp {__version__}")
        return EXIT_OK
    if ns.cmd is None:
        ap.print_help(sys.stderr)
        return EXIT_USAGE
    cfg = _resolve_config(ns)
    if ns.verbose:
        _p.log(f"config url={cfg.url or '(none)'} pin={'set' if cfg.sha256 else 'none'} "
               f"ca={cfg.cafile or 'none'} insecure={cfg.insecure}")

    if ns.cmd == "serve":
        return _p.serve_forever(cfg)
    if ns.cmd in ("smoke", "doctor"):
        timeout = float(getattr(ns, "timeout", 20.0) or 20.0)
        return cmd_smoke(cfg, timeout)
    if ns.cmd == "pair":
        return cmd_pair(ns)
    if ns.cmd == "usb":
        return cmd_usb(ns)
    if ns.cmd == "configure":
        return cmd_configure(ns, cfg)
    if ns.cmd == "list":
        return cmd_list(cfg, bool(getattr(ns, "as_json", False)))
    if ns.cmd == "call":
        return cmd_call(cfg, ns.name, ns.args, ns.args_file, bool(ns.as_json))
    if ns.cmd == "media":
        return cmd_media(cfg, ns.media_id, ns.out)
    if ns.cmd == "export-ca":
        return cmd_export_ca(ns, cfg)
    ap.print_help(sys.stderr)
    return EXIT_USAGE


if __name__ == "__main__":
    raise SystemExit(main())
