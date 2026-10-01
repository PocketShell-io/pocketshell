import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.pocketshell.app',
  appName: 'PocketShell',
  webDir: 'dist',
  // Never echo plugin calls or results to logcat: the SSH connect call
  // carries the private key, and debug builds are what we install for tests.
  loggingBehavior: 'none',
  android: {
    allowMixedContent: false,
  },
  plugins: {
    SystemBars: {
      insetsHandling: 'css',
      style: 'DARK',
      hidden: false,
    },
  },
};

export default config;
