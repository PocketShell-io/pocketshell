import { readFileSync } from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { sessionListErrorNotice, type HostKeyTrustPin } from '@pocketshell/core';

// The Android production path is `ConnectionController` bound to the
// Capacitor `SshCapability` plugin (src/native/sshCapability.ts). Only the
// native plugin is scripted here: registration, the Capacitor adapter, the
// shared controller and HostCliCore all run as they do on a device, fed the
// pinned core's captured `pocketshell==0.5.8` contract vectors.
const native = vi.hoisted(() => ({ exec: null as null | ((command: string) => ExecAnswer) }));

interface ExecAnswer {
  exitCode: number | null;
  stdout: string;
  stderr?: string;
}

const HOST_KEY = { keyType: 'ssh-ed25519', keyB64: 'AQIDBA==', fingerprintSha256: 'SHA256:abc123' } as const;
const PIN: HostKeyTrustPin = { kind: 'wire-key', ...HOST_KEY };

vi.mock('@capacitor/core', () => ({
  registerPlugin: () => {
    let connection = 0;
    return {
      addListener: async () => ({ remove: async () => undefined }),
      removeAllListeners: async () => undefined,
      connect: async (options: { requestId: string; generationId: string }) => ({
        requestId: options.requestId,
        connectionId: `connection-${++connection}`,
        generationId: options.generationId,
        hostKey: HOST_KEY,
      }),
      getConnectionState: async (options: { requestId: string }) => ({ requestId: options.requestId, state: 'connected' }),
      closeConnection: async (options: { requestId: string }) => ({ requestId: options.requestId }),
      cancelOperation: async (options: { requestId: string }) => ({ requestId: options.requestId, cancelled: true }),
      exec: async (options: { requestId: string; connectionId: string; generationId: string; command: string }) => {
        if (!native.exec) throw new Error('No scripted host CLI answer.');
        const answer = native.exec(options.command);
        return {
          requestId: options.requestId,
          connectionId: options.connectionId,
          generationId: options.generationId,
          exitCode: answer.exitCode,
          stdout: answer.stdout,
          stderr: answer.stderr ?? '',
          timedOut: false,
        };
      },
    };
  },
}));

const { ConnectionController } = await import('../../src/session/connectionController');

const vector = (name: string): string =>
  readFileSync(new URL(`../../vendor/pocketshell-core/tests/fixtures/pocketshell-0.5.8/${name}`, import.meta.url), 'utf8');

const host = {
  hostId: 'fixture-host',
  hostname: '127.0.0.1',
  port: 2222,
  username: 'testuser',
  credential: { kind: 'private-key' as const, privateKeyPem: 'test-private-key' },
};

async function connected(script: (command: string) => ExecAnswer) {
  const commands: string[] = [];
  native.exec = (command) => {
    commands.push(command);
    return script(command);
  };
  let id = 0;
  const controller = new ConnectionController({
    trustStore: { get: async () => PIN, record: async () => undefined },
    createId: () => `request-${++id}`,
    retryDelaysMs: [0],
  });
  controllers.push(controller);
  expect((await controller.connect(host)).ok).toBe(true);
  return { controller, commands };
}

const controllers: Array<InstanceType<typeof ConnectionController>> = [];

afterEach(async () => {
  await Promise.all(controllers.splice(0).map((controller) => controller.close()));
  native.exec = null;
});

describe('Android host CLI path runs the shared 0.5.8 contract vectors', () => {
  it('lists the captured sessions through `pocketshell sessions list --json`', async () => {
    const { controller, commands } = await connected(() => ({ exitCode: 0, stdout: vector('sessions-list.json') }));
    const listed = await controller.refreshSessions();

    expect(listed.ok).toBe(true);
    expect(commands).toEqual(['pocketshell sessions list --json']);
    expect(controller.getSnapshot().sessions.map((row) => row.name)).toEqual(['testuser:build', 'testuser:main']);
    expect(controller.getSnapshot().sessionListErrors).toEqual([]);
  });

  it('keeps the host errors[] in the snapshot instead of reading as an empty host', async () => {
    const { controller } = await connected(() => ({ exitCode: 0, stdout: vector('sessions-list-errors.json') }));
    expect((await controller.refreshSessions()).ok).toBe(true);

    const snapshot = controller.getSnapshot();
    expect(snapshot.sessions).toEqual([]);
    expect(snapshot.sessionListErrors).toEqual([
      { message: '`/does/not/exist --json snapshot` and `/does/not/exist --json list` both failed or returned unreadable JSON' },
    ]);
    expect(sessionListErrorNotice(snapshot.sessionListErrors)).toMatch(/^Some sessions may be missing: /);
  });

  it('shows the real CLI errors listing (exit 127) as a visible failure, not an empty host', async () => {
    // What published 0.5.8 actually does when aplexer is unreachable: the
    // errors document on stdout, exit 127, and the detail on stderr.
    const { controller } = await connected(() => ({
      exitCode: 127,
      stdout: vector('sessions-list-errors.json'),
      stderr: vector('sessions-list-errors.stderr.txt'),
    }));
    const listed = await controller.refreshSessions();

    expect(listed.ok).toBe(false);
    expect(listed.ok ? '' : listed.message).toContain('both failed or returned unreadable JSON');
    expect(controller.getSnapshot().error).toContain('(exit 127)');
  });

  it('treats the captured create-existing answer as success (idempotent create)', async () => {
    const { controller, commands } = await connected((command) => command.includes('sessions create')
      ? { exitCode: 0, stdout: vector('sessions-create-existing.json') }
      : { exitCode: 0, stdout: vector('sessions-list.json') });

    const created = await controller.createSession('main');
    expect(created).toEqual({
      ok: true,
      value: { name: 'testuser:main', id: '3763a224-3f30-4320-9d92-951ae7f6c972', created: false },
    });
    expect(commands[0]).toBe("pocketshell sessions create --json -- 'main'");
  });

  it('surfaces the captured structured create error from a non-zero exit', async () => {
    const { controller } = await connected(() => ({ exitCode: 127, stdout: vector('sessions-create-error.json') }));
    expect(await controller.createSession('core-cli-capture')).toEqual({
      ok: false,
      reason: 'failed',
      message: "pocketshell: `a start --tag core-cli-capture` exited 127: [Errno 2] No such file or directory: 'systemd-run'",
    });
  });

  it('quotes an adversarial session name after `--`', async () => {
    const { controller, commands } = await connected((command) => command.includes('sessions create')
      ? { exitCode: 0, stdout: vector('sessions-create-existing.json') }
      : { exitCode: 0, stdout: vector('sessions-list.json') });

    await controller.createSession("--help it's $(touch /tmp/x)");
    expect(commands[0]).toBe("pocketshell sessions create --json -- '--help it'\\''s $(touch /tmp/x)'");
  });

  it('refuses a too-old listing schema visibly', async () => {
    const { controller } = await connected(() => ({ exitCode: 0, stdout: '{"schema":2,"sessions":[]}' }));
    const listed = await controller.refreshSessions();

    expect(listed.ok).toBe(false);
    expect(listed.ok ? '' : listed.message).toMatch(/schema 2/i);
    expect(controller.getSnapshot().error).toMatch(/schema 2/i);
  });
});
