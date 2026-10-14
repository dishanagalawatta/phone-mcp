/**
 * Per-harness config rendering + install paths.
 *
 * Every template keeps the Bearer token out of the file: harness configs
 * carry env-var / prompt references only. The literal LAN URL is baked in
 * (it is not a secret); rotating the token or fingerprint never requires a
 * config rewrite — just re-export the env vars.
 *
 * Verified against (Oct 2026):
 * - OpenCode `{"mcp": {name: {"type": "local", "command": [...], "environment": ...}}}`
 * - Claude Code/Desktop/Cline/Windsurf `{"mcpServers": {name: {"command", "args", "env"}}}`
 * - Codex `~/.codex/config.toml` `[mcp_servers.<id>]` with `url` /
 *   `bearer_token_env_var` (remote) or `command`/`args`/`env` (stdio)
 * - VS Code `.vscode/mcp.json` `{"servers": ..., "inputs": ...}`
 * - Gemini CLI `~/.gemini/settings.json` `mcpServers` (+ `timeout`; direct
 *   entries use `httpUrl`, stdio entries need no such distinction)
 *
 * Zero dependencies. Node 18+.
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export const PHONE_MCP_SERVER_NAME = 'phone-mcp';

const BRIDGE_COMMANDS = {
  // Installed CLI (preferred after `pip install ./client` or `npm i -g phone-mcp-client`).
  'phone-mcp': ['phone-mcp', 'serve'],
  // Zero-dependency repo twins (no install needed; run from the repo root).
  node: ['node', 'bridges/phone-mcp-bridge.mjs'],
  python: ['python3', 'bridges/phone-mcp-bridge.py'],
};

export function bridgeCommand(kind = 'phone-mcp') {
  const cmd = BRIDGE_COMMANDS[kind];
  if (!cmd) throw new Error(`--bridge must be one of ${Object.keys(BRIDGE_COMMANDS).join(', ')}`);
  return [...cmd];
}

function envRefs(url, style) {
  if (style === 'opencode') {
    return {
      PHONE_MCP_URL: '{env:PHONE_MCP_URL}',
      PHONE_MCP_TOKEN: '{env:PHONE_MCP_TOKEN}',
      PHONE_MCP_SHA256: '{env:PHONE_MCP_SHA256}',
    };
  }
  if (style === 'gemini') {
    return {
      PHONE_MCP_URL: url,
      PHONE_MCP_TOKEN: '$PHONE_MCP_TOKEN',
      PHONE_MCP_SHA256: '$PHONE_MCP_SHA256',
    };
  }
  // claude / cursor / generic / cline / windsurf: ${VAR} refs.
  return {
    PHONE_MCP_URL: url,
    PHONE_MCP_TOKEN: '${PHONE_MCP_TOKEN}',
    PHONE_MCP_SHA256: '${PHONE_MCP_SHA256}',
  };
}

// --- Renderers — each returns [filename, textContent] -----------------------

export function renderOpencode(url, bridge = 'phone-mcp') {
  const cmd = bridgeCommand(bridge);
  const cfg = {
    mcp: {
      [PHONE_MCP_SERVER_NAME]: {
        type: 'local',
        command: cmd,
        enabled: true,
        environment: envRefs(url, 'opencode'),
      },
    },
  };
  return ['opencode.phone-mcp.json', JSON.stringify(cfg, null, 2) + '\n'];
}

export function renderClaude(url, bridge = 'phone-mcp') {
  const [command, ...args] = bridgeCommand(bridge);
  const cfg = {
    mcpServers: {
      [PHONE_MCP_SERVER_NAME]: { command, args, env: envRefs(url, 'claude') },
    },
  };
  return ['claude.phone-mcp.json', JSON.stringify(cfg, null, 2) + '\n'];
}

export function renderCodex(url, bridge = 'phone-mcp') {
  const [command, ...args] = bridgeCommand(bridge);
  const quoted = url.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
  const argsToml = args.map((a) => JSON.stringify(a)).join(', ');
  const lines = [
    '# Phone MCP — stdio bridge (handles self-signed TOFU; preferred on LAN).',
    '# Install: append to ~/.codex/config.toml, then `codex mcp list`.',
    '# Token/fingerprint via env — never baked in.',
    `[mcp_servers.${PHONE_MCP_SERVER_NAME}]`,
    `command = ${JSON.stringify(command)}`,
    `args = [${argsToml}]`,
    `url = "${quoted}"  # informational only; stdio entries ignore url`,
    '',
    '[mcp_servers.phone-mcp.env]',
    `PHONE_MCP_URL = "${quoted}"`,
    'PHONE_MCP_TOKEN = "${PHONE_MCP_TOKEN}"',
    'PHONE_MCP_SHA256 = "${PHONE_MCP_SHA256}"',
    '',
    '# Remote Streamable-HTTP alternative (stock TLS-verify rejects the',
    "# phone self-signed cert — pin it first via `phone-mcp export-ca`,",
    '# then SSL_CERT_FILE=./phone-mcp-ca.pem):',
    '# [mcp_servers.phone-mcp-remote]',
    `# url = "${quoted}"`,
    '# bearer_token_env_var = "PHONE_MCP_TOKEN"',
    '',
  ];
  return ['codex.phone-mcp.toml', lines.join('\n')];
}

export function renderVscode(url, bridge = 'phone-mcp') {
  const [rawCommand, ...rest] = bridgeCommand(bridge);
  const command = rawCommand === 'phone-mcp' ? 'phone-mcp' : rawCommand;
  const args = rawCommand === 'phone-mcp' ? ['serve'] : [`\${workspaceFolder}/${rest[0]}`];
  const cfg = {
    servers: {
      [PHONE_MCP_SERVER_NAME]: {
        type: 'stdio',
        command,
        args,
        env: {
          PHONE_MCP_URL: url,
          PHONE_MCP_TOKEN: '${input:phoneMcpToken}',
          PHONE_MCP_SHA256: '${input:phoneMcpSha256}',
        },
      },
    },
    inputs: [
      {
        id: 'phoneMcpToken',
        type: 'promptString',
        description: 'Phone MCP bearer token (dashboard pairing card)',
        password: true,
      },
      {
        id: 'phoneMcpSha256',
        type: 'promptString',
        description: 'Phone MCP TLS SHA-256 fingerprint (dashboard, colons ok)',
      },
    ],
  };
  return ['vscode.mcp.json', JSON.stringify(cfg, null, 2) + '\n'];
}

export function renderCursor(url, bridge = 'phone-mcp') {
  return renderGenericMcpServers(url, bridge, 'cursor.phone-mcp.json');
}

export function renderGemini(url, bridge = 'phone-mcp') {
  const [command, ...args] = bridgeCommand(bridge);
  const cfg = {
    mcpServers: {
      [PHONE_MCP_SERVER_NAME]: {
        command,
        args,
        env: envRefs(url, 'gemini'),
        timeout: 35000,
        // Explicit no-auto-trust (safe default; mirrors
        // config/harness-examples/gemini-settings.json).
        trust: false,
      },
    },
  };
  return ['gemini.phone-mcp.json', JSON.stringify(cfg, null, 2) + '\n'];
}

export function renderCline(url, bridge = 'phone-mcp') {
  return renderGenericMcpServers(url, bridge, 'cline.phone-mcp.json');
}

export function renderWindsurf(url, bridge = 'phone-mcp') {
  return renderGenericMcpServers(url, bridge, 'windsurf.phone-mcp.json');
}

export function renderGeneric(url, bridge = 'phone-mcp') {
  const [nodeCmd, ...nodeArgs] = BRIDGE_COMMANDS.node;
  const [pyCmd, ...pyArgs] = BRIDGE_COMMANDS.python;
  const servers = {
    [PHONE_MCP_SERVER_NAME]: { command: nodeCmd, args: nodeArgs, env: envRefs(url, 'generic') },
    'phone-mcp-python': { command: pyCmd, args: pyArgs, env: envRefs(url, 'generic') },
  };
  if (bridge === 'phone-mcp') {
    const [cliCmd, ...cliArgs] = BRIDGE_COMMANDS['phone-mcp'];
    servers[PHONE_MCP_SERVER_NAME] = { command: cliCmd, args: cliArgs, env: envRefs(url, 'generic') };
  }
  return ['generic.phone-mcp.json', JSON.stringify({ mcpServers: servers }, null, 2) + '\n'];
}

function renderGenericMcpServers(url, bridge, filename) {
  const [command, ...args] = bridgeCommand(bridge);
  const cfg = {
    mcpServers: {
      [PHONE_MCP_SERVER_NAME]: { command, args, env: envRefs(url, 'generic') },
    },
  };
  return [filename, JSON.stringify(cfg, null, 2) + '\n'];
}

export const RENDERERS = {
  opencode: renderOpencode,
  claude: renderClaude,
  codex: renderCodex,
  vscode: renderVscode,
  cursor: renderCursor,
  gemini: renderGemini,
  cline: renderCline,
  windsurf: renderWindsurf,
  generic: renderGeneric,
};

export const HARNESS_IDS = Object.keys(RENDERERS).sort();

// --- Install destinations ---------------------------------------------------

export function defaultInstallPath(harness, cwd = '.') {
  const home = os.homedir();
  const mapping = {
    opencode: path.join(home, '.config', 'opencode', 'opencode.json'),
    claude: path.join(cwd, '.mcp.json'),
    codex: path.join(home, '.codex', 'config.toml'),
    vscode: path.join(cwd, '.vscode', 'mcp.json'),
    cursor: path.join(home, '.cursor', 'mcp.json'),
    gemini: path.join(home, '.gemini', 'settings.json'),
    cline: path.join(
      home, '.config', 'Code', 'User', 'globalStorage',
      'saoudrizwan.claude-dev', 'settings', 'cline_mcp_settings.json',
    ),
    windsurf: path.join(home, '.codeium', 'windsurf', 'mcp_config.json'),
    generic: path.join(cwd, 'phone-mcp.generic.json'),
  };
  return mapping[harness];
}

export function cliAddHint(harness, url, bridge = 'phone-mcp') {
  const cmd = bridgeCommand(bridge);
  if (harness === 'claude') return `claude mcp add ${PHONE_MCP_SERVER_NAME} -- ${cmd.join(' ')}`;
  if (harness === 'codex') {
    return (
      `codex mcp add ${PHONE_MCP_SERVER_NAME} --command ${cmd[0]} ` +
      `--args ${cmd.slice(1).join(' ')} --env PHONE_MCP_URL=${url} ` +
      '--env PHONE_MCP_TOKEN=$PHONE_MCP_TOKEN --env PHONE_MCP_SHA256=$PHONE_MCP_SHA256'
    );
  }
  if (harness === 'gemini') {
    return (
      `# merge ${defaultInstallPath('gemini')} mcpServers, then \`/mcp list\` ` +
      '(Gemini uses `httpUrl`, not `url`, for direct entries — stdio needs neither)'
    );
  }
  if (harness === 'opencode') return '# restart OpenCode after editing (servers connect at startup); verify with /mcp';
  return '';
}

export const INSPECTOR_GUIDE =
  'MCP Inspector (manual probing): Streamable HTTP → URL + ' +
  'Authorization: Bearer $PHONE_MCP_TOKEN; accept the self-signed cert only ' +
  'AFTER verifying its SHA-256 against the dashboard (TOFU).';

// --- Install helpers (merge without clobbering) ------------------------------

export function mergeJsonFile(dest, key, addition) {
  let base = {};
  if (fs.existsSync(dest)) {
    try {
      base = JSON.parse(fs.readFileSync(dest, 'utf8')) || {};
    } catch (err) {
      throw new Error(`${dest} is not valid JSON: ${err.message}`);
    }
  }
  if (!base || typeof base !== 'object') throw new Error(`${dest}: top-level JSON must be an object`);
  if (!base[key] || typeof base[key] !== 'object') base[key] = {};
  Object.assign(base[key], addition);
  fs.mkdirSync(path.dirname(dest) || '.', { recursive: true });
  const tmp = `${dest}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(base, null, 2) + '\n');
  fs.renameSync(tmp, dest);
  return dest;
}

export function appendCodexToml(dest, block) {
  fs.mkdirSync(path.dirname(dest) || '.', { recursive: true });
  const existing = fs.existsSync(dest) ? fs.readFileSync(dest, 'utf8') : '';
  if (existing.includes(`[mcp_servers.${PHONE_MCP_SERVER_NAME}]`)) return dest; // already installed
  fs.appendFileSync(dest, `${existing && !existing.endsWith('\n') ? '\n' : ''}\n${block}${block.endsWith('\n') ? '' : '\n'}`);
  return dest;
}

export function installRendered(harness, content, dest) {
  if (harness === 'opencode') {
    return mergeJsonFile(dest, 'mcp', JSON.parse(content).mcp);
  }
  if (['claude', 'cursor', 'gemini', 'cline', 'windsurf'].includes(harness)) {
    return mergeJsonFile(dest, 'mcpServers', JSON.parse(content).mcpServers);
  }
  if (harness === 'generic') {
    fs.mkdirSync(path.dirname(dest) || '.', { recursive: true });
    fs.writeFileSync(dest, content);
    return dest;
  }
  if (harness === 'vscode') {
    const obj = JSON.parse(content);
    let base = {};
    if (fs.existsSync(dest)) base = JSON.parse(fs.readFileSync(dest, 'utf8')) || {};
    base.servers = { ...(base.servers ?? {}), ...obj.servers };
    const seen = new Set((base.inputs ?? []).filter((i) => i && typeof i === 'object').map((i) => i.id));
    base.inputs = [...(base.inputs ?? [])];
    for (const item of obj.inputs ?? []) {
      if (!seen.has(item.id)) base.inputs.push(item);
    }
    fs.mkdirSync(path.dirname(dest) || '.', { recursive: true });
    fs.writeFileSync(dest, JSON.stringify(base, null, 2) + '\n');
    return dest;
  }
  if (harness === 'codex') return appendCodexToml(dest, content);
  throw new Error(`unknown harness ${JSON.stringify(harness)}`);
}

export function renderAll(url, bridge = 'phone-mcp') {
  return Object.fromEntries(HARNESS_IDS.map((id) => [id, RENDERERS[id](url, bridge)]));
}
