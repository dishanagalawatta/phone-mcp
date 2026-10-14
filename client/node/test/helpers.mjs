import { execFile } from 'node:child_process';
import path from 'node:path';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';

const execFileAsync = promisify(execFile);

export const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
export const BRIDGE_MJS = path.join(REPO_ROOT, 'bridges', 'phone-mcp-bridge.mjs');

/**
 * Evaluate JS against the standalone bridge twin in an isolated process.
 * The bridge runs top-level side effects on import (env guard, stdin loop),
 * so it must never be imported into the test process itself.
 */
export async function bridgeEval(js) {
  const { stdout } = await execFileAsync(
    process.execPath,
    ['--input-type=module', '-e', js],
    {
      cwd: REPO_ROOT,
      env: { ...process.env, PHONE_MCP_URL: 'http://127.0.0.1:9/mcp', PHONE_MCP_TOKEN: 'x' },
      timeout: 20000,
    },
  );
  return JSON.parse(stdout);
}
