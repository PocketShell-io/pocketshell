/**
 * Which shell the Android entry mounts. The pre-#2936 phone screens stay the
 * default; the shared PocketShell app is opt-in until it reaches parity
 * (#2941) and the maintainer signs it off. MainActivity turns the
 * `pocketshell.shell=shared` launch extra into the WebView's START path
 * (`/?shell=shared`), so the choice is made before the first page load and
 * a launch boots exactly one shell.
 */
export type ShellChoice = 'shared' | 'legacy';

export function selectShell(search: string): ShellChoice {
  return new URLSearchParams(search).get('shell') === 'shared' ? 'shared' : 'legacy';
}

/** sessionStorage key listing every shell this WebView booted, in order. */
export const SHELL_BOOT_LOG_KEY = 'pocketshell.shell-boot-log';

/**
 * Record a boot. sessionStorage survives a reload of the same WebView, so a
 * launch that loaded one shell and then reloaded into another leaves two
 * entries; the packaged journeys assert exactly one (#2936 review).
 */
export function recordShellBoot(choice: ShellChoice, storage: Pick<Storage, 'getItem' | 'setItem'>): void {
  try {
    const raw = storage.getItem(SHELL_BOOT_LOG_KEY);
    const log: unknown = raw ? JSON.parse(raw) : [];
    const entries = Array.isArray(log) ? log : [];
    storage.setItem(SHELL_BOOT_LOG_KEY, JSON.stringify([...entries, choice]));
  } catch {
    // Storage unavailable: the boot itself must not fail over evidence.
  }
}
