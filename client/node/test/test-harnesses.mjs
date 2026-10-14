import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import {
  HARNESS_IDS,
  RENDERERS,
  bridgeCommand,
  cliAddHint,
  installRendered,
  renderAll,
  renderClaude,
  renderCodex,
  renderCursor,
  renderGemini,
  renderOpencode,
  renderVscode,
} from '../src/harnesses.mjs';

const URL = 'https://192.168.1.50:8080/mcp';
const SECRET = 'super-secret-token-123';

describe('renderers', () => {
  it('renders all 9 harnesses', () => {
    const rendered = renderAll(URL);
    assert.deepEqual(Object.keys(rendered).sort(), [...HARNESS_IDS].sort());
    assert.equal(HARNESS_IDS.length, 9);
    for (const [filename, content] of Object.values(rendered)) {
      assert.ok(filename, 'filename');
      assert.match(content, /phone-mcp/);
    }
  });

  it('never leaks a literal token', () => {
    for (const id of HARNESS_IDS) {
      const [, content] = RENDERERS[id](URL);
      // The literal URL is fine (not a secret); a token must never appear.
      assert.ok(!content.includes(SECRET), id);
      assert.ok(!content.includes('Bearer super-secret'), id);
    }
  });

  it('opencode shape (type local, env refs, enabled)', () => {
    const [, content] = renderOpencode(URL);
    const node = JSON.parse(content).mcp['phone-mcp'];
    assert.equal(node.type, 'local');
    assert.ok(node.enabled);
    assert.ok(node.environment.PHONE_MCP_TOKEN.includes('PHONE_MCP_TOKEN'));
  });

  it('claude shape (command/args/env)', () => {
    const [, content] = renderClaude(URL);
    const node = JSON.parse(content).mcpServers['phone-mcp'];
    assert.ok(node.command);
    assert.ok(node.env.PHONE_MCP_TOKEN.includes('PHONE_MCP_TOKEN'));
  });

  it('codex TOML shape (stdio block, no literal token)', () => {
    const [, content] = renderCodex(URL);
    assert.match(content, /\[mcp_servers\.phone-mcp\]/);
    assert.match(content, /command = /);
    assert.ok(!content.includes(SECRET));
  });

  it('vscode has servers + prompt inputs', () => {
    const [, content] = renderVscode(URL);
    const cfg = JSON.parse(content);
    assert.ok(cfg.servers['phone-mcp']);
    assert.ok(cfg.inputs.map((i) => i.id).includes('phoneMcpToken'));
  });

  it('gemini carries a timeout and no-auto-trust', () => {
    const [, content] = renderGemini(URL);
    const node = JSON.parse(content).mcpServers['phone-mcp'];
    assert.equal(node.timeout, 35000);
    assert.equal(node.trust, false);
  });

  it('bridge kinds switch the command', () => {
    for (const kind of ['phone-mcp', 'node', 'python']) {
      const [, content] = renderClaude(URL, kind);
      assert.ok(content.includes(bridgeCommand(kind)[0]), kind);
    }
    assert.throws(() => bridgeCommand('bogus'), /--bridge/);
  });

  it('node/python twins stay repo-relative', () => {
    const [, content] = renderCursor(URL, 'node');
    assert.ok(content.includes('bridges/phone-mcp-bridge.mjs'));
  });
});

describe('install', () => {
  it('opencode merge preserves existing servers', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'phone-mcp-'));
    const dest = path.join(dir, 'opencode.json');
    fs.writeFileSync(dest, JSON.stringify({ mcp: { other: { type: 'local' } } }));
    const [, content] = renderOpencode(URL);
    installRendered('opencode', content, dest);
    const merged = JSON.parse(fs.readFileSync(dest, 'utf8'));
    assert.ok(merged.mcp.other);
    assert.ok(merged.mcp['phone-mcp']);
  });

  it('codex append is idempotent', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'phone-mcp-'));
    const dest = path.join(dir, 'config.toml');
    const [, content] = renderCodex(URL);
    installRendered('codex', content, dest);
    const first = fs.readFileSync(dest, 'utf8');
    installRendered('codex', content, dest);
    assert.equal(fs.readFileSync(dest, 'utf8'), first);
    assert.equal(first.split('[mcp_servers.phone-mcp]').length - 1, 1);
  });

  it('vscode inputs dedupe on reinstall', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'phone-mcp-'));
    const dest = path.join(dir, 'mcp.json');
    const [, content] = renderVscode(URL);
    installRendered('vscode', content, dest);
    installRendered('vscode', content, dest);
    const ids = JSON.parse(fs.readFileSync(dest, 'utf8')).inputs.map((i) => i.id);
    assert.equal(ids.length, new Set(ids).size);
  });

  it('cursor merges mcpServers', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'phone-mcp-'));
    const dest = path.join(dir, 'mcp.json');
    const [, content] = renderCursor(URL);
    installRendered('cursor', content, dest);
    assert.ok(JSON.parse(fs.readFileSync(dest, 'utf8')).mcpServers['phone-mcp']);
  });

  it('refuses invalid JSON instead of clobbering', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'phone-mcp-'));
    const dest = path.join(dir, 'mcp.json');
    fs.writeFileSync(dest, '{broken');
    const [, content] = renderCursor(URL);
    assert.throws(() => installRendered('cursor', content, dest), /not valid JSON/);
  });

  it('emits copy-paste hints', () => {
    assert.match(cliAddHint('claude', URL), /claude mcp add/);
    assert.match(cliAddHint('codex', URL), /codex mcp add/);
  });
});
