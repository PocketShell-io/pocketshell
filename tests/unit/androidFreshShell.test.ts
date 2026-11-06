import { describe, it, expect, vi } from 'vitest';
import { ConnectionController, type SshCapability } from '@pocketshell/core';
import { createAndroidPlatform } from '@/platform/android/androidApi';
import { AndroidHostStore } from '@/platform/android/hostStore';
import { createLocalTrustStore } from '@/platform/android/trustStore';
import { FakeNative, MemoryStorage, HOST_KEY, sessionJson } from './support/androidFakeNative';
async function setup(home: string) {
  const native = new FakeNative(); native.sessions = [];
  const capability = native.capability(); const baseExec = capability.exec;
  capability.exec = async (options) => {
    if (options.command.includes('sessions create')) {
      native.execs.push(options.command);
      native.sessions = [sessionJson('home-orin', home)];
      return { ...options, exitCode: 0, stdout: JSON.stringify({ schema: 3, name: 'home-orin', id: 'home-orin-id', created: true }), stderr: '', timedOut: false };
    }
    return baseExec(options);
  };
  const storage = new MemoryStorage(); const hosts = new AndroidHostStore({ storage, readLegacyHosts: async () => [] });
  hosts.save({ name: 'fixture', hostname: 'host', port: 22, user: 'orin', keyHandleId: '00000000-0000-4000-8000-000000000001' });
  const trust = createLocalTrustStore(storage); await trust.record('orin@host:22', { kind: 'wire-key', ...HOST_KEY });
  const remoteDirectory = vi.fn(async () => home);
  const platform = createAndroidPlatform({ createController: () => new ConnectionController({ capability: capability as SshCapability, trustStore: trust }), hosts,
    remoteDirectory, backgroundGraceMs: () => 60_000, addHostRoute: '/add' });
  const connected = await platform.api.ssh.connect({ host: 'host', user: 'orin', hostAlias: 'fixture', port: 22 });
  if (!connected.ok || !connected.connectionId) throw new Error('fixture failed to connect');
  return { native, platform, remoteDirectory, id: connected.connectionId };
}
describe('Android fresh server terminal adapters', () => {
  it('creates an ordinary home shell from a canonical POSIX or Windows SFTP directory through the controller', async () => {
    for (const home of ['/home/orin', 'C:/Users/Orin']) {
      const f = await setup(home);
      expect(await f.platform.api.projects.home(f.id)).toEqual({ ok: true, home, error: null });
      const created = await f.platform.api.projects.startSession(f.id, { folder: home });
      expect(created).toMatchObject({ ok: true, sessionName: 'home-orin', folder: home, reused: false, aplexerId: 'home-orin-id' });
      expect(f.native.execs.some((command) => command.includes('sessions create --json') && command.includes(`--cwd '${home}'`))).toBe(true);
      expect(f.remoteDirectory).toHaveBeenCalledWith({ connectionId: expect.any(String), generationId: expect.any(String) }, home);
      f.platform.dispose();
    }
  });
  it('reuses an existing ordinary shell and never creates one after canonicalization fails', async () => {
    const f = await setup('/home/orin');
    f.native.sessions = [sessionJson('existing', '/home/orin')];
    expect(await f.platform.api.projects.startSession(f.id, { folder: '~' })).toMatchObject({ ok: true, sessionName: 'existing', reused: true });
    expect(f.native.execs.some((command) => command.includes('sessions create'))).toBe(false);
    f.remoteDirectory.mockRejectedValue(new Error('SFTP refused'));
    expect(await f.platform.api.projects.startSession(f.id, { folder: '/missing' })).toMatchObject({ ok: false, error: expect.stringContaining('SFTP refused') });
    expect(f.native.execs.some((command) => command.includes('sessions create'))).toBe(false);
    f.platform.dispose();
  });
});
