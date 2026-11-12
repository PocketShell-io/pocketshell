/** Dev-only native boundaries. Vault/descriptor encryption and unlock remain core's. */
import { KeyVaultStore, type TrustedHostRecord, type VaultSlotTransport } from '@pocketshell/core';
import { DEV_SYNC_EMAIL, DEV_SYNC_PASSPHRASE } from './googleSyncPlugin';
import { DevPluginError, type DevPlugin } from './nativeBridge';
import { mockPresentedHostKey } from './mockSsh';
import { bytesToBase64, randomId } from './bytes';

export const DEV_TRUSTED_ACCOUNT = 'google:browser-dev-fixture';
export const DEV_TRUSTED_TARGET = { serverUrl: 'wss://gateway.dev.invalid', deviceId: 'mock-devbox' };

export function createTrustedMockAccount(google: DevPlugin, ssh: DevPlugin, keys: DevPlugin) {
  const slots = new Map<string, { data: string; version: number }>();
  const transport: VaultSlotTransport = {
    pull: async slot => { const row = slots.get(slot); return row ? { kind: 'ok', ...row } : { kind: 'absent' }; },
    push: async (slot, data, base) => {
      const current = slots.get(slot)?.version ?? 0;
      if (base !== current) return { kind: 'conflict', currentVersion: current };
      slots.set(slot, { data, version: current + 1 });
      return { kind: 'ok', version: current + 1 };
    },
    delete: async slot => { slots.delete(slot); },
    list: async () => [...slots].map(([slot, row]) => ({ slot, version: row.version })),
  };
  let prepared: Promise<void> | undefined;
  let fixturePem = '';
  let fixtureFingerprint = '';
  let fixtureHost: TrustedHostRecord | undefined;
  const prepare = () => prepared ??= (async () => {
    // Throwaway per-page key, never an operator key or a persisted plaintext credential.
    const pair = await crypto.subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify']) as CryptoKeyPair;
    fixturePem = `-----BEGIN PRIVATE KEY-----\n${bytesToBase64(new Uint8Array(await crypto.subtle.exportKey('pkcs8', pair.privateKey)))}\n-----END PRIVATE KEY-----`;
    const store = new KeyVaultStore({ transport, kdf: { m: 32768, t: 3, p: 1 } });
    if ((await store.createVault(DEV_SYNC_PASSPHRASE)).kind !== 'ok') throw new Error('Dev vault creation failed');
    const added = await store.addKey(fixturePem, 'Browser dev fixture');
    if (added.kind !== 'ok') throw new Error('Dev vault key failed');
    await store.bindHost('mock-devbox', added.entry.keyId, 'managed');
    const pin = await mockPresentedHostKey();
    const saved = await store.putTrustedHost(DEV_TRUSTED_ACCOUNT, DEV_TRUSTED_TARGET, {
      hostAlias: 'mock-devbox', username: 'dev', keyId: added.entry.keyId,
      sshHostKey: `${pin.keyType} ${pin.keyB64}`, devicePublicKey: 'BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc',
      status: 'active', updatedAt: '2026-11-06T00:00:00.000Z',
    }, 0);
    if (saved.kind !== 'ok') throw new Error('Dev trusted descriptor failed');
    const host = await store.getTrustedHost(DEV_TRUSTED_ACCOUNT, DEV_TRUSTED_TARGET);
    if (host.kind !== 'ok') throw new Error('Dev descriptor verification failed');
    fixtureHost = host.host;
    const secret = await store.decryptKeyForHost('mock-devbox');
    if (secret.kind !== 'key') throw new Error('Dev fixture key did not decrypt');
    fixturePem = secret.pem;
    fixtureFingerprint = added.entry.fingerprint ?? '';
    store.lock();
  })();
  const signedIn = async () => {
    const status = await google.methods.status({}) as { signedIn: boolean; email: string };
    if (!status.signedIn || status.email !== DEV_SYNC_EMAIL) throw new DevPluginError('Sign in first', 'NOT_SIGNED_IN');
  };
  const request = google.methods.request;
  google.methods.accountId = async () => { await signedIn(); return { accountId: DEV_TRUSTED_ACCOUNT }; };
  google.methods.request = async options => {
    await signedIn();
    if (options.endpoint === 'vault/status' || options.endpoint === 'vault/verify') return { status: 404, body: '{}' };
    if (options.endpoint === 'settings') {
      await prepare();
      return { status: 200, body: JSON.stringify({ slots: [...slots].map(([slot, row]) => ({ slot, version: row.version, size: new TextEncoder().encode(row.data).length, updatedAt: '2026-11-06T00:00:00.000Z' })) }) };
    }
    if (typeof options.slot === 'string' && /^(vault|vk-|th-)/.test(options.slot)) {
      await prepare();
      if (options.method !== 'GET') return { status: 405, body: '{}' };
      const row = slots.get(options.slot);
      return row ? { status: 200, body: JSON.stringify({ slot: options.slot, ...row }) } : { status: 404, body: '{}' };
    }
    return request(options);
  };
  let installed: TrustedHostRecord | null = null;
  const floors = new Map<string, number>();
  const held = new Set<string>();
  const scope = async (options: Record<string, unknown>) => {
    await signedIn(); await prepare();
    if (options.accountId !== DEV_TRUSTED_ACCOUNT || options.vaultId !== fixtureHost?.vaultId) throw new Error('Dev trust scope mismatch');
  };
  const gateway: DevPlugin = { methods: {
    trustedRevisions: async options => { await scope(options); return { floors: Object.fromEntries(floors) }; },
    saveTrustedRevision: async options => { await scope(options); const slot = String(options.slot); const revision = Number(options.revision); if (!/^th-[A-Za-z0-9_-]{22}$/.test(slot) || !Number.isSafeInteger(revision) || revision < 1 || revision < (floors.get(slot) ?? 0)) throw new Error('Invalid revision or rollback'); floors.set(slot, revision); },
    installTrustedHost: async options => {
      await signedIn(); await prepare();
      if (JSON.stringify(options.host) !== JSON.stringify(fixtureHost)) throw new Error('Unexpected dev descriptor');
      installed = options.host as TrustedHostRecord;
    },
    list: async options => {
      await signedIn();
      const pin = await mockPresentedHostKey();
      return { requestId: options.requestId, pairings: installed ? [{ ...DEV_TRUSTED_TARGET, pinKind: 'host-key', fingerprintSha256: pin.fingerprintSha256,
        credentialSource: 'vault', keyHandleId: installed.keyId, vaultId: installed.vaultId, vaultEpoch: installed.epoch,
        vaultKeyId: installed.keyId, trustedRevision: installed.revision, hostAlias: installed.hostAlias, username: installed.username }] : [] };
    },
  } };
  keys.methods.holdVaultKey = async options => {
    await signedIn(); await prepare();
    if (!installed || options.privateKeyPem !== fixturePem || options.keyId !== installed.keyId
      || options.accountId !== installed.accountId || options.vaultId !== installed.vaultId || options.epoch !== installed.epoch) throw new Error('Dev vault credential mismatch');
    const handleId = `ephemeral-${randomId()}`;
    held.add(handleId);
    return { handleId, algorithm: 'ssh-ed25519', fingerprintSha256: fixtureFingerprint };
  };
  keys.methods.releaseVaultKeys = options => {
    const ids = Array.isArray(options.handleIds) ? options.handleIds : [...held];
    let released = 0; for (const id of ids) if (held.delete(String(id))) released++;
    return { released };
  };
  ssh.methods.transportCapabilities = options => ({ requestId: options.requestId, gatewayTransport: true });
  const connect = ssh.methods.connect;
  ssh.methods.connect = async options => {
    if (!options.gateway) return connect(options);
    await signedIn();
    const credential = options.credential as { handleId?: string } | undefined;
    const target = options.gateway as typeof DEV_TRUSTED_TARGET;
    if (!installed || target.serverUrl !== installed.serverUrl || target.deviceId !== installed.deviceId || !held.has(credential?.handleId ?? '')) throw new Error('Untrusted dev gateway dial');
    const pin = await mockPresentedHostKey();
    if (installed.sshHostKey !== `${pin.keyType} ${pin.keyB64}`) throw new Error('Dev gateway host key mismatch');
    const result = await connect({ ...options, expectedHostKey: { kind: 'wire-key', keyType: pin.keyType, keyB64: pin.keyB64 } }) as Record<string, unknown>;
    return { ...result, gatewayHostKeyVerified: true };
  };
  // Dev native memory store: no browser persistence of remembered DEKs.
  const remembered = new Map<string, string>();
  const device: DevPlugin = { methods: {
    recall: options => ({ record: remembered.get(String(options.scope)) ?? null }),
    remember: options => { remembered.set(String(options.scope), String(options.record)); },
    forget: options => { remembered.delete(String(options.scope)); },
    forgetAll: () => { remembered.clear(); },
  } };
  const signOut = google.methods.signOut;
  google.methods.signOut = options => { installed = null; held.clear(); remembered.clear(); return signOut(options); };
  return { gateway, device };
}
