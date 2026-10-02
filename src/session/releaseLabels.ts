import type { ConnectionPhase } from '@pocketshell/core';

/**
 * Plain-language status text for the release UI (#3023). Users see these
 * words; raw phase names, protocol terms (PTY) and build hashes stay in
 * data-* test hooks.
 */
const PHASE_LABELS: Record<ConnectionPhase, string> = {
  idle: 'Not connected',
  connecting: 'Connecting…',
  'awaiting-trust': 'Check host key',
  connected: 'Connected',
  listing: 'Connected',
  attaching: 'Opening session…',
  live: 'Connected',
  background: 'Paused',
  reconnecting: 'Reconnecting…',
  lost: 'Disconnected',
  error: 'Connection failed',
};

/** The host panel's connection state. */
export function connectionStateLabel(phase: ConnectionPhase): string {
  return PHASE_LABELS[phase];
}

/** The terminal heading's state when it is not live (a live terminal shows none). */
export function terminalStateLabel(phase: ConnectionPhase): string {
  return phase === 'lost' || phase === 'error' || phase === 'idle' ? 'Disconnected' : PHASE_LABELS[phase];
}

export type BuildCheckState = 'checking' | 'verified' | 'error';

/** About's one plain-language build line: version, short build id, file check. */
export function aboutBuildLine(state: BuildCheckState, coreRevision: string, versionName: string): string {
  const check = state === 'verified' ? 'files checked' : state === 'checking' ? 'checking files…' : 'file check failed';
  const version = versionName && versionName !== 'unknown' ? `Version ${versionName} · ` : '';
  return `${version}build ${coreRevision.slice(0, 12)} · ${check}`;
}
