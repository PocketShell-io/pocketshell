/**
 * The phone's key prompt for an account host (#3063). Synced hosts carry no
 * key material (#3020), so the first dial of an account host on this phone
 * asks which key-vault key it uses. The answer saves the host on the phone
 * (the add-host form's own validation and store, hostForm.ts), and the dial
 * then goes ahead with that key. Logic only; AccountHostKeyGate.vue renders
 * it over the shared app, the way core's HostKeyTrustGate renders the
 * host-key question.
 */
import { reactive } from 'vue';
import type { HostEntry } from '@pocketshell/core';
import { createHostForm, type HostFormDeps } from './hostForm';

export function createAccountHostKeyPrompt(deps: HostFormDeps) {
  const form = createHostForm(deps);
  const state = reactive({
    /** The account host waiting for this phone's key, or null. */
    pending: null as HostEntry | null,
    /** Changes with every question, so the prompt remounts for each. */
    seq: 0,
  });
  let settle: ((saved: boolean) => void) | null = null;

  /** Ask for the key; true once the host is saved on the phone with it. */
  function ask(host: HostEntry): Promise<boolean> {
    // One question at a time: a newer dial supersedes an unanswered one,
    // which is declined (nothing saved, nothing dialled).
    settle?.(false);
    Object.assign(form.state, {
      name: host.name,
      hostname: host.hostname,
      port: String(host.port),
      user: host.user,
      keyHandleId: '',
      keyLabel: host.name,
      keyPassphrase: '',
      error: null,
    });
    state.seq += 1;
    state.pending = host;
    void form.loadKeys();
    return new Promise<boolean>((resolve) => {
      settle = (saved) => {
        settle = null;
        state.pending = null;
        resolve(saved);
      };
    });
  }

  /** Save the host with the chosen key and let the dial go ahead; false keeps the prompt open with its error. */
  function confirm(): boolean {
    if (!settle || !form.save()) return false;
    settle(true);
    return true;
  }

  function cancel(): void {
    settle?.(false);
  }

  return { state, form, ask, confirm, cancel };
}

export type AccountHostKeyPrompt = ReturnType<typeof createAccountHostKeyPrompt>;
