import { describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { KeyVaultStore, type SyncStatus } from '@pocketshell/core';
import { createKeyVaultController } from '@ui/app/keyVault';
import { createGoogleSyncNative } from '@/native/googleSync';
import { bindAndroidAuthLoss } from '@/platform/android/authLoss';
import { MemoryTransport } from '../../vendor/pocketshell-core/tests/keyVault/memoryTransport';
import { createAndroidVault, type AndroidVaultDeps, type VaultNative } from '@/vault/androidVault';
import { createStoreVaultCore, serializeRememberRecord } from '@/vault/coreVault';

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

const scope = 'synthetic@example.test';
const phrase = 'synthetic unrelated words for lock race tests';

async function terminal<T>(request: Promise<T>) {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      request.then(value => ({ kind: 'done' as const, value }), error => ({ kind: 'failed' as const, error })),
      new Promise<{ kind: 'timeout' }>(resolve => { timer = setTimeout(() => resolve({ kind: 'timeout' }), 500); }),
    ]);
  } finally { clearTimeout(timer); }
}

/** Real encrypted cloud slots/Core store and an AES-GCM sealed native record.
 * Only scheduling/transport/native-lease completion are controlled by tests. */
async function fixture(kind: 'none' | 'key' | 'password' = 'none') {
  const transport = new MemoryTransport();
  const store = new KeyVaultStore({ transport, kdf: { m: 32768, t: 3, p: 1 } });
  expect((await store.createVault(phrase)).kind).toBe('ok');
  if (kind !== 'none') {
    const added = kind === 'key'
      ? await store.addKey(readFileSync('tests/docker/test_key', 'utf8'), 'fixture key')
      : await store.addPassword('synthetic SSH password', 'fixture password');
    if (added.kind !== 'ok') throw new Error(`fixture add: ${added.kind}`);
    expect((await store.bindHost('server', added.entry.keyId, 'managed')).kind).toBe('ok');
  }
  const record = serializeRememberRecord(store.exportRememberRecord()!);
  const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const sealed = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, new TextEncoder().encode(record)));
  const originalSealed = sealed.slice();
  const remembered = new Map([[scope, sealed]]);
  const native = {
    recall: vi.fn(async () => ({ record: remembered.has(scope)
      ? new TextDecoder().decode(await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, key, sealed)) : null })),
    releaseVaultKeys: vi.fn(async (_options?: { handleIds?: string[] }) => ({ released: 1 })),
    holdVaultKey: vi.fn(async () => ({ handleId: 'ephemeral-race', algorithm: 'ssh-ed25519', fingerprintSha256: 'SHA256:fixture' })),
    remember: vi.fn(async () => ({})),
    forget: vi.fn(async () => { remembered.delete(scope); return {}; }),
    forgetAll: vi.fn(async () => { remembered.clear(); return {}; }),
  } satisfies VaultNative;
  store.lock();
  const core = createStoreVaultCore(store);
  const deps: AndroidVaultDeps = { core, native, scope: async () => scope,
    accountId: async () => 'google:fixture', askUnlock: vi.fn(async () => false) };
  const subject = createAndroidVault(deps);
  const ciphertext = JSON.stringify([...transport.slots]);
  const custody = () => {
    expect(JSON.stringify([...transport.slots])).toBe(ciphertext);
    expect(remembered.get(scope)).toEqual(originalSealed);
    expect(native.forget).not.toHaveBeenCalled();
    expect(native.forgetAll).not.toHaveBeenCalled();
  };
  return { store, transport, core, deps, native, subject, custody };
}

describe('Android vault Lock fences real Core and sealed Remember across asynchronous boundaries', () => {
  it.each(['scope', 'probe', 'recall'] as const)('a refresh delayed at %s cannot import remembered keys during or after Lock', async boundary => {
    const f = await fixture();
    const entered = deferred<void>(), resume = deferred<void>(), deletion = deferred<{ released: number }>();
    const pause = async <T>(value: T): Promise<T> => { entered.resolve(); await resume.promise; return value; };
    if (boundary === 'scope') f.deps.scope = () => pause(scope);
    if (boundary === 'probe') { const probe = f.core.present; f.core.present = async () => pause(await probe()); }
    if (boundary === 'recall') { const recall = f.native.recall.getMockImplementation()!; f.native.recall.mockImplementation(async () => pause(await recall())); }
    const imported = vi.spyOn(f.core, 'unlockRemembered');
    f.native.releaseVaultKeys.mockImplementation(() => deletion.promise);
    const refreshing = f.subject.refresh();
    await entered.promise;
    const locking = f.subject.lock();
    resume.resolve();
    await refreshing;
    expect(imported).not.toHaveBeenCalled();
    expect(f.store.state).toBe('locked');
    if (boundary !== 'recall') expect(f.native.recall).not.toHaveBeenCalled();
    deletion.resolve({ released: 1 }); await locking;
    expect(f.store.state).toBe('locked'); expect(f.subject.state.status).not.toBe('unlocked'); f.custody();
  });

  it('an import already running in real Core cannot reinstall its DEK after Lock or replace a newer login', async () => {
    const f = await fixture();
    const entered = deferred<void>(), resume = deferred<void>();
    const pull = f.transport.pull.bind(f.transport);
    let pulls = 0;
    f.transport.pull = async slot => {
      const result = await pull(slot);
      if (++pulls === 2) { entered.resolve(); await resume.promise; }
      return result;
    };
    const refreshing = f.subject.refresh(); await entered.promise;
    await f.subject.lock();
    const newLogin = f.store.unlock(phrase);
    resume.resolve(); await refreshing;
    expect((await newLogin).kind).toBe('ok');
    expect(f.store.state).toBe('unlocked'); expect(f.subject.state.status).toBe('unlocked'); f.custody();
  });

  it('a stale remembered-import result arriving after Lock cannot forget the sealed record', async () => {
    const f = await fixture();
    const entered = deferred<void>(), resume = deferred<void>();
    const unlock = f.core.unlockRemembered;
    f.core.unlockRemembered = async record => {
      const altered = JSON.parse(record); altered.epoch++;
      const result = await unlock(JSON.stringify(altered));
      entered.resolve(); await resume.promise; return result;
    };
    const refreshing = f.subject.refresh(); await entered.promise;
    await f.subject.lock(); resume.resolve(); await refreshing;
    expect(f.store.state).toBe('locked'); f.custody();
  });

  it('an old probe error cannot overwrite a later authenticated account state', async () => {
    const f = await fixture();
    const entered = deferred<void>(), failed = deferred<boolean>();
    f.core.present = async () => { entered.resolve(); return failed.promise; };
    const refreshing = f.subject.refresh(); await entered.promise;
    await f.subject.lock(); expect((await f.store.unlock(phrase)).kind).toBe('ok');
    failed.reject(new Error('old account unavailable'));
    await expect(refreshing).resolves.toBe('unlocked');
    expect(f.subject.state.status).toBe('unlocked'); f.custody();
  });

  it('an old signed-out scope result cannot lock or relabel a newer authenticated session', async () => {
    const f = await fixture();
    const entered = deferred<void>(), resume = deferred<string | null>();
    f.deps.scope = () => { entered.resolve(); return resume.promise; };
    const refreshing = f.subject.refresh(); await entered.promise;
    await f.subject.lock(); expect((await f.store.unlock(phrase)).kind).toBe('ok');
    resume.resolve(null); await refreshing;
    expect(f.store.state).toBe('unlocked'); expect(f.subject.state.status).toBe('unlocked'); f.custody();
  });

  it('a second Lock invalidates reads already waiting for the first native cleanup', async () => {
    const f = await fixture();
    const deletion = deferred<{ released: number }>();
    f.native.releaseVaultKeys.mockImplementation(() => deletion.promise);
    const first = f.subject.lock();
    const refreshing = f.subject.refresh();
    const second = f.subject.lock();
    expect(f.native.releaseVaultKeys).toHaveBeenCalledTimes(1);
    deletion.resolve({ released: 1 }); await Promise.all([first, second, refreshing]);
    expect(f.native.recall).not.toHaveBeenCalled(); expect(f.store.state).toBe('locked'); f.custody();
  });

  it('a delayed credential refresh cannot open a password sheet after Lock', async () => {
    const f = await fixture();
    const entered = deferred<void>(), resume = deferred<string>();
    f.deps.scope = () => { entered.resolve(); return resume.promise; };
    const acquiring = f.subject.credentialFor('server'); await entered.promise;
    await f.subject.lock(); resume.resolve(scope);
    await expect(acquiring).resolves.toEqual({ kind: 'locked' });
    expect(f.deps.askUnlock).not.toHaveBeenCalled(); expect(f.native.recall).not.toHaveBeenCalled(); f.custody();
  });

  it('an old pending unlock answer cannot use keys from a newer authenticated session', async () => {
    const f = await fixture('key'); f.native.recall.mockResolvedValue({ record: null });
    const entered = deferred<void>(), resume = deferred<boolean>();
    f.deps.askUnlock = () => { entered.resolve(); return resume.promise; };
    const acquiring = f.subject.credentialFor('server'); await entered.promise;
    await f.subject.lock(); expect((await f.store.unlock(phrase)).kind).toBe('ok');
    resume.resolve(true);
    await expect(acquiring).resolves.toEqual({ kind: 'locked' });
    expect(f.native.holdVaultKey).not.toHaveBeenCalled(); expect(f.store.state).toBe('unlocked'); f.custody();
  });

  it.each(['key', 'password'] as const)('a %s decrypted before Lock cannot leave an older credential request', async kind => {
    const f = await fixture(kind); await f.subject.refresh();
    const entered = deferred<void>(), resume = deferred<void>();
    const current = f.core.current;
    f.core.current = () => {
      const open = current();
      return open && { ...open, decryptForHost: async alias => {
        const secret = await open.decryptForHost(alias); entered.resolve(); await resume.promise; return secret;
      } };
    };
    const acquiring = f.subject.credentialFor('server'); await entered.promise;
    await f.subject.lock(); resume.resolve();
    await expect(acquiring).resolves.toEqual({ kind: 'locked' });
    expect(f.native.holdVaultKey).not.toHaveBeenCalled(); f.custody();
  });

  it('an account lookup begun before Lock cannot start a native key hold afterwards', async () => {
    const f = await fixture('key'); await f.subject.refresh();
    const entered = deferred<void>(), resume = deferred<string>();
    f.deps.accountId = () => { entered.resolve(); return resume.promise; };
    const acquiring = f.subject.credentialFor('server');
    const refused = expect(acquiring).rejects.toThrow('vault locked'); await entered.promise;
    let completed = false;
    const locking = f.subject.lock().then(() => { completed = true; });
    const outcome = await terminal(locking);
    resume.resolve('google:fixture'); await refused; await locking;
    expect(outcome.kind).toBe('done'); expect(completed).toBe(true);
    expect(f.native.holdVaultKey).not.toHaveBeenCalled(); f.custody();
  });

  it('an old decryption error after Lock cannot replace the newer account outcome', async () => {
    const f = await fixture('key'); await f.subject.refresh();
    const entered = deferred<void>(), failed = deferred<never>();
    const current = f.core.current;
    f.core.current = () => {
      const open = current();
      return open && { ...open, decryptForHost: () => { entered.resolve(); return failed.promise; } };
    };
    const acquiring = f.subject.credentialFor('server'); await entered.promise;
    await f.subject.lock(); expect((await f.store.unlock(phrase)).kind).toBe('ok');
    failed.reject(new Error('old decrypt unavailable'));
    await expect(acquiring).resolves.toEqual({ kind: 'locked' });
    expect(f.subject.state.status).toBe('unlocked'); f.custody();
  });

  it('Lock waits for an in-flight native hold and final cleanup even if scoped cleanup fails', async () => {
    const f = await fixture('key'); await f.subject.refresh();
    const entered = deferred<void>(), held = deferred<{ handleId: string; algorithm: string; fingerprintSha256: string }>();
    f.native.holdVaultKey.mockImplementation(() => { entered.resolve(); return held.promise; });
    f.native.releaseVaultKeys.mockImplementation(async options => {
      if (options?.handleIds) throw new Error('scoped deletion failed');
      return { released: 1 };
    });
    const acquiring = f.subject.credentialFor('server');
    const refused = expect(acquiring).rejects.toThrow('vault locked'); await entered.promise;
    let completed = false;
    const locking = f.subject.lock().then(() => { completed = true; });
    await Promise.resolve(); expect(completed).toBe(false);
    held.resolve({ handleId: 'ephemeral-race', algorithm: 'ssh-ed25519', fingerprintSha256: 'SHA256:fixture' });
    await refused; await locking;
    expect(f.native.releaseVaultKeys.mock.calls.map(args => args[0])).toEqual([{ handleIds: ['ephemeral-race'] }, undefined]);
    expect(f.store.state).toBe('locked'); f.custody();
  });

  it('cleanup failure keeps refresh and credentials closed until a successful Lock retry', async () => {
    const f = await fixture();
    const entered = deferred<void>(), resume = deferred<string>();
    f.deps.scope = () => { entered.resolve(); return resume.promise; };
    const refreshing = f.subject.refresh(); await entered.promise;
    f.native.releaseVaultKeys.mockRejectedValueOnce(new Error('native deletion unavailable'));
    await expect(f.subject.lock()).rejects.toThrow('native deletion unavailable');
    resume.resolve(scope); await refreshing;
    await expect(f.subject.refresh()).rejects.toThrow('native deletion unavailable');
    await expect(f.subject.credentialFor('server')).rejects.toThrow('native deletion unavailable');
    expect(f.native.recall).not.toHaveBeenCalled(); expect(f.store.state).toBe('locked'); f.custody();
    await f.subject.lock(); f.deps.scope = async () => scope;
    expect(await f.subject.refresh()).toBe('unlocked'); f.custody();
  });

  it.each([
    ['NOT_SIGNED_IN', false], ['NOT_SIGNED_IN', true], ['SIGN_IN_CANCELLED', false],
  ] as const)('real Google account lookup %s with cleanup failure %s settles without a native lease or callback deadlock', async (code, cleanupFails) => {
    const f = await fixture('key'); await f.subject.refresh();
    const plugin = {
      accountId: vi.fn(async () => { throw Object.assign(new Error('synthetic account rejection'), { code }); }),
    };
    const google = createGoogleSyncNative(plugin as never);
    f.deps.accountId = async () => { await google.accountId!(); return 'google:fixture'; };
    const sync = { status: { loggedIn: true, email: scope, keychainAvailable: true } as SyncStatus | null,
      passphrase: 'ephemeral sync phrase', accountHosts: [], refreshStatus: vi.fn(async () => {}) };
    const controller = createKeyVaultController({ store: f.store });
    const cleanup = vi.fn(() => f.subject.lock());
    if (cleanupFails) f.native.releaseVaultKeys.mockRejectedValueOnce(new Error('native wipe failed'));
    const dispose = bindAndroidAuthLoss(google, { sync, vault: controller, lockAndroidVault: cleanup });
    try {
      const outcome = await terminal(f.subject.credentialFor('server'));
      expect(outcome.kind).toBe('failed');
      expect(plugin.accountId).toHaveBeenCalledTimes(1);
      expect(f.native.holdVaultKey).not.toHaveBeenCalled();
      if (code === 'NOT_SIGNED_IN') {
        expect(cleanup).toHaveBeenCalledTimes(1); expect(f.native.releaseVaultKeys).toHaveBeenCalledTimes(1);
        expect(f.store.state).toBe('locked'); expect(controller.view.state).toBe('locked');
        expect(sync.status?.loggedIn).toBe(false); expect(sync.passphrase).toBe('');
        if (cleanupFails) await expect(f.subject.refresh()).rejects.toThrow('native wipe failed');
      } else {
        expect(cleanup).not.toHaveBeenCalled(); expect(f.native.releaseVaultKeys).not.toHaveBeenCalled();
        expect(f.store.state).toBe('unlocked'); expect(sync.status?.loggedIn).toBe(true);
      }
      f.custody();
    } finally { dispose(); }
  });

  it.each([false, true])('real Google auth loss while native hold is pending completes cleanup with final wipe failure %s', async cleanupFails => {
    const f = await fixture('key'); await f.subject.refresh();
    const entered = deferred<void>(), held = deferred<{ handleId: string; algorithm: string; fingerprintSha256: string }>();
    f.native.holdVaultKey.mockImplementation(() => { entered.resolve(); return held.promise; });
    f.native.releaseVaultKeys.mockImplementation(async options => {
      if (!options?.handleIds && cleanupFails) throw new Error('native final wipe failed');
      return { released: 1 };
    });
    const google = createGoogleSyncNative({ request: async () => {
      throw Object.assign(new Error('synthetic auth loss'), { code: 'NOT_SIGNED_IN' });
    } } as never);
    const controller = createKeyVaultController({ store: f.store });
    const sync = { status: { loggedIn: true, email: scope, keychainAvailable: true } as SyncStatus | null,
      passphrase: 'ephemeral sync phrase', accountHosts: [], refreshStatus: vi.fn(async () => {}) };
    const cleanup = vi.fn(() => f.subject.lock());
    const dispose = bindAndroidAuthLoss(google, { sync, vault: controller, lockAndroidVault: cleanup });
    try {
      const acquiring = terminal(f.subject.credentialFor('server')); await entered.promise;
      let lossSettled = false;
      const losing = terminal(google.request({ method: 'GET' })).then(value => { lossSettled = true; return value; });
      await vi.waitFor(() => expect(cleanup).toHaveBeenCalledTimes(1));
      expect(lossSettled).toBe(false); expect(f.native.releaseVaultKeys).not.toHaveBeenCalled();
      held.resolve({ handleId: 'ephemeral-race', algorithm: 'ssh-ed25519', fingerprintSha256: 'SHA256:fixture' });
      expect((await acquiring).kind).toBe('failed'); expect((await losing).kind).toBe('failed');
      expect(f.native.releaseVaultKeys.mock.calls.map(args => args[0])).toEqual([{ handleIds: ['ephemeral-race'] }, undefined]);
      expect(f.store.state).toBe('locked'); expect(sync.status?.loggedIn).toBe(false);
      if (cleanupFails) await expect(f.subject.refresh()).rejects.toThrow('native final wipe failed');
      f.custody();
    } finally { dispose(); }
  });
});
