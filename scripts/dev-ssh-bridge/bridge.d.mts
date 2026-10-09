/** Types for scripts/dev-ssh-bridge/bridge.mjs (browser dev mode live SSH bridge, #3022). */
import type { AddressInfo } from 'node:net';

export const BRIDGE_PROTOCOL: string;
export const BRIDGE_BIND_HOST: '127.0.0.1';
export const BRIDGE_SUBPROTOCOL: 'pocketshell-dev-bridge.v1';
export function offeredToken(protocolHeader: string | undefined): string | null;

export interface PresentedHostKey {
  keyType: string;
  keyB64: string;
  fingerprintSha256: string;
}

export interface DevVaultKey {
  handleId: string;
  label: string;
  algorithm: 'ssh-ed25519' | 'ssh-rsa';
  fingerprintSha256: string;
  passphraseRequired: boolean;
  createdAt: number;
}

export interface DevKeyVault {
  list(): DevVaultKey[];
  addFile(file: string, label?: string): DevVaultKey;
  importKey(options: { privateKeyPem: string; label?: string; passphrase?: string }): DevVaultKey;
  generate(options: { label?: string; algorithm?: 'Ed25519' | 'RSA-3072' }): DevVaultKey;
  remove(handleId: string, fingerprintSha256: string): boolean;
}

export class BridgeFailure extends Error {
  readonly code: string;
  readonly data?: unknown;
  constructor(code: string, message: string, data?: unknown);
}

export function fingerprintOf(blob: Uint8Array): string;
export function presentedHostKey(blob: Buffer): PresentedHostKey;
export function hostKeyTrusted(pin: unknown, presented: PresentedHostKey): boolean;
export function createKeyVault(): DevKeyVault;
/** An unencrypted OpenSSH Ed25519 private key from a 32-byte seed, or a fresh random one (#3078). */
export function ed25519OpenSshPrivateKey(seed?: Uint8Array, comment?: string): string;

export interface RunningBridge {
  address: AddressInfo;
  vault: DevKeyVault;
  close(): Promise<void>;
}

export function startBridge(options: {
  port?: number;
  token: string;
  /** Exact page origins allowed to open the bridge, e.g. `http://127.0.0.1:5173`. */
  allowedOrigins: string[];
  identities?: string[];
  logger?: (line: string) => void;
}): Promise<RunningBridge>;
