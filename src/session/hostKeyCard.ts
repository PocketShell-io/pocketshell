/**
 * What the legacy phone screen shows for a host-key verdict, as the shared
 * `HostKeyTrustPrompt` takes it (#2953), and what an answer may do.
 *
 * First contact: the three-answer prompt. A CHANGED key: the refusal card —
 * both fingerprints, no trust action — the same verdict the shared shell
 * gives. Re-trusting a legitimately rotated server is the explicit "forget
 * host key" action (P1, #2960), never an answer on this card.
 */
import type { HostKeyTrustChoice, HostKeyTrustRequest, PendingHostKeyDecision } from '@pocketshell/core';

export interface HostKeyCard {
  request: HostKeyTrustRequest;
  /** Set only for a changed key: the fingerprint that was trusted before. */
  trustedFingerprintSha256: string | null;
}

export interface HostKeyCardTarget {
  hostname: string;
  port: number | string;
  username: string;
}

export function hostKeyCard(decision: PendingHostKeyDecision, target: HostKeyCardTarget): HostKeyCard {
  const port = Number(target.port);
  return {
    request: {
      hostLabel: decision.hostLabel,
      hostname: target.hostname.trim() || decision.hostLabel,
      port: Number.isInteger(port) ? port : 22,
      user: target.username.trim(),
      keyType: decision.presented.keyType,
      fingerprintSha256: decision.presented.fingerprintSha256,
    },
    trustedFingerprintSha256:
      decision.reason === 'mismatch' ? (decision.previouslyTrusted?.fingerprintSha256 ?? 'unknown') : null,
  };
}

/** The answer the screen acts on: a changed key can only ever be refused. */
export function hostKeyAnswer(decision: PendingHostKeyDecision, choice: HostKeyTrustChoice): HostKeyTrustChoice {
  return decision.reason === 'mismatch' ? 'reject' : choice;
}

/** The screen's message after a refusal. */
export function hostKeyRefusalMessage(decision: PendingHostKeyDecision): string {
  if (decision.reason !== 'mismatch') return 'Host key was not trusted. No SSH connection remains open.';
  const trusted = decision.previouslyTrusted?.fingerprintSha256;
  return `Host key for ${decision.hostLabel} has changed — connection refused. It now presents `
    + `${decision.presented.fingerprintSha256}${trusted ? ` instead of the trusted ${trusted}` : ''}. `
    + 'No SSH connection remains open.';
}
