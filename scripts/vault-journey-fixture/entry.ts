/**
 * Build the account the packaged key-vault journey reads (key-vault W4): a
 * real vault (core KeyVaultStore, the one implementation of the format)
 * holding the Docker agents fixture key bound to one account host, plus the
 * `main` host list encrypted with the sync passphrase the vault index
 * carries (one passphrase, contract #2). Output: every slot as the sync API
 * stores it. Test material only: the fixture key is the committed Docker
 * test key and the passphrases are throwaway journey values.
 *
 * Output contract: slots is the active API slot map {slot:{version,data}}.
 * With --trusted-hello, variants contains complete slot maps for active,
 * beforeRegistration (older authenticated header/index), wrongPin (valid AEAD,
 * different authenticated SSH pin at revision 2), missing (unknown route),
 * and restoredPin (the legitimate corrected pin at revision 3).
 * publicKey is public-only host authorization; privateKeyMarker is a fragment
 * used exclusively for custody assertions. This file is test-private evidence.
 * Consumers must serve variant slots atomically, never splice encrypted headers.

 */
import { randomBytes } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { KeyVaultStore, type VaultSlotTransport } from '@pocketshell/core';
import { serializeSyncPayload } from '@pocketshell/core';
import { encryptToEnvelope } from '../../src/sync/syncCrypto';

interface Args {
  key: string;
  alias: string;
  hostname: string;
  port: number;
  user: string;
  passphrase: string;
  syncPassphrase: string;
  gatewayAuth: 'host' | 'managed' | 'both';
  /** The key's OpenSSH public line, carried for a journey that authorizes it on a host. */
  publicKey: string | null;
  out: string;
  trustedHello: string | null;
}

function parseArgs(argv: string[]): Args {
  const values = new Map<string, string>();
  for (let index = 0; index < argv.length; index += 2) values.set(argv[index]!.replace(/^--/, ''), argv[index + 1]!);
  const need = (name: string) => {
    const value = values.get(name);
    if (!value) throw new Error(`--${name} is required`);
    return value;
  };
  return {
    key: need('key'),
    alias: need('alias'),
    hostname: need('hostname'),
    port: Number(need('port')),
    user: need('user'),
    passphrase: need('passphrase'),
    syncPassphrase: need('sync-passphrase'),
    gatewayAuth: (values.get('gateway-auth') ?? 'host') as Args['gatewayAuth'],
    publicKey: values.get('public-key') ?? null,
    out: need('out'),
    trustedHello: values.get('trusted-hello') ?? null,
  };
}

class Slots implements VaultSlotTransport {
  readonly slots = new Map<string, { version: number; data: string }>();
  async pull(slot: string) {
    const stored = this.slots.get(slot);
    return stored ? { kind: 'ok' as const, ...stored } : { kind: 'absent' as const };
  }
  async push(slot: string, data: string, baseVersion: number) {
    const current = this.slots.get(slot)?.version ?? 0;
    if (baseVersion !== current) return { kind: 'conflict' as const, currentVersion: current };
    this.slots.set(slot, { version: current + 1, data });
    return { kind: 'ok' as const, version: current + 1 };
  }
  async delete(slot: string) {
    this.slots.delete(slot);
  }
  async list() {
    return [...this.slots].map(([slot, { version }]) => ({ slot, version }));
  }
}

function must<T extends { kind: string }>(what: string, result: T): T {
  if (result.kind !== 'ok') throw new Error(`${what} failed: ${JSON.stringify(result)}`);
  return result;
}

async function main(): Promise<void> {
  const args = parseArgs(process.argv.slice(2));
  const transport = new Slots();
  // The journey unlocks on an emulator: the creation-only low-memory params
  // keep the fixture's Argon2id inside the emulator's budget.
  const store = new KeyVaultStore({ transport, kdf: { m: 32768, t: 3, p: 1 } });
  must('createVault', await store.createVault(args.passphrase, { syncPassphrase: args.syncPassphrase }));
  const added = must('addKey', await store.addKey(readFileSync(args.key, 'utf8'), 'docker fixture key'));
  const keyId = (added as unknown as { entry: { keyId: string } }).entry.keyId;
  const variants: Record<string, Record<string, { version: number; data: string }>> = {};
  const snapshot = () => Object.fromEntries([...transport.slots].map(([slot,row]) => [slot,{...row}]));
  variants.beforeRegistration = snapshot();
  must('bindHost', await store.bindHost(args.alias, keyId, args.gatewayAuth));
  if (args.trustedHello) {
    const hello = JSON.parse(readFileSync(args.trustedHello, 'utf8'));
    if (hello.directPort !== undefined)
      must('bind direct account host', await store.bindHost('account-direct', keyId, 'host'));
    const devicePublicKey = randomBytes(32).toString('base64url');
    must('managed bindHost', await store.bindHost(args.alias, keyId, 'managed'));
    must('trusted host', await store.putTrustedHost(`google:${hello.sub}`, { serverUrl: hello.serverUrl, deviceId: hello.deviceId }, {
      hostAlias: args.alias, username: hello.user, keyId, sshHostKey: hello.hostKeyLine,
      devicePublicKey, status: 'active', updatedAt: new Date().toISOString(),
    }, 0));
    variants.active = snapshot();
    const data = {hostAlias: args.alias, username: hello.user, keyId, sshHostKey: hello.wrongHostKeyLine,
      devicePublicKey, status: 'active' as const, updatedAt: new Date().toISOString()};
    must('wrong-pin trusted host', await store.putTrustedHost(`google:${hello.sub}`,
      {serverUrl:hello.serverUrl,deviceId:hello.deviceId},data,1));
    variants.wrongPin = snapshot();
    must('missing device binding', await store.bindHost('gw-missing',keyId,'managed'));
    must('missing trusted host', await store.putTrustedHost(`google:${hello.sub}`,
      {serverUrl:hello.serverUrl,deviceId:hello.missingDeviceId},{...data,hostAlias:'gw-missing',sshHostKey:hello.hostKeyLine},0));
    // Keep the already-seen primary descriptor: clients must preserve its floor
    // while inspecting a different, unknown device in the same account.
    variants.missing = snapshot();
    must('corrected-pin trusted host', await store.putTrustedHost(`google:${hello.sub}`,
      {serverUrl:hello.serverUrl,deviceId:hello.deviceId},
      {...data,sshHostKey:hello.hostKeyLine,updatedAt:new Date().toISOString()},2));
    variants.restoredPin = snapshot();
    transport.slots.clear();
    for (const [slot,row] of Object.entries(variants.active)) transport.slots.set(slot,row);
  }
  // Verify every generated variant by independently unlocking and authenticating
  // its descriptors through core, before an APK can consume it.
  if (args.trustedHello) {
    const hello = JSON.parse(readFileSync(args.trustedHello, 'utf8'));
    const account = `google:${hello.sub}`;
    for (const [name, slots] of Object.entries(variants)) {
      const readback = new Slots();
      for (const [slot, row] of Object.entries(slots)) readback.slots.set(slot, { ...row });
      const reader = new KeyVaultStore({ transport: readback });
      must(`${name} unlock`, await reader.unlock(args.passphrase));
      const hosts = await reader.listTrustedHosts(account);
      const expectedCount = name === 'beforeRegistration' ? 0 : ['missing','restoredPin'].includes(name) ? 2 : 1;
      if (hosts.length !== expectedCount) throw new Error(`${name}: expected ${expectedCount} authenticated descriptor`);
      for (const host of hosts) {
        const missing = host.deviceId === hello.missingDeviceId;
        const expectedPin = !missing && ['wrongPin','missing'].includes(name) ? hello.wrongHostKeyLine : hello.hostKeyLine;
        const expectedRevision = missing ? 1 : name === 'restoredPin' ? 3 : ['wrongPin','missing'].includes(name) ? 2 : 1;
        if ((!missing && host.deviceId !== hello.deviceId) || host.sshHostKey !== expectedPin || host.keyId !== keyId
          || host.revision !== expectedRevision || host.version !== expectedRevision)
          throw new Error(`${name}: descriptor failed the independent cryptographic readback`);
      }
      let foreignRejected = false;
      try { foreignRejected = (await reader.listTrustedHosts('google:foreign-subject')).length === 0; } catch { foreignRejected = true; }
      if (!foreignRejected) throw new Error(`${name}: foreign account accepted`);
      reader.lock();
    }
    const evolving = new Slots();
    for (const [slot, row] of Object.entries(variants.beforeRegistration!)) evolving.slots.set(slot, { ...row });
    const reader = new KeyVaultStore({ transport: evolving });
    must('pre-registration unlock', await reader.unlock(args.passphrase));
    if ((await reader.listTrustedHosts(account)).length) throw new Error('pre-registration vault unexpectedly lists a server');
    evolving.slots.clear();
    for (const [slot, row] of Object.entries(variants.active!)) evolving.slots.set(slot, { ...row });
    if ((await reader.listTrustedHosts(account)).length !== 1) throw new Error('unlocked client failed authenticated registration refresh');
    reader.lock();
    // Real core floors accept a legitimate new AEAD revision and reject the
    // original ciphertext; no protected native cache is cleared for recovery.
    evolving.slots.clear();
    for (const [slot, row] of Object.entries(variants.wrongPin!)) evolving.slots.set(slot, { ...row });
    must('revision-2 unlock', await reader.unlock(args.passphrase));
    const target = {serverUrl:hello.serverUrl,deviceId:hello.deviceId};
    const second = await reader.getTrustedHost(account,target,2);
    if (second.kind !== 'ok' || second.host.revision !== 2) throw new Error('valid wrong pin revision 2 was refused');
    evolving.slots.clear();
    for (const [slot, row] of Object.entries(variants.restoredPin!)) evolving.slots.set(slot, { ...row });
    const third = await reader.getTrustedHost(account,target,2);
    if (third.kind !== 'ok' || third.host.revision !== 3 || third.host.sshHostKey !== hello.hostKeyLine)
      throw new Error('legitimate corrected pin revision 3 was refused');
    evolving.slots.clear();
    for (const [slot, row] of Object.entries(variants.active!)) evolving.slots.set(slot, { ...row });
    if ((await reader.getTrustedHost(account,target,3)).kind !== 'rollback') throw new Error('revision 3 to 1 rollback was accepted');
    const slot = Object.keys(variants.active!).find(name => name.startsWith('th-'))!;
    const nonces = [variants.active!,variants.wrongPin!,variants.restoredPin!].map(slots => JSON.parse(slots[slot]!.data).n);
    if (new Set(nonces).size !== 3) throw new Error('trusted descriptor nonce was reused');
    reader.lock();
  }
  store.lock();
  const main = serializeSyncPayload([{ name: args.alias, hostname: args.hostname, port: args.port, user: args.user }]);
  const envelope = await encryptToEnvelope(main, args.syncPassphrase, 1000);
  const slots: Record<string, { version: number; data: string }> = { main: { version: 1, data: envelope } };
  for (const [slot, value] of transport.slots) slots[slot] = value;
  for (const variant of Object.values(variants)) variant.main = { version: 1, data: envelope };
  // A fragment of the key body the journey scans the app's storage and logs
  // for: a private key at rest anywhere would contain it.
  const body = readFileSync(args.key, 'utf8').split('\n').filter((line) => line && !line.startsWith('-----'));
  const privateKeyMarker = (body[1] ?? body[0] ?? '').slice(0, 40);
  if (privateKeyMarker.length < 32) throw new Error('the fixture key is too short to mark');
  writeFileSync(args.out, JSON.stringify({
    keyId, alias: args.alias, privateKeyMarker, slots, variants, ...(args.publicKey ? { publicKey: args.publicKey } : {}),
  }), { mode: 0o600 });
  process.stdout.write(`PASS: vault journey fixture with ${Object.keys(slots).length} slots (key ${keyId}) -> ${args.out}\n`);
}

main().catch((error) => {
  process.stderr.write(`FAIL: ${error instanceof Error ? error.message : String(error)}\n`);
  process.exit(1);
});
