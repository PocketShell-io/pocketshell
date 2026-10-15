/**
 * Build the account the packaged key-vault journey reads (key-vault W4): a
 * real vault (core KeyVaultStore, the one implementation of the format)
 * holding the Docker agents fixture key bound to one account host, plus the
 * `main` host list encrypted with the sync passphrase the vault index
 * carries (one passphrase, contract #2). Output: every slot as the sync API
 * stores it. Test material only: the fixture key is the committed Docker
 * test key and the passphrases are throwaway journey values.
 */
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
  must('bindHost', await store.bindHost(args.alias, keyId, args.gatewayAuth));
  store.lock();
  const main = serializeSyncPayload([{ name: args.alias, hostname: args.hostname, port: args.port, user: args.user }]);
  const envelope = await encryptToEnvelope(main, args.syncPassphrase, 1000);
  const slots: Record<string, { version: number; data: string }> = { main: { version: 1, data: envelope } };
  for (const [slot, value] of transport.slots) slots[slot] = value;
  // A fragment of the key body the journey scans the app's storage and logs
  // for: a private key at rest anywhere would contain it.
  const body = readFileSync(args.key, 'utf8').split('\n').filter((line) => line && !line.startsWith('-----'));
  const privateKeyMarker = (body[1] ?? body[0] ?? '').slice(0, 40);
  if (privateKeyMarker.length < 32) throw new Error('the fixture key is too short to mark');
  writeFileSync(args.out, JSON.stringify({
    keyId, alias: args.alias, privateKeyMarker, slots, ...(args.publicKey ? { publicKey: args.publicKey } : {}),
  }), { mode: 0o600 });
  process.stdout.write(`PASS: vault journey fixture with ${Object.keys(slots).length} slots (key ${keyId}) -> ${args.out}\n`);
}

main().catch((error) => {
  process.stderr.write(`FAIL: ${error instanceof Error ? error.message : String(error)}\n`);
  process.exit(1);
});
