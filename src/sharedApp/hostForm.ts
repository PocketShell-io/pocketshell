/**
 * The add-host page's state and actions, kept out of the component so they
 * run under unit tests with a fake key vault. Keys come from the Android key
 * vault through #2926's CredentialKeyManager (import with an optional
 * passphrase, then pick by handle); key bytes never reach this code.
 */
import { reactive } from 'vue';
import type { CredentialKeyManager } from '@/credentials/keyManagement';
import type { SshKeyMetadata } from '@/native/sshKeyVault';
import { validateSavedHost, type AndroidHostStore } from '@/platform/android/hostStore';

export interface HostFormDeps {
  keys: Pick<CredentialKeyManager, 'list' | 'pickAndImport'>;
  hosts: Pick<AndroidHostStore, 'save'>;
}

export function createHostForm(deps: HostFormDeps) {
  const state = reactive({
    name: '',
    hostname: '',
    port: '22',
    user: '',
    keyHandleId: '',
    keyLabel: '',
    keyPassphrase: '',
    keys: [] as SshKeyMetadata[],
    error: null as string | null,
    importing: false,
  });

  const message = (error: unknown) => (error instanceof Error ? error.message : String(error));

  async function loadKeys(): Promise<void> {
    try {
      state.keys = await deps.keys.list();
      if (!state.keyHandleId && state.keys.length === 1) state.keyHandleId = state.keys[0]!.handleId;
    } catch (error) {
      state.error = message(error);
    }
  }

  /** "Import key file…": pick a document, import it (with its passphrase, if any), select it. */
  async function importKey(): Promise<void> {
    state.error = null;
    state.importing = true;
    try {
      const key = await deps.keys.pickAndImport({
        label: state.keyLabel.trim() || state.name.trim() || 'SSH key',
        ...(state.keyPassphrase ? { passphrase: state.keyPassphrase } : {}),
      });
      if (!key) return;
      state.keyPassphrase = '';
      await loadKeys();
      state.keyHandleId = key.handleId;
    } catch (error) {
      state.error = message(error);
    } finally {
      state.importing = false;
    }
  }

  /** Validate and save; true when the host was stored. */
  function save(): boolean {
    const host = {
      name: state.name.trim() || state.hostname.trim(),
      hostname: state.hostname,
      port: Number(state.port),
      user: state.user,
      keyHandleId: state.keyHandleId,
    };
    const problem = validateSavedHost(host);
    if (problem) {
      state.error = problem;
      return false;
    }
    try {
      deps.hosts.save(host);
    } catch (error) {
      state.error = message(error);
      return false;
    }
    state.error = null;
    return true;
  }

  return { state, loadKeys, importKey, save };
}
