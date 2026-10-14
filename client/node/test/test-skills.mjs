import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { EXIT_OK, main } from '../src/cli.mjs';
import {
  SKILL_IDS,
  checkClean,
  projectSkillsDir,
  sourceDir,
  stageSkills,
} from '../src/skills.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '..', '..', '..');
const URL = 'https://192.168.1.50:8080/mcp';
const TOKEN = 'tok-live-secret-abc-123';

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

function treeFiles(root) {
  const out = {};
  const walk = (dir) => {
    for (const name of fs.readdirSync(dir).sort()) {
      const p = path.join(dir, name);
      if (fs.statSync(p).isDirectory()) walk(p);
      else out[path.relative(root, p)] = fs.readFileSync(p);
    }
  };
  walk(root);
  return out;
}

describe('skills bundle', () => {
  it('ships all 6 skills with SKILL.md + references', () => {
    assert.equal(SKILL_IDS.length, 6);
    for (const id of SKILL_IDS) {
      assert.ok(fs.existsSync(path.join(sourceDir(), id, 'SKILL.md')), id);
      assert.ok(fs.readdirSync(path.join(sourceDir(), id, 'references')).length >= 1, id);
    }
  });

  it('is byte-identical to the repo source of truth', () => {
    for (const id of SKILL_IDS) {
      const bundled = treeFiles(path.join(sourceDir(), id));
      const repo = treeFiles(path.join(REPO, '.agents', 'skills', id));
      assert.deepEqual(Object.keys(bundled).sort(), Object.keys(repo).sort(), `${id} file set`);
      for (const [rel, bytes] of Object.entries(repo)) {
        assert.ok(Buffer.from(bundled[rel]).equals(bytes), `${id}/${rel} bytes`);
      }
    }
  });

  it('stages all trees verbatim', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'phone-mcp-'));
    const staged = stageSkills(path.join(dir, 'skills'));
    assert.deepEqual(Object.keys(staged).sort(), [...SKILL_IDS].sort());
    for (const id of SKILL_IDS) {
      assert.ok(fs.existsSync(path.join(dir, 'skills', id, 'SKILL.md')), id);
    }
  });

  it('ignores static Bearer examples but refuses live secrets', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'phone-mcp-'));
    // Curl examples mention "Bearer" — only live secret *values* refuse.
    assert.equal(Object.keys(stageSkills(dir, [TOKEN])).length, 6);
    const planted = path.join(dir, 'planted.md');
    fs.writeFileSync(planted, `leaked ${TOKEN}\n`);
    assert.throws(() => checkClean([planted], [TOKEN]), /live secret/);
    assert.doesNotThrow(() => checkClean([planted], []));
  });

  it('projectSkillsDir targets .agents/skills', () => {
    assert.equal(projectSkillsDir('/proj'), path.join('/proj', '.agents', 'skills'));
  });
});

describe('configure skills', () => {
  it('stages skills next to rendered configs by default', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'phone-mcp-'));
    const [code, out] = await captureLogs(() =>
      main(['configure', '--harness', 'opencode', '--out', path.join(dir, 'gen'),
        '--url', URL, '--token', TOKEN], CLEAN_ENV),
    );
    assert.equal(code, EXIT_OK);
    for (const id of SKILL_IDS) {
      const f = path.join(dir, 'gen', 'skills', id, 'SKILL.md');
      assert.ok(fs.existsSync(f), id);
      assert.ok(!fs.readFileSync(f, 'utf8').includes(TOKEN), `${id} has no live token`);
    }
    assert.match(out, /skills staged/);
  });

  it('--no-skills skips staging', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'phone-mcp-'));
    const [code] = await captureLogs(() =>
      main(['configure', '--harness', 'opencode', '--out', path.join(dir, 'gen'),
        '--url', URL, '--token', TOKEN, '--no-skills'], CLEAN_ENV),
    );
    assert.equal(code, EXIT_OK);
    assert.ok(!fs.existsSync(path.join(dir, 'gen', 'skills')));
  });

  it('--install syncs project skills', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'phone-mcp-'));
    const [code, out] = await captureLogs(() =>
      main(['configure', '--harness', 'claude', '--out', path.join(dir, 'gen'),
        '--url', URL, '--token', TOKEN, '--install', '--cwd', dir], CLEAN_ENV),
    );
    assert.equal(code, EXIT_OK);
    for (const id of SKILL_IDS) {
      assert.ok(fs.existsSync(path.join(dir, '.agents', 'skills', id, 'SKILL.md')), id);
    }
    assert.match(out, /skills installed/);
  });
});
