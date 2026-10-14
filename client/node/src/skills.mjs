/**
 * Bundled agent skills for Phone MCP.
 *
 * The 6 skill trees shipped here are byte-identical copies of the source of
 * truth in the repo at `.agents/skills/phone-mcp*`. Bundling (instead of
 * reading the repo checkout) keeps `phone-mcp configure` working from an
 * installed global package anywhere. Edit the source trees, re-copy, and let
 * the sync tests prove identity — never edit these copies by hand.
 *
 * Layout: `skills/<skill-id>/{SKILL.md, references/*.md}`.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const SKILL_IDS = [
  'phone-mcp',
  'phone-mcp-sense',
  'phone-mcp-capture',
  'phone-mcp-wireless',
  'phone-mcp-ui',
  'phone-mcp-net',
];

// Project skills dir (agent-compatible discovery: OpenCode, Claude Code /
// Desktop, Cline, Windsurf, Cursor, Gemini CLI all read `.agents/skills/`).
export const PROJECT_SKILLS_DIRNAME = path.join('.agents', 'skills');

const HERE = path.dirname(fileURLToPath(import.meta.url));

export function sourceDir() {
  return path.join(HERE, '..', 'skills');
}

function iterSkillFiles(skillId) {
  const root = path.join(sourceDir(), skillId);
  const found = [];
  const walk = (dir) => {
    for (const name of fs.readdirSync(dir).sort()) {
      const p = path.join(dir, name);
      if (fs.statSync(p).isDirectory()) walk(p);
      else found.push(p);
    }
  };
  walk(root);
  return found.sort();
}

export function checkClean(paths, forbidden) {
  // Substring match, but only for secret-shaped markers: single characters
  // or short words (e.g. test tokens like "t") occur in ordinary prose and
  // would false-positive on every file. Real pairing tokens are long.
  const markers = (forbidden ?? []).filter((m) => m && m.length >= 8);
  if (!markers.length) return;
  for (const p of paths) {
    const text = fs.readFileSync(p, 'utf8');
    for (const marker of markers) {
      if (text.includes(marker)) {
        throw new Error(`refusing to stage ${p}: contains a live secret`);
      }
    }
  }
}

/**
 * Copy all skill trees verbatim into destDir.
 * Returns {skillId: fileCount}. `forbidden` holds live secrets (pairing
 * token, TLS pin); any staged file containing one aborts with an error.
 */
export function stageSkills(destDir, forbidden = []) {
  const staged = {};
  for (const skillId of SKILL_IDS) {
    const src = path.join(sourceDir(), skillId);
    if (!fs.existsSync(src) || !fs.statSync(src).isDirectory()) {
      throw new Error(`bundled skill missing: ${skillId} (re-copy from .agents/skills/)`);
    }
    const dst = path.join(destDir, skillId);
    fs.rmSync(dst, { recursive: true, force: true });
    fs.cpSync(src, dst, { recursive: true });
    const files = iterSkillFiles(skillId);
    // Re-scan the staged copies (not the source) for secrets.
    const stagedPaths = files.map((p) => path.join(dst, path.relative(src, p)));
    checkClean(stagedPaths, forbidden);
    staged[skillId] = stagedPaths.length;
  }
  return staged;
}

export function projectSkillsDir(cwd = '.') {
  return path.join(cwd, PROJECT_SKILLS_DIRNAME);
}
