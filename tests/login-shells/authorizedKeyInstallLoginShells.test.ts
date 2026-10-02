import { spawnSync } from 'node:child_process';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  buildAuthorizedKeyInstallCommand,
  parseAuthorizedKeyInstallOutput,
} from '@/credentials/authorizedKeys';

/**
 * Issue #3021: the authorized_keys install command must be data-safe under
 * EVERY login shell sshd may hand it to, not just POSIX sh. sshd runs an exec
 * request as `<login shell> -c <command>`; `docker exec <ctr> <shell> -c
 * <command>` passes the same argv with no extra quoting layer, so this is the
 * real parse path. Run by tests/scripts/authorized-key-install-login-shells-test.sh,
 * which builds tests/docker/Dockerfile.login-shells and fails unless every
 * test here executed and passed.
 */

const IMAGE = process.env.POCKETSHELL_LOGIN_SHELL_IMAGE ?? '';
const HOME = '/home/testuser';
const BLOB = 'AAAAC3NzaC1lZDI1NTE5AAAAIN7osVCLDIy5aFOk8IaZk040AFw1V+YDXgr8L+zQE/5k';
const SHELLS = ['/bin/sh', '/bin/bash', '/usr/bin/dash', '/bin/zsh', '/usr/bin/fish', '/bin/tcsh', '/bin/csh', '/bin/mksh'];

// Labels a user can type (1-80 printable characters). Each one tries to
// leave the quoted region of a naive `'…'` / `"…"` / `\'` encoding and run
// `touch` on a canary; none of them may change anything but the key line.
const LABELS: Record<string, string> = {
  apostrophe: "Alexey's phone",
  singleQuoteBreakout: `x'; touch ${HOME}/PWNED; echo '`,
  fishEscapedQuote: `x\\'; touch ${HOME}/PWNED; echo \\'`,
  doubleQuoteBreakout: `x"; touch ${HOME}/PWNED; echo "`,
  commandSubstitution: `$(touch ${HOME}/PWNED) (touch ${HOME}/PWNED)`,
  backticks: `\`touch ${HOME}/PWNED\``,
  semicolonsAndPipes: `a; touch ${HOME}/PWNED | b && c || d & e`,
  variablesAndHistory: '$HOME ${HOME} $status !! !$ %1 ~ ~root',
  globsAndBraces: '* ? [a-z] {a,b} ** <in >out 2>&1',
  backslashes: 'a\\b\\\\c\\nd\\\'e\\"f\\',
  unicode: 'Телефон Алексея — ключ 🔑',
};

let container = '';

function docker(args: string[], input?: string) {
  const result = spawnSync('docker', args, { encoding: 'utf8', input, timeout: 30_000 });
  if (result.error) throw result.error;
  return result;
}

/** Exactly what sshd does with an exec request: `<login shell> -c <command>`. */
function runAsLoginShell(shell: string, command: string) {
  const result = docker(['exec', container, shell, '-c', command]);
  return { exitCode: result.status, stdout: result.stdout, stderr: result.stderr, timedOut: false };
}

function sh(script: string): string {
  const result = docker(['exec', container, '/bin/sh', '-c', script]);
  expect(result.status, result.stderr).toBe(0);
  return result.stdout;
}

function resetHome(): void {
  sh(`rm -rf ${HOME}/.ssh ${HOME}/*`);
}

function outcome(result: ReturnType<typeof runAsLoginShell>): string {
  try {
    return parseAuthorizedKeyInstallOutput(result);
  } catch (error) {
    return `error: ${(error as Error).message}`;
  }
}

/**
 * What one label did to the host: both install attempts, the file, its
 * permission bits (Alpine's home is setgid, which .ssh inherits, so mask to
 * 0777), and every non-hidden file in $HOME (a canary or redirect target
 * there means the label ran as shell).
 */
function installLabel(shell: string, line: string) {
  resetHome();
  const command = buildAuthorizedKeyInstallCommand(line);
  const first = outcome(runAsLoginShell(shell, command));
  const second = outcome(runAsLoginShell(shell, command));
  const [file = '', modes = '', strays = ''] = sh([
    `cat ${HOME}/.ssh/authorized_keys 2>/dev/null; printf '\\0'`,
    `stat -c '%a' ${HOME}/.ssh ${HOME}/.ssh/authorized_keys 2>/dev/null; ls -A ${HOME}/.ssh 2>/dev/null; printf '\\0'`,
    `ls ${HOME}`,
  ].join('; ')).split('\0');
  const [dirMode, fileMode, ...sshEntries] = modes.trim().split('\n');
  const bits = (mode?: string) => (mode ? (Number.parseInt(mode, 8) & 0o777).toString(8) : 'missing');
  return {
    first,
    second,
    file,
    modes: [bits(dirMode), bits(fileMode)],
    sshEntries,
    strayFiles: strays.split('\n').filter(Boolean),
  };
}

describe('authorized_keys install under every login shell (#3021)', () => {
  beforeAll(() => {
    expect(IMAGE, 'POCKETSHELL_LOGIN_SHELL_IMAGE must name the login-shell image').not.toBe('');
    const started = docker(['run', '-d', '--rm', IMAGE]);
    expect(started.status, started.stderr).toBe(0);
    container = started.stdout.trim();
    for (const shell of SHELLS) expect(sh(`test -x ${shell} && echo ok`)).toBe('ok\n');
  });

  afterAll(() => {
    if (container) docker(['rm', '-f', container]);
  });

  for (const shell of SHELLS) {
    it(`installs every hostile label as data, exactly once, under ${shell}`, () => {
      const observed = Object.entries(LABELS).map(([name, label]) => {
        const line = `ssh-ed25519 ${BLOB} ${label}`;
        return { name, ...installLabel(shell, line), expectedFile: `${line}\n` };
      });
      expect(observed.map(({ expectedFile: _expected, ...rest }) => rest))
        .toEqual(observed.map(({ name, expectedFile }) => ({
          name,
          first: 'installed',
          second: 'already-present',
          file: expectedFile,
          modes: ['700', '600'],
          sshEntries: ['authorized_keys'],
          strayFiles: [],
        })));
    });
  }

  it('never sends a label containing a newline to any shell', () => {
    for (const label of ['a\ntouch /home/testuser/PWNED', 'a\rb', 'a\u0000b']) {
      expect(() => buildAuthorizedKeyInstallCommand(`ssh-ed25519 ${BLOB} ${label}`)).toThrow(/invalid public key/i);
    }
  });
});
