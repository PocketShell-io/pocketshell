import { defineConfig } from 'vitest/config';
import vue from '@vitejs/plugin-vue';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = path.dirname(fileURLToPath(import.meta.url));
const coreSrc = path.join(repoRoot, 'vendor/pocketshell-core/src');
const coreUi = path.join(repoRoot, 'vendor/pocketshell-core/packages/ui/src');

export default defineConfig({
  plugins: [vue()],
  esbuild: {
    tsconfigRaw: { compilerOptions: { target: 'ES2022', module: 'ESNext' } },
  },
  resolve: {
    alias: [
      { find: /^@ui\//, replacement: `${coreUi}/` },
      { find: /^@pocketshell\/ui$/, replacement: path.join(coreUi, 'index.ts') },
      { find: /^@pocketshell\/core\/(shared|attachments|preview)\//, replacement: `${coreSrc}/$1/` },
      { find: /^@pocketshell\/core$/, replacement: path.join(coreSrc, 'index.ts') },
      { find: /^@\//, replacement: `${path.join(repoRoot, 'src')}/` },
    ],
    dedupe: ['vue', 'pinia', 'vue-router'],
  },
  test: {
    environment: 'node',
    include: ['tests/unit/**/*.test.ts'],
  },
});
