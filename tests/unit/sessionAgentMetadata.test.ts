import { describe, expect, it } from 'vitest';
import { parseHostSessionsList, type SessionRow } from '@pocketshell/core';
import { projectSessionAgentPresentation, resolveSelectedSessionRow } from '../../src/session/agentMetadata';

function hostSession(input: Partial<SessionRow> & Pick<SessionRow, 'name' | 'id'>): SessionRow {
  return {
    name: input.name,
    id: input.id,
    workspace: input.workspace ?? '/workspace/project',
    tag: input.tag ?? input.name,
    engine: input.engine ?? null,
    profile: input.profile ?? null,
    agent: input.agent ?? null,
    agentState: input.agentState ?? null,
    agentStateSource: input.agentStateSource ?? null,
    attached: input.attached ?? false,
    createdEpoch: input.createdEpoch ?? null,
    activityEpoch: input.activityEpoch ?? null,
  };
}

function parseRow(row: Record<string, unknown>): SessionRow {
  return parseHostSessionsList(JSON.stringify({ schema: 3, sessions: [{ name: 'host-row', attached: false, ...row }] }))
    .sessions[0];
}

describe('session agent metadata projection', () => {
  it('uses the pinned HostCliCore row for known host-reported identities', () => {
    const labels: Record<string, string> = {
      claude: 'Claude Code',
      codex: 'Codex',
      opencode: 'OpenCode',
      grok: 'Grok',
      antigravity: 'Antigravity',
    };

    for (const [agent, label] of Object.entries(labels)) {
      const row = parseRow({ agent });
      expect(row.agent).toBe(agent);
      expect(projectSessionAgentPresentation(row).identity?.label).toBe(label);
    }
  });

  it('shows only the three fresh host-reported states', () => {
    const states = [
      ['working', 'Working'],
      ['waiting', 'Waiting'],
      ['idle', 'Idle'],
    ] as const;

    for (const [state, label] of states) {
      const row = parseRow({ agent: 'claude', agent_state: state, agent_state_source: 'reported' });
      expect(row.agentState).toBe(state);
      expect(row.agentStateSource).toBe('reported');
      expect(projectSessionAgentPresentation(row)).toMatchObject({ state, stateLabel: label });
    }
  });

  it('does not infer identity and suppresses non-reported or unknown state', () => {
    expect(projectSessionAgentPresentation(parseRow({ name: 'claude-codex', tag: 'grok', engine: 'opencode' })))
      .toEqual({ identity: null, state: null, stateLabel: null });
    expect(projectSessionAgentPresentation(parseRow({ name: 'plain-shell' })))
      .toEqual({ identity: null, state: null, stateLabel: null });
    expect(projectSessionAgentPresentation(parseRow({
      name: 'heuristic', agent: 'claude', agent_state: 'waiting', agent_state_source: 'heuristic',
    }))).toMatchObject({ identity: { label: 'Claude Code' }, state: null, stateLabel: null });
    expect(projectSessionAgentPresentation(parseRow({
      name: 'unknown-state', agent: 'shell', agent_state: 'paused', agent_state_source: 'reported',
    }))).toEqual({ identity: null, state: null, stateLabel: null });
    expect(projectSessionAgentPresentation(parseRow({
      name: 'unknown-source', agent: 'codex', agent_state: 'idle', agent_state_source: 'guessed',
    }))).toMatchObject({ identity: { label: 'Codex' }, state: null, stateLabel: null });
    expect(projectSessionAgentPresentation(null)).toEqual({ identity: null, state: null, stateLabel: null });
  });

  it('keeps A→B→A chrome bound to the selected host id when metadata arrives late', () => {
    const selectedAFromBeforeRefresh = hostSession({
      name: 'project:a', id: 'session-a', tag: 'a', agent: null, agentState: null,
    });
    const latestRows = [
      hostSession({ name: 'project:a', id: 'session-a', tag: 'a', agent: 'claude', agentState: 'waiting', agentStateSource: 'reported' }),
      hostSession({ name: 'project:b', id: 'session-b', tag: 'b', agent: 'codex', agentState: 'idle', agentStateSource: 'reported' }),
    ];

    const refreshedA = resolveSelectedSessionRow(selectedAFromBeforeRefresh, latestRows);
    expect(projectSessionAgentPresentation(refreshedA)).toMatchObject({ identity: { label: 'Claude Code' }, state: 'waiting' });

    const selectedB = resolveSelectedSessionRow(latestRows[1], latestRows);
    expect(projectSessionAgentPresentation(selectedB)).toMatchObject({ identity: { label: 'Codex' }, state: 'idle' });

    const returnedA = resolveSelectedSessionRow(latestRows[0], latestRows);
    expect(projectSessionAgentPresentation(returnedA)).toMatchObject({ identity: { label: 'Claude Code' }, state: 'waiting' });
    expect(resolveSelectedSessionRow(latestRows[0], [latestRows[1]])).toBeNull();
    expect(resolveSelectedSessionRow({ ...latestRows[0], id: '' }, latestRows)).toBeNull();
    expect(resolveSelectedSessionRow(latestRows[0], [...latestRows, latestRows[0]])).toBeNull();
  });
});
