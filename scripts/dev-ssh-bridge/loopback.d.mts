/** Types for scripts/dev-ssh-bridge/loopback.mjs (#3022). */
export const LOOPBACK_NAMES: readonly ['127.0.0.1', 'localhost'];
export function isLoopbackHost(hostHeader: string | undefined, port: number): boolean;
export function loopbackOrigins(port: number): string[];
export function isAllowedOrigin(origin: string | undefined, allowed: readonly string[]): boolean;
export function refusal(status: number, reason: string): string;
