import { defineConfig, mergeConfig } from 'vitest/config';
import base from './vitest.config';

// Issue #3021: Docker-backed login-shell matrix, kept out of the hermetic
// unit gate. Run only through tests/scripts/authorized-key-install-login-shells-test.sh.
export default mergeConfig(base, defineConfig({
  test: {
    include: ['tests/login-shells/**/*.test.ts'],
    testTimeout: 120_000,
    hookTimeout: 120_000,
  },
}));
