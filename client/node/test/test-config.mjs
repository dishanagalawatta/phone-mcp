import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import {
  fromEnv,
  loadPairedConfig,
  parseQrPayload,
  savePairedConfig,
  validate,
} from '../src/config.mjs';

describe('fromEnv', () => {
  it('defaults budgets and protocol', () => {
    const cfg = fromEnv({});
    assert.equal(cfg.protocol, '2025-06-18');
    assert.equal(cfg.timeoutMs, 35000);
    assert.equal(cfg.fastMs, 15000);
    assert.equal(cfg.marginMs, 10000);
  });

  it('normalizes sha and insecure flag', () => {
    assert.equal(fromEnv({ PHONE_MCP_SHA256: 'AA:BB:cc' }).sha256, 'aabbcc');
    assert.equal(fromEnv({ PHONE_MCP_INSECURE: '1' }).insecure, true);
    assert.equal(fromEnv({}).insecure, false);
  });
});

describe('validate', () => {
  it('flags missing url and token', () => {
    const problems = validate(fromEnv({}));
    assert.ok(problems.some((p) => p.includes('URL')));
    assert.ok(problems.some((p) => p.includes('TOKEN')));
  });

  it('rejects non-loopback http', () => {
    const cfg = fromEnv({});
    cfg.url = 'http://192.168.1.50:8080/mcp';
    cfg.token = 't';
    assert.ok(validate(cfg).some((p) => p.includes('loopback')));
  });

  it('accepts loopback http', () => {
    const cfg = fromEnv({});
    cfg.url = 'http://127.0.0.1:8080/mcp';
    cfg.token = 't';
    assert.ok(!validate(cfg).some((p) => p.includes('loopback')));
  });

  it('rejects short fingerprints', () => {
    const cfg = fromEnv({});
    cfg.url = 'https://x/mcp';
    cfg.token = 't';
    cfg.sha256 = 'aabb';
    assert.ok(validate(cfg).some((p) => p.includes('64')));
  });
});

describe('QR payload', () => {
  it('parses a valid envelope', () => {
    const raw = JSON.stringify({ url: 'https://192.168.1.50:8080/mcp', token: 'abc', sha256: 'AB:'.repeat(0) + 'ab'.repeat(32) });
    const parsed = parseQrPayload(raw);
    assert.equal(parsed.url, 'https://192.168.1.50:8080/mcp');
    assert.equal(parsed.sha256.length, 64);
  });

  it('rejects missing fields, http urls, and garbage', () => {
    assert.throws(() => parseQrPayload(JSON.stringify({ url: 'https://x/mcp' })), /url, token, sha256/);
    assert.throws(
      () => parseQrPayload(JSON.stringify({ url: 'http://x/mcp', token: 't', sha256: 'a'.repeat(64) })),
      /https/,
    );
    assert.throws(() => parseQrPayload('not json'), /JSON/);
  });
});

describe('paired file', () => {
  it('round-trips with 0600 perms', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'phone-mcp-'));
    const file = path.join(dir, 'sub', 'config.json');
    savePairedConfig({ url: 'https://192.168.1.50:8080/mcp', token: 'secret', sha256: 'a'.repeat(64), cafile: '' }, file);
    assert.equal((fs.statSync(file).mode & 0o777).toString(8), '600');
    const loaded = loadPairedConfig(file);
    assert.equal(loaded.url, 'https://192.168.1.50:8080/mcp');
    assert.equal(loaded.token, 'secret');
  });
});
