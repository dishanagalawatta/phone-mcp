import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  buildHeaders,
  effectiveTimeout,
  parseSseData,
} from '../src/protocol.mjs';
import {
  groupedFingerprint,
  normalizeFingerprint,
  pinMatches,
  resolveTls,
} from '../src/tls.mjs';
import { bridgeEval } from './helpers.mjs';

const VECTORS = JSON.parse(
  fs.readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..', 'vectors.json'), 'utf8'),
);

// Vectors travel to the bridge child via a temp file (robust against any
// quoting/escaping issues with inline `-e` scripts).
const VECTOR_FILE = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'phone-mcp-vec-')), 'vectors.json');
fs.writeFileSync(VECTOR_FILE, JSON.stringify(VECTORS));

// The bridge twin runs in an isolated child process (it exits on import
// without env), computing the same vectors for a three-way comparison.
async function bridgeVectors() {
  return bridgeEval(`
    Promise.all([import('./bridges/phone-mcp-bridge.mjs'), import('node:fs')]).then(([b, fs]) => {
      const V = JSON.parse(fs.readFileSync(${JSON.stringify(VECTOR_FILE)}, 'utf8'));
      const out = {
        tls: V.tlsModes.map((c) => b.resolveTls({ pin: c.input.pin, insecure: c.input.insecure, cafile: c.input.cafile })),
        headers: V.headers.map((c) => b.buildHeaders(c.token, c.protocol, c.method)),
        sse: V.sse.map((c) => b.parseSseData(c.body)),
        budgets: V.budgets.cases.map((c) => b.effectiveTimeout(c.message, { ceiling: V.budgets.params.ceiling, fast: V.budgets.params.fast, margin: V.budgets.params.margin })),
      };
      console.log(JSON.stringify(out));
      process.exit(0);
    });
  `);
}

describe('shared vectors (client/vectors.json)', () => {
  it('tlsModes: client and bridge match', async () => {
    const { tls } = await bridgeVectors();
    VECTORS.tlsModes.forEach((c, i) => {
      assert.deepEqual(
        resolveTls({ pin: c.input.pin, insecure: c.input.insecure, cafile: c.input.cafile }),
        c.expected,
        JSON.stringify(c.input),
      );
      assert.deepEqual(tls[i], c.expected, `bridge ${JSON.stringify(c.input)}`);
    });
  });

  it('headers: client and bridge match', async () => {
    const { headers } = await bridgeVectors();
    VECTORS.headers.forEach((c, i) => {
      assert.deepEqual(buildHeaders(c.token, c.protocol, c.method), c.expected, c.method);
      assert.deepEqual(headers[i], c.expected, `bridge ${c.method}`);
    });
  });

  it('sse: client and bridge match', async () => {
    const { sse } = await bridgeVectors();
    VECTORS.sse.forEach((c, i) => {
      assert.deepEqual(parseSseData(c.body), c.expected, `case ${i}`);
      assert.deepEqual(sse[i], c.expected, `bridge case ${i}`);
    });
  });

  it('budgets: client and bridge match', async () => {
    const { budgets } = await bridgeVectors();
    const p = VECTORS.budgets.params;
    VECTORS.budgets.cases.forEach((c, i) => {
      assert.equal(
        effectiveTimeout(c.message, { ceiling: p.ceiling, fast: p.fast, margin: p.margin }),
        c.expectedMs,
        `case ${i}`,
      );
      assert.equal(budgets[i], c.expectedMs, `bridge case ${i}`);
    });
  });

  it('fingerprints: normalize/grouped/pin', () => {
    for (const [raw, expected] of VECTORS.fingerprints.normalize) {
      assert.equal(normalizeFingerprint(raw), expected);
    }
    for (const [raw, expected] of VECTORS.fingerprints.grouped) {
      assert.equal(groupedFingerprint(raw), expected);
    }
    const digest = VECTORS.fingerprints.sha256abc;
    assert.equal(digest, createHash('sha256').update('abc').digest('hex'));
    assert.equal(pinMatches(Buffer.from('abc'), digest), true);
    assert.equal(pinMatches(Buffer.from('abd'), digest), false);
  });
});
