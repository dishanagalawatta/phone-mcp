#!/usr/bin/env node
/**
 * phone-mcp-bridge.mjs — stdio ↔ Streamable HTTP bridge for Phone MCP.
 *
 * Lets any MCP harness that launches local commands (Claude Code/Desktop,
 * Codex, OpenCode, Cursor, VS Code, Gemini CLI, Cline, Windsurf, …) talk to
 * the phone's TLS-only Streamable HTTP endpoint without teaching every
 * harness about self-signed TOFU certs + Bearer auth.
 *
 *   Harness (stdio JSON-RPC) ⇄ this bridge ⇄ POST https://<phone>:8080/mcp
 *
 * USB fallback (no LAN): `adb forward tcp:8080 tcp:8080`, then
 * PHONE_MCP_URL=https://127.0.0.1:8080/mcp — never `adb reverse`
 * (the phone already binds :8080, so reverse collides on-device).
 *
 * Zero dependencies. Node 18+.
 *
 * Env:
 *   PHONE_MCP_URL        required  e.g. https://192.168.1.50:8080/mcp
 *   PHONE_MCP_TOKEN      required  Bearer token from dashboard pairing card
 *   PHONE_MCP_SHA256     optional  pinned SHA-256 fingerprint (hex, `:` ok).
 *                                  Strongly recommended: TOFU — compare with the
 *                                  dashboard once, then pin. Takes precedence
 *                                  over PHONE_MCP_INSECURE.
 *   PHONE_MCP_CAFILE     optional  PEM from scripts/export-ca.sh. Enables real
 *                                  chain verification against the pinned leaf
 *                                  (hostname check still skipped: DHCP IP churn
 *                                  breaks SAN matching; the trust anchor IS the
 *                                  phone identity). Combines with SHA256 pin.
 *   PHONE_MCP_INSECURE=1 optional  skip TLS verification (LAN-trusted only).
 *   PHONE_MCP_PROTOCOL   optional  default 2025-06-18
 *   PHONE_MCP_TIMEOUT_MS optional  ceiling per call, default 35000 (covers 30s captures)
 *   PHONE_MCP_FAST_MS    optional  budget for calls without a duration arg,
 *                                  default 15000 (fail fast on cheap calls)
 *   PHONE_MCP_MARGIN_MS  optional  headroom added to durationMs/timeoutMs args,
 *                                  default 10000 (covers the final SSE envelope)
 *
 * Protocol: one JSON-RPC message per stdin line → POST /mcp → write the
 * response (or each SSE `data:` envelope, notifications first, final last)
 * as one line on stdout. Diagnostics go to stderr only (never stdout).
 */

import https from "node:https";
import http from "node:http";
import fs from "node:fs";
import { createHash } from "node:crypto";

const URL_ = process.env.PHONE_MCP_URL ?? "";
const TOKEN = process.env.PHONE_MCP_TOKEN ?? "";
const PIN = normalizeFingerprint(process.env.PHONE_MCP_SHA256 ?? "");
const CAFILE = process.env.PHONE_MCP_CAFILE ?? "";
const INSECURE = (process.env.PHONE_MCP_INSECURE ?? "") === "1";
const PROTOCOL = process.env.PHONE_MCP_PROTOCOL ?? "2025-06-18";
const TIMEOUT_MS = Number(process.env.PHONE_MCP_TIMEOUT_MS ?? "35000") || 35000;
const FAST_MS = Number(process.env.PHONE_MCP_FAST_MS ?? "15000") || 15000;
const MARGIN_MS = Number(process.env.PHONE_MCP_MARGIN_MS ?? "10000") || 10000;

let caPem = null;
if (CAFILE) {
  try {
    caPem = fs.readFileSync(CAFILE, "utf8");
    if (!caPem.includes("BEGIN CERTIFICATE")) throw new Error("not a PEM certificate");
  } catch (err) {
    fail(undefined, -32603, `phone-mcp-bridge: unreadable PHONE_MCP_CAFILE (${err.message})`);
    process.exit(1);
  }
}

if (!URL_ || !TOKEN) {
  fail(undefined, -32603, "phone-mcp-bridge: set PHONE_MCP_URL and PHONE_MCP_TOKEN");
  process.exit(1);
}
let target;
try {
  target = new URL(URL_);
} catch {
  fail(undefined, -32603, `phone-mcp-bridge: bad PHONE_MCP_URL ${JSON.stringify(URL_)}`);
  process.exit(1);
}
if (target.protocol !== "https:" && target.protocol !== "http:") {
  fail(undefined, -32603, "phone-mcp-bridge: PHONE_MCP_URL must be https:// (or http:// loopback)");
  process.exit(1);
}
if (target.protocol === "http:" && !/^(localhost|127\.0\.0\.1|\[::1\])$/.test(target.hostname)) {
  fail(undefined, -32603, "phone-mcp-bridge: http:// only allowed for loopback (phone serves TLS-only)");
  process.exit(1);
}

log(
  `bridge → ${target.origin}${target.pathname} ` +
    `(pin=${PIN ? "sha256:" + PIN.slice(0, 12) + "…" : "none"}, ` +
    `ca=${CAFILE || "none"}, insecure=${INSECURE}, proto=${PROTOCOL})`,
);

// --- stdio loop: one JSON message per line --------------------------------
let buf = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => {
  buf += chunk;
  let nl;
  while ((nl = buf.indexOf("\n")) >= 0) {
    const line = buf.slice(0, nl).trim();
    buf = buf.slice(nl + 1);
    if (line) void handleLine(line);
  }
});

async function handleLine(line) {
  let msg;
  try {
    msg = JSON.parse(line);
  } catch {
    return fail(undefined, -32700, "Parse error");
  }
  // Client → server notifications (notifications/*) get a 202-style ack:
  // there is no result to forward, so swallow after relaying.
  const isNotification =
    msg.id === undefined && typeof msg.method === "string" && msg.method.startsWith("notifications/");
  try {
    const budget = effectiveTimeout(msg);
    const { status, contentType, body } = await postMcp(JSON.stringify(msg), budget);
    if (status === 401 || status === 403) {
      return fail(msg.id, -32001, "Missing or invalid bearer token (check PHONE_MCP_TOKEN)");
    }
    if (status === 413) return fail(msg.id, -32600, "Body too large");
    if (status === 202 || body.length === 0) {
      // Notification accepted — stdio has nothing to return.
      return;
    }
    if (contentType.includes("text/event-stream")) {
      let emitted = false;
      for (const payload of parseSseData(body)) {
        let env;
        try {
          env = JSON.parse(payload);
        } catch {
          continue;
        }
        // Stream notifications pass through immediately; the envelope
        // carrying `result`/`error` (+ matching id) is the call result.
        emit(env);
        emitted = true;
      }
      if (!emitted) return fail(msg.id, -32603, "Empty SSE stream from server");
      return;
    }
    try {
      emit(JSON.parse(body));
    } catch {
      return fail(msg.id, -32603, `Upstream returned HTTP ${status} (non-JSON)`);
    }
    void isNotification;
  } catch (err) {
    const detail = err instanceof Error ? err.message : String(err);
    if (/fingerprint mismatch/i.test(detail)) return fail(msg.id, -32603, detail);
    if (/certificate|self-signed|unable to verify/i.test(detail))
      return fail(
        msg.id,
        -32603,
        `${detail} — verify the dashboard SHA-256 once, then set PHONE_MCP_SHA256 (or PHONE_MCP_INSECURE=1 on trusted LAN)`,
      );
    const hint = isLoopbackTarget(URL_) ? ` — ${usbHint()}` : "";
    return fail(msg.id, -32603, `Upstream unreachable: ${detail}${hint}`);
  }
}

/** POST one JSON-RPC envelope; resolves raw { status, contentType, body }. */
function postMcp(payload, budgetMs = TIMEOUT_MS) {
  return new Promise((resolve, reject) => {
    const lib = target.protocol === "https:" ? https : http;
    const headers = buildHeaders(TOKEN, PROTOCOL, JSON.parse(payload).method);
    const tls = resolveTls({ pin: PIN, insecure: INSECURE, cafile: CAFILE });
    const req = lib.request(
      {
        hostname: target.hostname,
        port: target.port || (target.protocol === "https:" ? 443 : 80),
        path: target.pathname + target.search,
        method: "POST",
        headers: { ...headers, "Content-Length": Buffer.byteLength(payload) },
        // Netty drops ALPN-less connections on first bytes (curl works only
        // because it offers h2,http/1.1) — always negotiate plain HTTP/1.1.
        ALPNProtocols: target.protocol === "https:" ? ["http/1.1"] : undefined,
        // Self-signed TOFU: pin mode accepts the socket cert in order to
        // hash-compare it below; CA mode verifies the chain against the
        // exported leaf; otherwise honor the default chain unless the user
        // explicitly opted into insecure LAN mode.
        rejectUnauthorized: tls.rejectUnauthorized,
        ...(caPem ? { ca: caPem } : {}),
        // checkServerIdentity must be omitted (not undefined) when the
        // default hostname check applies — Node rejects an explicit undefined.
        ...(tls.skipHostnameCheck || target.protocol !== "https:"
          ? { checkServerIdentity: () => undefined } // chain/pin verified instead
          : {}),
        timeout: budgetMs,
      },
      (res) => {
        // Manual TOFU pin check against the presented leaf certificate.
        if (PIN && target.protocol === "https:") {
          const peer = res.socket.getPeerCertificate?.();
          const der = peer?.raw;
          const actual = der ? createHash("sha256").update(der).digest("hex") : "";
          if (normalizeFingerprint(actual) !== PIN) {
            res.resume();
            reject(
              new Error(
                `TLS fingerprint mismatch: expected ${grouped(PIN)}, got ${grouped(normalizeFingerprint(actual)) || "(none)"} — re-verify against the dashboard`,
              ),
            );
            req.destroy();
            return;
          }
        }
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () =>
          resolve({
            status: res.statusCode ?? 0,
            contentType: String(res.headers["content-type"] ?? ""),
            body: Buffer.concat(chunks).toString("utf8"),
          }),
        );
      },
    );
    req.on("timeout", () => req.destroy(new Error(`timeout after ${budgetMs}ms (tool budget; raise PHONE_MCP_TIMEOUT_MS/FAST_MS/MARGIN_MS if legitimate)`)));
    req.on("error", reject);
    req.write(payload);
    req.end();
  });
}

// --- helpers (exported for tests via dynamic import) ----------------------
export function normalizeFingerprint(hex) {
  return String(hex ?? "")
    .replace(/[^0-9a-fA-F]/g, "")
    .toLowerCase();
}

// Loopback check for USB `adb forward` mode (inline: this bridge stays
// dependency-free and must not import the client tree).
function isLoopbackTarget(u) {
  try {
    const h = String(new URL(u).hostname).toLowerCase();
    return h === "localhost" || h === "127.0.0.1" || h === "[::1]";
  } catch {
    return false;
  }
}

// Points at `adb forward` (never `adb reverse` — the phone already binds
// :8080, so reverse collides on-device). Reflects the configured port.
function usbHint() {
  let port = 8080;
  try {
    port = Number(target.port) || 8080;
  } catch {
    /* keep default */
  }
  return (
    `USB: run \`adb forward tcp:${port} tcp:${port}\`, then use ` +
    `https://127.0.0.1:${port}/mcp (scripts/usb-mcp.sh setup)`
  );
}

/**
 * Pure TLS-mode resolution (unit-testable; Python twin mirrors it).
 * - insecure → verify nothing (explicit LAN opt-in only).
 * - cafile   → verify the chain against the exported leaf (TOFU trust
 *   anchor); hostname check skipped — DHCP churn breaks SAN matching and
 *   the anchor IS the phone identity.
 * - pin      → accept-then-hash-compare in the response handler.
 * - default  → stock chain verification (fails closed on self-signed).
 */
export function resolveTls({ pin, insecure, cafile }) {
  const hasPin = normalizeFingerprint(pin) !== "";
  const hasCa = Boolean(cafile);
  return {
    rejectUnauthorized: !insecure && (hasCa || !hasPin),
    skipHostnameCheck: hasPin || hasCa,
    verifyPin: hasPin,
  };
}

export function grouped(hex) {
  const h = normalizeFingerprint(hex);
  return h ? h.toUpperCase().match(/../g).join(":") : "";
}

export function buildHeaders(token, protocol, method) {
  const headers = {
    "Content-Type": "application/json",
    Accept: "application/json, text/event-stream",
    Authorization: `Bearer ${token}`,
  };
  // Server skips the version gate for `initialize` + notifications/*;
  // sending it unconditionally is still valid — except some strict
  // harnesses replay `initialize` verbatim, so keep parity with curl docs.
  if (method !== "initialize" && !String(method ?? "").startsWith("notifications/")) {
    headers["MCP-Protocol-Version"] = protocol;
  }
  return headers;
}

export function parseSseData(body) {
  const out = [];
  for (const chunk of String(body).split("\n\n")) {
    for (const line of chunk.split("\n")) {
      const t = line.trim();
      if (t.startsWith("data:")) out.push(t.slice(5).trim());
    }
  }
  return out.filter(Boolean);
}

/**
 * Per-call time budget in ms (unit-testable; Python twin mirrors it).
 * Calls carrying durationMs/timeoutMs (captures, scans, SSE streams) get
 * duration + margin headroom for the final envelope; everything else
 * (initialize, tools/list, instant tools) gets the fast budget so hung
 * cheap calls fail fast. Always bounded by the ceiling.
 */
export function effectiveTimeout(msg, { ceiling = TIMEOUT_MS, fast = FAST_MS, margin = MARGIN_MS } = {}) {
  const dur = messageDurationMs(msg);
  const want = dur > 0 ? dur + margin : fast;
  return Math.min(ceiling, Math.max(1, want));
}

export function messageDurationMs(msg) {
  try {
    const params = msg?.params ?? {};
    const args = params.arguments ?? params ?? {};
    const n = Number(args.durationMs ?? args.timeoutMs);
    return Number.isFinite(n) && n > 0 ? Math.floor(n) : 0;
  } catch {
    return 0;
  }
}

function emit(obj) {
  process.stdout.write(JSON.stringify(obj) + "\n");
}

function fail(id, code, message) {
  emit({ jsonrpc: "2.0", id: id ?? null, error: { code, message } });
}

function log(msg) {
  process.stderr.write(`[phone-mcp-bridge] ${msg}\n`);
}
