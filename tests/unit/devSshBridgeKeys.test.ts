import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { createKeyVault, ed25519OpenSshPrivateKey } from '../../scripts/dev-ssh-bridge/bridge.mjs';
import { BRIDGE_TEST_HOST_KEY_FILE, loadBridgeTestHostKey, parsedKeyOrThrow } from './support/devSshBridgeHostKey';

/**
 * #3078: ssh2 1.16.0's `generateKeyPairSync('ed25519')` strips leading zero
 * bytes from the 32-byte public key, so about 1 in 256 of its own keys is
 * written with a 31-byte key that its `parseKey` then rejects ("Malformed
 * OpenSSH private key"). Both the bridge suite's host key and the dev vault's
 * "generate" went through that path, so the suite failed at random.
 */
const DRAWS = 4096; // P(no leading-zero key in 4096 random draws) = (255/256)^4096 ≈ 1e-7

/** sha256("pocketshell-3078-leading-zero-4838"): its Ed25519 public key starts with two zero bytes. */
const LEADING_ZERO_SEED = Buffer.from('daaa61f3c5204c70b8f7212857feb155dfcd4f74141d1ce22752f2e8d1d90672', 'hex');
const LEADING_ZERO_PUBLIC = '0000e9b562dac2eca7adfb830a8e6e8e518e87a9545f0876b065ae5c61276959';
/** What OpenSSH's `ssh-keygen -lf` prints for that key. */
const LEADING_ZERO_FINGERPRINT = 'SHA256:KMgW/bze7fhMf8Tz65YFe++zpNplTak67pmqUCu6mnk';
/** What OpenSSH's `ssh-keygen -lf` prints for the committed throwaway host key fixture. */
const HOST_KEY_FIXTURE_FINGERPRINT = 'SHA256:0/cGqfP6/fsz7sxKTPwB7q0bFADA4DftUoK27sZN+IE';

describe('dev SSH bridge Ed25519 keys', () => {
  it('the bridge suite host key is a fixed key that ssh2 parses on every load', () => {
    const first = loadBridgeTestHostKey();
    expect(first.fingerprintSha256).toBe(HOST_KEY_FIXTURE_FINGERPRINT);
    expect(first.publicBlob.length).toBe(4 + 'ssh-ed25519'.length + 4 + 32);
    const openSshPublic = parsedKeyOrThrow(readFileSync(`${BRIDGE_TEST_HOST_KEY_FILE}.pub`), 'host key fixture .pub');
    expect(openSshPublic.getPublicSSH().equals(first.publicBlob)).toBe(true);
    expect(readFileSync(`${BRIDGE_TEST_HOST_KEY_FILE}.pub`, 'utf8')).toContain('test-only-throwaway-host-key-NOT-FOR-ANY-REAL-HOST');
    for (let i = 0; i < DRAWS; i += 1) {
      const again = loadBridgeTestHostKey();
      expect(again.fingerprintSha256).toBe(first.fingerprintSha256);
      expect(again.publicBlob.equals(first.publicBlob)).toBe(true);
    }
  });

  it('every Ed25519 key the dev vault generates is a valid OpenSSH key', () => {
    const vault = createKeyVault();
    for (let i = 0; i < DRAWS; i += 1) {
      const key = vault.generate({ label: `k${i}`, algorithm: 'Ed25519' });
      expect(key.algorithm).toBe('ssh-ed25519');
      expect(vault.remove(key.handleId, key.fingerprintSha256)).toBe(true);
    }
    expect(vault.list()).toEqual([]);
  });

  it('writes an Ed25519 key whose public key starts with zero bytes at full length', () => {
    const pem = ed25519OpenSshPrivateKey(LEADING_ZERO_SEED, 'leading zero');
    const parsed = parsedKeyOrThrow(pem, 'leading-zero Ed25519 key');
    expect(parsed.type).toBe('ssh-ed25519');
    const blob = parsed.getPublicSSH();
    expect(blob.subarray(blob.length - 32).toString('hex')).toBe(LEADING_ZERO_PUBLIC);
    expect(blob.length).toBe(4 + 'ssh-ed25519'.length + 4 + 32);
    const data = Buffer.from('signed by the leading-zero key');
    expect(parsed.verify(data, parsed.sign(data))).toBe(true);
    const imported = createKeyVault().importKey({ privateKeyPem: pem, label: 'leading zero' });
    expect(imported).toMatchObject({ algorithm: 'ssh-ed25519', fingerprintSha256: LEADING_ZERO_FINGERPRINT });
  });
});
