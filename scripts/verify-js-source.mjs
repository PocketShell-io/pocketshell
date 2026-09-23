import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readPinnedCore } from './js-source-integrity.mjs';

const repoRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const core = readPinnedCore(repoRoot);
process.stdout.write(`pocketshell-core source: ${core.revision}\n`);
