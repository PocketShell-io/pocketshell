import { describe, expect, it } from 'vitest';
import { transitionHomeSurface, type HomeSurface } from '../../src/session/homeSurface';

describe('phone workspace destinations', () => {
  it('starts at connection setup and opens sessions after a connection is ready', () => {
    const initial: HomeSurface = 'connection';
    expect(transitionHomeSurface(initial, 'connected')).toBe('sessions');
  });

  it('returns to the live terminal after a session is attached', () => {
    expect(transitionHomeSurface('sessions', 'session-attached')).toBe('live');
  });

  it('keeps host setup and session selection reachable from the live terminal', () => {
    expect(transitionHomeSurface('live', 'open-connection')).toBe('connection');
    expect(transitionHomeSurface('live', 'open-sessions')).toBe('sessions');
  });

  it('backs from the terminal to sessions, then to connection details', () => {
    const sessions = transitionHomeSurface('live', 'back');
    expect(sessions).toBe('sessions');
    expect(transitionHomeSurface(sessions, 'back')).toBe('connection');
  });

  it('returns to connection setup when disconnected', () => {
    expect(transitionHomeSurface('live', 'disconnected')).toBe('connection');
  });
});
