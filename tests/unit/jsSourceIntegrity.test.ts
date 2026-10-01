import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  assertOnlyPinnedSubmodules,
  assertPinIsUpstreamMain,
  readGitlinkPaths,
  readPinnedCore,
} from '../../scripts/js-source-integrity.mjs';
import { agentMark } from '@pocketshell/core/shared/agentBadge';
import { THEMES } from '@ui/themes';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const DESKTOP_SUBMODULE = 'vendor/pocketshell-desktop';

describe('pinned shared source integrity', () => {
  it('pins exactly one shared-source gitlink, pocketshell-core, and no desktop submodule', () => {
    const gitlinks = readGitlinkPaths(repoRoot);

    expect(gitlinks).toEqual(['vendor/pocketshell-core']);
    expect(() => assertOnlyPinnedSubmodules(gitlinks)).not.toThrow();
    expect(readFileSync(path.join(repoRoot, '.gitmodules'), 'utf8')).not.toContain('pocketshell-desktop');
  });

  it('rejects a reintroduced desktop gitlink or a missing core gitlink', () => {
    expect(() => assertOnlyPinnedSubmodules(['vendor/pocketshell-core', DESKTOP_SUBMODULE]))
      .toThrow(/Unexpected shared-source gitlinks/);
    expect(() => assertOnlyPinnedSubmodules([])).toThrow(/Unexpected shared-source gitlinks/);
  });

  it('resolves the shared UI package from inside the clean core pin', () => {
    const core = readPinnedCore(repoRoot);

    expect(core.revision).toMatch(/^[a-f0-9]{40}$/);
    expect(core.uiRoot).toBe(path.join(repoRoot, 'vendor/pocketshell-core/packages/ui/src'));
    expect(existsSync(path.join(core.uiRoot, 'styles.css'))).toBe(true);
    expect(existsSync(path.join(core.uiRoot, 'components/AppIcon.vue'))).toBe(true);
    // The aliases the app uses resolve to real, executable core/UI modules.
    expect(Object.keys(THEMES).length).toBeGreaterThan(0);
    expect(agentMark('claude')).not.toBeNull();
  });

  it('fails closed unless the core pin equals the upstream main head', () => {
    const pinned = 'caec24ce134d4081ac486b65826cda46da868323';

    expect(() => assertPinIsUpstreamMain(pinned, pinned)).not.toThrow();
    expect(() => assertPinIsUpstreamMain(pinned, '1c5e7d6694b99ae7406c3802e73f817e632418ea'))
      .toThrow(/is not upstream main/);
  });

  it('keeps no tracked app, test, config or script reference to the desktop checkout', () => {
    const grep = () => execFileSync(
      'git',
      ['grep', '-n', '-I', '-e', DESKTOP_SUBMODULE, '-e', 'readPinnedDesktop', '-e', '__POCKETSHELL_UI_REVISION__',
        '--', 'src', 'tests/unit/*.ts', 'scripts', 'android/app/src', '*.ts', '*.json', '.gitmodules',
        ':!tests/unit/jsSourceIntegrity.test.ts', ':!pnpm-lock.yaml'],
      { cwd: repoRoot, encoding: 'utf8' },
    ).trim();
    let hits: string;
    try {
      hits = grep();
    } catch (error) {
      // git grep exits 1 with no output when nothing matches.
      if ((error as { status?: number }).status !== 1) throw error;
      hits = '';
    }
    expect(hits).toBe('');
  }, 30_000);
});
