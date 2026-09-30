/**
 * Which shell the Android entry mounts. `shared` is the app; `legacy` is the
 * temporary way back to the pre-#2936 phone screens, requested by the native
 * launch intent (MainActivity turns the `pocketshell.shell` extra into this
 * query) so the existing packaged journeys keep their oracle until each
 * screen's shared replacement passes it. Deleted with the last legacy screen.
 */
export type ShellChoice = 'shared' | 'legacy';

export function selectShell(search: string): ShellChoice {
  return new URLSearchParams(search).get('shell') === 'legacy' ? 'legacy' : 'shared';
}
