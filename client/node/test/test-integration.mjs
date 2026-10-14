import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { execFile, execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import https from 'node:https';
import os from 'node:os';
import { promisify } from 'node:util';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { fetchMedia, smoke, toolsCall, toolsList, rpc } from '../src/protocol.mjs';
import { exportCaPem } from '../src/tls.mjs';

const execFileAsync = promisify(execFile);
const BIN = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'bin', 'phone-mcp.mjs');

const TOKEN = 'test-token';

function mockHandler({ pingable = true } = {}) {
  return (req, res) => {
    const send = (code, body, ctype = 'application/json') => {
      const raw = Buffer.isBuffer(body) ? body : Buffer.from(body);
      res.writeHead(code, { 'Content-Type': ctype, 'Content-Length': raw.length });
      res.end(raw);
    };
    if (req.method === 'GET' && req.url.startsWith('/media/')) {
      if (req.headers.authorization !== `Bearer ${TOKEN}`) return send(401, 'unauthorized', 'text/plain');
      return send(200, Buffer.from('\x89PNG-mock-bytes'), 'image/png');
    }
    if (req.method !== 'POST') return send(404, 'nope', 'text/plain');
    if (req.headers.authorization !== `Bearer ${TOKEN}`) return send(401, 'unauthorized', 'text/plain');
    let raw = '';
    req.on('data', (c) => (raw += c));
    req.on('end', () => {
      const msg = JSON.parse(raw || '{}');
      const id = msg.id ?? 1;
      const result = (r) => send(200, JSON.stringify({ jsonrpc: '2.0', id, result: r }));
      if (msg.method === 'initialize') {
        return result({ protocolVersion: '2025-06-18', serverInfo: { name: 'mock-phone', version: '0' } });
      }
      if (msg.method === 'tools/list') {
        const names = ['battery_read', 'device_info', 'vibrate', 'sensors_read', 'location_read',
          'audio_record', 'camera_capture', 'speaker_say', 'torch', 'clipboard_get', 'net_status', 'torch'];
        return result({ tools: names.map((name) => ({ name, description: `${name} tool` })) });
      }
      if (msg.method === 'tools/call') {
        const name = msg.params?.name ?? '';
        const text = name === 'device_info'
          ? JSON.stringify({ tlsSha256: '00'.repeat(32), model: 'Mock' })
          : JSON.stringify(name === 'battery_read' ? { level: 87 } : { ok: true });
        return result({ content: [{ type: 'text', text }], isError: false });
      }
      if (msg.method === 'ping' && pingable) return result({});
      return send(200, JSON.stringify({ jsonrpc: '2.0', id, error: { code: -32601, message: 'unknown' } }));
    });
  };
}

function startServer(handler) {
  return new Promise((resolve) => {
    const srv = http.createServer(handler);
    srv.listen(0, '127.0.0.1', () => resolve(srv));
  });
}

function baseCfg(srv, over = {}) {
  const port = srv.address().port;
  return {
    url: `http://127.0.0.1:${port}/mcp`,
    token: TOKEN,
    sha256: '',
    cafile: '',
    insecure: false,
    protocol: '2025-06-18',
    timeoutMs: 10000,
    fastMs: 5000,
    marginMs: 2000,
    ...over,
  };
}

describe('loopback mock phone', () => {
  let srv;
  before(async () => {
    srv = await startServer(mockHandler());
  });
  after(() => srv.close());

  it('smoke passes 4/4', async () => {
    const checks = await smoke(baseCfg(srv));
    assert.equal(checks.length, 4);
    for (const [name, ok, detail] of checks) assert.equal(ok, true, `${name}: ${detail}`);
  });

  it('list + call round-trip', async () => {
    const cfg = baseCfg(srv);
    const listed = await toolsList(cfg);
    assert.ok(listed.tools.length >= 10);
    const called = await toolsCall(cfg, 'vibrate', {});
    assert.equal(called.isError, false);
  });

  it('media downloads with mime', async () => {
    const { data, mime } = await fetchMedia(baseCfg(srv), 'abc123');
    assert.equal(mime, 'image/png');
    assert.ok(data.toString().startsWith('\x89PNG'));
  });

  it('wrong token surfaces 401', async () => {
    const [, err] = await rpc(baseCfg(srv, { token: 'wrong' }), { jsonrpc: '2.0', id: 1, method: 'tools/list' });
    assert.match(err, /401/);
  });
});

describe('spec compat (no ping)', () => {
  let srv;
  before(async () => {
    srv = await startServer(mockHandler({ pingable: false }));
  });
  after(() => srv.close());

  it('smoke never requires ping (2026-07-28 removed it)', async () => {
    const checks = await smoke(baseCfg(srv));
    for (const [name, ok, detail] of checks) assert.equal(ok, true, `${name}: ${detail}`);
  });
});

describe('TLS round-trip (pinned)', () => {
  let srv;
  let pin;
  let dir;
  let available = true;

  before(async () => {
    try {
      dir = fs.mkdtempSync(path.join(os.tmpdir(), 'phone-mcp-tls-'));
      const key = path.join(dir, 't.key');
      const crt = path.join(dir, 't.crt');
      execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-keyout', key,
        '-out', crt, '-days', '1', '-nodes', '-subj', '/CN=127.0.0.1',
        '-addext', 'subjectAltName=IP:127.0.0.1'], { stdio: 'ignore' });
      const der = execFileSync('openssl', ['x509', '-in', crt, '-outform', 'DER']);
      pin = createHash('sha256').update(der).digest('hex');
      srv = https.createServer(
        { key: fs.readFileSync(key), cert: fs.readFileSync(crt) },
        mockHandler(),
      );
      srv.on('tlsClientError', () => {}); // TOFU capture handshakes close early; expected noise
      await new Promise((res) => srv.listen(0, '127.0.0.1', res));
    } catch {
      available = false;
    }
  });

  after(() => {
    if (srv) srv.close();
  });

  const gate = (t) => {
    if (!available) {
      t.skip('openssl unavailable');
      return false;
    }
    return true;
  };
  const tlsCfg = (over = {}) => ({
    ...baseCfg(srv, { timeoutMs: 15000, fastMs: 8000, marginMs: 3000 }),
    url: `https://127.0.0.1:${srv.address().port}/mcp`,
    ...over,
  });

  it('smoke pinned passes with pin warning', async (t) => {
    if (!gate(t)) return;
    // Mock device_info reports tlsSha256=00*32 ≠ pin: probes PASS, but the
    // cross-check must surface a WARNING (proves the pin comparison ran).
    const checks = await smoke(tlsCfg({ sha256: pin }));
    assert.equal(checks.length, 4);
    for (const [name, ok, detail] of checks) assert.equal(ok, true, `${name}: ${detail}`);
    assert.match(checks[3][2], /WARNING/);
  });

  it('wrong pin fails every probe with mismatch', async (t) => {
    if (!gate(t)) return;
    const checks = await smoke(tlsCfg({ sha256: `00${pin.slice(2)}` }));
    assert.ok(checks.every(([, ok]) => !ok));
    for (const [, , detail] of checks) assert.match(detail, /fingerprint mismatch/);
  });

  it('cafile mode passes after fail-closed export', async (t) => {
    if (!gate(t)) return;
    const out = path.join(dir, 'phone-mcp-ca.pem');
    await exportCaPem('127.0.0.1', srv.address().port, pin, out, 10000);
    const checks = await smoke(tlsCfg({ sha256: '', cafile: out }));
    for (const [name, ok, detail] of checks) assert.equal(ok, true, `${name}: ${detail}`);
  });

  it('insecure LAN opt-out passes', async (t) => {
    if (!gate(t)) return;
    const checks = await smoke(tlsCfg({ insecure: true }));
    for (const [name, ok, detail] of checks) assert.equal(ok, true, `${name}: ${detail}`);
  });
});

describe('serve over stdio (spawned bin)', () => {
  it('emits a JSON-RPC error on stdout when upstream is dead', async () => {
    const child = execFileAsync(
      process.execPath,
      [BIN, 'serve'],
      {
        env: { ...process.env, PHONE_MCP_URL: 'http://127.0.0.1:1/mcp', PHONE_MCP_TOKEN: 't' },
        timeout: 15000,
      },
    );
    child.child.stdin.write('{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}\n');
    child.child.stdin.end();
    const { stdout, stderr } = await child;
    const msg = JSON.parse(stdout.trim());
    assert.equal(msg.id, 1);
    assert.equal(msg.error.code, -32603);
    assert.match(stderr, /\[phone-mcp\] bridge →/);
  });
});
