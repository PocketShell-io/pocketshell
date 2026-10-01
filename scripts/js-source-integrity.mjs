import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';

function gitOutput(repoRoot, args) {
  return execFileSync('git', args, { cwd: repoRoot, encoding: 'utf8' }).trim();
}

/** Return the clean source revision pinned by this repository's gitlink. */
function readPinnedSource(repoRoot, { name, submodulePath, sourceRelativePath }) {
  const sourcePath = path.join(repoRoot, submodulePath);
  const sourceEntry = path.join(sourcePath, sourceRelativePath);
  try {
    if (!existsSync(sourceEntry)) throw new Error(`${sourceRelativePath} is absent`);
    const expectedRevision = gitOutput(repoRoot, ['rev-parse', `:${submodulePath}`]);
    const checkedOutRevision = execFileSync(
      'git',
      ['-C', sourcePath, 'rev-parse', 'HEAD'],
      { cwd: repoRoot, encoding: 'utf8' },
    ).trim();
    const dirtySource = execFileSync(
      'git',
      ['-C', sourcePath, 'status', '--porcelain'],
      { cwd: repoRoot, encoding: 'utf8' },
    ).trim();

    if (checkedOutRevision !== expectedRevision) {
      throw new Error(
        `gitlink expects ${expectedRevision}, but submodule HEAD is ${checkedOutRevision}`,
      );
    }
    if (dirtySource) throw new Error('the checked-out submodule has local changes');

    return { revision: checkedOutRevision, sourceEntry };
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error);
    throw new Error(
      `Pinned ${name} source is missing or mismatched: ${detail}. ` +
        'Clone with --recurse-submodules or run git submodule update --init --recursive, ' +
        'and use the commit recorded by this branch.',
    );
  }
}

/**
 * The pinned pocketshell-core checkout. It carries both the platform-free core
 * (`src/`) and the shared Vue UI package (`packages/ui/src/`), so one gitlink
 * identifies every shared source Android bundles.
 */
export function readPinnedCore(repoRoot) {
  const core = readPinnedSource(repoRoot, {
    name: 'pocketshell-core',
    submodulePath: 'vendor/pocketshell-core',
    sourceRelativePath: 'src/index.ts',
  });
  const sourceRoot = path.dirname(core.sourceEntry);
  const uiRoot = path.join(path.dirname(sourceRoot), 'packages', 'ui', 'src');
  for (const required of ['index.ts', 'styles.css']) {
    if (!existsSync(path.join(uiRoot, required))) {
      throw new Error('Pinned pocketshell-core shared UI source is missing or mismatched: ' +
        `packages/ui/src/${required} is absent. Clone with --recurse-submodules or run ` +
        'git submodule update --init --recursive, and use the commit recorded by this branch.');
    }
  }
  return { ...core, sourceRoot, uiRoot };
}

/** The only shared source Android may pin: the core repo, which also carries the UI package. */
export const PINNED_SUBMODULES = Object.freeze(['vendor/pocketshell-core']);

/** Every submodule path the index records as a gitlink (mode 160000). */
export function readGitlinkPaths(repoRoot) {
  return gitOutput(repoRoot, ['ls-files', '--stage'])
    .split('\n')
    .filter((line) => line.startsWith('160000 '))
    .map((line) => line.split('\t')[1])
    .sort();
}

/** Fail unless the index pins exactly the allowed shared-source submodules. */
export function assertOnlyPinnedSubmodules(gitlinkPaths) {
  const unexpected = gitlinkPaths.filter((entry) => !PINNED_SUBMODULES.includes(entry));
  const missing = PINNED_SUBMODULES.filter((entry) => !gitlinkPaths.includes(entry));
  if (unexpected.length > 0 || missing.length > 0) {
    throw new Error(
      `Unexpected shared-source gitlinks: expected exactly ${PINNED_SUBMODULES.join(', ')}; ` +
        `found ${gitlinkPaths.join(', ') || 'none'}. The shared UI ships inside pocketshell-core ` +
        '(packages/ui); no other source repository may be pinned.',
    );
  }
}

/** Read the upstream `main` head of the pinned core's origin via `git ls-remote`. */
export function readUpstreamCoreMain(repoRoot) {
  const url = gitOutput(repoRoot, ['config', '-f', '.gitmodules', 'submodule.vendor/pocketshell-core.url']);
  const line = gitOutput(repoRoot, ['ls-remote', url, 'refs/heads/main']);
  const revision = line.split(/\s+/)[0];
  if (!/^[a-f0-9]{40}$/.test(revision ?? '')) {
    throw new Error(`Could not read pocketshell-core main from ${url}: ${JSON.stringify(line)}`);
  }
  return revision;
}

/** Fail unless the core pin is exactly the upstream `main` head. */
export function assertPinIsUpstreamMain(pinnedRevision, upstreamMainRevision) {
  if (pinnedRevision !== upstreamMainRevision) {
    throw new Error(
      `pocketshell-core pin ${pinnedRevision} is not upstream main ${upstreamMainRevision}. ` +
        'Bump vendor/pocketshell-core to upstream main in one coordinated change.',
    );
  }
}
