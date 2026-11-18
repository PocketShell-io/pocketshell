import { afterEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { AndroidForwards } from '../../src/platform/android/forwards';
import type { AndroidConnectionHub } from '../../src/platform/android/connectionHub';
import type { ConnectionSnapshot, SshCapability, SshExecOptions, SshPortForwardOptions } from '@pocketshell/core';

const wire = readFileSync(new URL('../../vendor/pocketshell-core/tests/fixtures/portscan/portscan-wire-alpine-root.txt', import.meta.url), 'utf8');
let adapter: AndroidForwards;
afterEach(() => { adapter?.dispose(); vi.unstubAllGlobals(); });
function setup(saved: object = { auto: true }, initialScanDelayMs = 0) {
  const store = new Map([['pocketshell.android.forwards.host', JSON.stringify(saved)]]);
  vi.stubGlobal('localStorage', { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => store.set(k, v) });
  const exec = vi.fn(async (o: SshExecOptions) => ({ ...o, exitCode: 0, stdout: /readlink/.test(o.command) ? '' : wire, stderr: '', timedOut: false }));
  let serial = 0;
  const open = vi.fn(async (o: SshPortForwardOptions) => ({ ...o, forwardId: `forward-${++serial}`, localPort: o.localPort || 41000 + serial }));
  const close = vi.fn(async (o: { requestId: string }) => ({ requestId: o.requestId }));
  const phase = { value: 'connected' };
  const hub = { controllerOf: () => ({ getSnapshot: () => ({ phase: phase.value }) }) } as unknown as AndroidConnectionHub;
  adapter = new AndroidForwards(hub, { exec, openPortForward: open, closePortForward: close } as unknown as SshCapability, initialScanDelayMs);
  const snapshot = { phase: 'connected', hostId: 'host', connectionId: 'native', generationId: 'g1' } as ConnectionSnapshot;
  return { open, close, exec, snapshot, phase };
}
async function settled() { await vi.waitFor(async () => { expect(await adapter.api.status('logical')).toMatchObject({ scanning: false, lastScanOk: true }); }); }

describe('Android forwards over the shared core controller', () => {
  it('starts automatic forwarding on connection mount before any Ports API read and restores on generation swap', async () => {
    const { open, close, snapshot, phase } = setup();
    adapter.observe('logical', snapshot);
    await vi.waitFor(() => expect(open).toHaveBeenCalled());
    await settled();
    expect(adapter.hasActive('logical')).toBe(true);
    const firstCount = open.mock.calls.length;
    // A host tree's session polling leaves the usable controller in listing
    // until a session is attached; forwarding must continue in that phase.
    phase.value = 'listing';
    adapter.observe('logical', { ...snapshot, phase: 'listing', generationId: 'g2' });
    await vi.waitFor(() => expect(open.mock.calls.length).toBeGreaterThan(firstCount));
    expect(close).toHaveBeenCalled();
    expect(open.mock.calls.at(-1)?.[0].generationId).toBe('g2');
  });
  it('remembers auto-off and user force-off across app recreation', async () => {
    const { open, snapshot } = setup({ auto: false, manual: [], off: [8000] });
    adapter.observe('logical', snapshot);
    expect(open).not.toHaveBeenCalled();
    await adapter.api.startAuto('logical');
    expect(open.mock.calls.every(([o]) => o.remotePort !== 8000)).toBe(true);
    expect(await adapter.api.isAutoEnabled('logical')).toBe(true);
    expect(await adapter.api.addManual('logical', { kind: 'local', listenHost: '127.0.0.1', listenPort: 5511, destHost: '127.0.0.1', destPort: 22 })).toBe(true);
    expect(open.mock.calls.find(([o]) => o.remotePort === 22)?.[0].localPort).toBe(5511);
    await adapter.api.setName('logical', 22, 'SSH fixture');
    expect((await adapter.api.list('logical')).find((f) => f.destPort === 22)?.name).toBe('SSH fixture');
  });
  it('never scans the host without forwarding intent or a Ports subscriber, and defers a restore scan past connect', async () => {
    vi.useFakeTimers();
    try {
      // No intent: connect and several poll ticks execute nothing on the host.
      const idle = setup({ auto: false, manual: [], off: [] }, 2_000);
      adapter.observe('logical', idle.snapshot);
      await vi.advanceTimersByTimeAsync(30_000);
      expect(idle.exec).not.toHaveBeenCalled();
      // A Ports subscriber appearing makes scanning useful: one scan, then polling.
      const unsubscribe = adapter.api.onStates(() => undefined);
      await vi.advanceTimersByTimeAsync(100);
      expect(idle.exec).toHaveBeenCalled();
      unsubscribe();
      const afterPanel = idle.exec.mock.calls.length;
      await vi.advanceTimersByTimeAsync(30_000);
      expect(idle.exec.mock.calls.length).toBe(afterPanel);
      adapter.dispose();
      // Remembered auto-forwarding: nothing at connect, restore scan after the delay.
      const auto = setup({ auto: true }, 2_000);
      adapter.observe('logical', auto.snapshot);
      await vi.advanceTimersByTimeAsync(1_900);
      expect(auto.exec).not.toHaveBeenCalled();
      await vi.advanceTimersByTimeAsync(500);
      expect(auto.exec).toHaveBeenCalled();
    } finally { vi.useRealTimers(); }
  });
  it('keeps scanning while a manual forward is live and stops once it is removed', async () => {
    vi.useFakeTimers();
    try {
      const t = setup({ auto: false, manual: [], off: [] }, 2_000);
      adapter.observe('logical', t.snapshot);
      await vi.advanceTimersByTimeAsync(10_000);
      expect(t.exec).not.toHaveBeenCalled();
      const added = adapter.api.addManual('logical', { kind: 'local', listenHost: '127.0.0.1', listenPort: 0, destHost: '127.0.0.1', destPort: 22 });
      await vi.advanceTimersByTimeAsync(100);
      expect(await added).toBe(true);
      const live = t.exec.mock.calls.length;
      await vi.advanceTimersByTimeAsync(11_000);
      expect(t.exec.mock.calls.length, 'a live manual forward must keep being reconciled').toBeGreaterThan(live);
      const [forward] = await adapter.api.list('logical');
      const removed = adapter.api.remove('logical', forward.key);
      await vi.advanceTimersByTimeAsync(100);
      await removed;
      await vi.advanceTimersByTimeAsync(100);
      // The removed port stays force-off (manual list empty), nothing active, no subscriber: idle again.
      const idle = t.exec.mock.calls.length;
      await vi.advanceTimersByTimeAsync(30_000);
      expect(t.exec.mock.calls.length).toBe(idle);
    } finally { vi.useRealTimers(); }
  });
});
