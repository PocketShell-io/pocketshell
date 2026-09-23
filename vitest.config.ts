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
    // Keep server-only unit helpers in Node while compiling this SFC as a
    // client render function so its actual template event bindings are tested.
    testTransformMode: { web: ['**/tests/unit/mobileHotkeys.test.ts'] },
    include: ['tests/unit/**/*.test.ts'],
  },
});
