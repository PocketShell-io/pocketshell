import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readPinnedCore, readPinnedDesktop } from './js-source-integrity.mjs';

const repoRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const core = readPinnedCore(repoRoot);
const desktop = readPinnedDesktop(repoRoot);
process.stdout.write(`pocketshell-core source: ${core.revision}\n`);
process.stdout.write(`pocketshell-desktop shared UI source: ${desktop.revision}\n`);
