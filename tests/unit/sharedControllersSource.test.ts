import { readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import * as core from '@pocketshell/core';

// Issue #2937 (D22): the usage, port-scan and port-forward controllers, the
// checked-exec echo check and the settings-sync round live only in
// @pocketshell/core. Android binds them; it must not grow a second copy.
const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const srcRoot = path.join(repoRoot, 'src');

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = path.join(dir, name);
    if (statSync(full).isDirectory()) return sourceFiles(full);
    return /\.(ts|vue)$/.test(name) ? [full] : [];
  });
}

const localDefinitions: Array<[string, RegExp]> = [
  ['readHostUsage', /\bfunction\s+readHostUsage\b/],
  ['scanRemotePorts', /\bfunction\s+scanRemotePorts\b/],
  ['PortForwardController', /\bclass\s+PortForwardController\b/],
  ['sync round', /\bfunction\s+(runSyncRound|syncSelectedHosts)\b/],
  ['usage command', /['"]pocketshell usage --json['"]/],
  ['generation echo check', /\.generationId\s*!==\s*[\w.]*connection\.generationId/],
];

describe('shared controllers are bound from core, never copied', () => {
  it('core exports the shared controllers and sync round', () => {
    expect(typeof core.readHostUsage).toBe('function');
    expect(typeof core.scanRemotePorts).toBe('function');
    expect(typeof core.PortForwardController).toBe('function');
    expect(typeof core.execChecked).toBe('function');
    expect(typeof core.runSyncRound).toBe('function');
  });

  it('Android src holds no local definition of them', () => {
    const offenders: string[] = [];
    for (const file of sourceFiles(srcRoot)) {
      const text = readFileSync(file, 'utf8');
      for (const [label, pattern] of localDefinitions) {
        if (pattern.test(text)) offenders.push(`${path.relative(repoRoot, file)}: ${label}`);
      }
    }
    expect(offenders).toEqual([]);
  });

  it('the app binds usage, forwarding and the sync probe from @pocketshell/core', () => {
    const app = readFileSync(path.join(srcRoot, 'App.vue'), 'utf8');
    const coreImport = app.match(/import\s*\{([^}]*)\}\s*from\s*'@pocketshell\/core'/)?.[1] ?? '';
    for (const name of ['readHostUsage', 'PortForwardController', 'runSyncRound']) {
      expect(coreImport).toMatch(new RegExp(`\\b${name}\\b`));
    }
    expect(app).toMatch(/__ps2852RunSettingsSync\s*=\s*runSyncRound\b/);
  });
});
