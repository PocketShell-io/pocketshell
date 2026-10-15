/**
 * The vault unlock sheet's logic (key-vault W4, design section 5 "Unlock"):
 * a passphrase field, "Remember on this device" (default off: type it every
 * time), and Unlock. A wrong passphrase says so and keeps the sheet open;
 * after three in a row the device backs off (1 s, 2 s, 4 s … 30 s). "Forgot
 * it?" explains that the passphrase cannot be recovered and sends the user to
 * the web's reset. VaultUnlockGate.vue renders it over the shared app; a dial
 * of a locked vault's host waits on `ask()`, which never offers a key picker.
 */
import { reactive } from 'vue';
import { WRONG_PASSPHRASE_MESSAGE, type VaultUnlockResult } from './androidVault';

export const VAULT_RESET_URL = 'https://app.pocketshell.io/keys?reset=1';

export const FORGOT_PASSPHRASE_TEXT = 'Your vault passphrase cannot be recovered: PocketShell never sees it. '
  + 'Reset the vault on the PocketShell web app, choose a new passphrase and upload your keys again. '
  + 'Hosts keep their key assignments, so re-uploading the same key reattaches it.';

export interface VaultUnlockPromptDeps {
  unlock(passphrase: string, remember: boolean): Promise<VaultUnlockResult | { kind: 'backoff'; retryAt: number }>;
  /** The vault's own backoff deadline (epoch ms), 0 when none. */
  retryAt(): number;
  openUrl(url: string): void | Promise<void>;
  now?(): number;
}

export function createVaultUnlockPrompt(deps: VaultUnlockPromptDeps) {
  const now = deps.now ?? (() => Date.now());
  const state = reactive({
    open: false,
    /** Why it opened: a dial waiting on it, or the user asked from the account screen. */
    reason: 'connect' as 'connect' | 'account',
    passphrase: '',
    remember: false,
    busy: false,
    error: null as string | null,
    showForgot: false,
    seq: 0,
  });
  let settle: ((unlocked: boolean) => void) | null = null;

  function ask(reason: 'connect' | 'account' = 'connect'): Promise<boolean> {
    // One sheet: a second dial waits on the same answer.
    if (state.open && settle) {
      const previous = settle;
      return new Promise<boolean>((resolve) => {
        settle = (unlocked) => {
          previous(unlocked);
          resolve(unlocked);
        };
      });
    }
    Object.assign(state, { open: true, reason, passphrase: '', busy: false, error: null, showForgot: false });
    state.seq += 1;
    return new Promise<boolean>((resolve) => {
      settle = (unlocked) => {
        settle = null;
        state.open = false;
        state.passphrase = '';
        resolve(unlocked);
      };
    });
  }

  function waitSeconds(): number {
    return Math.max(0, Math.ceil((deps.retryAt() - now()) / 1000));
  }

  async function submit(): Promise<boolean> {
    if (state.busy || !settle) return false;
    if (state.passphrase === '') {
      state.error = 'Enter your vault passphrase.';
      return false;
    }
    if (waitSeconds() > 0) {
      state.error = `${WRONG_PASSPHRASE_MESSAGE} Try again in ${waitSeconds()} s.`;
      return false;
    }
    state.busy = true;
    state.error = null;
    try {
      const result = await deps.unlock(state.passphrase, state.remember);
      switch (result.kind) {
        case 'unlocked':
          settle?.(true);
          return true;
        case 'wrong-passphrase':
        case 'backoff': {
          const wait = waitSeconds();
          state.error = wait > 0 ? `${WRONG_PASSPHRASE_MESSAGE} Try again in ${wait} s.` : WRONG_PASSPHRASE_MESSAGE;
          state.passphrase = '';
          return false;
        }
        case 'absent':
          state.error = 'This account has no key vault yet. Create one on the PocketShell web app.';
          return false;
        case 'error':
          state.error = result.message;
          return false;
      }
    } catch (error) {
      state.error = error instanceof Error && error.message ? error.message : 'The vault could not be unlocked.';
      return false;
    } finally {
      state.busy = false;
    }
  }

  function cancel(): void {
    settle?.(false);
  }

  function forgot(): void {
    state.showForgot = true;
  }

  async function openReset(): Promise<void> {
    await deps.openUrl(VAULT_RESET_URL);
  }

  return { state, ask, submit, cancel, forgot, openReset };
}

export type VaultUnlockPrompt = ReturnType<typeof createVaultUnlockPrompt>;
