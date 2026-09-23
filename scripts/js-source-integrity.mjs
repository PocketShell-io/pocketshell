import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';

function gitOutput(repoRoot, args) {
  return execFileSync('git', args, { cwd: repoRoot, encoding: 'utf8' }).trim();
}

/** Return the clean source revision pinned by this repository's gitlink. */
export function readPinnedCore(repoRoot) {
  const corePath = path.join(repoRoot, 'vendor/pocketshell-core');
  const sourceEntry = path.join(corePath, 'src/index.ts');
  try {
    if (!existsSync(sourceEntry)) throw new Error('src/index.ts is absent');
    const expectedRevision = gitOutput(repoRoot, ['rev-parse', ':vendor/pocketshell-core']);
    const checkedOutRevision = execFileSync(
      'git',
      ['-C', corePath, 'rev-parse', 'HEAD'],
      { cwd: repoRoot, encoding: 'utf8' },
    ).trim();
    const dirtyCore = execFileSync(
      'git',
      ['-C', corePath, 'status', '--porcelain'],
      { cwd: repoRoot, encoding: 'utf8' },
    ).trim();

    if (checkedOutRevision !== expectedRevision) {
      throw new Error(
        `gitlink expects ${expectedRevision}, but submodule HEAD is ${checkedOutRevision}`,
      );
    }
    if (dirtyCore) throw new Error('the checked-out core submodule has local changes');

    return { revision: checkedOutRevision, sourceEntry };
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error);
    throw new Error(
      `Pinned pocketshell-core source is missing or mismatched: ${detail}. ` +
        'Clone with --recurse-submodules or run git submodule update --init --recursive, ' +
        'and use the commit recorded by this branch.',
    );
  }
}
