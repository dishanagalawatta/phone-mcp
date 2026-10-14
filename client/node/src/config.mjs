/**
 * Client configuration: env/QR/file loading + validation.
 *
 * Tokens stay in env vars / the local paired config file — never baked into
 * generated harness configs (harness files only carry `{env:...}` refs).
 * Zero dependencies. Node 18+.
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { normalizeFingerprint } from './tls.mjs';

export const DEFAULT_PROTOCOL = '2025-06-18';
export const DEFAULT_TIMEOUT_MS = 35000;
export const DEFAULT_FAST_MS = 15000;
export const DEFAULT_MARGIN_MS = 10000;

export const PAIRED_CONFIG_DEFAULT = path.join(os.homedir(), '.config', 'phone-mcp', 'config.json');

export function blankConfig() {
  return {
    url: '',
    token: '',
    sha256: '', // normalized hex (no colons, lowercase)
    cafile: '',
    insecure: false,
    protocol: DEFAULT_PROTOCOL,
    timeoutMs: DEFAULT_TIMEOUT_MS,
    fastMs: DEFAULT_FAST_MS,
    marginMs: DEFAULT_MARGIN_MS,
  };
}

/** Human-readable problems (empty == valid). */
export function validate(cfg) {
  const problems = [];
  if (!cfg.url) {
    problems.push('missing PHONE_MCP_URL (e.g. https://192.168.1.50:8080/mcp)');
  } else {
    let parts;
    try {
      parts = new URL(cfg.url);
    } catch {
      problems.push(`bad PHONE_MCP_URL ${JSON.stringify(cfg.url)}`);
    }
    if (parts) {
      if (parts.protocol !== 'https:' && parts.protocol !== 'http:') {
        problems.push('PHONE_MCP_URL must be https:// (http:// loopback only)');
      } else if (parts.protocol === 'http:' && !['localhost', '127.0.0.1', '[::1]'].includes(parts.hostname.toLowerCase())) {
        problems.push('http:// only allowed for loopback (phone serves TLS-only)');
      }
      if (parts.protocol === 'https:' && !parts.hostname) {
        problems.push(`bad PHONE_MCP_URL ${JSON.stringify(cfg.url)}`);
      }
    }
  }
  if (!cfg.token) problems.push('missing PHONE_MCP_TOKEN (dashboard pairing card / QR)');
  if (cfg.sha256 && cfg.sha256.length !== 64) {
    problems.push('PHONE_MCP_SHA256 must be 64 hex chars (dashboard fingerprint, colons ok)');
  }
  if (cfg.cafile && !fs.existsSync(cfg.cafile)) {
    problems.push(`PHONE_MCP_CAFILE not found: ${cfg.cafile}`);
  }
  if (!(cfg.timeoutMs > 0) || !(cfg.fastMs > 0) || !(cfg.marginMs >= 0)) {
    problems.push('timeout budgets must be positive (margin may be 0)');
  }
  return problems;
}

export function configHost(cfg) {
  try {
    return new URL(cfg.url).hostname || '';
  } catch {
    return '';
  }
}

export function configPort(cfg) {
  try {
    const parts = new URL(cfg.url);
    if (parts.port) return Number(parts.port);
    return parts.protocol === 'https:' ? 443 : 80;
  } catch {
    return 443;
  }
}

function ms(env, name, def) {
  const n = Number(env[name] ?? String(def));
  return Number.isFinite(n) ? Math.floor(n) : def;
}

export function fromEnv(env = process.env) {
  return {
    url: String(env.PHONE_MCP_URL ?? '').trim(),
    token: String(env.PHONE_MCP_TOKEN ?? ''),
    sha256: normalizeFingerprint(env.PHONE_MCP_SHA256 ?? ''),
    cafile: String(env.PHONE_MCP_CAFILE ?? '').trim(),
    insecure: (env.PHONE_MCP_INSECURE ?? '') === '1',
    protocol: String(env.PHONE_MCP_PROTOCOL ?? DEFAULT_PROTOCOL).trim() || DEFAULT_PROTOCOL,
    timeoutMs: ms(env, 'PHONE_MCP_TIMEOUT_MS', DEFAULT_TIMEOUT_MS),
    fastMs: ms(env, 'PHONE_MCP_FAST_MS', DEFAULT_FAST_MS),
    marginMs: ms(env, 'PHONE_MCP_MARGIN_MS', DEFAULT_MARGIN_MS),
  };
}

/**
 * Parse the dashboard QR envelope `{"url","token","sha256"}`.
 * Throws on missing fields / non-TLS URL / bad fingerprint (fail closed).
 */
export function parseQrPayload(raw) {
  let obj;
  try {
    obj = JSON.parse(raw);
  } catch (err) {
    throw new Error(`QR payload is not valid JSON: ${err.message}`);
  }
  if (!obj || typeof obj !== 'object') throw new Error('QR payload must be a JSON object');
  const url = String(obj.url ?? '').trim();
  const token = String(obj.token ?? '');
  const sha = normalizeFingerprint(String(obj.sha256 ?? ''));
  if (!url || !token || !sha) throw new Error('QR payload must contain url, token, sha256');
  if (!url.startsWith('https://')) throw new Error('QR url must be https://');
  if (sha.length !== 64) throw new Error('QR sha256 must be 64 hex chars');
  return { url, token, sha256: sha };
}

export function fromQr(raw, env = process.env) {
  const parsed = parseQrPayload(raw);
  const base = fromEnv(env);
  base.url = parsed.url;
  base.token = parsed.token;
  base.sha256 = parsed.sha256;
  return base;
}

export function loadPairedConfig(file = PAIRED_CONFIG_DEFAULT) {
  const obj = JSON.parse(fs.readFileSync(file, 'utf8'));
  const cfg = fromEnv({});
  cfg.url = String(obj.url ?? '');
  cfg.token = String(obj.token ?? '');
  cfg.sha256 = normalizeFingerprint(String(obj.sha256 ?? ''));
  cfg.cafile = String(obj.cafile ?? '');
  return cfg;
}

/** Persist pairing values with 0600 perms (token lives here, not in harness files). */
export function savePairedConfig(cfg, file = PAIRED_CONFIG_DEFAULT) {
  const parent = path.dirname(file);
  if (parent) fs.mkdirSync(parent, { recursive: true });
  const payload = { url: cfg.url, token: cfg.token, sha256: cfg.sha256, cafile: cfg.cafile };
  fs.writeFileSync(file, JSON.stringify(payload, null, 2) + '\n', { mode: 0o600 });
  try {
    fs.chmodSync(file, 0o600);
  } catch {
    /* non-POSIX filesystems may not support chmod — content is already written */
  }
  return file;
}
