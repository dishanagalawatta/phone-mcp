import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

import {
  buildHeaders,
  effectiveTimeout,
  handleStdioLine,
  messageDurationMs,
  parseSseData,
  rpc,
} from '../src/protocol.mjs';

import { bridgeEval } from './helpers.mjs';

function cfg(over = {}) {
  return {
    url: 'http://127.0.0.1:9/mcp',
    token: 't',
    sha256: '',
    cafile: '',
    insecure: false,
    protocol: '2025-06-18',
    timeoutMs: 35000,
    fastMs: 15000,
    marginMs: 10000,
    ...over,
  };
}

async function captureStdout(fn) {
  const orig = process.stdout.write;
  let out = '';
  process.stdout.write = (chunk) => {
    out += chunk;
    return true;
  };
  try {
    await fn();
  } finally {
    process.stdout.write = orig;
  }
  return out;
}

describe('headers', () => {
  it('always carries Bearer + SSE accept, version for tool calls', () => {
    const h = buildHeaders('tok', '2025-06-18', 'tools/call');
    assert.equal(h.Authorization, 'Bearer tok');
    assert.match(h.Accept, /text\/event-stream/);
    assert.equal(h['MCP-Protocol-Version'], '2025-06-18');
  });

  it('skips version for initialize + notifications (harness parity)', () => {
    assert.ok(!('MCP-Protocol-Version' in buildHeaders('t', '2025-06-18', 'initialize')));
    assert.ok(!('MCP-Protocol-Version' in buildHeaders('t', '2025-06-18', 'notifications/initialized')));
  });
});

describe('SSE + budgets', () => {
  it('extracts data payloads, ignores comments', () => {
    assert.deepEqual(parseSseData(': ping\n\ndata: {"a":1}\n\ndata: {"b":2}\n\n'), ['{"a":1}', '{"b":2}']);
    assert.deepEqual(parseSseData(''), []);
  });

  it('fast vs duration+margin vs ceiling', () => {
    const B = { ceiling: 35000, fast: 15000, margin: 10000 };
    for (const msg of [
      { method: 'initialize', params: {} },
      { method: 'tools/list', params: {} },
      { method: 'tools/call', params: { name: 'vibrate', arguments: {} } },
    ]) {
      assert.equal(effectiveTimeout(msg, B), 15000);
    }
    assert.equal(
      effectiveTimeout({ method: 'tools/call', params: { name: 'a', arguments: { durationMs: 2000 } } }, B),
      12000,
    );
    assert.equal(
      effectiveTimeout({ method: 'tools/call', params: { name: 'x', arguments: { durationMs: 3600000 } } }, B),
      35000,
    );
    assert.equal(messageDurationMs(null), 0);
  });

  it('stays in sync with the Node bridge twin', async () => {
    const got = await bridgeEval(`
      import('./bridges/phone-mcp-bridge.mjs').then((b) => {
        const out = {
          headers: [
            b.buildHeaders('tok', '2025-06-18', 'tools/call'),
            b.buildHeaders('t', '2025-06-18', 'initialize'),
            b.buildHeaders('t', '2025-06-18', 'notifications/initialized'),
          ],
          sse: b.parseSseData('data: {"a":1}\\n\\n'),
          budgets: [
            b.effectiveTimeout({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'vibrate', arguments: {} } }, { ceiling: 35000, fast: 15000, margin: 10000 }),
            b.effectiveTimeout({ jsonrpc: '2.0', id: 5, method: 'tools/call', params: { name: 'a', arguments: { durationMs: 2000 } } }, { ceiling: 35000, fast: 15000, margin: 10000 }),
            b.effectiveTimeout(null, { ceiling: 35000, fast: 15000, margin: 10000 }),
          ],
        };
        console.log(JSON.stringify(out));
        process.exit(0);
      });
    `);
    assert.deepEqual(
      [
        buildHeaders('tok', '2025-06-18', 'tools/call'),
        buildHeaders('t', '2025-06-18', 'initialize'),
        buildHeaders('t', '2025-06-18', 'notifications/initialized'),
      ],
      got.headers,
    );
    assert.deepEqual(parseSseData('data: {"a":1}\n\n'), got.sse);
    assert.deepEqual(
      [
        effectiveTimeout({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'vibrate', arguments: {} } }, { ceiling: 35000, fast: 15000, margin: 10000 }),
        effectiveTimeout({ jsonrpc: '2.0', id: 5, method: 'tools/call', params: { name: 'a', arguments: { durationMs: 2000 } } }, { ceiling: 35000, fast: 15000, margin: 10000 }),
        effectiveTimeout(null, { ceiling: 35000, fast: 15000, margin: 10000 }),
      ],
      got.budgets,
    );
  });
});

describe('rpc', () => {
  it('round-trips JSON', async () => {
    const deps = { postMcp: async () => ({ status: 200, contentType: 'application/json', body: '{"jsonrpc":"2.0","id":1,"result":{}}' }) };
    const [resp, err] = await rpc(cfg(), { jsonrpc: '2.0', id: 1, method: 'ping' }, null, deps);
    assert.equal(err, null);
    assert.ok('result' in resp);
  });

  it('maps 401 to a token hint', async () => {
    const deps = { postMcp: async () => ({ status: 401, contentType: 'text/plain', body: '' }) };
    const [resp, err] = await rpc(cfg(), { jsonrpc: '2.0', id: 1, method: 'tools/list' }, null, deps);
    assert.equal(resp, null);
    assert.match(err, /401/);
  });

  it('reads the first SSE envelope', async () => {
    const deps = {
      postMcp: async () => ({ status: 200, contentType: 'text/event-stream', body: 'data: {"jsonrpc":"2.0","id":2,"result":{"tools":[]}}\n\n' }),
    };
    const [resp, err] = await rpc(cfg(), { jsonrpc: '2.0', id: 2, method: 'tools/list' }, null, deps);
    assert.equal(err, null);
    assert.ok('result' in resp);
  });

  it('appends the pin hint on cert errors', async () => {
    const deps = { postMcp: async () => { throw new Error('self-signed certificate'); } };
    const [, err] = await rpc(cfg(), { jsonrpc: '2.0', id: 1, method: 'tools/list' }, null, deps);
    assert.match(err, /PHONE_MCP_SHA256/);
  });

  it('passes fingerprint mismatch through verbatim', async () => {
    const deps = { postMcp: async () => { throw new Error('TLS fingerprint mismatch: x'); } };
    const [, err] = await rpc(cfg(), { jsonrpc: '2.0', id: 1, method: 'tools/list' }, null, deps);
    assert.match(err, /fingerprint mismatch/);
  });
});

describe('handleStdioLine', () => {
  it('forwards results and maps 401 → -32001', async () => {
    const ok = await captureStdout(() =>
      handleStdioLine('{"jsonrpc":"2.0","id":3,"method":"tools/list","params":{}}', cfg(), {
        postMcp: async () => ({ status: 200, contentType: 'application/json', body: '{"jsonrpc":"2.0","id":3,"result":{}}' }),
      }),
    );
    assert.match(ok, /"result"/);
    const denied = await captureStdout(() =>
      handleStdioLine('{"jsonrpc":"2.0","id":4,"method":"tools/list","params":{}}', cfg(), {
        postMcp: async () => ({ status: 401, contentType: 'application/json', body: '' }),
      }),
    );
    assert.equal(JSON.parse(denied).error.code, -32001);
  });

  it('emits parse errors and fans out SSE in order', async () => {
    const bad = await captureStdout(() => handleStdioLine('not json', cfg()));
    assert.equal(JSON.parse(bad).error.code, -32700);
    const sse =
      'data: {"jsonrpc":"2.0","method":"notifications/x"}\n\n' +
      'data: {"jsonrpc":"2.0","id":8,"result":{}}\n\n';
    const out = await captureStdout(() =>
      handleStdioLine('{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"s","arguments":{}}}', cfg(), {
        postMcp: async () => ({ status: 200, contentType: 'text/event-stream', body: sse }),
      }),
    );
    const lines = out.trim().split('\n').map((l) => JSON.parse(l));
    assert.equal(lines.length, 2);
    assert.equal(lines[1].id, 8);
  });
});
