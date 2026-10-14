/**
 * TLS helpers: TOFU fingerprint pinning + verified CA-file mode.
 *
 * Canonical logic shared by the client CLI and (by parity test) the
 * standalone `bridges/` twins and the Python client. Modes:
 * - `SHA256` pin — capture-then-anchor: hash-compare the presented leaf
 *   against the dashboard fingerprint, then chain-verify the data leg
 *   against that exact leaf (in-memory anchor).
 * - `CAFILE` — PEM from `phone-mcp export-ca`; real chain verification
 *   against the pinned leaf; hostname check skipped (DHCP churn breaks SAN
 *   matching; the trust anchor IS the phone identity).
 * - pin + cafile combined (strongest).
 * - `INSECURE=1` — explicit trusted-LAN opt-out only.
 * - default — stock chain verification (fails closed on self-signed).
 *
 * Zero dependencies. Node 18+.
 */

import { createHash } from 'node:crypto';
import fs from 'node:fs';
import net from 'node:net';
import tls from 'node:tls';

export const ALPN_PROTOCOLS = ['http/1.1'];

export function normalizeFingerprint(hex) {
  return String(hex ?? '').replace(/[^0-9a-fA-F]/g, '').toLowerCase();
}

export function groupedFingerprint(hex) {
  const h = normalizeFingerprint(hex);
  return h ? h.toUpperCase().match(/../g).join(':') : '';
}

export function pinMatches(der, pin) {
  if (!der || der.length === 0) return false;
  return createHash('sha256').update(der).digest('hex') === normalizeFingerprint(pin);
}

/**
 * Pure TLS-mode resolution (unit-testable; bridges + Python twin mirror it).
 * Mirrors `resolveTls` in bridges/phone-mcp-bridge.mjs exactly.
 */
export function resolveTls({ pin, insecure, cafile }) {
  const hasPin = normalizeFingerprint(pin) !== '';
  const hasCa = Boolean(cafile);
  return {
    rejectUnauthorized: !insecure && (hasCa || !hasPin),
    skipHostnameCheck: hasPin || hasCa,
    verifyPin: hasPin,
  };
}

export function derToPem(der) {
  const b64 = Buffer.from(der).toString('base64');
  const lines = b64.match(/.{1,64}/g).join('\n');
  return `-----BEGIN CERTIFICATE-----\n${lines}\n-----END CERTIFICATE-----\n`;
}

/**
 * Capture the presented leaf cert via a throwaway TLS handshake.
 * rejectUnauthorized:false here is safe: the caller hash-compares the DER
 * against the dashboard pin before trusting it (fail-closed TOFU).
 */
export function preflightDer(host, port, timeoutMs) {
  return new Promise((resolve, reject) => {
    let done = false;
    const finish = (fn, arg) => {
      if (!done) {
        done = true;
        fn(arg);
      }
    };
    const sock = tls.connect(
      {
        host,
        port,
        // SNI servername must be a DNS name — Node rejects IP literals here
        // (phone LAN URLs are almost always IPs; ALPN still negotiates).
        ...(net.isIP(host) ? {} : { servername: host }),
        rejectUnauthorized: false,
        ALPNProtocols: [...ALPN_PROTOCOLS],
        timeout: timeoutMs,
      },
      () => {
        let der;
        try {
          der = sock.getPeerCertificate(true)?.raw;
        } catch (err) {
          finish(reject, err);
          sock.destroy();
          return;
        }
        sock.destroy();
        if (!der || der.length === 0) finish(reject, new Error('no peer certificate presented'));
        else finish(resolve, Buffer.from(der));
      },
    );
    sock.on('timeout', () => {
      sock.destroy(new Error(`TLS capture timeout after ${timeoutMs}ms`));
    });
    sock.on('error', (err) => finish(reject, err));
  });
}

/**
 * TOFU capture + hash-compare. Resolves { der, actualHex }.
 * Throws with an actionable message on mismatch (callers map it to -32603).
 */
export async function verifyPinAgainstHost(host, port, pin, timeoutMs, deps = {}) {
  const capture = deps.preflightDer ?? preflightDer;
  const der = await capture(host, port, timeoutMs);
  const actual = der && der.length ? createHash('sha256').update(der).digest('hex') : '';
  if (!pinMatches(der, pin)) {
    throw new Error(
      `TLS fingerprint mismatch: expected ${groupedFingerprint(pin)}, ` +
        `got ${groupedFingerprint(actual) || '(none)'} ` +
        '— re-verify against the dashboard (phone may have rotated its identity)',
    );
  }
  return { der, actual };
}

/**
 * Fail-closed leaf-cert export for verified direct-remote mode.
 * Writes NOTHING unless the presented cert hashes to the dashboard pin.
 * Mirrors scripts/export-ca.sh.
 */
export async function exportCaPem(host, port, pin, outPath, timeoutMs = 15000, deps = {}) {
  const { der } = await verifyPinAgainstHost(host, port, pin, timeoutMs, deps);
  if (!der) throw new Error('unreachable: pin verified but no DER captured');
  const pem = derToPem(der);
  const parent = outPath.includes('/') ? outPath.slice(0, outPath.lastIndexOf('/')) : '';
  if (parent) fs.mkdirSync(parent, { recursive: true });
  const tmp = `${outPath}.tmp`;
  fs.writeFileSync(tmp, pem.endsWith('\n') ? pem : pem + '\n');
  fs.renameSync(tmp, outPath);
  return outPath;
}
