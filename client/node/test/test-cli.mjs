import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { EXIT_FAIL, EXIT_OK, EXIT_USAGE, _test, main } from '../src/cli.mjs';

const CLEAN_ENV = {
  PATH: process.env.PATH,
  HOME: fs.mkdtempSync(path.join(os.tmpdir(), 'phone-mcp-home-')),
};

async function captureLogs(fn) {
  const origLog = console.log;
  let out = '';
  console.log = (...args) => {
    out += args.join(' ') + '\n';
  };
  try {
    const code = await fn();
    return [code, out];
  } finally {
    console.log = origLog;
  }
}

async function captureStderr(fn) {
  const orig = process.stderr.write;
  let out = '';
  process.stderr.write = (chunk) => {
    out += chunk;
    return true;
  };
  try {
    const code = await fn();
    return [code, out];
  } finally {
    process.stderr.write = orig;
  }
}

describe('parser', () => {
  it('accepts connection flags before and after the subcommand', () => {
    for (const argv of [
      ['--url', 'https://x/mcp', '--token', 't', 'configure', '--harness', 'generic', '--out', '/tmp/x'],
      ['configure', '--harness', 'generic', '--out', '/tmp/x', '--url', 'https://x/mcp', '--token', 't'],
    ]) {
      const { flags, cmd } = _test.parseArgv(argv);
      assert.equal(cmd, 'configure');
      assert.equal(flags.url, 'https://x/mcp');
      assert.equal(flags.token, 't');
    }
  });

  it('rejects unknown harnesses and flags', () => {
    assert.throws(() => _test.parseHarnessSelection('bogus'), /unknown harness/);
    assert.throws(() => _test.parseArgv(['list', '--bogus']), /unknown flag/);
    assert.deepEqual(_test.parseHarnessSelection('all').length, 9);
  });

  it('bare invocation prints help with usage exit', async () => {
    const [code] = await captureLogs(() => main([], CLEAN_ENV));
    assert.equal(code, EXIT_USAGE);
  });

  it('--help exits zero like the Python twin', async () => {
    const [code] = await captureLogs(() => main(['--help'], CLEAN_ENV));
    assert.equal(code, EXIT_OK);
  });

  it('--verbose logs the redacted config (parity with Python)', async () => {
    const [code, err] = await captureStderr(() =>
      main(['--verbose', '--url', 'https://192.168.1.50:8080/mcp', 'list'], CLEAN_ENV),
    );
    assert.equal(code, EXIT_FAIL); // no token → usage error, but after the verbose line
    assert.match(err, /config url=https:\/\/192\.168\.1\.50:8080\/mcp/);
    // With a token present the verbose line still runs (then the call fails
    // on network) — the secret VALUE must never reach stderr.
    const env = { ...CLEAN_ENV, PHONE_MCP_TOKEN: 's3cr3t-value-zzz' };
    const [code2, err2] = await captureStderr(() =>
      main(['-v', '--url', 'https://192.168.1.50:8080/mcp', 'list'], env),
    );
    assert.equal(code2, EXIT_FAIL);
    assert.match(err2, /config url=/);
    assert.ok(!err2.includes('s3cr3t-value-zzz'), 'token value must never appear in logs');
  });
});

describe('configure', () => {
  it('renders all harnesses to --out', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'phone-mcp-'));
    const [code] = await captureLogs(() =>
      main(['configure', '--harness', 'all', '--out', dir, '--url', 'https://192.168.1.50:8080/mcp', '--token', 't'], CLEAN_ENV),
    );
    assert.equal(code, EXIT_OK);
    const files = fs.readdirSync(dir);
    assert.ok(files.some((f) => f.includes('opencode')));
    assert.ok(files.some((f) => f.includes('codex')));
    assert.ok(files.some((f) => f.includes('vscode')));
    assert.equal(files.length, 9);
  });

  it('installs project-scoped targets', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'phone-mcp-'));
    const [code] = await captureLogs(() =>
      main(
        ['--url', 'https://192.168.1.50:8080/mcp', '--token', 't', 'configure',
          '--harness', 'claude vscode', '--out', path.join(dir, 'gen'), '--install', '--cwd', dir],
        CLEAN_ENV,
      ),
    );
    assert.equal(code, EXIT_OK);
    assert.ok(fs.existsSync(path.join(dir, '.mcp.json')));
    assert.ok(fs.existsSync(path.join(dir, '.vscode', 'mcp.json')));
  });
});

describe('pair', () => {
  it('saves QR pairing with 0600 perms', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'phone-mcp-'));
    const out = path.join(dir, 'config.json');
    const qr = JSON.stringify({ url: 'https://192.168.1.50:8080/mcp', token: 'abc', sha256: 'a'.repeat(64) });
    const [code] = await captureLogs(() => main(['pair', '--qr', qr, '--out', out], CLEAN_ENV));
    assert.equal(code, EXIT_OK);
    assert.equal((fs.statSync(out).mode & 0o777).toString(8), '600');
  });

  it('rejects bad QR payloads', async () => {
    const [code] = await captureLogs(() =>
      main(['pair', '--qr', '{"url":"http://x"}', '--out', path.join(os.tmpdir(), 'never.json')], CLEAN_ENV),
    );
    assert.notEqual(code, EXIT_OK);
  });

  it('pair honors the passed env, not process.env', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'phone-mcp-'));
    const out = path.join(dir, 'config.json');
    const env = { ...CLEAN_ENV, PHONE_MCP_TOKEN: 'env-token-xyz' };
    const [code] = await captureLogs(() =>
      main(['pair', '--url', 'https://192.168.1.50:8080/mcp',
        '--sha256', 'a'.repeat(64), '--out', out], env),
    );
    assert.equal(code, EXIT_OK);
    assert.equal(JSON.parse(fs.readFileSync(out, 'utf8')).token, 'env-token-xyz');
  });
});
