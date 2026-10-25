import { afterEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { AndroidForwards } from '../../src/platform/android/forwards';
import type { AndroidConnectionHub } from '../../src/platform/android/connectionHub';
import type { ConnectionSnapshot, SshCapability, SshExecOptions, SshPortForwardOptions } from '@pocketshell/core';

const wire = readFileSync(new URL('../../vendor/pocketshell-core/tests/fixtures/portscan/portscan-wire-alpine-root.txt', import.meta.url), 'utf8');
let adapter: AndroidForwards;
afterEach(() => { adapter?.dispose(); vi.unstubAllGlobals(); });
function setup(saved: object = { auto: true }) {
  const store = new Map([['pocketshell.android.forwards.host', JSON.stringify(saved)]]);
  vi.stubGlobal('localStorage', { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => store.set(k, v) });
  const exec = vi.fn(async (o: SshExecOptions) => ({ ...o, exitCode: 0, stdout: /readlink/.test(o.command) ? '' : wire, stderr: '', timedOut: false }));
  let serial = 0;
  const open = vi.fn(async (o: SshPortForwardOptions) => ({ ...o, forwardId: `forward-${++serial}`, localPort: o.localPort || 41000 + serial }));
  const close = vi.fn(async (o: { requestId: string }) => ({ requestId: o.requestId }));
  const phase = { value: 'connected' };
  const hub = { controllerOf: () => ({ getSnapshot: () => ({ phase: phase.value }) }) } as unknown as AndroidConnectionHub;
  adapter = new AndroidForwards(hub, { exec, openPortForward: open, closePortForward: close } as unknown as SshCapability);
  const snapshot = { phase: 'connected', hostId: 'host', connectionId: 'native', generationId: 'g1' } as ConnectionSnapshot;
  return { open, close, snapshot, phase };
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
    await settled();
    expect(open).not.toHaveBeenCalled();
    await adapter.api.startAuto('logical');
    expect(open.mock.calls.every(([o]) => o.remotePort !== 8000)).toBe(true);
    expect(await adapter.api.isAutoEnabled('logical')).toBe(true);
    expect(await adapter.api.addManual('logical', { kind: 'local', listenHost: '127.0.0.1', listenPort: 5511, destHost: '127.0.0.1', destPort: 22 })).toBe(true);
    expect(open.mock.calls.find(([o]) => o.remotePort === 22)?.[0].localPort).toBe(5511);
    await adapter.api.setName('logical', 22, 'SSH fixture');
    expect((await adapter.api.list('logical')).find((f) => f.destPort === 22)?.name).toBe('SSH fixture');
  });
});
