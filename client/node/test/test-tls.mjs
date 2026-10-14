import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import {
  derToPem,
  exportCaPem,
  groupedFingerprint,
  normalizeFingerprint,
  pinMatches,
  resolveTls,
  verifyPinAgainstHost,
} from '../src/tls.mjs';

import { bridgeEval } from './helpers.mjs';

describe('fingerprints', () => {
  it('normalizes case/colons/whitespace', () => {
    assert.equal(normalizeFingerprint('AA:bb:CC dd'), 'aabbccdd');
    assert.equal(normalizeFingerprint(''), '');
  });

  it('groups round-trip', () => {
    assert.equal(groupedFingerprint('aabbcc'), 'AA:BB:CC');
  });

  it('pinMatches known vector (sha256 of "abc")', () => {
    const digest = createHash('sha256').update('abc').digest('hex'); // ba7816bf…
    assert.equal(pinMatches(Buffer.from('abc'), digest), true);
    assert.equal(pinMatches(Buffer.from('abc'), digest.toUpperCase().match(/../g).join(':')), true);
    assert.equal(pinMatches(Buffer.from('abd'), digest), false);
    assert.equal(pinMatches(Buffer.alloc(0), digest), false);
    assert.equal(pinMatches(null, digest), false);
  });
});

describe('resolveTls', () => {
  it('matches the 6 documented modes', () => {
    assert.deepEqual(resolveTls({ pin: '', insecure: false, cafile: '' }),
      { rejectUnauthorized: true, skipHostnameCheck: false, verifyPin: false });
    assert.deepEqual(resolveTls({ pin: 'AA:BB', insecure: false, cafile: '' }),
      { rejectUnauthorized: false, skipHostnameCheck: true, verifyPin: true });
    assert.deepEqual(resolveTls({ pin: '', insecure: false, cafile: '/tmp/ca.pem' }),
      { rejectUnauthorized: true, skipHostnameCheck: true, verifyPin: false });
    assert.deepEqual(resolveTls({ pin: 'aabb', insecure: false, cafile: '/tmp/ca.pem' }),
      { rejectUnauthorized: true, skipHostnameCheck: true, verifyPin: true });
    assert.deepEqual(resolveTls({ pin: '', insecure: true, cafile: '' }),
      { rejectUnauthorized: false, skipHostnameCheck: false, verifyPin: false });
    assert.deepEqual(resolveTls({ pin: '', insecure: true, cafile: '/tmp/ca.pem' }),
      { rejectUnauthorized: false, skipHostnameCheck: true, verifyPin: false });
  });

  it('stays in sync with bridges/phone-mcp-bridge.mjs', async () => {
    const got = await bridgeEval(`
      import('./bridges/phone-mcp-bridge.mjs').then((b) => {
        const cases = [
          { pin: '', insecure: false, cafile: '' },
          { pin: 'AA:BB', insecure: false, cafile: '' },
          { pin: '', insecure: false, cafile: '/tmp/ca.pem' },
          { pin: 'aabb', insecure: false, cafile: '/tmp/ca.pem' },
          { pin: '', insecure: true, cafile: '' },
          { pin: '', insecure: true, cafile: '/tmp/ca.pem' },
        ];
        console.log(JSON.stringify(cases.map((c) => b.resolveTls(c))));
        process.exit(0);
      });
    `);
    const cases = [
      { pin: '', insecure: false, cafile: '' },
      { pin: 'AA:BB', insecure: false, cafile: '' },
      { pin: '', insecure: false, cafile: '/tmp/ca.pem' },
      { pin: 'aabb', insecure: false, cafile: '/tmp/ca.pem' },
      { pin: '', insecure: true, cafile: '' },
      { pin: '', insecure: true, cafile: '/tmp/ca.pem' },
    ];
    assert.deepEqual(cases.map((c) => resolveTls(c)), got);
  });
});

describe('exportCaPem', () => {
  it('writes nothing on mismatch (fail-closed)', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'phone-mcp-'));
    const out = path.join(dir, 'ca.pem');
    await assert.rejects(
      exportCaPem('127.0.0.1', 8080, 'b'.repeat(64), out, 1000, {
        preflightDer: async () => Buffer.from('presented-cert-bytes'),
      }),
      /fingerprint mismatch/,
    );
    assert.equal(fs.existsSync(out), false);
  });

  it('writes PEM on match', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'phone-mcp-'));
    const presented = Buffer.from('real-leaf-bytes');
    const pin = createHash('sha256').update(presented).digest('hex');
    const out = path.join(dir, 'sub', 'phone-mcp-ca.pem');
    const got = await exportCaPem('127.0.0.1', 8080, pin, out, 1000, {
      preflightDer: async () => presented,
    });
    assert.equal(got, out);
    assert.match(fs.readFileSync(out, 'utf8'), /BEGIN CERTIFICATE/);
  });

  it('derToPem round-trips through base64', () => {
    const pem = derToPem(Buffer.from('hello-leaf'));
    assert.match(pem, /BEGIN CERTIFICATE/);
    assert.equal(Buffer.from(pem.split('\n')[1], 'base64').toString(), 'hello-leaf');
  });

  it('verifyPinAgainstHost surfaces dashboard hint', async () => {
    await assert.rejects(
      verifyPinAgainstHost('127.0.0.1', 8080, 'c'.repeat(64), 1000, {
        preflightDer: async () => Buffer.from('other'),
      }),
      /dashboard/,
    );
  });

  it('captures the leaf over real TLS with an IP literal (no SNI)', async (t) => {
    const { execFileSync } = await import('node:child_process');
    const tlsMod = await import('node:tls');
    try {
      execFileSync('openssl', ['version'], { stdio: 'ignore' });
    } catch {
      t.skip('openssl CLI not available');
      return;
    }
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'phone-mcp-'));
    const key = path.join(dir, 'k.pem');
    const crt = path.join(dir, 'c.pem');
    execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-keyout', key,
      '-out', crt, '-days', '1', '-nodes', '-subj', '/CN=127.0.0.1'], { stdio: 'ignore' });
    const server = tlsMod.createServer(
      { key: fs.readFileSync(key), cert: fs.readFileSync(crt) },
      (sock) => sock.on('error', () => {}),
    );
    await new Promise((res) => server.listen(0, '127.0.0.1', res));
    try {
      const { preflightDer } = await import('../src/tls.mjs');
      const der = await preflightDer('127.0.0.1', server.address().port, 5000);
      const expected = execFileSync('openssl', ['x509', '-in', crt, '-outform', 'DER']);
      assert.deepEqual(der, expected);
      assert.ok(pinMatches(der, createHash('sha256').update(expected).digest('hex')));
    } finally {
      server.close();
    }
  });
});
