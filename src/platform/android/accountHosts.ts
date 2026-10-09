/**
 * Account hosts on the phone (#3063): the shared picker lists the hosts of the
 * signed-in account ("From your account") next to the phone's own, but a
 * synced entry never carries key material (#3020): each phone chooses its own
 * key for a host. So when the user taps an account host the phone has no key
 * for, the dial asks which key this phone uses, saves the host on the phone
 * with that key, and only then dials — the same rule the legacy home screen
 * applied by filling its form from a synced host.
 *
 * A phone host whose key is gone (deleted from the vault) gets the same
 * prompt, and the chosen key is attached to that host in place: the host is
 * never saved a second time.
 *
 * This module is the decision; the prompt itself is the Android shell's
 * (src/sharedApp/accountHostKey.ts).
 */
import type { HostEntry } from '@pocketshell/core';

export interface AccountHostRequest {
  host: string;
  port?: number;
  user: string;
}

/** Why the phone asks: a host from the account, or a phone host whose key is gone. */
export type HostKeyReason = 'account' | 'missing-key';

export interface AccountHostKeys {
  /** The account host a connect request names, or null when it names none. */
  find(request: AccountHostRequest): Promise<HostEntry | null>;
  /**
   * Ask the user for this phone's key for `host`. Resolves true once the host
   * is saved on the phone with a key-vault key (an existing phone host of the
   * same name is updated, not duplicated), false when the user declined.
   */
  adopt(host: HostEntry, reason: HostKeyReason): Promise<boolean>;
}

export interface AccountHostKeysDeps {
  /** The unlocked account copy (`api.sync.accountHosts`), null while locked. */
  accountHosts(): Promise<HostEntry[] | null>;
  /** The phone's key prompt. */
  ask(host: HostEntry, reason: HostKeyReason): Promise<boolean>;
}

/**
 * The account host a dial is for, matched the way the shared store names a
 * host in `ssh.connect`: hostname and port, and the user when both sides
 * name one.
 */
export function matchAccountHost(hosts: readonly HostEntry[], request: AccountHostRequest): HostEntry | null {
  const port = request.port ?? 22;
  return hosts.find((host) => host.hostname === request.host && host.port === port
    && (!request.user || !host.user || host.user === request.user)) ?? null;
}

export function createAccountHostKeys(deps: AccountHostKeysDeps): AccountHostKeys {
  return {
    async find(request) {
      const hosts = await deps.accountHosts().catch(() => null);
      return hosts ? matchAccountHost(hosts, request) : null;
    },
    adopt: (host, reason) => deps.ask(host, reason),
  };
}

/** What the picker shows when the user closed the key prompt without a key. */
export function declinedAccountHostMessage(name: string): string {
  return `No SSH key was chosen for “${name}” on this phone, so nothing was dialled.`;
}
