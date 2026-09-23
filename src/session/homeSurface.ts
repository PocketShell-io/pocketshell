/** The three reachable surfaces in the phone's connected workspace. */
export type HomeSurface = 'connection' | 'sessions' | 'live';

export type HomeSurfaceAction =
  | 'connected'
  | 'session-attached'
  | 'open-connection'
  | 'open-sessions'
  | 'disconnected'
  | 'back';

/** Keep setup and session management out of the live terminal viewport. */
export function transitionHomeSurface(
  current: HomeSurface,
  action: HomeSurfaceAction,
): HomeSurface {
  switch (action) {
    case 'connected':
      return 'sessions';
    case 'session-attached':
      return 'live';
    case 'open-connection':
    case 'disconnected':
      return 'connection';
    case 'open-sessions':
      return 'sessions';
    case 'back':
      if (current === 'live') return 'sessions';
      return 'connection';
  }
}
