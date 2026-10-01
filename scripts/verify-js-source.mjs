import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  assertOnlyPinnedSubmodules,
  assertPinIsUpstreamMain,
  readGitlinkPaths,
  readPinnedCore,
  readUpstreamCoreMain,
} from './js-source-integrity.mjs';

// Usage: node scripts/verify-js-source.mjs [--require-upstream-main]
//
// Always: the only gitlink is vendor/pocketshell-core, its checkout matches the
// gitlink and is clean, and it carries both src/ and the shared packages/ui/src.
// --require-upstream-main: additionally require the pin to equal the current
// upstream pocketshell-core main head (network; used when bumping the pin).
const args = process.argv.slice(2);
const unknown = args.filter((arg) => arg !== '--require-upstream-main');
if (unknown.length > 0) {
  process.stderr.write(`FAIL: unknown argument(s): ${unknown.join(' ')}\n`);
  process.exit(2);
}

const repoRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
assertOnlyPinnedSubmodules(readGitlinkPaths(repoRoot));
const core = readPinnedCore(repoRoot);
process.stdout.write(`pocketshell-core source (core + packages/ui): ${core.revision}\n`);
if (args.includes('--require-upstream-main')) {
  const upstream = readUpstreamCoreMain(repoRoot);
  assertPinIsUpstreamMain(core.revision, upstream);
  process.stdout.write(`pocketshell-core pin equals upstream main: ${upstream}\n`);
}
