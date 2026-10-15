/**
 * USB transport helpers (zero dependencies, Node 18+).
 *
 * Verified: USB uses `adb forward tcp:PORT tcp:PORT` (host :PORT → device
 * :PORT over the cable) plus a loopback URL such as
 * https://127.0.0.1:8080/mcp. `adb reverse` is WRONG here — the phone
 * already binds :8080, so a reverse forward collides on-device.
 *
 * Pure helpers take plain values (no child_process); `runAdb` is the only
 * thin wrapper around node:child_process and accepts an injected runner
 * so tests never shell out. Mirrors the Python twin (`usb.py`).
 */

import { spawnSync } from 'node:child_process';

export const USB_DEFAULT_PORT = 8080;

const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]']);

/** True when the URL targets this host (USB `adb forward` mode). */
export function isLoopbackUrl(url) {
  try {
    return LOOPBACK_HOSTS.has(String(new URL(String(url)).hostname).toLowerCase());
  } catch {
    return false;
  }
}

/** Canonical USB URL for a port (phone serves TLS, even over the cable). */
export function loopbackUrlForPort(port = USB_DEFAULT_PORT) {
  const p = Number(port) || USB_DEFAULT_PORT;
  return `https://127.0.0.1:${p}/mcp`;
}

/** Port carried by a URL, defaulting to the USB port (never throws). */
export function portOfUrl(url) {
  try {
    return Number(new URL(String(url)).port) || USB_DEFAULT_PORT;
  } catch {
    return USB_DEFAULT_PORT;
  }
}

/**
 * Rewrite any URL to its loopback equivalent (scheme + path preserved).
 * Empty/unparseable input falls back to the canonical loopback URL —
 * `--usb` means "use the cable", so there is nothing to fail closed on.
 */
export function rewriteUrlToLoopback(url, port = 0) {
  const raw = String(url ?? '').trim();
  let u;
  try {
    u = new URL(raw);
  } catch {
    return loopbackUrlForPort(Number(port) || USB_DEFAULT_PORT);
  }
  u.hostname = '127.0.0.1';
  u.port = String(Number(port) || Number(u.port) || USB_DEFAULT_PORT);
  return u.toString();
}

/**
 * Parse `adb devices` output into [{ serial, state }].
 * Skips the header line, blanks, and malformed rows.
 */
export function parseAdbDevices(output) {
  const out = [];
  for (const line of String(output ?? '').split('\n')) {
    const t = line.trim();
    if (!t || t.toLowerCase().startsWith('list of devices')) continue;
    const parts = t.split(/\s+/);
    if (parts.length < 2) continue;
    out.push({ serial: parts[0], state: parts[1].toLowerCase() });
  }
  return out;
}

/**
 * Parse `adb forward --list` output into [{ serial, local, remote }].
 * Each row looks like `<serial> tcp:<port> tcp:<port>`.
 */
export function parseAdbForwardList(output) {
  const out = [];
  for (const line of String(output ?? '').split('\n')) {
    const t = line.trim();
    if (!t) continue;
    const parts = t.split(/\s+/);
    if (parts.length < 3) continue;
    out.push({ serial: parts[0], local: parts[1], remote: parts[2] });
  }
  return out;
}

/** argv for `adb forward` (host :port → device :port); `-s` when pinned. */
export function adbForwardArgs(serial = '', port = USB_DEFAULT_PORT) {
  const p = Number(port) || USB_DEFAULT_PORT;
  const spec = `tcp:${p}`;
  const s = String(serial ?? '').trim();
  return s ? ['-s', s, 'forward', spec, spec] : ['forward', spec, spec];
}

/** argv to drop one `adb forward` rule again (idempotent: absent is clean). */
export function adbRemoveArgs(serial = '', port = USB_DEFAULT_PORT) {
  const p = Number(port) || USB_DEFAULT_PORT;
  const spec = `tcp:${p}`;
  const s = String(serial ?? '').trim();
  return s ? ['-s', s, 'forward', '--remove', spec] : ['forward', '--remove', spec];
}

/** Forward-list entries touching `tcp:port` (optionally scoped to a serial). */
export function findForwards(forwards, serial = '', port = USB_DEFAULT_PORT) {
  const p = Number(port) || USB_DEFAULT_PORT;
  const s = String(serial ?? '').trim();
  return (Array.isArray(forwards) ? forwards : []).filter(
    (f) => f?.local === `tcp:${p}` && (!s || f?.serial === s),
  );
}

/** One-line setup hint for USB mode (forward, never reverse). */
export function usbSetupHint(port = USB_DEFAULT_PORT) {
  const p = Number(port) || USB_DEFAULT_PORT;
  return (
    `USB mode: run \`adb forward tcp:${p} tcp:${p}\`, then use ${loopbackUrlForPort(p)} ` +
    '(setup: scripts/usb-mcp.sh setup)'
  );
}

/**
 * Pick the adb target for a USB forward (pure; never shells out).
 * Returns `{ serial, error }` — exactly one is non-empty. Mirrors the
 * Python twin (`pick_usb_device`): explicit serial must be in `device`
 * state; without one, a single `device`-state attachment is auto-picked.
 */
export function pickUsbDevice(devices, serial = '') {
  const wanted = String(serial ?? '').trim();
  const list = Array.isArray(devices) ? devices : [];
  const bySerial = new Map(list.map((d) => [String(d?.serial ?? ''), d]));
  if (wanted) {
    const dev = bySerial.get(wanted);
    if (!dev) {
      const known = [...bySerial.keys()].sort().join(', ') || '(none attached)';
      return { serial: '', error: `device '${wanted}' not attached (known: ${known}) — check the USB cable / \`adb devices\`` };
    }
    const state = String(dev.state ?? '').toLowerCase();
    if (state === 'device') return { serial: wanted, error: '' };
    if (state === 'unauthorized') {
      return { serial: '', error: `device '${wanted}' is unauthorized — accept the USB-debugging RSA prompt on the phone, then retry` };
    }
    if (state === 'offline') {
      return { serial: '', error: `device '${wanted}' is offline — replug USB / run \`adb reconnect\`, then retry` };
    }
    return { serial: '', error: `device '${wanted}' in state '${state}' — replug USB and retry` };
  }
  const usable = list.filter((d) => String(d?.state ?? '').toLowerCase() === 'device');
  if (usable.length === 1) return { serial: String(usable[0].serial ?? ''), error: '' };
  if (usable.length === 0) {
    if (list.length) {
      const rows = list.map((d) => `${d?.serial ?? '?'} ${d?.state ?? '?'}`).join('; ');
      const states = new Set(list.map((d) => String(d?.state ?? '').toLowerCase()));
      let hint = `no usable device (attached: ${rows})`;
      if (states.has('unauthorized')) hint += ' — accept the USB-debugging RSA prompt on the phone';
      if (states.has('offline')) hint += ' — replug USB / run `adb reconnect`';
      return { serial: '', error: hint };
    }
    return { serial: '', error: 'no adb devices attached — plug in USB + enable USB debugging' };
  }
  const rows = usable.map((d) => String(d?.serial ?? '?')).join(', ');
  return { serial: '', error: `${usable.length} devices in 'device' state (${rows}) — pass --serial/-s to pick one (see \`adb devices -l\`)` };
}

/** Append the USB setup hint when the failing URL is loopback. */
export function actionableUsbError(detail, url = '') {
  const msg = String(detail ?? '');
  if (url && isLoopbackUrl(url)) {
    return `${msg} — ${usbSetupHint(portOfUrl(url))}`;
  }
  return msg;
}

/**
 * Thin `adb` runner. Only this function touches child_process; pass
 * `{ spawnSync: fake }` to avoid shelling out (tests do this).
 */
export function runAdb(args, deps = {}) {
  const runner = deps.spawnSync ?? spawnSync;
  try {
    const res = runner('adb', [...args], { encoding: 'utf8' });
    return {
      status: res?.status ?? null,
      stdout: String(res?.stdout ?? ''),
      stderr: String(res?.stderr ?? ''),
      error: res?.error ?? null,
    };
  } catch (err) {
    return { status: null, stdout: '', stderr: '', error: err };
  }
}
