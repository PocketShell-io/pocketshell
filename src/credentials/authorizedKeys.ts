import { execChecked, type ConnectionSnapshot, type SshCapability, type SshConnectionRef } from '@pocketshell/core';
import { parseSshPublicKeyLine } from '@/native/sshKeyVault';

/**
 * "Install on host" (#3021): append a stored key's OpenSSH public line to
 * ~/.ssh/authorized_keys over an already-authenticated connection, the way
 * ssh-copy-id does. Idempotent: a key whose type and blob are already listed
 * (with any options or comment) is left alone, so the file never gains a
 * duplicate line. Only public data crosses into the command.
 */

export const AUTHORIZED_KEY_INSTALL_TIMEOUT_MS = 15_000;
const INSTALLED = 'POCKETSHELL_AUTHORIZED_KEY_INSTALLED';
const PRESENT = 'POCKETSHELL_AUTHORIZED_KEY_PRESENT';

export type AuthorizedKeyInstallResult = 'installed' | 'already-present';

function shellQuote(value: string): string {
  return `'${value.replace(/'/g, `'\\''`)}'`;
}

const BASE64_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

/** UTF-8 base64 without Buffer/btoa, so labels in any script survive. */
function base64Utf8(value: string): string {
  const bytes = new TextEncoder().encode(value);
  let out = '';
  for (let i = 0; i < bytes.length; i += 3) {
    const a = bytes[i]!;
    const b = bytes[i + 1];
    const c = bytes[i + 2];
    out += BASE64_ALPHABET[a >> 2];
    out += BASE64_ALPHABET[((a & 3) << 4) | ((b ?? 0) >> 4)];
    out += b === undefined ? '=' : BASE64_ALPHABET[((b & 15) << 2) | ((c ?? 0) >> 6)];
    out += c === undefined ? '=' : BASE64_ALPHABET[c & 63];
  }
  return out;
}

/**
 * Every character the login shell ever sees. None of these has a meaning in
 * sh, bash, dash, zsh, ksh, fish, tcsh or csh outside a word, and a base64
 * word never starts with `=` (zsh's `=cmd` expansion).
 */
export const AUTHORIZED_KEY_INSTALL_COMMAND_CHARSET = /^[A-Za-z0-9+/= |-]+$/;

/**
 * The remote command. sshd hands it to the user's LOGIN shell, which may be
 * fish or tcsh, whose quoting rules differ from POSIX (fish treats `\'`
 * inside single quotes as an escaped quote; csh rejects a newline inside
 * quotes). So no quoting reaches that shell at all: the whole POSIX script,
 * validated public line included, travels as one base64 word, is decoded by
 * `base64 -d` (GNU coreutils, BusyBox, macOS 13+) and piped into /bin/sh.
 * Only the script's own /bin/sh ever parses the quoted key line.
 */
export function buildAuthorizedKeyInstallCommand(publicKeyLine: string): string {
  const line = parseSshPublicKeyLine({ publicKey: publicKeyLine });
  const [type, blob] = line.split(' ');
  const script = [
    'umask 077',
    'set -e',
    `line=${shellQuote(line)}`,
    `key_type=${shellQuote(type!)}`,
    `key_blob=${shellQuote(blob!)}`,
    'mkdir -p "$HOME/.ssh"',
    'file="$HOME/.ssh/authorized_keys"',
    // Match the type+blob pair in any field position, so an entry with
    // options (`from="…" ssh-ed25519 AAAA…`) or another comment still counts.
    'if [ -f "$file" ] && awk -v t="$key_type" -v b="$key_blob" \'$0 !~ /^[[:space:]]*#/ { for (i = 1; i < NF; i++) if ($i == t && $(i + 1) == b) found = 1 } END { exit found ? 0 : 1 }\' "$file"; then',
    `  printf '%s\\n' ${PRESENT}`,
    '  exit 0',
    'fi',
    // Never glue the new key onto a last line that lacks its newline.
    'if [ -s "$file" ] && [ -n "$(tail -c 1 "$file")" ]; then printf \'\\n\' >> "$file"; fi',
    'printf \'%s\\n\' "$line" >> "$file"',
    `printf '%s\\n' ${INSTALLED}`,
    '',
  ].join('\n');
  const command = `echo ${base64Utf8(script)} | base64 -d | /bin/sh`;
  if (!AUTHORIZED_KEY_INSTALL_COMMAND_CHARSET.test(command)) {
    throw new Error('Could not build a shell-safe install command for this key.');
  }
  return command;
}

export function parseAuthorizedKeyInstallOutput(result: {
  exitCode: number | null;
  stdout: string;
  stderr: string;
  timedOut: boolean;
}): AuthorizedKeyInstallResult {
  if (result.timedOut) throw new Error('Installing the key on the host timed out. Check the connection and try again.');
  const marker = result.stdout.trim().split(/\r?\n/).pop();
  if (result.exitCode === 0 && marker === INSTALLED) return 'installed';
  if (result.exitCode === 0 && marker === PRESENT) return 'already-present';
  const detail = result.stderr.trim().split(/\r?\n/).find(Boolean)?.slice(0, 200);
  throw new Error(detail
    ? `The host could not add the key to ~/.ssh/authorized_keys: ${detail}`
    : 'The host could not add the key to ~/.ssh/authorized_keys.');
}

export async function installPublicKeyOnHost(
  capability: Pick<SshCapability, 'exec'>,
  connection: SshConnectionRef,
  publicKeyLine: string,
  requestId: string,
): Promise<AuthorizedKeyInstallResult> {
  const result = await execChecked(capability, connection, {
    requestId,
    command: buildAuthorizedKeyInstallCommand(publicKeyLine),
    timeoutMs: AUTHORIZED_KEY_INSTALL_TIMEOUT_MS,
  });
  return parseAuthorizedKeyInstallOutput(result);
}

/** The address a live controller actually dialed (no credential). */
export interface DialedHost {
  hostId: string;
  username: string;
  hostname: string;
  port: number;
}

/**
 * Who "Install on …" will write to: the LIVE connection, never the host form,
 * whose fields stay editable while connected. Named from the host the current
 * controller dialed when that is still the snapshot's host, else from the
 * snapshot's own label. Null when there is no live connection to write over.
 */
export function liveAuthorizedKeyInstallHost(
  snapshot: Pick<ConnectionSnapshot, 'hostId' | 'hostLabel' | 'connectionId' | 'generationId'> | null,
  dialed: DialedHost | null,
): { connection: SshConnectionRef; hostLabel: string } | null {
  if (!snapshot?.connectionId || !snapshot.generationId) return null;
  const connection = { connectionId: snapshot.connectionId, generationId: snapshot.generationId };
  if (dialed && dialed.hostId === snapshot.hostId) {
    return { connection, hostLabel: `${dialed.username}@${dialed.hostname}:${dialed.port}` };
  }
  return snapshot.hostLabel ? { connection, hostLabel: snapshot.hostLabel } : null;
}
