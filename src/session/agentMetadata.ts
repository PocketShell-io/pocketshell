import { agentKindFromEngine, type AgentState, type SessionRow } from '@pocketshell/core';
import { agentMark, type AgentMark } from '@pocketshell/core/shared/agentBadge';

export interface SessionAgentPresentation {
  identity: AgentMark | null;
  state: AgentState | null;
  stateLabel: string | null;
}

const STATE_LABELS: Record<AgentState, string> = {
  working: 'Working',
  waiting: 'Waiting',
  idle: 'Idle',
};

/** Present only the recognized identity carried by the host session row. */
export function projectSessionAgentPresentation(
  session: SessionRow | null | undefined,
): SessionAgentPresentation {
  if (!session) return { identity: null, state: null, stateLabel: null };

  const identity = agentMark(agentKindFromEngine(session.agent));
  const state = session.agentStateSource === 'reported' && isKnownAgentState(session.agentState)
    ? session.agentState
    : null;

  return {
    identity,
    state,
    stateLabel: state ? STATE_LABELS[state] : null,
  };
}

/**
 * Resolve the selected target against the newest host listing.
 *
 * `selectedSession` can predate a later metadata refresh. Prefer the host's
 * immutable session id so that refreshing or switching rows cannot carry one
 * session's metadata into another session's chrome.
 */
export function resolveSelectedSessionRow(
  selected: SessionRow | null | undefined,
  sessions: readonly SessionRow[],
): SessionRow | null {
  if (!selected?.id) return null;
  const matches = sessions.filter((session) => session.id === selected.id);
  return matches.length === 1 ? matches[0] : null;
}

function isKnownAgentState(value: AgentState | null | undefined): value is AgentState {
  return value === 'working' || value === 'waiting' || value === 'idle';
}
