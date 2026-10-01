/**
 * Key-vault references held by hosts added in the shared app, in the shape
 * #2926's CredentialKeyManager uses to warn before deleting a key that hosts
 * still use and to detach/restore those hosts atomically. Combined with the
 * 0.5.x import's references, so either shell's key screen sees every host.
 */
import type { SshKeyHostReference, SshKeyReferenceStore } from '@/credentials/keyManagement';
import type { AndroidHostStore } from './hostStore';

const HOST_ID_PREFIX = 'android-host:';

function referencesOf(hosts: AndroidHostStore, handleId: string): SshKeyHostReference[] {
  return hosts.hostsUsingKey(handleId)
    .map((host) => ({ hostId: `${HOST_ID_PREFIX}${host.name}`, hostLabel: host.name }))
    .sort((left, right) => left.hostId.localeCompare(right.hostId));
}

function namesOf(references: readonly SshKeyHostReference[]): string[] {
  return references
    .filter((reference) => reference.hostId.startsWith(HOST_ID_PREFIX))
    .map((reference) => reference.hostId.slice(HOST_ID_PREFIX.length));
}

export function createAndroidHostKeyReferenceStore(hosts: AndroidHostStore): SshKeyReferenceStore {
  return {
    async list(handleId) {
      return referencesOf(hosts, handleId);
    },
    async detach(handleId, references) {
      const names = namesOf(references).sort();
      const current = namesOf(referencesOf(hosts, handleId)).sort();
      if (current.join('\n') !== names.join('\n')) return false;
      return names.length === 0 || hosts.replaceKeyHandle(names, handleId, '');
    },
    async restore(handleId, references) {
      const names = namesOf(references);
      return names.length === 0 || hosts.replaceKeyHandle(names, '', handleId);
    },
    async reconcileMissing(available) {
      const stale = new Set(hosts.savedHosts().map((host) => host.keyHandleId).filter((handle) => handle && !available.has(handle)));
      for (const handle of stale) {
        hosts.replaceKeyHandle(hosts.hostsUsingKey(handle).map((host) => host.name), handle, '');
      }
    },
  };
}

/**
 * One reference store over several: lists concatenate; detach applies to each
 * store with only its own references and rolls back the earlier ones if a
 * later store refuses.
 */
export function combineKeyReferenceStores(...stores: SshKeyReferenceStore[]): SshKeyReferenceStore {
  const own = async (store: SshKeyReferenceStore, handleId: string, references: readonly SshKeyHostReference[]) => {
    const ids = new Set((await store.list(handleId)).map((row) => row.hostId));
    return references.filter((reference) => ids.has(reference.hostId));
  };
  const detachedByStore = new Map<string, Array<[SshKeyReferenceStore, SshKeyHostReference[]]>>();
  return {
    async list(handleId) {
      return (await Promise.all(stores.map((store) => store.list(handleId)))).flat();
    },
    async detach(handleId, references) {
      const done: Array<[SshKeyReferenceStore, SshKeyHostReference[]]> = [];
      for (const store of stores) {
        const mine = await own(store, handleId, references);
        if (!await store.detach(handleId, mine)) {
          for (const [undo, theirs] of done) await undo.restore(handleId, theirs);
          return false;
        }
        done.push([store, mine]);
      }
      const claimed = done.reduce((count, [, mine]) => count + mine.length, 0);
      if (claimed !== references.length) {
        for (const [undo, theirs] of done) await undo.restore(handleId, theirs);
        return false;
      }
      detachedByStore.set(handleId, done);
      return true;
    },
    async restore(handleId) {
      const done = detachedByStore.get(handleId) ?? [];
      detachedByStore.delete(handleId);
      let ok = true;
      for (const [store, mine] of done) ok = (await store.restore(handleId, mine)) && ok;
      return ok;
    },
    commitDelete(handleId) {
      detachedByStore.delete(handleId);
      for (const store of stores) store.commitDelete?.(handleId);
    },
    async reconcileMissing(available) {
      for (const store of stores) await store.reconcileMissing?.(available);
    },
  };
}
