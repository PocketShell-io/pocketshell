import { describe, expect, it, vi } from 'vitest';
import type { GoogleSyncHttpResponse, GoogleSyncNative, GoogleSyncRequest } from '@/native/googleSync';
import { createVaultNative } from '@/native/vaultNative';
import {
  createAndroidVault,
  WRONG_PASSPHRASE_MESSAGE,
  type UnlockedVault,
  type VaultCore,
  type VaultNative,
  type VaultUnlockResult,
} from '@/vault/androidVault';
import {
  ANDROID_VAULT_READ_ONLY,
  authToWire,
  createVaultServerVerifier,
  createVaultSlotTransport,
} from '@/vault/vaultAccount';
import { createVaultUnlockPrompt, FORGOT_PASSPHRASE_TEXT, VAULT_RESET_URL } from '@/vault/vaultUnlockPrompt';
import {
  createAndroidVaultCore,
  createStoreVaultCore,
  parseRememberRecord,
  serializeRememberRecord,
  type KeyVaultStoreLike,
} from '@/vault/coreVault';

/**
 * Key-vault W4: the phone's unlock (design section 5): passphrase each launch
 * unless "Remember on this device", wrong-passphrase retry with backoff, the
 * forgot/reset path, one passphrase for vault and sync, and the account IO.
 */

function vault(overrides: Partial<UnlockedVault> = {}): UnlockedVault {
  return {
    vaultId: 'v1',
    epoch: 2,
    binding: () => null,
    syncPassphrase: () => 'sync words',
    decryptForHost: async () => ({ kind: 'password', password: 'x' }),
    exportRemembered: async () => 'DEK-RECORD',
    close: vi.fn(),
    ...overrides,
  };
}

class Native implements VaultNative {
  remembered = new Map<string, string>();
  forgotAll = 0;
  released: Array<string[] | null> = [];
  async holdVaultKey() { return { handleId: 'ephemeral-1', algorithm: 'ssh-ed25519', fingerprintSha256: 'SHA256:x' }; }
  async releaseVaultKeys(options: { handleIds?: string[] } = {}) { this.released.push(options.handleIds ?? null); return { released: 0 }; }
  async remember({ scope, record }: { scope: string; record: string }) { this.remembered.set(scope, record); return {}; }
  async recall({ scope }: { scope: string }) { return { record: this.remembered.get(scope) ?? null }; }
  async forget({ scope }: { scope: string }) { this.remembered.delete(scope); return {}; }
  async forgetAll() { this.remembered.clear(); this.forgotAll += 1; return {}; }
}

function make(options: { correct?: string; remembered?: VaultUnlockResult | { kind: 'stale' }; signedIn?: boolean; present?: boolean } = {}) {
  let clock = 1_000_000;
  const native = new Native();
  const opened = vault();
  const core: VaultCore = {
    present: async () => options.present ?? true,
    unlock: vi.fn(async (passphrase: string): Promise<VaultUnlockResult> =>
      passphrase === (options.correct ?? 'right') ? { kind: 'unlocked', vault: opened } : { kind: 'wrong-passphrase' }),
    unlockRemembered: vi.fn(async () => options.remembered ?? { kind: 'stale' as const }),
  };
  const onUnlocked = vi.fn();
  const subject = createAndroidVault({
    core,
    native,
    scope: async () => (options.signedIn === false ? null : 'me@example.com'),
    askUnlock: async () => false,
    onUnlocked,
    now: () => clock,
  });
  return { subject, native, core, opened, onUnlocked, advance: (ms: number) => { clock += ms; }, now: () => clock };
}

class Google implements GoogleSyncNative {
  readonly requests: GoogleSyncRequest[] = [];
  constructor(private readonly reply: (request: GoogleSyncRequest) => GoogleSyncHttpResponse) {}
  async status() { return { signedIn: true, email: 'me@example.com', packageName: 'p' }; }
  async signIn() { return this.status(); }
  async signOut() { return this.status(); }
  async request(request: GoogleSyncRequest) { this.requests.push(request); return this.reply(request); }
}

const AUTH = new Uint8Array(32).fill(7);
const AUTH_WIRE = authToWire(AUTH);

/** The slice of core's KeyVaultStore the Android adapter calls, scripted. */
function fakeStore(overrides: Partial<KeyVaultStoreLike> = {}): KeyVaultStoreLike & { lock: ReturnType<typeof vi.fn> } {
  return {
    vaultId: 'vault-1',
    epoch: 3,
    probe: async () => ({ kind: 'vault', vaultId: 'vault-1', epoch: 3 }),
    unlock: async () => ({ kind: 'ok' }),
    unlockWithRememberedDek: async () => ({ kind: 'ok' }),
    exportRememberRecord: () => ({ vaultId: 'vault-1', epoch: 3, dek: new Uint8Array(32).fill(1), auth: new Uint8Array(32).fill(2) }),
    getHostBinding: (alias) => (alias === 'hetzner' ? { keyId: 'k1', gatewayAuth: 'both' } : null),
    getSyncPassphrase: () => 'sync words',
    decryptKeyForHost: async (alias) => (alias === 'hetzner' ? { kind: 'key', pem: 'PEM' } : { kind: 'unbound' }),
    lock: vi.fn(),
    ...overrides,
  } as KeyVaultStoreLike & { lock: ReturnType<typeof vi.fn> };
}

describe('Android key vault unlock, remember, account IO and core adapter (key-vault W4)', () => {
  it('honours the backoff core reports: no attempt until it passes, and a success clears it', async () => {
    const { subject, core, advance } = make();
    (core.unlock as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({ kind: 'wrong-passphrase', retryAfterMs: 0 })
      .mockResolvedValueOnce({ kind: 'wrong-passphrase', retryAfterMs: 0 })
      .mockResolvedValueOnce({ kind: 'wrong-passphrase', retryAfterMs: 1000 });
    expect((await subject.unlock('nope', false)).kind).toBe('wrong-passphrase');
    expect((await subject.unlock('nope', false)).kind).toBe('wrong-passphrase');
    expect(subject.state.retryAt).toBe(0);
    expect((await subject.unlock('nope', false)).kind).toBe('wrong-passphrase');
    expect(subject.state.failures).toBe(3);
    expect((await subject.unlock('right', false)).kind).toBe('backoff');
    expect(core.unlock).toHaveBeenCalledTimes(3);
    advance(1000);
    expect((await subject.unlock('right', false)).kind).toBe('unlocked');
    expect(subject.state).toMatchObject({ status: 'unlocked', failures: 0, retryAt: 0 });
  });

  it('type every time (the default) stores nothing on the device', async () => {
    const { subject, native, onUnlocked } = make();
    await subject.refresh();
    expect(subject.state.status).toBe('locked');
    await subject.unlock('right', false);
    expect(native.remembered.size).toBe(0);
    expect(onUnlocked).toHaveBeenCalledWith('sync words');
  });

  it('remember on this device seals the DEK record (never the passphrase) and unlocks silently next launch', async () => {
    const first = make();
    await first.subject.refresh();
    await first.subject.unlock('right', true);
    expect([...first.native.remembered.values()]).toEqual(['DEK-RECORD']);
    expect([...first.native.remembered.values()].join()).not.toContain('right');

    const next = make({ remembered: { kind: 'unlocked', vault: vault() } });
    next.native.remembered = first.native.remembered;
    expect(await next.subject.refresh()).toBe('unlocked');
    expect(next.core.unlockRemembered).toHaveBeenCalledWith('DEK-RECORD');
    expect(next.core.unlock).not.toHaveBeenCalled();
  });

  it('a remembered record from an older epoch or another vault is deleted and the passphrase is asked for', async () => {
    const { subject, native } = make({ remembered: { kind: 'stale' } });
    native.remembered.set('me@example.com', 'OLD');
    expect(await subject.refresh()).toBe('locked');
    expect(native.remembered.size).toBe(0);
    expect(subject.state.remembered).toBe(false);
  });

  it('unlocking with "type every time" after remembering forgets the device record', async () => {
    const { subject, native } = make();
    native.remembered.set('me@example.com', 'OLD');
    await subject.refresh();
    await subject.unlock('right', false);
    expect(native.remembered.size).toBe(0);
  });

  it('sign-out locks, wipes held keys and forgets every remembered record', async () => {
    const { subject, native, opened } = make();
    await subject.refresh();
    await subject.unlock('right', true);
    await subject.signedOut();
    expect(subject.state.status).toBe('signed-out');
    expect(opened.close).toHaveBeenCalled();
    expect(native.released).toEqual([null]);
    expect(native.forgotAll).toBe(1);
  });

  it('a signed-out phone or an account without a vault is "no-vault": the phone key flow applies', async () => {
    expect(await make({ signedIn: false }).subject.credentialFor('h')).toEqual({ kind: 'no-vault' });
    expect(await make({ present: false }).subject.credentialFor('h')).toEqual({ kind: 'no-vault' });
  });
  function sheet(results: Array<VaultUnlockResult | { kind: 'backoff'; retryAt: number }>, retryAt = 0) {
    const opened: string[] = [];
    const unlock = vi.fn(async () => results.shift()!);
    const prompt = createVaultUnlockPrompt({ unlock, retryAt: () => retryAt, openUrl: (url) => { opened.push(url); }, now: () => 0 });
    return { prompt, unlock, opened, setRetryAt: (value: number) => { retryAt = value; } };
  }

  it('says the passphrase was wrong, clears it and keeps the sheet open; a right one resolves the waiting dial', async () => {
    const { prompt } = sheet([{ kind: 'wrong-passphrase' }, { kind: 'unlocked', vault: vault() }]);
    const waiting = prompt.ask('connect');
    prompt.state.passphrase = 'nope';
    expect(await prompt.submit()).toBe(false);
    expect(prompt.state).toMatchObject({ open: true, passphrase: '', error: WRONG_PASSPHRASE_MESSAGE });
    prompt.state.passphrase = 'right';
    expect(await prompt.submit()).toBe(true);
    await expect(waiting).resolves.toBe(true);
    expect(prompt.state.open).toBe(false);
  });

  it('during a backoff it says how long to wait and does not try', async () => {
    const { prompt, unlock } = sheet([], 4000);
    void prompt.ask();
    prompt.state.passphrase = 'x';
    expect(await prompt.submit()).toBe(false);
    expect(prompt.state.error).toBe(`${WRONG_PASSPHRASE_MESSAGE} Try again in 4 s.`);
    expect(unlock).not.toHaveBeenCalled();
  });

  it('cancel resolves the waiting dial with false; a second dial shares the same sheet', async () => {
    const { prompt } = sheet([]);
    const first = prompt.ask();
    const second = prompt.ask();
    prompt.cancel();
    await expect(first).resolves.toBe(false);
    await expect(second).resolves.toBe(false);
  });

  it('forgot explains that the passphrase cannot be recovered and opens the web reset', async () => {
    const { prompt, opened } = sheet([]);
    void prompt.ask();
    prompt.forgot();
    expect(prompt.state.showForgot).toBe(true);
    expect(FORGOT_PASSPHRASE_TEXT).toContain('cannot be recovered');
    await prompt.openReset();
    expect(opened).toEqual([VAULT_RESET_URL]);
  });
  it('reads a vault slot raw (no envelope); a missing slot is absent; a verify-gated read is typed', async () => {
    const header = '{"v":1}';
    const google = new Google((request) => {
      const slot = (request as { slot?: string }).slot;
      if (slot === 'vault') return { status: 200, body: JSON.stringify({ version: 3, data: header }) };
      if (slot === 'vk-gated') return { status: 403, body: '{"error":"vault_verify_required"}' };
      return { status: 404, body: '{}' };
    });
    const transport = createVaultSlotTransport(google);
    expect(await transport.pull('vault')).toEqual({ kind: 'ok', version: 3, data: header });
    expect(await transport.pull('vk-none')).toEqual({ kind: 'absent' });
    expect(await transport.pull('vk-gated')).toEqual({ kind: 'gated' });
    expect(await createAndroidVaultCore(google).present()).toBe(true);
  });

  it('never writes the vault from the phone', async () => {
    const google = new Google(() => ({ status: 200, body: '{}' }));
    const transport = createVaultSlotTransport(google);
    expect(await transport.push('vault', '{}', 1)).toEqual({ kind: 'error', message: ANDROID_VAULT_READ_ONLY });
    await expect(transport.delete('vk-x')).rejects.toThrow(ANDROID_VAULT_READ_ONLY);
    expect(await transport.list()).toEqual([]);
    expect(await createVaultServerVerifier(google).setVerifier({ auth: AUTH, epoch: 1 })).toEqual({ kind: 'unavailable' });
    expect(google.requests).toEqual([]);
  });

  it('a vault the server hides until a verify (gated mode) still counts as a vault, never as "no vault"', async () => {
    const gatedRead = new Google((request) => (request as { endpoint?: string }).endpoint
      ? { status: 404, body: '{}' }
      : { status: 403, body: '{"error":"vault_verify_required"}' });
    expect(await createAndroidVaultCore(gatedRead).present()).toBe(true);
    const none = new Google(() => ({ status: 404, body: '{}' }));
    expect(await createAndroidVaultCore(none).present()).toBe(false);
  });

  it.each([
    [404, 'Not Found'],
    [503, '{"error":"vault_not_configured"}'],
    [409, '{"error":"verifier_stale"}'],
    [400, '{"error":"invalid_request"}'],
  ])('verify answers "unavailable" on HTTP %s so the unlock falls back to the check value', async (status, body) => {
    const verifier = createVaultServerVerifier(new Google(() => ({ status, body })));
    expect(await verifier.verify(AUTH)).toEqual({ kind: 'unavailable' });
  });

  it('maps the W2 verify answers 1:1: ok, wrong, locked (429 both), no verifier', async () => {
    const answers: GoogleSyncHttpResponse[] = [
      { status: 200, body: '{"ok":true,"epoch":4}' },
      { status: 401, body: '{"error":"wrong_passphrase","retryAfter":2,"attemptsRemaining":3}' },
      { status: 429, body: '{"error":"too_many_attempts","retryAfter":900}' },
      { status: 429, body: '{"error":"rate_limited","retryAfter":60}' },
      { status: 404, body: '{"error":"no_verifier"}' },
    ];
    const google = new Google(() => answers.shift()!);
    const verifier = createVaultServerVerifier(google);
    expect(await verifier.verify(AUTH)).toEqual({ kind: 'ok' });
    expect(await verifier.verify(AUTH)).toEqual({ kind: 'wrong', retryAfterMs: 2000 });
    expect(await verifier.verify(AUTH)).toEqual({ kind: 'locked', retryAfterMs: 900_000 });
    expect(await verifier.verify(AUTH)).toEqual({ kind: 'locked', retryAfterMs: 60_000 });
    expect(await verifier.verify(AUTH)).toEqual({ kind: 'noVerifier' });
    expect(google.requests[0]).toEqual({ method: 'POST', endpoint: 'vault/verify', body: JSON.stringify({ auth: AUTH_WIRE }) });
    expect(AUTH_WIRE).toMatch(/^[A-Za-z0-9_-]{43}$/);
  });

  it('refuses an AUTH that is not 32 bytes before any request', async () => {
    const google = new Google(() => ({ status: 200, body: '{}' }));
    await expect(createVaultServerVerifier(google).verify(new Uint8Array(31))).rejects.toThrow('malformed');
    expect(google.requests).toEqual([]);
  });

  it('status reads the epoch, verifier presence and the public KDF params, or unavailable', async () => {
    const ok = createVaultServerVerifier(new Google(() => ({ status: 200, body: '{"epoch":2,"hasVerifier":true,"retryAfter":0,"kdf":{"m":65536}}' })));
    expect(await ok.status!()).toEqual({ kind: 'ok', epoch: 2, hasVerifier: true, retryAfterMs: 0, kdf: { m: 65536 } });
    const missing = createVaultServerVerifier(new Google(() => ({ status: 404, body: '' })));
    expect(await missing.status!()).toEqual({ kind: 'unavailable' });
  });
  it('maps unlock results onto the phone states', async () => {
    const results = [
      { kind: 'wrongPassphrase', retryAfterMs: 0 },
      { kind: 'backoff', retryAfterMs: 2000 },
      { kind: 'rateLimited', retryAfterMs: 60_000 },
      { kind: 'noVault' },
      { kind: 'corrupt' },
      { kind: 'ok' },
    ] as const;
    const queue = [...results];
    const core = createStoreVaultCore(fakeStore({ unlock: async () => queue.shift()! }));
    expect(await core.unlock('x')).toEqual({ kind: 'wrong-passphrase', retryAfterMs: 0 });
    expect(await core.unlock('x')).toEqual({ kind: 'wrong-passphrase', retryAfterMs: 2000 });
    expect(await core.unlock('x')).toEqual({ kind: 'wrong-passphrase', retryAfterMs: 60_000 });
    expect(await core.unlock('x')).toEqual({ kind: 'absent' });
    expect((await core.unlock('x')).kind).toBe('error');
    const opened = await core.unlock('x');
    if (opened.kind !== 'unlocked') throw new Error('expected unlocked');
    expect(opened.vault.binding('hetzner')).toEqual({ keyId: 'k1', gatewayAuth: 'both' });
    expect(opened.vault.syncPassphrase()).toBe('sync words');
    expect(await opened.vault.decryptForHost('hetzner')).toEqual({ kind: 'key', pem: 'PEM' });
    await expect(opened.vault.decryptForHost('other')).rejects.toThrow('No key in your key vault');
  });

  it('the remember record carries vaultId, epoch, DEK and AUTH (decision #6), round-trips, and wipes the bytes', async () => {
    const record = { vaultId: 'vault-1', epoch: 3, dek: new Uint8Array(32).fill(1), auth: new Uint8Array(32).fill(2) };
    const text = serializeRememberRecord(record);
    expect(record.dek.every((byte) => byte === 0)).toBe(true);
    expect(record.auth.every((byte) => byte === 0)).toBe(true);
    const parsed = parseRememberRecord(text)!;
    expect(parsed.vaultId).toBe('vault-1');
    expect(parsed.epoch).toBe(3);
    expect([...parsed.dek]).toEqual(new Array(32).fill(1));
    expect([...parsed.auth!]).toEqual(new Array(32).fill(2));
    expect(parseRememberRecord('{"v":1,"vaultId":"x","epoch":1,"dek":"AAAA"}')).toBeNull();
    expect(parseRememberRecord('not json')).toBeNull();
  });

  it('a remembered record that no longer opens the vault reads as stale; a rate-limited one is kept', async () => {
    const record = serializeRememberRecord({ vaultId: 'vault-1', epoch: 3, dek: new Uint8Array(32), auth: new Uint8Array(32) });
    const stale = createStoreVaultCore(fakeStore({ unlockWithRememberedDek: async () => ({ kind: 'stale' }) }));
    expect(await stale.unlockRemembered(record)).toEqual({ kind: 'stale' });
    // Rate limited or unreadable: not stale, so the record is kept for next time.
    const limited = createStoreVaultCore(fakeStore({ unlockWithRememberedDek: async () => ({ kind: 'rateLimited', retryAfterMs: 5 }) }));
    expect((await limited.unlockRemembered(record)).kind).toBe('error');
    expect(await createStoreVaultCore(fakeStore()).unlockRemembered('garbage')).toEqual({ kind: 'stale' });
    const ok = await createStoreVaultCore(fakeStore()).unlockRemembered(record);
    expect(ok.kind).toBe('unlocked');
  });

  it('closing the session locks the store; presence follows probe', async () => {
    const store = fakeStore();
    const core = createStoreVaultCore(store);
    const opened = await core.unlock('x');
    if (opened.kind !== 'unlocked') throw new Error('expected unlocked');
    opened.vault.close();
    expect(store.lock).toHaveBeenCalled();
    expect(await core.present()).toBe(true);
    expect(await createStoreVaultCore(fakeStore({ probe: async () => ({ kind: 'noVault' }) })).present()).toBe(false);
  });
  it('accepts only ephemeral handles and never echoes key material', async () => {
    const keys = { holdVaultKey: vi.fn(async () => ({ handleId: 'ephemeral-abc', algorithm: 'ssh-ed25519', fingerprintSha256: 'SHA256:x' })), releaseVaultKeys: vi.fn(async () => ({ released: 2 })) };
    const device = { remember: vi.fn(), recall: vi.fn(async () => ({ record: null })), forget: vi.fn(), forgetAll: vi.fn() };
    const bridge = createVaultNative(keys as never, device as never);
    expect(await bridge.holdVaultKey({ privateKeyPem: 'PEM' })).toEqual({ handleId: 'ephemeral-abc', algorithm: 'ssh-ed25519', fingerprintSha256: 'SHA256:x' });
    expect(await bridge.releaseVaultKeys()).toEqual({ released: 2 });
    expect(await bridge.recall({ scope: 's' })).toEqual({ record: null });
    keys.holdVaultKey.mockResolvedValueOnce({ handleId: '00000000-0000-4000-8000-000000000001', algorithm: 'ssh-ed25519', fingerprintSha256: 'SHA256:x' });
    await expect(bridge.holdVaultKey({ privateKeyPem: 'PEM' })).rejects.toThrow('invalid vault-key handle');
  });
});
