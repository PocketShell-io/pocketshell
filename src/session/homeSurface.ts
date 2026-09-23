/** The three reachable surfaces in the phone's connected workspace. */
export type HomeSurface = 'connection' | 'sessions' | 'live';

export type HomeSurfaceAction =
  | 'connected'
  | 'session-attached'
  | 'open-connection'
  | 'open-sessions'
  | 'disconnected'
  | 'back';

export type AndroidBackDestination = 'navigation' | 'workspace' | 'minimize';

/** Route overlays always close before the connected workspace steps backward. */
export function resolveAndroidBackDestination(
  canNavigateBack: boolean,
  homeSurface: HomeSurface,
  hasConnection: boolean,
): AndroidBackDestination {
  if (canNavigateBack) return 'navigation';
  if (hasConnection && homeSurface !== 'connection') return 'workspace';
  return 'minimize';
}

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
