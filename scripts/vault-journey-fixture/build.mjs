#!/usr/bin/env node
// Bundle entry.ts against the vendored core (or CORE_DIR) and run it with the
// given arguments. esbuild is the one vite already ships.
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../..');
// The esbuild vite depends on (no separate dependency).
const fromRoot = createRequire(path.join(root, 'package.json'));
const { build } = createRequire(fromRoot.resolve('vite'))('esbuild');
const core = path.resolve(process.env.CORE_DIR ?? path.join(root, 'vendor/pocketshell-core'));
const work = mkdtempSync(path.join(tmpdir(), 'ps-vault-fixture-'));
const out = path.join(work, 'fixture.mjs');
try {
  await build({
    entryPoints: [path.join(here, 'entry.ts')],
    bundle: true,
    platform: 'node',
    format: 'esm',
    target: 'node22',
    outfile: out,
    alias: { '@pocketshell/core': path.join(core, 'src/index.ts') },
    nodePaths: [path.join(core, 'node_modules'), path.join(root, 'node_modules')],
    logLevel: 'warning',
  });
  const run = spawnSync(process.execPath, [out, ...process.argv.slice(2)], { stdio: 'inherit' });
  process.exitCode = run.status ?? 1;
} finally {
  rmSync(work, { recursive: true, force: true });
}
