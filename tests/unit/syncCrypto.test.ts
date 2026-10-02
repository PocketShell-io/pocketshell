import { describe, expect, it } from 'vitest';
import { decryptEnvelope, encryptToEnvelope, SYNC_KDF_ITERATIONS, SyncCryptoError } from '../../src/sync/syncCrypto';

// A real envelope the desktop client's SyncCrypto.ts writes (Node pbkdf2Sync /
// aes-256-gcm, tag appended), carried over from the 0.5.x Android suite where
// it pinned the same compatibility. Never regenerate it to make a change pass:
// reading the desktop's output is the property under test.
const DESKTOP_PASSPHRASE = 'correct horse battery staple';
const DESKTOP_PLAINTEXT = '{"hosts":[{"name":"hetzner","hostname":"135.181.114.209","port":22,"user":"alexey"},'
  + '{"name":"bäckerei","hostname":"höfn.internal","port":2222,"user":"root"}]}';
const DESKTOP_ENVELOPE = '{"v":1,"kdf":"pbkdf2-sha256","iter":600000,"salt":"pqH7wTMSdf45pMlYo0Tfpw==",'
  + '"iv":"/hOH2hHO8BXuAVIP","ct":"o4m6kdyYvsiPjWDpp4ZihqDTmD9HAutBJLPbatZiS7rw4hGb8yQRd8Oygu'
  + 'M5BTorSLja73RKmMbodf0qDOEVFFsdmGKMdqi4QKcQEr1l1ma2XwlQvCL9Bsko08gvH6FGFCdEW5id4dlT95pCu0AN'
  + 'yLaR8zftF3v498aBuI/HvqAAAKVmwyQHrtF+GEDV/MwZnRn8KmHjxElOtsegXqZ5MA+48hIhZzMecMwBNLNVz/4="}';

const FAST = 1000;

async function rejects(promise: Promise<unknown>, text: string): Promise<void> {
  await expect(promise).rejects.toBeInstanceOf(SyncCryptoError);
  await expect(promise).rejects.toThrow(text);
}

describe('settings-sync envelope encryption', () => {
  it('decrypts an envelope written by the desktop client', async () => {
    expect(await decryptEnvelope(DESKTOP_ENVELOPE, DESKTOP_PASSPHRASE)).toBe(DESKTOP_PLAINTEXT);
  });

  it('writes the desktop parameters at full strength and reads its own envelope back', async () => {
    const envelope = await encryptToEnvelope(DESKTOP_PLAINTEXT, DESKTOP_PASSPHRASE);
    const fields = JSON.parse(envelope) as Record<string, unknown>;
    expect(Object.keys(fields).sort()).toEqual(['ct', 'iter', 'iv', 'kdf', 'salt', 'v']);
    expect(fields).toMatchObject({ v: 1, kdf: 'pbkdf2-sha256', iter: 600_000 });
    expect(SYNC_KDF_ITERATIONS).toBe(600_000);
    expect(atob(fields.salt as string)).toHaveLength(16);
    expect(atob(fields.iv as string)).toHaveLength(12);
    expect(await decryptEnvelope(envelope, DESKTOP_PASSPHRASE)).toBe(DESKTOP_PLAINTEXT);
  });

  it('uses a fresh salt and IV per write and round-trips multi-byte text and passphrases', async () => {
    const first = JSON.parse(await encryptToEnvelope('same', 'pässword', FAST)) as Record<string, string>;
    const second = JSON.parse(await encryptToEnvelope('same', 'pässword', FAST)) as Record<string, string>;
    expect(first.salt).not.toBe(second.salt);
    expect(first.iv).not.toBe(second.iv);
    const text = '{"hosts":[{"name":"bäckerei","hostname":"höfn.internal"}]}';
    expect(await decryptEnvelope(await encryptToEnvelope(text, 'pässword', FAST), 'pässword')).toBe(text);
  });

  it('refuses a wrong passphrase, a tampered ciphertext and an empty passphrase with one message', async () => {
    const envelope = await encryptToEnvelope('secret', 'right', FAST);
    await rejects(decryptEnvelope(envelope, 'wrong'), 'Wrong sync passphrase, or the account data is corrupted.');
    const fields = JSON.parse(envelope) as Record<string, string>;
    const ct = Uint8Array.from(atob(fields.ct), (char) => char.charCodeAt(0));
    ct[ct.length - 1] ^= 1;
    const tampered = JSON.stringify({ ...fields, ct: btoa(String.fromCharCode(...ct)) });
    await rejects(decryptEnvelope(tampered, 'right'), 'Wrong sync passphrase, or the account data is corrupted.');
    await rejects(encryptToEnvelope('x', ''), 'Enter your sync passphrase.');
    await rejects(decryptEnvelope(envelope, ''), 'Enter your sync passphrase.');
  });

  it('refuses malformed envelopes and bounds the blob-controlled iteration count before deriving', async () => {
    const good = JSON.parse(await encryptToEnvelope('x', 'pw', FAST)) as Record<string, unknown>;
    const cases: Array<[string, string]> = [
      ['not json', 'not a sync envelope'],
      ['[]', 'not a sync envelope'],
      [JSON.stringify({ ...good, v: 2 }), 'cannot read'],
      [JSON.stringify({ ...good, kdf: 'scrypt' }), 'cannot read'],
      [JSON.stringify({ v: 1, kdf: 'pbkdf2-sha256', iter: 1 }), 'missing required fields'],
      [JSON.stringify({ ...good, iter: 500_000_000 }), 'iteration count is out of range'],
      [JSON.stringify({ ...good, iter: 0 }), 'iteration count is out of range'],
      [JSON.stringify({ ...good, salt: btoa('short') }), 'salt has the wrong length'],
      [JSON.stringify({ ...good, iv: btoa('short') }), 'IV has the wrong length'],
      [JSON.stringify({ ...good, ct: btoa('tiny') }), 'ciphertext is truncated'],
      [JSON.stringify({ ...good, salt: 'not base64 !!!' }), 'sync data is damaged (salt could not be read)'],
    ];
    for (const [envelope, text] of cases) await rejects(decryptEnvelope(envelope, 'pw'), text);
  });
});
