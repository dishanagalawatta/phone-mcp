/**
 * MCP Streamable-HTTP protocol layer (zero dependencies).
 *
 * One JSON-RPC envelope per POST; SSE `data:` envelopes fan out
 * (notifications first, final `result`/`error` last). Diagnostics go to
 * stderr — stdout carries only JSON-RPC in `serve` mode.
 */

import { createHash } from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import https from 'node:https';

import { configHost, configPort } from './config.mjs';
import {
  ALPN_PROTOCOLS,
  derToPem,
  groupedFingerprint,
  normalizeFingerprint,
  resolveTls,
  verifyPinAgainstHost,
} from './tls.mjs';
import { isLoopbackUrl, portOfUrl, usbSetupHint } from './usb.mjs';

export const MCP_PROTOCOL_VERSION = '2025-06-18';

export function log(msg) {
  process.stderr.write(`[phone-mcp] ${msg}\n`);
}

export function buildHeaders(token, protocol, method) {
  const headers = {
    'Content-Type': 'application/json',
    Accept: 'application/json, text/event-stream',
    Authorization: `Bearer ${token}`,
  };
  // Server skips the version gate for `initialize` + notifications/*;
  // sending it unconditionally is still valid — except strict harnesses
  // replay `initialize` verbatim, so keep parity with curl docs.
  if (method !== 'initialize' && !String(method ?? '').startsWith('notifications/')) {
    headers['MCP-Protocol-Version'] = protocol;
  }
  return headers;
}

export function parseSseData(body) {
  const out = [];
  for (const chunk of String(body).split('\n\n')) {
    for (const line of chunk.split('\n')) {
      const t = line.trim();
      if (t.startsWith('data:')) {
        const payload = t.slice(5).trim();
        if (payload) out.push(payload);
      }
    }
  }
  return out;
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

/** Per-call budget: duration+margin for captures/streams, fast otherwise. */
export function effectiveTimeout(msg, { ceiling, fast, margin } = {}) {
  const dur = messageDurationMs(msg);
  const want = dur > 0 ? dur + margin : fast;
  return Math.min(ceiling, Math.max(1, want));
}

function budgets(cfg) {
  return { ceiling: cfg.timeoutMs, fast: cfg.fastMs, margin: cfg.marginMs };
}

/** USB hint suffix for loopback failures (forgot `adb forward`, wrong port). */
function usbSuffix(url) {
  try {
    return url && isLoopbackUrl(url) ? ` — ${usbSetupHint(portOfUrl(url))}` : '';
  } catch {
    return '';
  }
}

export function actionableTransportError(detail, url = '') {
  const lowered = String(detail).toLowerCase();
  if (lowered.includes('fingerprint mismatch')) return String(detail);
  if (lowered.includes('certificate') || lowered.includes('self-signed') || lowered.includes('verify failed')) {
    return (
      `${detail} — verify the dashboard SHA-256 once, then set ` +
      'PHONE_MCP_SHA256 (or PHONE_MCP_INSECURE=1 on trusted LAN)'
    );
  }
  if (lowered.includes('timed out') || lowered.includes('timeout')) {
    return detail + ` — tool budget exceeded (raise PHONE_MCP_TIMEOUT_MS/FAST_MS/MARGIN_MS if legitimate)${usbSuffix(url)}`;
  }
  return `Upstream unreachable: ${detail}${usbSuffix(url)}`;
}

/**
 * TLS material for the data leg. When pinned (and not insecure), the leaf is
 * captured and hash-compared first, then the data leg chain-verifies against
 * that exact leaf (or the CA file when the user chained `export-ca`).
 */
async function dataLegTls(cfg, budgetMs, deps = {}) {
  const pin = normalizeFingerprint(cfg.sha256);
  if (pin && cfg.url.startsWith('https://') && !cfg.insecure) {
    const host = deps.host ?? configHost(cfg);
    const port = deps.port ?? configPort(cfg);
    const capture = deps.preflightDer;
    const { der } = await verifyPinAgainstHost(host, port, pin, Math.max(1000, budgetMs), { preflightDer: capture });
    if (cfg.cafile) {
      // Capture above already hash-compared; chain checks against the export.
      return { ca: fs.readFileSync(cfg.cafile, 'utf8') };
    }
    return { ca: derToPem(der) };
  }
  if (cfg.cafile && cfg.url.startsWith('https://') && !cfg.insecure) {
    return { ca: fs.readFileSync(cfg.cafile, 'utf8') };
  }
  return {};
}

/** POST one envelope; resolves raw { status, contentType, body }. */
export function postMcp(cfg, payload, headers, budgetMs = cfg.timeoutMs, deps = {}) {
  return (async () => {
    const target = new URL(cfg.url);
    const body = Buffer.isBuffer(payload) ? payload : Buffer.from(payload);
    const requestHeaders = { ...headers, 'Content-Length': body.length };
    const mode = resolveTls({ pin: cfg.sha256, insecure: cfg.insecure, cafile: cfg.cafile });
    // Anchor mode (capture-then-pin) upgrades verification beyond resolveTls:
    // the chain verifies against the TOFU-captured leaf itself. The manual
    // response-leg check only applies to insecure+pin (explicit LAN opt-out
    // that still wants mismatch diagnostics instead of silent trust).
    const anchor = target.protocol === 'https:' ? await dataLegTls(cfg, budgetMs, deps) : {};
    const pinForDataCheck =
      target.protocol === 'https:' && mode.verifyPin && cfg.insecure && !mode.rejectUnauthorized
        ? normalizeFingerprint(cfg.sha256)
        : '';

    const runRequest = (lib, options) =>
      new Promise((resolve, reject) => {
        const req = lib.request(options, (res) => {
          if (pinForDataCheck) {
            const peer = res.socket.getPeerCertificate?.();
            const actual = peer?.raw ? createHash('sha256').update(peer.raw).digest('hex') : '';
            if (normalizeFingerprint(actual) !== pinForDataCheck) {
              res.resume();
              req.destroy();
              reject(
                new Error(
                  `TLS fingerprint mismatch: expected ${groupedFingerprint(pinForDataCheck)}, ` +
                    `got ${groupedFingerprint(actual) || '(none)'}`,
                ),
              );
              return;
            }
          }
          const chunks = [];
          res.on('data', (c) => chunks.push(c));
          res.on('end', () =>
            resolve({
              status: res.statusCode ?? 0,
              contentType: String(res.headers['content-type'] ?? ''),
              body: Buffer.concat(chunks).toString('utf8'),
            }),
          );
        });
        req.on('timeout', () =>
          req.destroy(new Error(`timeout after ${budgetMs}ms (tool budget; raise PHONE_MCP_TIMEOUT_MS/FAST_MS/MARGIN_MS if legitimate)`)),
        );
        req.on('error', reject);
        req.write(body);
        req.end();
      });

    if (target.protocol === 'http:') {
      return runRequest(http, {
        hostname: target.hostname,
        port: Number(target.port) || 80,
        path: target.pathname + target.search,
        method: 'POST',
        headers: requestHeaders,
        timeout: budgetMs,
      });
    }

    return runRequest(https, {
      hostname: target.hostname,
      port: Number(target.port) || 443,
      path: target.pathname + target.search,
      method: 'POST',
      headers: requestHeaders,
      // Netty drops ALPN-less connections on first bytes — always negotiate
      // plain HTTP/1.1 (harmless against servers that ignore ALPN).
      ALPNProtocols: [...ALPN_PROTOCOLS],
      rejectUnauthorized: anchor.ca ? true : mode.rejectUnauthorized,
      ...(anchor.ca ? { ca: anchor.ca } : cfg.cafile ? { ca: fs.readFileSync(cfg.cafile, 'utf8') } : {}),
      // checkServerIdentity must be omitted (not undefined) when the default
      // hostname check applies — Node rejects an explicit undefined.
      ...(mode.skipHostnameCheck ? { checkServerIdentity: () => undefined } : {}),
      timeout: budgetMs,
    });
  })();
}

/** Single JSON-RPC round trip. Resolves [response|null, error|null]. */
export async function rpc(cfg, body, timeoutS = null, deps = {}) {
  const budgetS = timeoutS ?? Math.max(1, cfg.timeoutMs / 1000);
  const headers = buildHeaders(cfg.token, cfg.protocol, body.method);
  const payload = JSON.stringify(body);
  let status, contentType, raw;
  try {
    ({ status, contentType, body: raw } = deps.postMcp
      ? await deps.postMcp(cfg, payload, headers, budgetS * 1000)
      : await postMcp(cfg, payload, headers, budgetS * 1000, deps));
  } catch (err) {
    const detail = err instanceof Error ? err.message : String(err);
    if (/fingerprint mismatch/i.test(detail)) return [null, detail];
    return [null, actionableTransportError(detail, cfg.url)];
  }
  if (status === 401 || status === 403) {
    return [null, 'HTTP 401 Missing or invalid bearer token (check PHONE_MCP_TOKEN)'];
  }
  if (status === 413) return [null, 'HTTP 413 Body too large'];
  if (status === 202 || !raw) return [{ jsonrpc: '2.0', id: body.id, result: {} }, null];
  if ((contentType || '').includes('text/event-stream')) {
    for (const chunk of raw.split('\n\n')) {
      for (const line of chunk.split('\n')) {
        if (line.trim().startsWith('data:')) {
          try {
            return [JSON.parse(line.trim().slice(5).trim()), null];
          } catch {
            continue;
          }
        }
      }
    }
    return [null, 'empty SSE stream'];
  }
  try {
    return [JSON.parse(raw), null];
  } catch {
    return [null, `Upstream returned HTTP ${status} (non-JSON)`];
  }
}

function* nextId(start = 1) {
  let n = start;
  while (true) yield n++;
}

/** 4-probe health check: initialize → tools/list → battery → device_info. */
export async function smoke(cfg, deps = {}) {
  const ids = nextId(1);
  const timeoutS = Math.max(1, cfg.timeoutMs / 1000);
  const checks = [];

  const init = {
    jsonrpc: '2.0',
    id: ids.next().value,
    method: 'initialize',
    params: {
      protocolVersion: cfg.protocol,
      capabilities: {},
      clientInfo: { name: 'phone-mcp-client', version: '0.2.0' },
    },
  };
  const [resp, err] = await rpc(cfg, init, timeoutS, deps);
  const ok = !err && resp && typeof resp === 'object' && 'result' in resp;
  checks.push(['initialize', ok, err || `server=${JSON.stringify((resp || {}).result?.serverInfo ?? {})}`]);

  const [resp2, err2] = await rpc(cfg, { jsonrpc: '2.0', id: ids.next().value, method: 'tools/list', params: {} }, timeoutS, deps);
  const tools = !err2 ? ((resp2 || {}).result?.tools ?? []) : [];
  const ok2 = !err2 && tools.length >= 10;
  checks.push(['tools/list', ok2, err2 || `${tools.length} tools`]);

  const [resp3, err3] = await rpc(
    cfg,
    { jsonrpc: '2.0', id: ids.next().value, method: 'tools/call', params: { name: 'battery_read', arguments: {} } },
    timeoutS,
    deps,
  );
  const ok3 = !err3 && !((resp3 || {}).result?.isError ?? true);
  checks.push(['tools/call battery_read', ok3, err3 || 'isError=false']);

  const [resp4, err4] = await rpc(
    cfg,
    { jsonrpc: '2.0', id: ids.next().value, method: 'tools/call', params: { name: 'device_info', arguments: {} } },
    timeoutS,
    deps,
  );
  let hint = '';
  if (!err4) {
    try {
      const tlsVal = JSON.parse(resp4.result.content[0].text).tlsSha256 ?? '';
      if (cfg.sha256 && tlsVal && normalizeFingerprint(tlsVal) !== cfg.sha256) {
        hint = `WARNING: device tlsSha256 != pinned (${tlsVal.slice(0, 16)}…)`;
      }
    } catch {
      /* text was not JSON — no pin cross-check */
    }
  }
  checks.push(['tools/call device_info', !err4, err4 || hint || 'ok']);
  return checks;
}

export async function toolsList(cfg, deps = {}) {
  const [resp, err] = await rpc(cfg, { jsonrpc: '2.0', id: 1, method: 'tools/list', params: {} }, null, deps);
  if (err) throw new Error(err);
  if (!resp || resp.error) throw new Error(JSON.stringify((resp || {}).error ?? {}));
  return resp.result ?? {};
}

export async function toolsCall(cfg, name, args = {}, deps = {}) {
  const msg = { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name, arguments: args ?? {} } };
  const budgetMs = effectiveTimeout(msg, budgets(cfg));
  const [resp, err] = await rpc(cfg, msg, budgetMs / 1000, deps);
  if (err) throw new Error(err);
  if (!resp || resp.error) throw new Error(JSON.stringify((resp || {}).error ?? {}));
  return resp.result ?? {};
}

/** GET /media/{id} with Bearer auth. Resolves { data: Buffer, mime }. */
export function fetchMedia(cfg, mediaId, timeoutS = null, deps = {}) {
  return (async () => {
    const base = cfg.url.replace(/\/mcp\/?$/, '').replace(/\/$/, '');
    const url = `${base}/media/${encodeURIComponent(mediaId)}`;
    const target = new URL(url);
    const lib = target.protocol === 'https:' ? https : http;
    const budgetMs = Math.round((timeoutS ?? 30) * 1000);
    const anchor = target.protocol === 'https:' ? await dataLegTls(cfg, budgetMs, deps) : {};
    const hasPin = normalizeFingerprint(cfg.sha256) !== '';
    const hasCa = Boolean(cfg.cafile);
    return new Promise((resolve, reject) => {
      const req = lib.request(
        {
          hostname: target.hostname,
          port: Number(target.port) || (target.protocol === 'https:' ? 443 : 80),
          path: target.pathname + target.search,
          method: 'GET',
          headers: { Authorization: `Bearer ${cfg.token}` },
          ...(target.protocol === 'https:'
            ? {
                rejectUnauthorized: anchor.ca ? true : !cfg.insecure && !hasPin,
                ALPNProtocols: [...ALPN_PROTOCOLS],
                ...(anchor.ca ? { ca: anchor.ca } : {}),
                ...(hasPin || hasCa ? { checkServerIdentity: () => undefined } : {}),
                timeout: budgetMs,
              }
            : { timeout: budgetMs }),
        },
        (res) => {
          if (res.statusCode === 401 || res.statusCode === 403) {
            res.resume();
            reject(new Error('media fetch HTTP 401 (check PHONE_MCP_TOKEN)'));
            return;
          }
          if ((res.statusCode ?? 0) >= 400) {
            res.resume();
            reject(new Error(`media fetch HTTP ${res.statusCode}`));
            return;
          }
          const chunks = [];
          res.on('data', (c) => chunks.push(c));
          res.on('end', () =>
            resolve({
              data: Buffer.concat(chunks),
              mime: String(res.headers['content-type'] ?? 'application/octet-stream'),
            }),
          );
        },
      );
      req.on('timeout', () => req.destroy(new Error(`media timeout after ${budgetMs}ms`)));
      req.on('error', reject);
      req.end();
    });
  })();
}

// ---------------------------------------------------------------------------
// stdio bridge loop (harness ↔ phone)
// ---------------------------------------------------------------------------

export function emit(obj) {
  process.stdout.write(JSON.stringify(obj) + '\n');
}

export function fail(id, code, message) {
  emit({ jsonrpc: '2.0', id: id ?? null, error: { code, message } });
}

export async function handleStdioLine(line, cfg, deps = {}) {
  let msg;
  try {
    msg = JSON.parse(line);
  } catch {
    return fail(undefined, -32700, 'Parse error');
  }
  const reqId = msg?.id;
  const method = msg?.method ?? '';
  const headers = buildHeaders(cfg.token, cfg.protocol, method);
  const budgetMs = effectiveTimeout(msg, budgets(cfg));
  let status, contentType, raw;
  try {
    ({ status, contentType, body: raw } = deps.postMcp
      ? await deps.postMcp(cfg, line, headers, budgetMs)
      : await postMcp(cfg, line, headers, budgetMs, deps));
  } catch (err) {
    const detail = err instanceof Error ? err.message : String(err);
    if (/fingerprint mismatch/i.test(detail)) return fail(reqId, -32603, detail);
    return fail(reqId, -32603, actionableTransportError(detail, cfg.url));
  }
  if (status === 401 || status === 403) {
    return fail(reqId, -32001, 'Missing or invalid bearer token (check PHONE_MCP_TOKEN)');
  }
  if (status === 413) return fail(reqId, -32600, 'Body too large');
  if (status === 202 || !raw) return; // notification ack — nothing to emit on stdio
  if ((contentType || '').includes('text/event-stream')) {
    const payloads = parseSseData(raw);
    if (!payloads.length) return fail(reqId, -32603, 'Empty SSE stream from server');
    for (const payload of payloads) {
      try {
        emit(JSON.parse(payload));
      } catch {
        continue;
      }
    }
    return;
  }
  try {
    emit(JSON.parse(raw));
  } catch {
    return fail(reqId, -32603, `Upstream returned HTTP ${status} (non-JSON)`);
  }
}
