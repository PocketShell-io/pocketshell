import { defineConfig } from 'vitest/config';
import vue from '@vitejs/plugin-vue';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  plugins: [vue()],
  esbuild: {
    tsconfigRaw: { compilerOptions: { target: 'ES2022', module: 'ESNext' } },
  },
  resolve: {
    alias: {
      '@pocketshell/core': path.join(repoRoot, 'vendor/pocketshell-core/src/index.ts'),
      '@pocketshell/ui': path.join(repoRoot, 'vendor/pocketshell-desktop/packages/ui/src/index.ts'),
      '@': path.join(repoRoot, 'src'),
    },
  },
  test: {
    environment: 'node',
    testTransformMode: { web: ['**/tests/unit/snippetBar.test.ts'] },
    include: ['tests/unit/**/*.test.ts'],
  },
});
