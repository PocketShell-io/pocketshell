/**
 * #2954 (U1 of the #2936 plan, D28/D42): on Android the shared connection
 * store must delegate recovery to the core `ConnectionController` instead of
 * running its own `ReconnectLoop` beside it.
 *
 * These tests wire the REAL pieces together — the shared store from core
 * `packages/ui`, the Android `PocketShellApi`/hub, and a real core
 * `ConnectionController` — over a physical-effects fake of the native plugin,
 * and count dials at the native boundary. Two ladders racing show up there as
 * extra `connect` calls and a new logical connection id.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { ConnectionController } from '@pocketshell/core';
import { provideApi } from '@ui/app/ipc';
import { useConnectionStore } from '@ui/app/stores/connection';
import { linkDownText, transportRetryText } from '@ui/app/linkLostText';
import { createAndroidPlatform, type AndroidPlatform } from '@/platform/android/androidApi';
import type { ConnectionJournalEntry } from '@/platform/android/connectionHub';
import { AndroidHostStore } from '@/platform/android/hostStore';
import { createLocalTrustStore } from '@/platform/android/trustStore';
import { FakeNative, HOST_KEY, MemoryStorage } from './support/androidFakeNative';

/** The controller's own ladder in these tests: dial now, then 1 s, then 1 s. */
const RETRY_DELAYS_MS = [0, 1_000, 1_000];

/** Drive fake time until the condition holds (the controller and store both run on it). */
async function until(predicate: () => boolean, limitMs = 10_000): Promise<void> {
  for (let elapsed = 0; elapsed <= limitMs; elapsed += 10) {
    if (predicate()) return;
    await vi.advanceTimersByTimeAsync(10);
  }
  throw new Error('condition never held');
}

let platform: AndroidPlatform | null = null;

function setup() {
  const native = new FakeNative();
  const storage = new MemoryStorage();
  const hosts = new AndroidHostStore({ storage, readLegacyHosts: async () => [] });
  hosts.save({ name: 'fixture', hostname: 'fixture', port: 2222, user: 'u', keyHandleId: 'handle-1' });
  // The host's key is already trusted: these tests are about recovery, not
  // the first-contact prompt the shared store raises since #2953.
  void createLocalTrustStore(storage).record('u@fixture:2222', { kind: 'wire-key', ...HOST_KEY });
  const controllers: ConnectionController[] = [];
  const journal: ConnectionJournalEntry[] = [];
  let ids = 0;
  platform = createAndroidPlatform({
    createController: () => {
      const controller = new ConnectionController({
        capability: native.capability(),
        trustStore: createLocalTrustStore(storage),
        createId: () => `u1-${++ids}`,
        retryDelaysMs: RETRY_DELAYS_MS,
        delay: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
      });
      controllers.push(controller);
      return controller;
    },
    hosts,
    backgroundGraceMs: () => 60_000,
    addHostRoute: '/android/hosts',
    observeConnections: (entry) => journal.push(entry),
  });
  provideApi(platform.api);
  const store = useConnectionStore();
  const seenStates: string[] = [];
  const seenRetries: Array<{ attempt: number; maxAttempts: number | null }> = [];
  store.$subscribe(() => {
    if (seenStates.at(-1) !== store.state) seenStates.push(store.state);
    const retry = store.transportRetry;
    const last = seenRetries.at(-1);
    if (retry && (!last || last.attempt !== retry.attempt)) seenRetries.push({ ...retry });
  });
  return { native, hosts, controllers, store, seenStates, seenRetries, journal };
}

/** Connect through the shared store and attach `main`, the way the workspace does. */
async function connectAndAttach(ctx: ReturnType<typeof setup>) {
  const [host] = await platform!.api.ssh.listConfigHosts();
  expect(await ctx.store.connect(host!)).toBe(true);
  const connectionId = ctx.store.connectionId!;
  await platform!.api.helper.sessionsList(connectionId);
  await platform!.api.shell.attachSession({ connectionId, sessionName: 'main', aplexerId: 'main-id' });
  await until(() => ctx.controllers.at(-1)!.getSnapshot().phase === 'live');
  return connectionId;
}

beforeEach(() => {
  vi.useFakeTimers();
  setActivePinia(createPinia());
});

afterEach(async () => {
  const current = platform;
  platform = null;
  if (current) {
    await useConnectionStore().disconnect().catch(() => undefined);
    current.dispose();
  }
  vi.useRealTimers();
});

describe('Shared store delegates recovery to the ConnectionController', () => {
  it('advertises controller-owned recovery on the Android ssh group', () => {
    setup();
    expect(typeof platform!.api.ssh.reconnect).toBe('function');
  });

  it('recovers a dropped link with one ladder when an attempt inside it fails', async () => {
    const ctx = setup();
    const connectionId = await connectAndAttach(ctx);
    const dialsBefore = ctx.native.connects.length;
    const ptysBefore = ctx.native.opened.length;
    const journalAtDrop = ctx.journal.length;

    // The first re-dial is refused (the host is still coming back), the
    // second lands. On the stage-1 base the refused attempt surfaced as a
    // momentary `lost`, which started the store's own 5→60 s ladder beside
    // the controller's — a second reconnect after the link was already back.
    ctx.native.refuseDials = true;
    ctx.native.dropTransport();
    await until(() => ctx.native.connects.length === dialsBefore + 1);
    ctx.native.refuseDials = false;
    await until(() => ctx.controllers.at(-1)!.getSnapshot().phase === 'live' && ctx.native.opened.length === ptysBefore + 1);

    // Give any second ladder every chance to fire.
    await vi.advanceTimersByTimeAsync(5 * 60_000);

    expect(ctx.native.connects.length - dialsBefore).toBe(2);
    expect(ctx.native.opened.length - ptysBefore).toBe(1);
    expect(ctx.native.opened.at(-1)!.command).toContain("sessions attach -- '/home/u/git/demo:main'");
    expect(ctx.controllers).toHaveLength(1);
    expect(ctx.store.connectionId).toBe(connectionId);
    expect(ctx.store.state).toBe('connected');
    // The banner read the controller's ladder, and never a give-up.
    expect(ctx.seenStates).not.toContain('lost');
    expect(ctx.seenStates).toContain('reconnecting');
    expect(ctx.seenRetries.map((retry) => `${retry.attempt}/${retry.maxAttempts}`)).toEqual(['0/3', '1/3', '2/3']);
    expect(ctx.store.transportRetry).toBeNull();
    expect(ctx.store.autoRetry).toBeNull();
    // The diagnostics journal the packaged journey reads tells the same story:
    // one ladder (one attempt-0 entry), one logical id, one new transport live.
    const afterDrop = ctx.journal.slice(journalAtDrop);
    expect(afterDrop.filter((entry) => entry.phase === 'reconnecting' && entry.retryAttempt === 0)).toHaveLength(1);
    expect(new Set(afterDrop.map((entry) => entry.connectionId))).toEqual(new Set([connectionId]));
    expect(afterDrop.at(-1)).toMatchObject({ phase: 'live', selectedId: 'main-id', selectedTag: 'main' });
  });

  it('runs no second ladder after the controller gives up, and Retry re-attaches through ssh.reconnect', async () => {
    const ctx = setup();
    const connectionId = await connectAndAttach(ctx);
    const dialsBefore = ctx.native.connects.length;

    ctx.native.refuseDials = true;
    ctx.native.dropTransport();
    await until(() => ctx.controllers[0]!.getSnapshot().phase === 'lost');
    expect(ctx.native.connects.length - dialsBefore).toBe(RETRY_DELAYS_MS.length);

    // The give-up is final until the user asks: no store ladder starts.
    await vi.advanceTimersByTimeAsync(10 * 60_000);
    expect(ctx.native.connects.length - dialsBefore).toBe(RETRY_DELAYS_MS.length);
    expect(ctx.controllers).toHaveLength(1);
    expect(ctx.store.error).toMatch(/Could not reconnect to fixture after 3 attempts/);
    expect(ctx.store.autoRetry).toBeNull();
    expect(ctx.store.state).toBe('lost');

    // Retry: one controller recovery, the same logical id, `main` re-attached.
    // A second press while it is on the wire joins it rather than dialling again.
    ctx.native.refuseDials = false;
    const ptysBefore = ctx.native.opened.length;
    const first = ctx.store.reconnect();
    const second = ctx.store.retryNow();
    await until(() => ctx.controllers[0]!.getSnapshot().phase === 'live');
    expect(await first).toBe(true);
    await second;
    expect(ctx.native.connects.length - dialsBefore).toBe(RETRY_DELAYS_MS.length + 1);
    expect(ctx.native.opened.length - ptysBefore).toBe(1);
    expect(ctx.native.opened.at(-1)!.command).toContain("sessions attach -- '/home/u/git/demo:main'");
    expect(ctx.controllers).toHaveLength(1);
    expect(ctx.store.connectionId).toBe(connectionId);
    expect(ctx.store.state).toBe('connected');
    expect(ctx.store.error).toBeNull();
  });

  it('words the lost-link banner from the controller ladder and its give-up reason', async () => {
    const ctx = setup();
    await connectAndAttach(ctx);
    ctx.native.refuseDials = true;
    ctx.native.dropTransport();
    await until(() => (ctx.store.transportRetry?.attempt ?? 0) === 2);
    expect(transportRetryText('fixture', ctx.store.transportRetry!)).toBe(
      'Lost the connection to fixture. Reconnecting — attempt 2 of 3.',
    );
    expect(transportRetryText('fixture', { attempt: 0, maxAttempts: 3 })).toBe('Lost the connection to fixture. Reconnecting…');
    await until(() => ctx.store.state === 'lost' && ctx.store.transportRetry === null);
    expect(linkDownText('fixture', ctx.store.error)).toBe(
      'Could not reconnect to fixture after 3 attempts. The sessions and terminals on screen are frozen until you reconnect.',
    );
    expect(linkDownText('fixture', null)).toMatch(/^Connection to fixture was lost\./);
  });

  it('leaves the OS-resume probe to the controller when it owns recovery', async () => {
    const ctx = setup();
    await connectAndAttach(ctx);
    const execsBefore = ctx.native.execs.length;
    const dialsBefore = ctx.native.connects.length;
    await ctx.store.onOsResume();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(ctx.native.execs.slice(execsBefore)).not.toContain('true');
    expect(ctx.native.connects.length).toBe(dialsBefore);
  });

  it('lets the pane ask its client-exit verdict on a dying link without a second ladder (#3039)', async () => {
    // #3039 review B2: the pane's verdict listing used helper.sessionsList,
    // whose failure on a dead transport starts the controller's reconnect AND
    // writes a second retry-0 `reconnecting` snapshot — doubling the #2954
    // abrupt-drop ladder. The pane's probe is inert: no journal entry, no dial.
    const ctx = setup();
    const connectionId = await connectAndAttach(ctx);
    expect(typeof platform!.api.helper.sessionsProbe).toBe('function');
    const lost = () => Object.assign(new Error('SSH connection is no longer available.'), { code: 'CONNECTION_LOST' });
    const journalAt = ctx.journal.length;
    const dialsAt = ctx.native.connects.length;

    ctx.native.sessionListFailures.push(lost());
    await expect(platform!.api.helper.sessionsProbe!(connectionId)).rejects.toThrow(/no longer available/);
    await vi.advanceTimersByTimeAsync(10_000);

    expect(ctx.journal.slice(journalAt), 'the verdict probe wrote connection state').toEqual([]);
    expect(ctx.native.connects.length - dialsAt).toBe(0);
    expect(ctx.store.state).toBe('connected');
    expect(ctx.seenStates).not.toContain('reconnecting');

    // While the controller recovers, the probe does not touch the link at all.
    ctx.native.refuseDials = true;
    ctx.native.dropTransport();
    await until(() => ctx.store.state === 'reconnecting');
    const execsAt = ctx.native.execs.length;
    await expect(platform!.api.helper.sessionsProbe!(connectionId)).rejects.toThrow();
    expect(ctx.native.execs.length).toBe(execsAt);
    ctx.native.refuseDials = false;
    await until(() => ctx.controllers.at(-1)!.getSnapshot().phase === 'live');
    expect(await platform!.api.helper.sessionsProbe!(connectionId)).toEqual(
      expect.arrayContaining([expect.objectContaining({ name: 'main' })]),
    );

    // Liveness of the injected failure: the ordinary listing reads the very
    // same failure as a lost link and recovers it.
    const dialsBeforeList = ctx.native.connects.length;
    ctx.native.sessionListFailures.push(lost());
    await expect(platform!.api.helper.sessionsList(connectionId)).rejects.toThrow();
    await until(() => ctx.native.connects.length === dialsBeforeList + 1);
  });
});
