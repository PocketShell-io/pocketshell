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

export function readPinnedCore(repoRoot) {
  return readPinnedSource(repoRoot, {
    name: 'pocketshell-core',
    submodulePath: 'vendor/pocketshell-core',
    sourceRelativePath: 'src/index.ts',
  });
}

export function readPinnedDesktop(repoRoot) {
  const desktop = readPinnedSource(repoRoot, {
    name: 'pocketshell-desktop',
    submodulePath: 'vendor/pocketshell-desktop',
    sourceRelativePath: 'packages/ui/src/index.ts',
  });
  const stylesEntry = path.join(path.dirname(desktop.sourceEntry), 'styles.css');
  if (!existsSync(stylesEntry)) {
    throw new Error('Pinned pocketshell-desktop shared UI source is missing or mismatched: ' +
      'packages/ui/src/styles.css is absent. Clone with --recurse-submodules or run ' +
      'git submodule update --init --recursive, and use the commit recorded by this branch.');
  }
  return { ...desktop, stylesEntry };
}
