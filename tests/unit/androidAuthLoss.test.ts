import { describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { readFileSync } from 'node:fs';
import { KeyVaultStore, type VaultSlotTransport } from '@pocketshell/core';
import { createKeyVaultController } from '@ui/app/keyVault';
import { provideApi } from '@ui/app/ipc';
import { useSyncStore } from '@ui/app/stores/sync';
import { bindAndroidAuthLoss } from '@/platform/android/authLoss';
import { createGoogleSyncNative } from '@/native/googleSync';

function deferred<T>() { let resolve!: (v: T) => void; const promise = new Promise<T>(r => { resolve = r; }); return { promise, resolve }; }

describe('Android native auth loss shared-store custody', () => {
  it('cancels an in-flight unlock and restores normal login without deleting encrypted vault or remembered trust', async () => {
    const slots = new Map<string, { version: number; data: string }>();
    let paused = false;
    const entered = deferred<void>(), release = deferred<void>();
    const transport: VaultSlotTransport = {
      pull: async slot => { if (paused) { entered.resolve(); await release.promise; } const x = slots.get(slot); return x ? { kind: 'ok', ...x } : { kind: 'absent' }; },
      push: async (slot, data, version) => { slots.set(slot, { version: version + 1, data }); return { kind: 'ok', version: version + 1 }; },
      delete: async slot => { slots.delete(slot); }, list: async () => [],
    };
    const store = new KeyVaultStore({ transport, kdf: { m: 32768, t: 3, p: 1 } });
    expect((await store.createVault('fixture vault words', { syncPassphrase: 'fixture sync words' })).kind).toBe('ok');
    const identity = { vaultId: store.vaultId, epoch: store.epoch };
    const encrypted = JSON.stringify([...slots]);
    const forget = vi.fn();
    const vault = createKeyVaultController({ store, remember: { isRemembered: async () => true, save: async () => undefined, forget } });
    vault.lock();
    let signedIn = true;
    const plugin = {
      status: async () => ({ signedIn, email: signedIn ? 'orin@example.test' : null, packageName: 'fixture' }),
      signIn: async () => { signedIn = true; return plugin.status(); },
      request: async () => { signedIn = false; throw { code: 'NOT_SIGNED_IN', message: 'Sign in again.' }; },
    };
    const native = createGoogleSyncNative(plugin as never);
    provideApi({ sync: { status: async () => { const s = await native.status(); return { loggedIn: s.signedIn, email: s.email, keychainAvailable: true }; }, accountHosts: async () => null } } as never);
    setActivePinia(createPinia());
    const sync = useSyncStore();
    await sync.refreshStatus();
    sync.passphrase = 'in-memory sync words';
    sync.accountHosts = [{ name: 'old-account-host' }] as never;
    const nativeLock = vi.fn(async () => undefined);
    const dispose = bindAndroidAuthLoss(native, { sync, vault, lockAndroidVault: nativeLock });
    const sheet = vault.requestUnlock('account');
    paused = true;
    const unlocking = vault.unlock('fixture vault words', false);
    await entered.promise;
    try {
      await expect(native.request({ method: 'GET' })).rejects.toMatchObject({ code: 'NOT_SIGNED_IN' });
      expect(vault.sheet.open).toBe(false);
      expect(await sheet).toBe(false);
      expect(sync.status?.loggedIn).toBe(false);
      expect(sync.passphrase).toBe('');
      expect(sync.accountHosts).toBeNull();
      expect(nativeLock).toHaveBeenCalledTimes(1);
      release.resolve();
      expect((await unlocking).kind).not.toBe('ok');
      expect(store.state).toBe('locked');
      expect(vault.view.state).toBe('locked');
      expect(JSON.stringify([...slots])).toBe(encrypted);
      expect(forget).not.toHaveBeenCalled();
      // Normal Google login, no manual sign-out, opens the same bound encrypted vault.
      await native.signIn(); await sync.refreshStatus();
      paused = false;
      expect((await vault.unlock('fixture vault words', false)).kind).toBe('ok');
      expect({ vaultId: store.vaultId, epoch: store.epoch }).toEqual(identity);
      expect(JSON.stringify([...slots])).toBe(encrypted);
    } finally {
      release.resolve(); dispose(); store.lock();
      await unlocking;
    }
  });

  it('does not cancel or lock a signed-in consumer for ordinary network or provider cancellation', async () => {
    let failure = { code: 'SYNC_NETWORK_FAILED' };
    const native = createGoogleSyncNative({ request: async () => { throw failure; } } as never);
    const sync = { status: { loggedIn: true, email: 'orin@example.test', keychainAvailable: true }, passphrase: 'kept', accountHosts: [], refreshStatus: vi.fn() };
    const vault = { cancelUnlock: vi.fn(), lock: vi.fn() };
    const lockAndroidVault = vi.fn();
    const dispose = bindAndroidAuthLoss(native, { sync, vault, lockAndroidVault });
    for (const code of ['SYNC_NETWORK_FAILED', 'SIGN_IN_CANCELLED']) {
      failure = { code }; await expect(native.request({ method: 'GET' })).rejects.toBe(failure);
    }
    expect(sync.status.loggedIn).toBe(true); expect(sync.passphrase).toBe('kept');
    expect(vault.cancelUnlock).not.toHaveBeenCalled(); expect(vault.lock).not.toHaveBeenCalled();
    expect(lockAndroidVault).not.toHaveBeenCalled(); dispose();
  });

  it('rejects pre-loss status completion without resurrecting the actual shared signed-in display', async () => {
    const pending = deferred<unknown>();
    let first = true;
    const signedOut = { signedIn: false, email: null, packageName: 'fixture' };
    const native = createGoogleSyncNative({
      status: () => first ? (first = false, pending.promise) : Promise.resolve(signedOut),
      request: async () => { throw { code: 'NOT_SIGNED_IN' }; },
    } as never);
    provideApi({ sync: {
      status: async () => { const s = await native.status(); return { loggedIn: s.signedIn, email: s.email, keychainAvailable: true }; },
      accountHosts: async () => null,
    } } as never);
    setActivePinia(createPinia());
    const sync = useSyncStore();
    const dispose = bindAndroidAuthLoss(native, { sync, vault: { cancelUnlock: vi.fn(), lock: vi.fn() }, lockAndroidVault: async () => undefined });
    try {
      const old = sync.refreshStatus();
      await expect(native.request({ method: 'GET' })).rejects.toMatchObject({ code: 'NOT_SIGNED_IN' });
      expect(sync.status?.loggedIn).toBe(false);
      const stale = expect(old).rejects.toThrow('sign-in changed');
      pending.resolve({ signedIn: true, email: 'old@example.test', packageName: 'fixture' });
      await stale;
      expect(sync.status?.loggedIn).toBe(false);
      expect(sync.status?.email).toBeNull();
    } finally { dispose(); }
  });

  it('keeps a later different login when obsolete signed-in or signed-out status completes out of order', async () => {
    for (const obsoleteSignedIn of [true, false]) {
      const pending = deferred<unknown>();
      let first = true;
      let current = { signedIn: false, email: null as string | null, packageName: 'fixture' };
      const native = createGoogleSyncNative({
        status: () => first ? (first = false, pending.promise) : Promise.resolve(current),
        request: async () => { throw { code: 'NOT_SIGNED_IN' }; },
        signIn: async () => { current = { signedIn: true, email: 'new@example.test', packageName: 'fixture' }; return current; },
      } as never);
      provideApi({ sync: {
        status: async () => { const s = await native.status(); return { loggedIn: s.signedIn, email: s.email, keychainAvailable: true }; },
        login: async () => (await native.signIn()).email,
        accountHosts: async () => null,
      } } as never);
      setActivePinia(createPinia());
      const sync = useSyncStore();
      const locked = vi.fn();
      const dispose = bindAndroidAuthLoss(native, { sync, vault: { cancelUnlock: vi.fn(), lock: locked }, lockAndroidVault: async () => undefined });
      try {
        const old = sync.refreshStatus();
        await expect(native.request({ method: 'GET' })).rejects.toMatchObject({ code: 'NOT_SIGNED_IN' });
        await sync.login(); // Actual shared login + fresh status refresh, not direct status assignment.
        expect(sync.status).toMatchObject({ loggedIn: true, email: 'new@example.test' });
        const stale = expect(old).rejects.toThrow('sign-in changed');
        pending.resolve({ signedIn: obsoleteSignedIn, email: obsoleteSignedIn ? 'old@example.test' : null, packageName: 'fixture' });
        await stale;
        expect(sync.status).toMatchObject({ loggedIn: true, email: 'new@example.test' });
        expect(locked).toHaveBeenCalledTimes(1); // Stale status never emits a fresh auth-loss event.
      } finally { dispose(); }
    }
  });

  it('binds auth loss in the actual shared bootstrap to transient-only Android cleanup', () => {
    const main = readFileSync('src/sharedApp/main.ts', 'utf8');
    const platform = readFileSync('src/sharedApp/platform.ts', 'utf8');
    expect(main).toContain('bindAndroidAuthLoss(googleSync,');
    expect(main).toContain('lockAndroidVault: lockAndroidVaultAfterAuthLoss');
    expect(main).toContain('app.onUnmount(releaseAuthLoss)');
    const hook = platform.split('export function lockAndroidVaultAfterAuthLoss()')[1]!.split('\n}')[0]!;
    expect(hook).toContain("vaultSync.passphrase = ''");
    expect(hook).toContain('clearAccountCache()'); expect(hook).toContain('androidVault.lock()');
    expect(hook).not.toMatch(/signedOut|forgetAll|logout|removeItem/);
  });
});
