/**
 * The SSH host key the dev:live bridge suite's in-process ssh2 server presents.
 *
 * It is a fixed, committed, test-only key
 * (`tests/unit/fixtures/dev-ssh-bridge-test-host-key-ed25519`, written by
 * OpenSSH's `ssh-keygen`, comment `...-NOT-FOR-ANY-REAL-HOST`). It is a
 * throwaway: no real host uses it, and its private half is public on purpose.
 *
 * It must not be generated per run (#3078): ssh2 1.16.0's
 * `generateKeyPairSync('ed25519')` strips leading zero bytes from the public
 * key, so about 1 in 256 of its keys is one its own `parseKey` rejects, and the
 * suite failed at import whenever the random draw hit one.
 */
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import ssh2 from 'ssh2';

export const BRIDGE_TEST_HOST_KEY_FILE = path.resolve(__dirname, '../fixtures/dev-ssh-bridge-test-host-key-ed25519');

export interface BridgeTestHostKey {
  privateKey: string;
  publicBlob: Buffer;
  fingerprintSha256: string;
}

/** `ssh2.utils.parseKey` returns an Error instead of throwing; never cast that away. */
export function parsedKeyOrThrow(key: string | Buffer, what: string): ssh2.ParsedKey {
  const parsed = ssh2.utils.parseKey(key);
  if (parsed instanceof Error) throw new Error(`${what} does not parse: ${parsed.message}`);
  return Array.isArray(parsed) ? parsed[0] : parsed;
}

export function loadBridgeTestHostKey(): BridgeTestHostKey {
  const privateKey = readFileSync(BRIDGE_TEST_HOST_KEY_FILE, 'utf8');
  const publicBlob = parsedKeyOrThrow(privateKey, 'bridge test host key').getPublicSSH();
  return {
    privateKey,
    publicBlob,
    fingerprintSha256: `SHA256:${createHash('sha256').update(publicBlob).digest('base64').replace(/=+$/u, '')}`,
  };
}
