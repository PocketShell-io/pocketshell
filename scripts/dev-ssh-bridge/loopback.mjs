/**
 * Loopback-only request checks for browser dev mode's live server (#3022).
 * Shared by the Vite dev server plugin (vite.config.ts) and the SSH bridge;
 * dependency-free so the Vite config can import it for every build.
 *
 * A DNS-rebinding page reaches 127.0.0.1 under its own host name, so its
 * requests carry a foreign `Host` (and `Origin`). Only the literal loopback
 * names on the exact port are accepted.
 */
export const LOOPBACK_NAMES = Object.freeze(['127.0.0.1', 'localhost']);

/** `127.0.0.1:<port>` / `localhost:<port>`, the only Host values accepted. */
export function isLoopbackHost(hostHeader, port) {
  if (typeof hostHeader !== 'string' || !Number.isInteger(port) || port <= 0) return false;
  const value = hostHeader.trim().toLowerCase();
  return LOOPBACK_NAMES.some((name) => value === `${name}:${port}`);
}

/** The exact page origins of a loopback server on `port`. */
export function loopbackOrigins(port) {
  return LOOPBACK_NAMES.map((name) => `http://${name}:${port}`);
}

/** True when `origin` is exactly one of `allowed` (no prefix, case or port slack). */
export function isAllowedOrigin(origin, allowed) {
  return typeof origin === 'string' && Array.isArray(allowed) && allowed.includes(origin);
}

/** The minimal raw HTTP refusal written to a socket that is never upgraded. */
export function refusal(status, reason) {
  return `HTTP/1.1 ${status} ${reason}\r\nConnection: close\r\nContent-Type: text/plain\r\nContent-Length: 0\r\n\r\n`;
}
