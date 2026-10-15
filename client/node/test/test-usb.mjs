import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

import {
  USB_DEFAULT_PORT,
  actionableUsbError,
  adbForwardArgs,
  adbRemoveArgs,
  findForwards,
  isLoopbackUrl,
  loopbackUrlForPort,
  parseAdbDevices,
  parseAdbForwardList,
  pickUsbDevice,
  portOfUrl,
  rewriteUrlToLoopback,
  runAdb,
  usbSetupHint,
} from '../src/usb.mjs';
import { actionableTransportError } from '../src/protocol.mjs';
import { _test } from '../src/cli.mjs';

describe('usb constants + loopback detection', () => {
  it('defaults to port 8080', () => {
    assert.equal(USB_DEFAULT_PORT, 8080);
  });

  it('recognizes loopback hosts', () => {
    for (const url of [
      'https://127.0.0.1:8080/mcp',
      'http://127.0.0.1:8080/mcp',
      'https://localhost:8080/mcp',
      'https://[::1]:8080/mcp',
    ]) {
      assert.equal(isLoopbackUrl(url), true, url);
    }
  });

  it('rejects LAN, remote, and garbage URLs', () => {
    for (const url of [
      'https://192.168.1.50:8080/mcp',
      'https://10.0.0.5:8080/mcp',
      'https://example.com/mcp',
      'https://127.0.0.1.evil.com/mcp',
      '',
      'not a url',
      undefined,
    ]) {
      assert.equal(isLoopbackUrl(url), false, String(url));
    }
  });
});

describe('loopbackUrlForPort + portOfUrl', () => {
  it('builds the canonical USB url', () => {
    assert.equal(loopbackUrlForPort(), 'https://127.0.0.1:8080/mcp');
    assert.equal(loopbackUrlForPort(9090), 'https://127.0.0.1:9090/mcp');
    assert.equal(loopbackUrlForPort('bogus'), 'https://127.0.0.1:8080/mcp');
  });

  it('reads the port, defaulting to 8080', () => {
    assert.equal(portOfUrl('https://192.168.1.50:9999/mcp'), 9999);
    assert.equal(portOfUrl('https://192.168.1.50/mcp'), 8080);
    assert.equal(portOfUrl('garbage'), 8080);
  });
});

describe('rewriteUrlToLoopback', () => {
  it('rewrites LAN https to loopback, keeping scheme + path + port', () => {
    assert.equal(
      rewriteUrlToLoopback('https://192.168.1.50:8080/mcp'),
      'https://127.0.0.1:8080/mcp',
    );
  });

  it('honors an explicit port override', () => {
    assert.equal(
      rewriteUrlToLoopback('https://192.168.1.50:8080/mcp', 9090),
      'https://127.0.0.1:9090/mcp',
    );
  });

  it('falls back to the canonical url for empty/garbage input', () => {
    assert.equal(rewriteUrlToLoopback(''), 'https://127.0.0.1:8080/mcp');
    assert.equal(rewriteUrlToLoopback('garbage', 9090), 'https://127.0.0.1:9090/mcp');
  });
});

describe('adb output parsing', () => {
  it('parses device rows, skips header/blanks/garbage', () => {
    const out = parseAdbDevices('List of devices attached\nABC123\tdevice\nXYZ789\tunauthorized\n\nbogus\n');
    assert.deepEqual(out, [
      { serial: 'ABC123', state: 'device' },
      { serial: 'XYZ789', state: 'unauthorized' },
    ]);
    assert.deepEqual(parseAdbDevices('List of devices attached\n\n'), []);
    assert.deepEqual(parseAdbDevices(''), []);
  });

  it('parses forward rows, skips malformed lines', () => {
    const out = parseAdbForwardList('ABC123 tcp:8080 tcp:8080\nXYZ789 tcp:9090 tcp:9090\n\njunk\n');
    assert.deepEqual(out, [
      { serial: 'ABC123', local: 'tcp:8080', remote: 'tcp:8080' },
      { serial: 'XYZ789', local: 'tcp:9090', remote: 'tcp:9090' },
    ]);
    assert.deepEqual(parseAdbForwardList(''), []);
  });
});

describe('adbForwardArgs', () => {
  it('uses adb forward (never reverse), host:port → device:port', () => {
    assert.deepEqual(adbForwardArgs('', 8080), ['forward', 'tcp:8080', 'tcp:8080']);
    assert.deepEqual(adbForwardArgs('ABC123', 9090), ['-s', 'ABC123', 'forward', 'tcp:9090', 'tcp:9090']);
    assert.deepEqual(adbForwardArgs(), ['forward', 'tcp:8080', 'tcp:8080']);
    for (const args of [adbForwardArgs('', 8080), adbForwardArgs('s', 1)]) {
      assert.ok(!args.includes('reverse'), 'must never emit adb reverse');
    }
  });
});

describe('hints + actionable errors', () => {
  it('setup hint names adb forward with port + loopback url, no reverse', () => {
    const hint = usbSetupHint(8080);
    assert.match(hint, /adb forward tcp:8080 tcp:8080/);
    assert.match(hint, /https:\/\/127\.0\.0\.1:8080\/mcp/);
    assert.ok(!hint.includes('reverse'));
  });

  it('actionableUsbError appends the hint only for loopback urls', () => {
    const loop = actionableUsbError('ECONNREFUSED', 'https://127.0.0.1:8080/mcp');
    assert.match(loop, /ECONNREFUSED/);
    assert.match(loop, /adb forward/);
    assert.equal(actionableUsbError('ECONNREFUSED', 'https://192.168.1.50:8080/mcp'), 'ECONNREFUSED');
  });

  it('protocol transport errors append the usb hint for loopback', () => {
    const loop = actionableTransportError('connect ECONNREFUSED 127.0.0.1:8080', 'https://127.0.0.1:8080/mcp');
    assert.match(loop, /Upstream unreachable/);
    assert.match(loop, /adb forward/);
    const lan = actionableTransportError('connect ECONNREFUSED 192.168.1.50:8080', 'https://192.168.1.50:8080/mcp');
    assert.match(lan, /Upstream unreachable/);
    assert.ok(!lan.includes('adb forward'));
    // Back-compat: url is optional, timeout branch keeps its budget advice.
    assert.match(actionableTransportError('timeout after 5000ms'), /tool budget/);
  });
});

describe('pickUsbDevice', () => {
  it('auto-picks a single device-state attachment', () => {
    assert.deepEqual(pickUsbDevice([{ serial: 'ABC123', state: 'device' }], ''), { serial: 'ABC123', error: '' });
  });

  it('requires --serial when several are usable', () => {
    const devs = [{ serial: 'A', state: 'device' }, { serial: 'B', state: 'device' }];
    assert.match(pickUsbDevice(devs, '').error, /--serial/);
    assert.deepEqual(pickUsbDevice(devs, 'B'), { serial: 'B', error: '' });
  });

  it('hints RSA/reconnect for unauthorized/offline', () => {
    assert.match(pickUsbDevice([{ serial: 'A', state: 'unauthorized' }], '').error, /RSA/);
    assert.match(pickUsbDevice([{ serial: 'A', state: 'offline' }], 'A').error, /reconnect/);
    assert.match(pickUsbDevice([], '').error, /no adb devices/);
    assert.match(pickUsbDevice([{ serial: 'A', state: 'device' }], 'NOPE').error, /not attached/);
  });
});

describe('runAdb (injected runner, never shells out)', () => {
  it('passes argv through and normalizes the result', () => {
    const seen = [];
    const fake = (bin, args, opts) => {
      seen.push([bin, args, opts]);
      return { status: 0, stdout: 'ok\n', stderr: '' };
    };
    const res = runAdb(['forward', 'tcp:8080', 'tcp:8080'], { spawnSync: fake });
    assert.deepEqual(seen[0][0], 'adb');
    assert.deepEqual(seen[0][1], ['forward', 'tcp:8080', 'tcp:8080']);
    assert.deepEqual(res, { status: 0, stdout: 'ok\n', stderr: '', error: null });
  });

  it('surfaces runner failures without throwing', () => {
    const res = runAdb(['forward', 'tcp:8080', 'tcp:8080'], {
      spawnSync: () => ({ status: 1, stdout: '', stderr: 'device not found' }),
    });
    assert.equal(res.status, 1);
    assert.match(res.stderr, /device not found/);
    const thrown = runAdb(['forward', 'tcp:8080', 'tcp:8080'], {
      spawnSync: () => { throw new Error('spawn adb ENOENT'); },
    });
    assert.equal(thrown.status, null);
    assert.match(String(thrown.error?.message ?? thrown.error), /ENOENT/);
  });
});

describe('adbRemoveArgs + findForwards', () => {
  it('builds forward --remove argv (never reverse)', () => {
    assert.deepEqual(adbRemoveArgs('ABC123', 8080), ['-s', 'ABC123', 'forward', '--remove', 'tcp:8080']);
    assert.deepEqual(adbRemoveArgs('', 9090), ['forward', '--remove', 'tcp:9090']);
    for (const args of [adbRemoveArgs('s', 8080), adbRemoveArgs('', 1)]) {
      assert.ok(!args.includes('reverse'), 'must never emit adb reverse');
    }
  });

  it('finds mappings by port, optionally scoped to a serial', () => {
    const fwds = [
      { serial: 'A', local: 'tcp:8080', remote: 'tcp:8080' },
      { serial: 'B', local: 'tcp:8080', remote: 'tcp:8080' },
      { serial: 'A', local: 'tcp:9090', remote: 'tcp:9090' },
    ];
    assert.equal(findForwards(fwds, '', 8080).length, 2);
    assert.deepEqual(findForwards(fwds, 'B', 8080).map((f) => f.serial), ['B']);
    assert.deepEqual(findForwards(fwds, '', 1234), []);
    assert.deepEqual(findForwards([], '', 8080), []);
  });
});

describe('cli usb wiring', () => {
  it('parses the usb command, action positional, --usb switch, and --serial value', () => {
    assert.equal(_test.parseArgv(['usb', '--port', '8080']).cmd, 'usb');
    const withAction = _test.parseArgv(['usb', 'status', '--port', '8080']);
    assert.equal(withAction.cmd, 'usb');
    assert.deepEqual(withAction.positionals, ['status']);
    assert.ok(_test.parseArgv(['smoke', '--usb']).bools.has('usb'));
    assert.equal(_test.parseArgv(['usb', '--serial', 'ABC123']).flags.serial, 'ABC123');
  });

  it('--usb rewrites the resolved url to loopback', () => {
    const cfg = _test.resolveConfig(
      { usb: true, url: 'https://192.168.1.50:8080/mcp', token: 't' },
      {},
      '/nonexistent-paired.json',
    );
    assert.equal(cfg.url, 'https://127.0.0.1:8080/mcp');
    const custom = _test.resolveConfig(
      { usb: true, port: '9090', url: 'https://192.168.1.50:8080/mcp', token: 't' },
      {},
      '/nonexistent-paired.json',
    );
    assert.equal(custom.url, 'https://127.0.0.1:9090/mcp');
  });
});
