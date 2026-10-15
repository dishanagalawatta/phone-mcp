/**
 * `phone-mcp` CLI — one client for every harness (Node twin).
 *
 * Subcommands: serve | smoke | doctor | pair | configure | list | call | media | export-ca
 * Connection flags (--url/--token/--sha256/--cafile/--insecure) work before
 * AND after the subcommand. Zero dependencies. Node 18+.
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { __version__ } from './version.mjs';
import {
  PAIRED_CONFIG_DEFAULT,
  fromEnv,
  fromQr,
  loadPairedConfig,
  savePairedConfig,
  validate,
} from './config.mjs';
import {
  cliAddHint,
  defaultInstallPath,
  installRendered,
  INSPECTOR_GUIDE,
  HARNESS_IDS,
  RENDERERS,
  bridgeCommand,
} from './harnesses.mjs';
import { stageSkills, projectSkillsDir } from './skills.mjs';
import {
  actionableTransportError,
  fail,
  fetchMedia,
  handleStdioLine,
  log,
  smoke,
  toolsCall,
  toolsList,
} from './protocol.mjs';
import { exportCaPem, normalizeFingerprint } from './tls.mjs';
import {
  USB_DEFAULT_PORT,
  adbForwardArgs,
  adbRemoveArgs,
  findForwards,
  loopbackUrlForPort,
  parseAdbDevices,
  parseAdbForwardList,
  pickUsbDevice,
  rewriteUrlToLoopback,
  runAdb,
  usbSetupHint,
} from './usb.mjs';

export const EXIT_OK = 0;
export const EXIT_FAIL = 1;
export const EXIT_USAGE = 2;

const COMMANDS = ['serve', 'smoke', 'doctor', 'pair', 'configure', 'list', 'call', 'media', 'export-ca', 'usb'];

// Flags that take a value vs. boolean switches.
const VALUE_FLAGS = new Set([
  'url', 'token', 'sha256', 'cafile', 'timeout-ms', 'timeout',
  'qr', 'out', 'harness', 'bridge', 'cwd', 'args', 'args-file',
  'host', 'port', 'serial', 'skills-dir',
]);
const BOOL_FLAGS = new Set([
  'insecure', 'verbose', 'version', 'help',
  'print-env', 'install', 'print-only', 'json', 'no-skills', 'usb',
]);
const ALIAS = { v: 'verbose', h: 'help' };

function parseArgv(argv) {
  const flags = {};
  const bools = new Set();
  let cmd = null;
  const positionals = [];
  for (let i = 0; i < argv.length; i++) {
    const tok = argv[i];
    if (tok === '--') {
      positionals.push(...argv.slice(i + 1));
      break;
    }
    if (tok.startsWith('--')) {
      const eq = tok.indexOf('=');
      const name = (eq >= 0 ? tok.slice(2, eq) : tok.slice(2)).toLowerCase();
      if (BOOL_FLAGS.has(name)) {
        bools.add(name);
        continue;
      }
      if (VALUE_FLAGS.has(name)) {
        const val = eq >= 0 ? tok.slice(eq + 1) : argv[++i];
        if (val === undefined) throw new Error(`flag --${name} needs a value`);
        flags[name] = val;
        continue;
      }
      throw new Error(`unknown flag --${name}`);
    }
    if (tok.startsWith('-') && tok.length === 2) {
      const name = ALIAS[tok[1]];
      if (!name) throw new Error(`unknown flag ${tok}`);
      bools.add(name);
      continue;
    }
    if (!cmd && COMMANDS.includes(tok)) {
      cmd = tok;
      continue;
    }
    positionals.push(tok);
  }
  return { flags, bools, cmd, positionals };
}

function helpText() {
  return `phone-mcp — unified MCP client for Phone MCP (opencode, claude, codex, vscode, cursor, gemini, …)

Usage: phone-mcp [--url U --token T --sha256 H --cafile F --insecure] <command> [options]

Commands:
  serve                 stdio bridge: harness (stdin/stdout) ↔ phone HTTPS
  smoke | doctor        4-probe health check (initialize/list/battery/device_info) [--timeout S]
  pair                  validate pairing values and save them (0600) [--qr JSON --out PATH --print-env]
  configure             render/install harness configs [--harness all|… --bridge phone-mcp|node|python --out DIR --install --cwd DIR --print-only --no-skills --skills-dir DIR]
  list                  list phone tools [--json]
  call <name>           call a phone tool [--args JSON --args-file PATH --json]
  media <id>            download a /media/{id} blob [--out PATH]
  export-ca             pin phone leaf cert to a CA file [--host H --port P --out PATH --timeout S]
  usb                   set up USB transport via adb forward [setup|status|remove|check] [--port P --serial S]

Connection flags work before or after the command. Token sources: flags >
$PHONE_MCP_* env > ~/.config/phone-mcp/config.json (from \`pair\`).
USB (no LAN): \`adb forward tcp:8080 tcp:8080\`, then
PHONE_MCP_URL=https://127.0.0.1:8080/mcp — never \`adb reverse\` (the phone
already binds :8080, so reverse collides on-device). \`--usb\` rewrites the
URL to loopback for smoke/configure/export-ca (honors --port when given).`;
}

function resolveConfig(flags, env = process.env, pairedFile = PAIRED_CONFIG_DEFAULT) {
  const cfg = fromEnv(env);
  if (flags.url) cfg.url = String(flags.url).trim();
  if (flags.token) cfg.token = String(flags.token);
  if (flags.sha256) cfg.sha256 = normalizeFingerprint(flags.sha256);
  if (flags.cafile) cfg.cafile = String(flags.cafile).trim();
  if (flags.insecure) cfg.insecure = true;
  if (flags['timeout-ms']) {
    const n = Number(flags['timeout-ms']);
    if (Number.isFinite(n)) cfg.timeoutMs = Math.floor(n);
  }
  if (flags.usb) {
    // USB cable mode: traffic goes through `adb forward` to this host's
    // loopback, so rewrite any LAN URL (keeps the port unless --port given).
    const port = Number(flags.port) || portOf(cfg) || USB_DEFAULT_PORT;
    cfg.url = rewriteUrlToLoopback(cfg.url, port);
  }
  if ((!cfg.url || !cfg.token) && fs.existsSync(pairedFile)) {
    try {
      const paired = loadPairedConfig(pairedFile);
      if (!cfg.url) cfg.url = paired.url;
      if (!cfg.token) cfg.token = paired.token;
      if (!cfg.sha256) cfg.sha256 = paired.sha256;
      if (!cfg.cafile) cfg.cafile = paired.cafile;
    } catch (err) {
      log(`paired config unreadable: ${err.message}`);
    }
  }
  return cfg;
}

function failMsg(msg) {
  log(`error: ${msg}`);
  return EXIT_FAIL;
}

function redact(cfg, s) {
  return cfg.token ? String(s).split(cfg.token).join('<redacted>') : String(s);
}

// --- subcommands ------------------------------------------------------------

async function cmdSmoke(cfg, timeout) {
  const problems = validate(cfg);
  if (problems.length) {
    for (const p of problems) log(`config: ${p}`);
    if (!cfg.url || !cfg.token) return failMsg('set PHONE_MCP_URL + PHONE_MCP_TOKEN (or run `phone-mcp pair`)');
  }
  cfg.timeoutMs = Math.floor(timeout * 1000);
  log(`smoke → ${cfg.url} (pin=${cfg.sha256 ? cfg.sha256.slice(0, 12) + '…' : 'none'})`);
  const checks = await smoke(cfg);
  let failed = false;
  for (const [name, ok, detail] of checks) {
    console.log(`[${ok ? 'PASS' : 'FAIL'}] ${name}: ${redact(cfg, detail)}`);
    failed = failed || !ok;
  }
  if (failed) {
    console.log('SMOKE FAIL — check URL/token/fingerprint, dashboard server state, LAN/adb forward (scripts/usb-mcp.sh setup).');
    return EXIT_FAIL;
  }
  console.log('SMOKE PASS — harness configs generated by `phone-mcp configure` will connect.');
  return EXIT_OK;
}

function cmdPair(flags, env = process.env) {
  let cfg;
  if (flags.qr) {
    try {
      cfg = fromQr(flags.qr, env);
    } catch (err) {
      return failMsg(err.message);
    }
  } else {
    const base = fromEnv(env);
    cfg = {
      ...base,
      url: String(flags.url ?? '').trim() || base.url,
      token: flags.token ?? base.token,
      sha256: normalizeFingerprint(flags.sha256 ?? base.sha256),
      cafile: String(flags.cafile ?? '').trim() || base.cafile,
      insecure: Boolean(flags.insecure) || base.insecure,
    };
  }
  const problems = validate(cfg);
  if (problems.length) {
    for (const p of problems) log(`config: ${p}`);
    return EXIT_USAGE;
  }
  const out = flags.out ?? PAIRED_CONFIG_DEFAULT;
  savePairedConfig(cfg, out);
  log(`paired → ${out} (0600)`);
  if (flags['print-env']) {
    console.log(`export PHONE_MCP_URL="${cfg.url}"`);
    console.log('export PHONE_MCP_TOKEN="<saved — re-export from your shell history>"');
    console.log(`export PHONE_MCP_SHA256="${cfg.sha256}"`);
  } else {
    console.log(`paired ${cfg.url} → ${out}`);
    console.log('exports: PHONE_MCP_URL / PHONE_MCP_TOKEN / PHONE_MCP_SHA256 (see `pair --print-env`)');
  }
  return EXIT_OK;
}

function parseHarnessSelection(raw) {
  const parts = String(raw ?? 'all').replace(/,/g, ' ').split(/\s+/).map((p) => p.trim().toLowerCase()).filter(Boolean);
  if (!parts.length || (parts.length === 1 && parts[0] === 'all')) return [...HARNESS_IDS];
  const unknown = parts.filter((p) => !RENDERERS[p]);
  if (unknown.length) {
    throw new Error(`unknown harness(s): ${unknown.join(', ')} (choose from: all ${HARNESS_IDS.join(' ')})`);
  }
  return [...new Set(parts)].sort((a, b) => HARNESS_IDS.indexOf(a) - HARNESS_IDS.indexOf(b));
}

function cmdConfigure(flags, cfg) {
  if (!cfg.url) return failMsg('missing URL — pass --url or set PHONE_MCP_URL (pair first)');
  let wanted;
  try {
    wanted = parseHarnessSelection(flags.harness);
  } catch (err) {
    return failMsg(err.message);
  }
  const bridge = flags.bridge ?? 'phone-mcp';
  try {
    bridgeCommand(bridge);
  } catch (err) {
    return failMsg(err.message);
  }
  const out = flags.out ?? './generated-harness';
  const cwd = flags.cwd ?? '.';
  fs.mkdirSync(out, { recursive: true });
  log(`configure bridge=${bridge} out=${out}`);
  log(`url=${cfg.url} sha=${cfg.sha256 ? 'set' : '(none — verify dashboard fingerprint once)'}`);
  for (const id of wanted) {
    const [filename, content] = RENDERERS[id](cfg.url, bridge);
    const dest = `${out}/${filename}`;
    fs.writeFileSync(dest, content);
    console.log(`wrote ${dest}`);
    const hint = cliAddHint(id, cfg.url, bridge);
    if (hint) console.log(`  ${hint}`);
    if (flags.install) {
      const target = defaultInstallPath(id, cwd);
      try {
        installRendered(id, content, target);
        console.log(`  installed → ${target}`);
      } catch (err) {
        log(`install ${id} → ${target} failed: ${err.message}`);
        return EXIT_FAIL;
      }
    }
  }
  console.log('');
  console.log('next:');
  console.log(`  export PHONE_MCP_URL="${cfg.url}"`);
  console.log("  export PHONE_MCP_TOKEN='<paste-from-pairing-card>'");
  console.log(`  export PHONE_MCP_SHA256="${cfg.sha256 || '<dashboard-fingerprint>'}"`);
  console.log('  phone-mcp smoke  # expect 4× PASS before touching any harness');
  console.log(`  # guide: docs/harness.md · manual probe: ${INSPECTOR_GUIDE}`);
  if (!flags['no-skills']) {
    const skillsDir = (flags['skills-dir'] ?? '').trim() || path.join(out, 'skills');
    const secrets = [cfg.token, cfg.sha256].filter(Boolean);
    let staged;
    try {
      staged = stageSkills(skillsDir, secrets);
    } catch (err) {
      return failMsg(`skills staging failed: ${err.message}`);
    }
    const total = Object.values(staged).reduce((a, b) => a + b, 0);
    console.log(`skills staged → ${skillsDir} (${Object.keys(staged).length} skills, ${total} files; --no-skills to skip)`);
    if (flags.install) {
      const dest = projectSkillsDir(cwd);
      try {
        const installed = stageSkills(dest, secrets);
        const n = Object.values(installed).reduce((a, b) => a + b, 0);
        console.log(`  skills installed → ${dest} (${n} files; agents discover .agents/skills/ automatically)`);
      } catch (err) {
        return failMsg(`skills install failed: ${err.message}`);
      }
    }
  }
  if (flags['print-only']) {
    console.log('== rendered ==');
    for (const id of wanted) {
      const [filename, content] = RENDERERS[id](cfg.url, bridge);
      console.log(`--- ${filename}`);
      process.stdout.write(content.endsWith('\n') ? content : content + '\n');
    }
  }
  return EXIT_OK;
}

async function cmdList(cfg, asJson) {
  const problems = validate(cfg);
  if (problems.length) return failMsg(problems.join('; '));
  let result;
  try {
    result = await toolsList(cfg);
  } catch (err) {
    return failMsg(err.message);
  }
  if (asJson) {
    console.log(JSON.stringify(result, null, 2));
    return EXIT_OK;
  }
  const tools = result.tools ?? [];
  console.log(`${tools.length} tools:`);
  for (const t of tools) {
    console.log(`  ${String(t.name ?? '?').padEnd(22)} ${String(t.description ?? '').split('\n')[0].slice(0, 100)}`);
  }
  return EXIT_OK;
}

async function cmdCall(cfg, name, argsRaw, argsFile, asJson) {
  const problems = validate(cfg);
  if (problems.length) return failMsg(problems.join('; '));
  let args;
  if (argsFile) {
    try {
      args = JSON.parse(fs.readFileSync(argsFile, 'utf8'));
    } catch (err) {
      return failMsg(`unreadable --args-file: ${err.message}`);
    }
  } else {
    try {
      args = JSON.parse(argsRaw || '{}');
    } catch (err) {
      return failMsg(`bad --args JSON: ${err.message}`);
    }
  }
  if (!args || typeof args !== 'object' || Array.isArray(args)) return failMsg('--args must be a JSON object');
  log(`call ${name} args=${Object.keys(args).sort().join(',')}`);
  let result;
  try {
    result = await toolsCall(cfg, name, args);
  } catch (err) {
    return failMsg(err.message);
  }
  if (asJson) {
    console.log(JSON.stringify(result, null, 2));
    return EXIT_OK;
  }
  for (const item of result.content ?? []) {
    if (item && typeof item === 'object' && item.type === 'text') console.log(item.text ?? '');
    else console.log(JSON.stringify(item));
  }
  return result.isError ? EXIT_FAIL : EXIT_OK;
}

async function cmdMedia(cfg, mediaId, out) {
  const problems = validate(cfg);
  if (problems.length) return failMsg(problems.join('; '));
  log(`media ${mediaId} → ${out}`);
  let data, mime;
  try {
    ({ data, mime } = await fetchMedia(cfg, mediaId));
  } catch (err) {
    return failMsg(actionableTransportError(err.message, cfg.url));
  }
  const { dirname, resolve } = await import('node:path');
  const abs = resolve(out);
  fs.mkdirSync(dirname(abs), { recursive: true });
  fs.writeFileSync(abs, data);
  console.log(`wrote ${out} (${data.length} bytes, ${mime})`);
  return EXIT_OK;
}

async function cmdExportCa(flags, cfg) {
  const host = flags.host || hostOf(cfg);
  const port = Number(flags.port) || portOf(cfg) || 8080;
  const pin = normalizeFingerprint(flags.sha256 || cfg.sha256);
  if (!host) return failMsg('missing host — pass --host or set PHONE_MCP_URL first');
  if (!pin) return failMsg('refusing to pin blind — pass --sha256 (dashboard fingerprint, verified once)');
  if (pin.length !== 64) return failMsg('--sha256 must be 64 hex chars');
  if (!flags.out) return failMsg('missing --out');
  log(`export-ca ${host}:${port} → ${flags.out}`);
  try {
    const dest = await exportCaPem(host, port, pin, flags.out, (Number(flags.timeout) || 15) * 1000);
    console.log(`wrote ${dest} (fail-closed: hash matched the dashboard pin)`);
    console.log(`export PHONE_MCP_CAFILE="${dest}"`);
    return EXIT_OK;
  } catch (err) {
    return failMsg(actionableTransportError(err.message, cfg.url));
  }
}

function cmdUsb(flags, action = 'setup') {
  const port = Number(flags.port) || USB_DEFAULT_PORT;
  const serial = String(flags.serial ?? '').trim();

  if (action === 'status') {
    const devs = runAdb(['devices', '-l']);
    if ((devs.status ?? 1) !== 0) return failMsg(`\`adb devices -l\` failed — ${usbSetupHint(port)}`);
    for (const d of parseAdbDevices(devs.stdout)) console.log(`${d.serial}\t${d.state}`);
    const fwds = runAdb(['forward', '--list']);
    const matches = (fwds.status ?? 1) === 0 ? findForwards(parseAdbForwardList(fwds.stdout), serial, port) : [];
    if (matches.length) {
      for (const f of matches) console.log(`forward: ${f.serial} ${f.local} -> ${f.remote}`);
    } else {
      console.log(`no adb forward for tcp:${port} (${usbSetupHint(port)})`);
    }
    return EXIT_OK;
  }

  if (action === 'remove') {
    let target = serial;
    if (!target) {
      const devs = runAdb(['devices']);
      if ((devs.status ?? 1) !== 0) return failMsg(`\`adb devices\` failed — ${usbSetupHint(port)}`);
      const picked = pickUsbDevice(parseAdbDevices(devs.stdout), '');
      if (picked.error) return failMsg(`${picked.error} — ${usbSetupHint(port)}`);
      target = picked.serial;
    }
    const res = runAdb(adbRemoveArgs(target, port));
    const detail = String(res.stderr || res.error?.message || '').trim();
    if ((res.status ?? 1) !== 0 && !/not found/i.test(detail)) {
      return failMsg(`adb forward --remove failed (${detail || `exit ${res.status ?? '?'}`})`);
    }
    if ((res.status ?? 1) === 0) console.log(`removed adb forward tcp:${port}`);
    else console.log(`no adb forward tcp:${port} to remove — already clean`);
    return EXIT_OK;
  }

  if (action === 'check') {
    // Mapping-only verify (no TLS/healthz here — `smoke --usb` covers that).
    let target = serial;
    if (!target) {
      const devs = runAdb(['devices']);
      if ((devs.status ?? 1) !== 0) return failMsg(`\`adb devices\` failed — ${usbSetupHint(port)}`);
      const picked = pickUsbDevice(parseAdbDevices(devs.stdout), '');
      if (picked.error) return failMsg(`${picked.error} — ${usbSetupHint(port)}`);
      target = picked.serial;
    }
    const fwds = runAdb(['forward', '--list']);
    const matches = (fwds.status ?? 1) === 0 ? findForwards(parseAdbForwardList(fwds.stdout), target, port) : [];
    if (!matches.length) return failMsg(`no adb forward for tcp:${port} — ${usbSetupHint(port)}`);
    console.log(`USB check PASS: ${target} tcp:${port} -> tcp:${port} (then phone-mcp smoke --usb)`);
    return EXIT_OK;
  }

  if (action !== 'setup') return failMsg('usage: phone-mcp usb [setup|status|remove|check] [--port P --serial S]');
  let target = serial;
  if (!target) {
    // No adb round-trip in tests: resolve against attached devices so a
    // single phone Just Works and multi-phone fails with --serial guidance.
    const devs = runAdb(['devices']);
    if ((devs.status ?? 1) !== 0) return failMsg(`\`adb devices\` failed — ${usbSetupHint(port)}`);
    const picked = pickUsbDevice(parseAdbDevices(devs.stdout), '');
    if (picked.error) return failMsg(`${picked.error} — ${usbSetupHint(port)}`);
    target = picked.serial;
  }
  let res;
  try {
    res = runAdb(adbForwardArgs(target, port));
  } catch (err) {
    return failMsg(`adb unavailable (${err.message}) — ${usbSetupHint(port)}`);
  }
  if (res.error || (res.status ?? 0) !== 0) {
    const detail = String(res.stderr || res.error?.message || `exit ${res.status ?? '?'}`).trim();
    return failMsg(`adb forward failed (${detail}) — ${usbSetupHint(port)}`);
  }
  const url = loopbackUrlForPort(port);
  console.log(`USB forward ready: adb -s ${target} forward tcp:${port} tcp:${port} → ${url}`);
  console.log(`export PHONE_MCP_URL="${url}"`);
  console.log('phone-mcp smoke --usb  # expect 4x PASS before touching any harness');
  return EXIT_OK;
}

function hostOf(cfg) {
  try {
    return new URL(cfg.url).hostname || '';
  } catch {
    return '';
  }
}

function portOf(cfg) {
  try {
    const u = new URL(cfg.url);
    return Number(u.port) || 0;
  } catch {
    return 0;
  }
}

function serveForever(cfg) {
  const problems = validate(cfg);
  if (problems.length) {
    fail(undefined, -32603, 'phone-mcp: ' + problems.join('; '));
    return EXIT_FAIL;
  }
  log(
    `bridge → ${cfg.url} (pin=${cfg.sha256 ? 'sha256:' + cfg.sha256.slice(0, 12) + '…' : 'none'}, ` +
      `ca=${cfg.cafile || 'none'}, insecure=${cfg.insecure})`,
  );
  let buf = '';
  let inflight = 0;
  let ended = false;
  let resolveExit = null;
  // stdin 'end' may arrive while requests are still in flight (one-shot pipes);
  // wait for both so the final envelope is never truncated.
  const maybeExit = () => {
    if (ended && inflight === 0 && resolveExit) resolveExit(EXIT_OK);
  };
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', (chunk) => {
    buf += chunk;
    let nl;
    while ((nl = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, nl).trim();
      buf = buf.slice(nl + 1);
      if (line) {
        inflight++;
        handleStdioLine(line, cfg)
          .catch((err) => fail(undefined, -32603, String(err?.message ?? err)))
          .finally(() => {
            inflight--;
            maybeExit();
          });
      }
    }
  });
  return new Promise((resolve) => {
    resolveExit = resolve;
    process.stdin.on('end', () => {
      ended = true;
      maybeExit();
    });
    process.stdin.resume();
  });
}

export async function main(argv = process.argv.slice(2), env = process.env) {
  let parsed;
  try {
    parsed = parseArgv(argv);
  } catch (err) {
    log(`error: ${err.message}`);
    return EXIT_USAGE;
  }
  const { flags, bools, cmd, positionals } = parsed;
  // Boolean switches merge into the flag bag so subcommands see one view
  // regardless of whether the flag came before or after the command.
  for (const b of bools) flags[b] = true;
  if (bools.has('version')) {
    console.log(`phone-mcp ${__version__}`);
    return EXIT_OK;
  }
  if (bools.has('help')) {
    console.log(helpText());
    return EXIT_OK;
  }
  if (!cmd) {
    console.error(helpText());
    return EXIT_USAGE;
  }
  // Paired-file fallback honors the caller's HOME (sandboxable in tests).
  const pairedFile = path.join(env.HOME ?? os.homedir(), '.config', 'phone-mcp', 'config.json');
  const cfg = resolveConfig(flags, env, pairedFile);
  if (flags.verbose) {
    log(`config url=${cfg.url || '(none)'} pin=${cfg.sha256 ? 'set' : 'none'} ` +
      `ca=${cfg.cafile || 'none'} insecure=${cfg.insecure}`);
  }

  switch (cmd) {
    case 'serve':
      return serveForever(cfg);
    case 'smoke':
    case 'doctor':
      return cmdSmoke(cfg, Number(flags.timeout) || 20);
    case 'pair':
      return cmdPair(flags, env);
    case 'configure':
      return cmdConfigure(flags, cfg);
    case 'list':
      return cmdList(cfg, Boolean(flags.json));
    case 'call': {
      const [name] = positionals;
      if (!name) return failMsg('usage: phone-mcp call <name> [--args JSON]');
      return cmdCall(cfg, name, flags.args, flags['args-file'], Boolean(flags.json));
    }
    case 'media': {
      const [mediaId] = positionals;
      if (!mediaId || !flags.out) return failMsg('usage: phone-mcp media <id> --out PATH');
      return cmdMedia(cfg, mediaId, flags.out);
    }
    case 'export-ca':
      return cmdExportCa(flags, cfg);
    case 'usb': {
      const [usbAction] = positionals;
      return cmdUsb(flags, usbAction ?? 'setup');
    }
    default:
      return failMsg(`unknown command ${cmd}`);
  }
}

// Re-exported for tests.
export const _test = { parseArgv, parseHarnessSelection, resolveConfig };
