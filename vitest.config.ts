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
      '@ui': path.join(repoRoot, 'vendor/pocketshell-core/packages/ui/src'),
      '@pocketshell/core/shared': path.join(repoRoot, 'vendor/pocketshell-core/src/shared'),
      '@pocketshell/core/attachments': path.join(repoRoot, 'vendor/pocketshell-core/src/attachments'),
      '@pocketshell/core/preview': path.join(repoRoot, 'vendor/pocketshell-core/src/preview'),
      '@pocketshell/core': path.join(repoRoot, 'vendor/pocketshell-core/src/index.ts'),
      '@': path.join(repoRoot, 'src'),
    },
  },
  test: {
    environment: 'node',
    include: ['tests/unit/**/*.test.ts'],
  },
});
