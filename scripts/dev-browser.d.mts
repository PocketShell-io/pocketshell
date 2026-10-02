/** Types for scripts/dev-browser.mjs (browser dev mode launcher, #3022). */
export interface DevHostSpec {
  name: string;
  hostname: string;
  port: number;
  user: string;
}

export interface DevBrowserOptions {
  mode: 'mock' | 'live';
  hosts: DevHostSpec[];
  identities: string[];
  bridgePort: number;
  vitePort: number | null;
  viteArgs: string[];
}

export function parseHostSpec(spec: string): DevHostSpec;
export function parseArgs(argv: string[], env?: Record<string, string | undefined>): DevBrowserOptions;
export const DEFAULT_VITE_PORT: 5173;
export const BRIDGE_TOKEN_FRAGMENT: 'devBridgeToken';
export function livePageUrl(port: number, token: string): string;
