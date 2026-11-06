/** Android native auth invalidation wired to existing shared-store/vault hooks (D42). */
import type { GoogleSyncNative } from '@/native/googleSync';
import type { SyncStatus, HostEntry } from '@pocketshell/core';

export function bindAndroidAuthLoss(native: GoogleSyncNative, deps: {
  sync: { status: SyncStatus | null; passphrase: string; accountHosts: HostEntry[] | null; refreshStatus(): Promise<void> };
  vault: { cancelUnlock(): void; lock(): void };
  lockAndroidVault(): Promise<void>;
}): () => void {
  return native.onAuthLost?.(async () => {
    // Synchronous state/generation invalidation must precede any cleanup await.
    deps.sync.status = { loggedIn: false, email: null, keychainAvailable: true };
    deps.sync.passphrase = '';
    deps.sync.accountHosts = null;
    deps.vault.cancelUnlock();
    deps.vault.lock();
    await deps.lockAndroidVault();
    await deps.sync.refreshStatus();
  }) ?? (() => undefined);
}
