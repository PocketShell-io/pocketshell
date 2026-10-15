import { describe, expect, it, vi } from 'vitest';
import type { GoogleSyncHttpResponse, GoogleSyncNative, GoogleSyncRequest } from '@/native/googleSync';
import { createVaultNative } from '@/native/vaultNative';
import { KeyVaultStore } from '@pocketshell/core';
import { createKeyVaultController } from '@ui/app/keyVault';
import {
  createAndroidVault,
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
import {
  createAndroidRememberApi,
  createAndroidVaultStore,
  createStoreVaultCore,
  UNSUPPORTED_VERSION,
  parseRememberRecord,
  serializeRememberRecord,
  type KeyVaultStoreLike,
} from '@/vault/coreVault';

/**
 * Key-vault W4: the phone's side of the unlock (design section 5). Core's
 * shared sheet asks and core's store counts wrong passphrases; Android
 * supplies the Keystore-sealed remember record, unlocks a remembered vault at
 * launch, hands over the sync passphrase, wipes held keys on lock and
 * sign-out, and reads the account over the native sync transport.
 */

function vault(overrides: Partial<UnlockedVault> = {}): UnlockedVault {
  return {
    vaultId: 'v1',
    epoch: 2,
    binding: () => null,
    syncPassphrase: () => 'sync words',
    decryptForHost: async () => ({ kind: 'password', password: 'x' }),
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

function make(options: { remembered?: VaultUnlockResult | { kind: 'stale' }; signedIn?: boolean; present?: boolean } = {}) {
  const native = new Native();
  const opened = vault();
  const listeners = new Set<() => void>();
  let current: UnlockedVault | null = null;
  const core: VaultCore & { open(): void } = {
    present: async () => options.present ?? true,
    unlockRemembered: vi.fn(async () => {
      const result = options.remembered ?? { kind: 'stale' as const };
      if (result.kind === 'unlocked') core.open();
      return result;
    }),
    current: () => current,
    subscribe: (listener) => { listeners.add(listener); return () => listeners.delete(listener); },
    lock: () => { current = null; listeners.forEach((listener) => listener()); },
    open: () => { current = opened; listeners.forEach((listener) => listener()); },
  };
  const onUnlocked = vi.fn();
  const subject = createAndroidVault({
    core,
    native,
    scope: async () => (options.signedIn === false ? null : 'me@example.com'),
    askUnlock: async () => false,
    onUnlocked,
  });
  return { subject, native, core, opened, onUnlocked };
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
    state: 'unlocked',
    vaultId: 'vault-1',
    epoch: 3,
    probe: async () => ({ kind: 'vault', vaultId: 'vault-1', epoch: 3 }),
    unlockWithRememberedDek: async () => ({ kind: 'ok' }),
    subscribe: () => () => undefined,
    getHostBinding: (alias) => (alias === 'hetzner' ? { keyId: 'k1', gatewayAuth: 'both' } : null),
    getSyncPassphrase: () => 'sync words',
    decryptKeyForHost: async (alias) => (alias === 'hetzner' ? { kind: 'key', pem: 'PEM' } : { kind: 'unbound' }),
    lock: vi.fn(),
    ...overrides,
  } as KeyVaultStoreLike & { lock: ReturnType<typeof vi.fn> };
}

describe('Android key vault unlock, remember, account IO and core adapter (key-vault W4)', () => {
  it('a remembered record unlocks silently at launch through core, and hands over the sync passphrase', async () => {
    const { subject, core, native, onUnlocked } = make({ remembered: { kind: 'unlocked', vault: vault() } });
    native.remembered.set('me@example.com', 'SEALED-RECORD');
    expect(await subject.refresh()).toBe('unlocked');
    expect(core.unlockRemembered).toHaveBeenCalledWith('SEALED-RECORD');
    expect(onUnlocked).toHaveBeenCalledWith('sync words');
    expect(subject.state.remembered).toBe(true);
  });

  it('without a remembered record the launch leaves the vault locked for the shared sheet', async () => {
    const { subject, core } = make();
    expect(await subject.refresh()).toBe('locked');
    expect(core.unlockRemembered).not.toHaveBeenCalled();
  });

  it('a remembered record from an older epoch or another vault is deleted and the passphrase is asked for', async () => {
    const { subject, native } = make({ remembered: { kind: 'stale' } });
    native.remembered.set('me@example.com', 'OLD');
    expect(await subject.refresh()).toBe('locked');
    expect(native.remembered.size).toBe(0);
    expect(subject.state.remembered).toBe(false);
  });

  it('a remembered record that fails for another reason (offline, rate limited) is kept for the next launch', async () => {
    const { subject, native } = make({ remembered: { kind: 'error', message: 'offline' } });
    native.remembered.set('me@example.com', 'KEEP');
    expect(await subject.refresh()).toBe('locked');
    expect(native.remembered.get('me@example.com')).toBe('KEEP');
  });

  it('whoever unlocks the store (the shared sheet) is followed; a lock wipes every held native key', async () => {
    const { subject, core, native, onUnlocked } = make();
    await subject.refresh();
    core.open();
    expect(subject.state.status).toBe('unlocked');
    expect(onUnlocked).toHaveBeenCalledWith('sync words');
    core.lock();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(subject.state.status).toBe('locked');
    expect(native.released).toContainEqual(null);
  });

  it('sign-out locks, wipes held keys and forgets every remembered record', async () => {
    const { subject, core, native } = make();
    await subject.refresh();
    core.open();
    await subject.signedOut();
    expect(subject.state.status).toBe('signed-out');
    expect(core.current()).toBeNull();
    expect(native.released).toContainEqual(null);
    expect(native.forgotAll).toBe(1);
  });

  it('a signed-out phone or an account without a vault is "no-vault": the phone key flow applies', async () => {
    expect(await make({ signedIn: false }).subject.credentialFor('h')).toEqual({ kind: 'no-vault' });
    expect(await make({ present: false }).subject.credentialFor('h')).toEqual({ kind: 'no-vault' });
  });

  it('remember on this device: the shared sheet saves the record sealed natively per account, never the passphrase', async () => {
    const native = new Native();
    const remember = createAndroidRememberApi(native, async () => 'me@example.com');
    expect(await remember.isRemembered()).toBe(false);
    await remember.save({ vaultId: 'v1', epoch: 2, dek: new Uint8Array(32).fill(9), auth: new Uint8Array(32).fill(8) });
    expect(await remember.isRemembered()).toBe(true);
    const stored = native.remembered.get('me@example.com')!;
    expect(parseRememberRecord(stored)).toMatchObject({ vaultId: 'v1', epoch: 2 });
    await remember.forget();
    expect(await remember.isRemembered()).toBe(false);
    const signedOut = createAndroidRememberApi(native, async () => null);
    expect(await signedOut.isRemembered()).toBe(false);
    await expect(signedOut.save({ vaultId: 'v1', epoch: 2, dek: new Uint8Array(32) })).rejects.toThrow('Sign in');
  });

  it('the shared controller over the phone store offers remember and the web reset, never key upload', async () => {
    const google = new Google(() => ({ status: 404, body: '{}' }));
    const controller = createKeyVaultController({
      store: createAndroidVaultStore(google),
      remember: createAndroidRememberApi(new Native(), async () => 'me@example.com'),
      resetUrl: 'https://app.pocketshell.io/keys?reset=1',
      openUrl: () => undefined,
    });
    expect(controller.canRemember).toBe(true);
    expect(controller.canForgot).toBe(true);
    expect(controller.canUpload).toBe(false);
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
    expect(await createStoreVaultCore(createAndroidVaultStore(google)).present()).toBe(true);
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
    expect(await createStoreVaultCore(createAndroidVaultStore(gatedRead)).present()).toBe(true);
    const none = new Google(() => ({ status: 404, body: '{}' }));
    expect(await createStoreVaultCore(createAndroidVaultStore(none)).present()).toBe(false);
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

  it('the adapter follows the store: current while unlocked, lock through it, presence follows probe', async () => {
    const store = fakeStore();
    const core = createStoreVaultCore(store);
    expect(core.current()?.binding('hetzner')).toEqual({ keyId: 'k1', gatewayAuth: 'both' });
    expect(await core.current()!.decryptForHost('hetzner')).toEqual({ kind: 'key', pem: 'PEM' });
    await expect(core.current()!.decryptForHost('other')).rejects.toThrow('No key in your key vault');
    core.lock();
    expect(store.lock).toHaveBeenCalled();
    expect(await core.present()).toBe(true);
    expect(await createStoreVaultCore(fakeStore({ probe: async () => ({ kind: 'noVault' }) })).present()).toBe(false);
    expect(await createStoreVaultCore(fakeStore({ probe: async () => ({ kind: 'unsupportedVersion' }) })).present()).toBe(true);
    const newer = createStoreVaultCore(fakeStore({ unlockWithRememberedDek: async () => ({ kind: 'unsupportedVersion' }) }));
    const record = serializeRememberRecord({ vaultId: 'vault-1', epoch: 3, dek: new Uint8Array(32) });
    expect(await newer.unlockRemembered(record)).toEqual({ kind: 'error', message: UNSUPPORTED_VERSION });
    expect(UNSUPPORTED_VERSION).not.toMatch(/reset/i);
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
