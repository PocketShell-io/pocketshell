import { afterEach, describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { provideApi } from '@ui/app/ipc';
import { useConnectionStore } from '@ui/app/stores/connection';
import { ConnectionController, type HostKeyTrustPin, type HostKeyTrustRequest } from '@pocketshell/core';
import {
  AndroidConnectionHub,
  connectionStateFor,
  HELD_OUTPUT_LIMIT_BYTES,
  holdBounded,
  SHELL_CLAIM_FALLBACK_MS,
} from '@/platform/android/connectionHub';
import { createAndroidPlatform, UnsupportedCapability } from '@/platform/android/androidApi';
import { AndroidHostStore, ANDROID_HOSTS_STORAGE_KEY } from '@/platform/android/hostStore';
import { createLocalTrustStore, pinStorageKey } from '@/platform/android/trustStore';
import { recordShellBoot, selectShell, SHELL_BOOT_LOG_KEY } from '@/shellSelection';
import { FakeNative, HOST_KEY, MemoryStorage, settle, target } from './support/androidFakeNative';

/** The fixture key already pinned — for tests whose subject is not first contact. */
const PIN: HostKeyTrustPin = { kind: 'wire-key', ...HOST_KEY };

function harness(pin: HostKeyTrustPin | null = PIN) {
  const native = new FakeNative();
  const storage = new MemoryStorage();
  const trust = createLocalTrustStore(storage);
  if (pin) void trust.record(target.hostId, pin);
  let ids = 0;
  const controllers: ConnectionController[] = [];
  const hub = new AndroidConnectionHub({
    createController: () => {
      const controller = new ConnectionController({
        capability: native.capability(),
        trustStore: trust,
        createId: () => `r-${++ids}`,
        retryDelaysMs: [0, 0],
      });
      controllers.push(controller);
      return controller;
    },
  });
  return { native, storage, hub, controllers };
}

const open: Array<{ hub: AndroidConnectionHub; id: string }> = [];
afterEach(async () => {
  await Promise.all(open.splice(0).map(({ hub, id }) => hub.close(id)));
});

describe('Android PocketShellApi platform', () => {
  it('never pins a first-contact key without a decision: no decider means refused', async () => {
    const first = harness(null);
    const refused = await first.hub.connect(target);
    expect(refused).toEqual({ ok: false, error: expect.stringContaining('was not trusted') });
    expect(first.storage.getItem(pinStorageKey(target.hostId))).toBeNull();
    expect(first.native.opened).toHaveLength(0);
  });

  it('asks the registered decider with host, key type and fingerprint, and honours each answer', async () => {
    const asked: HostKeyTrustRequest[] = [];
    const answering = (choice: 'accept-once' | 'accept-always' | 'reject') => {
      const h = harness(null);
      h.hub.setTrustDecider(async (request) => {
        asked.push(request);
        // Nothing may be pinned while the user is still looking at the key.
        expect(h.storage.getItem(pinStorageKey(target.hostId))).toBeNull();
        return choice;
      });
      return h;
    };

    const rejected = answering('reject');
    const refused = await rejected.hub.connect(target, undefined, 'fixture box');
    expect(refused).toEqual({ ok: false, error: 'Host key for fixture box was not trusted. No connection was opened.' });
    expect(asked[0]).toEqual({
      hostLabel: 'fixture box', hostname: 'fixture', port: 2222, user: 'u',
      keyType: 'ssh-ed25519', fingerprintSha256: 'SHA256:abc123',
    });
    expect(rejected.storage.getItem(pinStorageKey(target.hostId))).toBeNull();
    expect((await rejected.native.capability().resourceSnapshot('x')).connections).toBe(0);

    const once = answering('accept-once');
    const onceResult = await once.hub.connect(target);
    expect(onceResult).toEqual({ ok: true, connectionId: 'android-1' });
    open.push({ hub: once.hub, id: 'android-1' });
    expect(once.storage.getItem(pinStorageKey(target.hostId))).toBeNull();
    // The next connection is a new first contact: asked again.
    const askedBefore = asked.length;
    const again = await once.hub.connect(target);
    open.push({ hub: once.hub, id: again.connectionId! });
    expect(asked.length).toBe(askedBefore + 1);

    const always = answering('accept-always');
    const alwaysResult = await always.hub.connect(target);
    expect(alwaysResult.ok).toBe(true);
    open.push({ hub: always.hub, id: alwaysResult.connectionId! });
    expect(JSON.parse(always.storage.getItem(pinStorageKey(target.hostId))!)).toMatchObject({ fingerprintSha256: 'SHA256:abc123' });
    const pinnedAsks = asked.length;
    const pinned = await always.hub.connect(target);
    open.push({ hub: always.hub, id: pinned.connectionId! });
    expect(pinned.ok).toBe(true);
    expect(asked.length).toBe(pinnedAsks);
  });

  it('refuses a changed host key with a visible message and never asks about it', async () => {
    const changed = harness({ kind: 'sha256-fingerprint', fingerprintSha256: 'SHA256:other' });
    let asked = 0;
    changed.hub.setTrustDecider(async () => { asked += 1; return 'accept-always'; });
    const refused = await changed.hub.connect(target, undefined, 'fixture');
    expect(refused.ok).toBe(false);
    expect(refused.error).toMatch(/^Host key for fixture has changed — connection refused\. It now presents SHA256:abc123 instead of the trusted SHA256:other\./);
    expect(asked).toBe(0);
    expect(changed.native.opened).toHaveLength(0);
    expect(JSON.parse(changed.storage.getItem(pinStorageKey(target.hostId))!)).toMatchObject({ fingerprintSha256: 'SHA256:other' });
    // Not even an explicit standing decision overrides a changed key.
    expect((await changed.hub.connect(target, 'accept-always')).ok).toBe(false);
  });

  it('the shared connection store prompts through ssh.onTrustDecision instead of dialling accept-always', async () => {
    const { storage, hosts, created } = platform(null);
    hosts.save({ name: 'dev box', hostname: 'fixture', port: 2222, user: 'u', keyHandleId: 'handle-1' });
    expect(typeof created.api.ssh.onTrustDecision).toBe('function');
    provideApi(created.api);
    setActivePinia(createPinia());
    const connection = useConnectionStore();
    const host = (await created.api.ssh.listConfigHosts())[0]!;

    const dial = connection.connect(host);
    await settle(() => connection.pendingTrust !== null);
    expect(connection.pendingTrust).toMatchObject({ hostLabel: 'dev box', hostname: 'fixture', port: 2222, keyType: 'ssh-ed25519', fingerprintSha256: 'SHA256:abc123' });
    expect(connection.state).toBe('connecting');
    expect(storage.getItem(pinStorageKey(target.hostId))).toBeNull();
    connection.answerTrust('reject');
    expect(await dial).toBe(false);
    expect(connection.pendingTrust).toBeNull();
    expect(connection.error).toMatch(/was not trusted/);
    expect(storage.getItem(pinStorageKey(target.hostId))).toBeNull();

    const second = connection.connect(host);
    await settle(() => connection.pendingTrust !== null);
    connection.answerTrust('accept-always');
    expect(await second).toBe(true);
    open.push({ hub: created.hub, id: connection.connectionId! });
    expect(connection.state).toBe('connected');
    expect(JSON.parse(storage.getItem(pinStorageKey(target.hostId))!)).toMatchObject({ fingerprintSha256: 'SHA256:abc123' });
  });

  it('abandons a waiting host-key question when the user disconnects', async () => {
    const { hosts, created } = platform(null);
    hosts.save({ name: 'fixture', hostname: 'fixture', port: 2222, user: 'u', keyHandleId: 'handle-1' });
    provideApi(created.api);
    setActivePinia(createPinia());
    const connection = useConnectionStore();
    const dial = connection.connect((await created.api.ssh.listConfigHosts())[0]!);
    await settle(() => connection.pendingTrust !== null);
    await connection.disconnect();
    expect(await dial).toBe(false);
    expect(connection.pendingTrust).toBeNull();
  });

  it('ignores a caller-supplied standing accept: only the user\'s answer on the prompt trusts a key', async () => {
    const { storage, hosts, created, native } = platform(null);
    hosts.save({ name: 'fixture', hostname: 'fixture', port: 2222, user: 'u', keyHandleId: 'handle-1' });
    for (const tofuDecision of ['accept-always', 'accept-once'] as const) {
      const result = await created.api.ssh.connect({ host: 'fixture', port: 2222, user: 'u', tofuDecision });
      // No decider is registered, so the unknown key is refused, not pinned.
      expect(result).toEqual({ ok: false, error: expect.stringContaining('was not trusted') });
    }
    expect(storage.getItem(pinStorageKey(target.hostId))).toBeNull();
    expect(native.opened).toHaveLength(0);
  });

  it('lists sessions as shared summaries and attaches, types and resizes on the selected PTY', async () => {
    const { native, hub } = harness();
    const { connectionId } = await hub.connect(target);
    open.push({ hub, id: connectionId! });
    const sessions = await hub.sessionsList(connectionId!);
    expect(sessions.map((row) => [row.name, row.workspace, row.aplexerId, row.backend])).toEqual([
      ['main', '/home/u/git/demo', 'main-id', 'aplexer'],
      ['tests', '/home/u/git/demo', 'tests-id', 'aplexer'],
    ]);

    const received: string[] = [];
    hub.onData(({ data }) => received.push(new TextDecoder().decode(data)));
    const attached = await hub.attachSession({
      connectionId: connectionId!, sessionName: 'main', backend: 'aplexer', workspace: '/home/u/git/demo', aplexerId: 'main-id', cols: 50, rows: 30,
    });
    expect(attached.switched).toBe(false);
    expect(native.opened[0]!.command).toContain("sessions attach -- '/home/u/git/demo:main'");
    // Geometry-first: the PTY opens at the pane's size; no follow-up resize.
    expect(native.opened[0]).toMatchObject({ cols: 50, rows: 30 });
    expect(native.resizes).toEqual([]);

    await settle(() => native.hasPendingRead());
    native.output('$ ');
    // Held until the pane claims the new id (its first geometry push)...
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(received).toEqual([]);
    expect(await hub.resize(attached.shellId, 50, 30)).toBe(true);
    // ...then delivered under that id, in order.
    expect(received.join('')).toBe('$ ');

    expect(await hub.input(attached.shellId, 'echo hi\r', 'main', '/home/u/git/demo')).toBe(true);
    expect(native.writes).toEqual(['echo hi\r']);
    // The fence: a superseded tab's session name is refused, not typed elsewhere.
    expect(await hub.input(attached.shellId, 'x', 'tests')).toBe(false);
    expect(native.writes).toHaveLength(1);

    const again = await hub.attachSession({ connectionId: connectionId!, sessionName: 'main', aplexerId: 'main-id' });
    expect(again).toEqual({ shellId: attached.shellId, switched: true });
    expect(native.opened).toHaveLength(1);
  });

  it('never drops the attach snapshot, on a first attach or a re-attach, even when it beats the shell id to the pane', async () => {
    const { native, hub } = harness();
    native.attachSnapshot = '$ echo MARK\r\nMARK\r\n$ ';
    const { connectionId } = await hub.connect(target);
    open.push({ hub, id: connectionId! });
    await hub.sessionsList(connectionId!);
    const received: Array<[string, string]> = [];
    hub.onData(({ shellId, data }) => received.push([shellId, new TextDecoder().decode(data)]));

    let mainShell: string | null = null;
    for (const [name, id] of [['main', 'main-id'], ['tests', 'tests-id'], ['main', 'main-id']] as const) {
      // The third pass re-joins main after its pane gave the shell up.
      if (name === 'main' && mainShell) await hub.closeShell(mainShell);
      const attached = await hub.attachSession({ connectionId: connectionId!, sessionName: name, aplexerId: id, cols: 40, rows: 12 });
      if (name === 'main') mainShell = attached.shellId;
      // The snapshot was read inside attachSession, before this id existed.
      await new Promise((resolve) => setTimeout(resolve, 20));
      expect(received.filter(([shellId]) => shellId === attached.shellId)).toEqual([]);
      await hub.resize(attached.shellId, 40, 12);
      expect(received.filter(([shellId]) => shellId === attached.shellId).map(([, text]) => text))
        .toEqual(['$ echo MARK\r\nMARK\r\n$ ']);
    }
    expect(native.opened).toHaveLength(3);
  });

  it('re-attaches and repaints the same session after its pane closed the shell', async () => {
    const { native, hub } = harness();
    native.attachSnapshot = 'repaint';
    const { connectionId } = await hub.connect(target);
    open.push({ hub, id: connectionId! });
    await hub.sessionsList(connectionId!);
    const received: Array<[string, string]> = [];
    hub.onData(({ shellId, data }) => received.push([shellId, new TextDecoder().decode(data)]));

    const first = await hub.attachSession({ connectionId: connectionId!, sessionName: 'main', aplexerId: 'main-id' });
    expect(await hub.closeShell(first.shellId)).toBe(true);
    expect(native.closedPtys).toHaveLength(1);
    const again = await hub.attachSession({ connectionId: connectionId!, sessionName: 'main', aplexerId: 'main-id' });
    expect(again.switched).toBe(false);
    expect(native.opened).toHaveLength(2);
    await hub.redraw(again.shellId);
    expect(received.filter(([id]) => id === again.shellId).map(([, text]) => text)).toEqual(['repaint']);
  });

  it('delivers keystrokes a re-joining pane types under its retired id of the same session, never into another', async () => {
    const { native, hub } = harness();
    const { connectionId } = await hub.connect(target);
    open.push({ hub, id: connectionId! });
    await hub.sessionsList(connectionId!);
    const main1 = await hub.attachSession({ connectionId: connectionId!, sessionName: 'main', aplexerId: 'main-id' });
    const tests = await hub.attachSession({ connectionId: connectionId!, sessionName: 'tests', aplexerId: 'tests-id' });
    // main's pane gives its shell up (a rejoin), so main1 is retired.
    expect(await hub.closeShell(main1.shellId)).toBe(true);
    // A retired id with no current shell of its session: refused.
    expect(await hub.input(main1.shellId, 'wrong', 'main')).toBe(false);
    expect(native.writes).toEqual([]);

    // main's pane re-joins main and types before the new id reaches it.
    const rejoin = hub.attachSession({ connectionId: connectionId!, sessionName: 'main', aplexerId: 'main-id' });
    const typed = hub.input(main1.shellId, 'early\r', 'main');
    const main2 = await rejoin;
    expect(await typed).toBe(true);
    expect(native.channelWrites).toEqual([[native.channelOf('main'), 'early\r']]);
    expect(native.opened.at(-1)!.command).toContain(":main'");
    // The fence still holds: main's retired id never types into tests.
    expect(await hub.input(main1.shellId, 'x', 'tests')).toBe(false);
    expect(await hub.input(main2.shellId, 'late\r', 'main')).toBe(true);
    expect(await hub.input(tests.shellId, 'b\r', 'tests')).toBe(true);
    expect(native.channelWrites).toEqual([
      [native.channelOf('main'), 'early\r'], [native.channelOf('main'), 'late\r'], [native.channelOf('tests'), 'b\r'],
    ]);
  });

  it('a re-joining pane typing under its retired id does not flush the repaint before it adopts the new id', async () => {
    const { native, hub } = harness();
    native.attachSnapshot = 'REPAINT';
    const { connectionId } = await hub.connect(target);
    open.push({ hub, id: connectionId! });
    await hub.sessionsList(connectionId!);
    const events: string[] = [];
    hub.onData(({ shellId, data }) => events.push(`data:${shellId}:${new TextDecoder().decode(data)}`));
    const main1 = await hub.attachSession({ connectionId: connectionId!, sessionName: 'main', aplexerId: 'main-id' });
    await hub.resize(main1.shellId, 80, 24);
    await hub.attachSession({ connectionId: connectionId!, sessionName: 'tests', aplexerId: 'tests-id' });
    events.length = 0;
    // Mirrors terminalPane: private async requestShell() { return api.shell.attachSession(...) }; showTarget awaits it.
    const requestShell = async () => hub.attachSession({ connectionId: connectionId!, sessionName: 'main', aplexerId: 'main-id' });
    const showTarget = async () => { const r = await requestShell(); events.push(`adopted:${r.shellId}`); };
    const shown = showTarget();
    const typed = hub.input(main1.shellId, 'early\r', 'main');
    await shown; await typed;
    expect(events[0]?.startsWith('adopted:')).toBe(true); // a9aedabb1: ["data:android-1:shell-3:REPAINT","adopted:android-1:shell-3"]
  });

  it('caps output held for an unclaimed shell id, keeping the newest bytes', () => {
    const queue: Uint8Array[] = [];
    const chunk = (fill: number) => new Uint8Array(HELD_OUTPUT_LIMIT_BYTES / 4).fill(fill);
    for (let fill = 1; fill <= 6; fill += 1) holdBounded(queue, chunk(fill));
    expect(queue.reduce((total, part) => total + part.length, 0)).toBeLessThanOrEqual(HELD_OUTPUT_LIMIT_BYTES);
    expect(queue.map((part) => part[0])).toEqual([3, 4, 5, 6]);
    // A single chunk larger than the cap is still kept whole (it is the newest screen).
    const big: Uint8Array[] = [];
    holdBounded(big, new Uint8Array(HELD_OUTPUT_LIMIT_BYTES + 1));
    expect(big).toHaveLength(1);
  });

  it('delivers held output after a fallback when the pane never calls on its new shell id', async () => {
    const { native, hub } = harness();
    native.attachSnapshot = 'snap';
    const { connectionId } = await hub.connect(target);
    open.push({ hub, id: connectionId! });
    await hub.sessionsList(connectionId!);
    const received: string[] = [];
    hub.onData(({ data }) => received.push(new TextDecoder().decode(data)));
    await hub.attachSession({ connectionId: connectionId!, sessionName: 'main', aplexerId: 'main-id' });
    await new Promise((resolve) => setTimeout(resolve, SHELL_CLAIM_FALLBACK_MS / 2));
    expect(received).toEqual([]);
    await new Promise((resolve) => setTimeout(resolve, SHELL_CLAIM_FALLBACK_MS));
    expect(received.join('')).toBe('snap');
  });

  it('keeps the first session\'s shell id live when another session is attached', async () => {
    const { native, hub } = harness();
    const { connectionId } = await hub.connect(target);
    open.push({ hub, id: connectionId! });
    await hub.sessionsList(connectionId!);
    const exited: string[] = [];
    hub.onExited(({ shellId }) => exited.push(shellId));
    const main = await hub.attachSession({ connectionId: connectionId!, sessionName: 'main', aplexerId: 'main-id' });
    const tests = await hub.attachSession({ connectionId: connectionId!, sessionName: 'tests', aplexerId: 'tests-id' });
    expect(tests.shellId).not.toBe(main.shellId);
    expect(exited).toEqual([]);
    expect(native.closedPtys).toHaveLength(0);
    expect(await hub.input(main.shellId, 'late', 'main')).toBe(true);
    expect(native.channelWrites).toEqual([[native.channelOf('main'), 'late']]);
  });

  it('keeps one logical connection id and reports reconnecting, not lost, while the controller re-dials', async () => {
    const { native, hub, controllers } = harness();
    const { connectionId } = await hub.connect(target);
    expect(connectionId).toBe('android-1');
    open.push({ hub, id: connectionId! });
    await hub.sessionsList(connectionId!);
    const attached = await hub.attachSession({ connectionId: connectionId!, sessionName: 'main', aplexerId: 'main-id' });
    const states: string[] = [];
    hub.onState(({ connectionId: id, state }) => {
      if (id === connectionId) states.push(state);
    });
    const received: string[] = [];
    hub.onData(({ shellId, data }) => {
      if (shellId === attached.shellId) received.push(new TextDecoder().decode(data));
    });

    expect(await hub.redraw(attached.shellId)).toBe(true); // the pane claims its id
    const dialsBefore = native.connects.length;
    native.dropTransport();
    await settle(() => native.connects.length === dialsBefore + 1 && native.opened.length === 2
      && controllers.at(-1)!.getSnapshot().phase === 'live' && native.hasPendingRead());
    // The controller's re-dial reads as one recovery, never as a drop the
    // shared store would answer with its own reconnect loop.
    // (`reconnecting` repeats once per dial of the ladder, with its attempt.)
    expect(states.filter((state, index) => state !== states[index - 1])).toEqual(['reconnecting', 'connected']);
    // The same shell id keeps receiving the re-attached session's bytes.
    native.output('redrawn');
    await settle(() => received.includes('redrawn'));
    expect(await hub.input(attached.shellId, 'y', 'main')).toBe(true);
  });

  it('maps controller phases onto the shared connection states', () => {
    const base = { revision: 1, hostId: null, hostLabel: null, generationId: null, sessions: [], sessionListErrors: [], selectedSession: null, terminals: [], retryAttempt: 0, error: null, trustDecision: null, uncertainMutation: null };
    expect(connectionStateFor({ ...base, phase: 'live', connectionId: 'c' })).toBe('connected');
    expect(connectionStateFor({ ...base, phase: 'background', connectionId: null })).toBe('connected');
    expect(connectionStateFor({ ...base, phase: 'awaiting-trust', connectionId: null })).toBe('connecting');
    expect(connectionStateFor({ ...base, phase: 'reconnecting', connectionId: null })).toBe('reconnecting');
    expect(connectionStateFor({ ...base, phase: 'connecting', connectionId: null, retryAttempt: 2 })).toBe('reconnecting');
    expect(connectionStateFor({ ...base, phase: 'listing', connectionId: 'c' })).toBe('connected');
    expect(connectionStateFor({ ...base, phase: 'error', connectionId: 'c' })).toBe('connected');
    expect(connectionStateFor({ ...base, phase: 'error', connectionId: null })).toBe('lost');
    // A refused dial inside the retry ladder is not the give-up (#2954).
    expect(connectionStateFor({ ...base, phase: 'error', connectionId: null, retryAttempt: 1 })).toBe('reconnecting');
    expect(connectionStateFor({ ...base, phase: 'lost', connectionId: null })).toBe('lost');
  });

  function platform(pin: HostKeyTrustPin | null = PIN) {
    const { native, storage, controllers } = harness(pin);
    const hosts = new AndroidHostStore({ storage, readLegacyHosts: async () => [] });
    let lifecycle: ((active: boolean) => void) | null = null;
    let ids = 0;
    const created = createAndroidPlatform({
      createController: () => {
        const controller = new ConnectionController({
          capability: native.capability(),
          trustStore: createLocalTrustStore(storage),
          createId: () => `p-${++ids}`,
          retryDelaysMs: [0],
        });
        controllers.push(controller);
        return controller;
      },
      hosts,
      lifecycle: { onActiveChange: (handler) => { lifecycle = handler; return () => { lifecycle = null; }; } },
      backgroundGraceMs: () => 60_000,
      addHostRoute: '/android/hosts',
    });
    return { native, storage, hosts, created, controllers, emitActive: (active: boolean) => lifecycle?.(active) };
  }

  it('connects a saved host with its session key and serves bootstrap, usage and home over the controller', async () => {
    const { native, hosts, created } = platform();
    expect(await created.api.ssh.listConfigHosts()).toEqual([]);
    expect(created.api.hosts?.emptyAction).toEqual({ label: 'Add a host', route: '/android/hosts' });

    hosts.save({ name: 'fixture', hostname: 'fixture', port: 2222, user: 'u', keyHandleId: 'handle-1' });
    expect((await created.api.ssh.listConfigHosts()).map((host) => host.name)).toEqual(['fixture']);
    const result = await created.api.ssh.connect({ host: 'fixture', port: 2222, user: 'u', tofuDecision: 'accept-always' });
    expect(result.ok).toBe(true);
    open.push({ hub: created.hub, id: result.connectionId! });
    expect(native.connects[0]).toMatchObject({ hostname: 'fixture', port: 2222, username: 'u', credential: { kind: 'key-handle', handleId: 'handle-1' } });

    expect(await created.api.projects.home(result.connectionId!)).toEqual({ ok: true, home: '/home/u', error: null });
    expect((await created.api.helper.usage(result.connectionId!))).toHaveLength(1);
    const bootstrap = await created.api.helper.bootstrap(result.connectionId!);
    expect(bootstrap.pocketshell.installed).toBe(false);
    expect(native.execs.some((command) => command.includes('command -v pocketshell'))).toBe(true);
  });

  it('refuses unimplemented work by name instead of pretending', async () => {
    const { created } = platform();
    await expect(created.api.sftp.list('android-1', '/')).rejects.toBeInstanceOf(UnsupportedCapability);
    await expect(created.api.forwards.addManual('android-1', { kind: 'local', listenHost: '', listenPort: 1, destHost: '', destPort: 1 }))
      .rejects.toThrow('forwards.addManual');
    expect(await created.api.forwards.list('android-1')).toEqual([]);
  });

  it('reports a saved host with no key-vault handle as a dial failure', async () => {
    const { storage, created } = platform();
    storage.setItem(ANDROID_HOSTS_STORAGE_KEY, JSON.stringify([{ name: 'box', hostname: 'box', port: 22, user: 'u' }]));
    const result = await created.api.ssh.connect({ host: 'box', port: 22, user: 'u' });
    expect(result).toEqual({ ok: false, error: expect.stringContaining('No SSH key is chosen for “box”') });
  });

  it('drives the controller grace from Android lifecycle and never feeds the shared resume probe', async () => {
    const { hosts, created, controllers, emitActive } = platform();
    hosts.save({ name: 'fixture', hostname: 'fixture', port: 2222, user: 'u', keyHandleId: 'handle-1' });
    const result = await created.api.ssh.connect({ host: 'fixture', port: 2222, user: 'u' });
    open.push({ hub: created.hub, id: result.connectionId! });
    let resumed = 0;
    created.api.app.onResumed(() => { resumed += 1; });

    emitActive(false);
    await settle(() => controllers.at(-1)!.getSnapshot().phase === 'background');
    emitActive(true);
    await settle(() => controllers.at(-1)!.getSnapshot().phase === 'connected');
    expect(resumed).toBe(0);
  });

  it('mounts the legacy screens unless the launch opted into the shared app', () => {
    expect(selectShell('')).toBe('legacy');
    expect(selectShell('?shell=legacy')).toBe('legacy');
    expect(selectShell('?shell=other')).toBe('legacy');
    expect(selectShell('?shell=shared')).toBe('shared');
  });

  it('records each boot so a load-then-reload launch is visible as two entries', () => {
    const storage = new MemoryStorage();
    recordShellBoot('legacy', storage);
    expect(JSON.parse(storage.getItem(SHELL_BOOT_LOG_KEY)!)).toEqual(['legacy']);
    recordShellBoot('shared', storage);
    expect(JSON.parse(storage.getItem(SHELL_BOOT_LOG_KEY)!)).toEqual(['legacy', 'shared']);
  });
});

