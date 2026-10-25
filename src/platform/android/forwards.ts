import { Capacitor, registerPlugin, type Plugin } from '@capacitor/core';
import type { PocketShellApi } from '@ui/app/api';
import {
  PortForwardController, planPortForwards, type ConnectionSnapshot,
  type PortForwardControllerSnapshot, type ForwardState, type SshCapability, type PortIntent,
} from '@pocketshell/core';
import type { AndroidConnectionHub } from './connectionHub';

type Listener = Parameters<PocketShellApi['forwards']['onStates']>[0];
interface Entry {
  owner: PortForwardController;
  host: string;
  generation: string;
  auto: boolean;
  manual: number[];
  off: number[];
  pins: Record<number, number>;
  names: Record<number, string>;
  snapshot: PortForwardControllerSnapshot;
  scanning: boolean;
  pending: Promise<boolean> | null;
  lastScanAt: number | null;
}

/** Android resource adapter. All scanning and forwarding decisions are made by core. */
export class AndroidForwards {
  private entries = new Map<string, Entry>();
  private listeners = new Set<Listener>();
  private timer: ReturnType<typeof setInterval>;
  private removeStopListener: (() => void) | null = null;
  constructor(private hub: AndroidConnectionHub, private capability: SshCapability) {
    this.timer = setInterval(() => {
      for (const id of this.entries.keys()) void this.refresh(id).catch(() => undefined);
    }, 5_000);
    if (Capacitor.getPlatform() === 'android') {
      void registerPlugin<Plugin>('SshCapability').addListener('forwardsStopped', () => {
        for (const [id, entry] of this.entries) {
          entry.auto = false;
          entry.owner.setAutoEnabled(false);
          for (const port of new Set([...entry.owner.getManualDesiredPorts(), ...entry.snapshot.activeForwards.map((f) => f.remotePort)])) {
            entry.owner.setManualDesiredPort(port, false);
            entry.off = [...new Set([...entry.off, port])];
          }
          entry.manual = [];
          this.save(entry);
          void entry.owner.closeAll().then(() => { entry.snapshot = entry.owner.snapshot(); this.emit(id); });
        }
      }).then((handle) => { this.removeStopListener = () => { void handle.remove(); }; });
    }
  }
  private load(host: string): { auto: boolean; manual: number[]; off: number[]; pins: Record<number, number>; names: Record<number, string> } {
    try {
      const parsed = JSON.parse(localStorage.getItem(`pocketshell.android.forwards.${host}`) ?? '{}');
      const ports = (value: unknown) => Array.isArray(value) ? value.filter((p): p is number => Number.isInteger(p) && p > 0 && p < 65536) : [];
      return { auto: parsed.auto === true, manual: ports(parsed.manual), off: ports(parsed.off),
        pins: Object.fromEntries(Object.entries(parsed.pins ?? {}).filter(([key, value]) => Number(key) > 0 && Number(key) < 65536 && Number.isInteger(value) && Number(value) > 0 && Number(value) < 65536)),
        names: Object.fromEntries(Object.entries(parsed.names ?? {}).filter(([, value]) => typeof value === 'string')) } as ReturnType<AndroidForwards['load']>;
    } catch { return { auto: false, manual: [], off: [], pins: {}, names: {} }; }
  }
  private save(entry: Entry): void {
    localStorage.setItem(`pocketshell.android.forwards.${entry.host}`, JSON.stringify({ auto: entry.auto, manual: entry.manual, off: entry.off, pins: entry.pins, names: entry.names }));
  }
  observe(id: string, snapshot: ConnectionSnapshot): void {
    if (!snapshot.connectionId || !snapshot.generationId || !snapshot.hostId) {
      if (snapshot.phase === 'idle' || snapshot.phase === 'lost') {
        const entry = this.entries.get(id);
        this.entries.delete(id);
        if (entry) void entry.owner.closeAll();
      }
      return;
    }
    if (!['connected', 'live', 'listing', 'attaching'].includes(snapshot.phase)) return;
    const ref = { connectionId: snapshot.connectionId, generationId: snapshot.generationId };
    let entry = this.entries.get(id);
    if (entry) {
      if (entry.generation === snapshot.generationId) return;
      entry.generation = snapshot.generationId;
      void entry.owner.setConnection(ref).then(() => this.refresh(id)).catch(() => undefined);
    } else {
      const preferences = this.load(snapshot.hostId);
      const owner = new PortForwardController({
        exec: (request) => this.capability.exec(request),
        openPortForward: (request) => this.capability.openPortForward({ ...request,
          ...(preferences.pins[request.remotePort] ? { localPort: preferences.pins[request.remotePort] } : {}) }),
        closePortForward: (request) => this.capability.closePortForward(request),
      }, ref, { desiredManualPorts: preferences.manual });
      owner.setAutoEnabled(preferences.auto);
      for (const port of preferences.off) owner.setManualDesiredPort(port, false);
      entry = { owner, host: snapshot.hostId, generation: snapshot.generationId, ...preferences,
        snapshot: owner.snapshot(), scanning: false, pending: null, lastScanAt: null };
      this.entries.set(id, entry);
      // Connection mount owns this, never the Ports screen. This is also the
      // resume/reconnect path for remembered user intent.
      void this.refresh(id).catch(() => undefined);
    }
  }
  isRememberedAutoEnabled(hostId: string): boolean { return this.load(hostId).auto; }
  hasActive(id: string): boolean { return (this.entries.get(id)?.snapshot.activeForwards.length ?? 0) > 0; }
  private entry(id: string): Entry {
    const found = this.entries.get(id);
    if (!found) throw new Error('Connect to a host before forwarding ports.');
    return found;
  }
  private states(id: string): ForwardState[] {
    const entry = this.entry(id);
    return entry.snapshot.activeForwards.map((f) => {
      const port = entry.snapshot.scan.ports.find((p) => p.port === f.remotePort);
      return { key: `local:127.0.0.1:${f.localPort}:127.0.0.1:${f.remotePort}`, kind: 'local',
        listenHost: '127.0.0.1', listenPort: f.localPort, destHost: '127.0.0.1', destPort: f.remotePort,
        origin: f.origin, active: true, bytesIn: 0, bytesOut: 0, rateIn: 0, rateOut: 0,
        name: entry.names[f.remotePort] ?? null, process: port?.process ?? null, cwd: port?.cwd ?? null, remapped: f.localPort !== f.remotePort };
    });
  }
  private emit(id: string): void { if (!this.entries.has(id)) return; for (const listener of this.listeners) listener({ connectionId: id, states: this.states(id) }); }
  async refresh(id: string): Promise<boolean> {
    const entry = this.entry(id);
    if (entry.pending) return entry.pending;
    const phase = this.hub.controllerOf(id)?.getSnapshot().phase;
    if (!phase || !['connected', 'live', 'listing', 'attaching'].includes(phase)) return false;
    entry.scanning = true;
    entry.pending = (async () => {
      try {
        entry.snapshot = await entry.owner.scanAndReconcile();
        entry.lastScanAt = Date.now();
        entry.scanning = false;
        this.emit(id);
        return entry.snapshot.scan.ok;
      } finally { entry.scanning = false; entry.pending = null; }
    })();
    return entry.pending;
  }
  async intent(id: string, port: number, intent: PortIntent | null): Promise<boolean> {
    const entry = this.entry(id);
    if (intent === null) entry.owner.followAutomaticPolicy(port);
    else entry.owner.setManualDesiredPort(port, intent === 'force-on');
    entry.manual = entry.owner.getManualDesiredPorts();
    entry.off = intent === 'force-off' ? [...new Set([...entry.off, port])] : entry.off.filter((p) => p !== port);
    this.save(entry);
    // Finish a scan already applying the old plan, then apply the new user
    // intent before acknowledging the Add/Remove action.
    if (entry.pending) await entry.pending;
    return this.refresh(id);
  }
  readonly api: PocketShellApi['forwards'] = {
    scan: async (id) => { await this.refresh(id); return this.entry(id).snapshot.scan.ports; },
    refresh: (id) => this.refresh(id),
    startAuto: async (id, config = []) => {
      if (config.length) throw new Error('SSH config forwarding is not available on this phone.');
      const e = this.entry(id); e.auto = true; e.owner.setAutoEnabled(true); this.save(e); if (e.pending) await e.pending; return this.refresh(id);
    },
    stopAuto: async (id) => { const e = this.entry(id); e.auto = false; e.owner.setAutoEnabled(false); this.save(e); if (e.pending) await e.pending; return this.refresh(id); },
    addManual: async (id, spec) => {
      if (spec.kind !== 'local' || !['127.0.0.1', 'localhost'].includes(spec.destHost)) throw new Error('Only loopback local tunnels are supported.');
      if (spec.listenHost !== '127.0.0.1' && spec.listenHost !== 'localhost') throw new Error('Local tunnels bind only to loopback.');
      if (!Number.isInteger(spec.listenPort) || spec.listenPort < 0 || spec.listenPort > 65535) throw new Error('Choose a valid local TCP port.');
      if (spec.listenPort > 0) this.entry(id).pins[spec.destPort] = spec.listenPort;
      await this.intent(id, spec.destPort, 'force-on');
      return this.entry(id).snapshot.activeForwards.some((f) => f.remotePort === spec.destPort && (!spec.listenPort || f.localPort === spec.listenPort));
    },
    remove: async (id, key) => {
      const state = this.states(id).find((s) => s.key === key);
      if (!state) return false;
      await this.intent(id, state.destPort, 'force-off');
      return !this.entry(id).snapshot.activeForwards.some((f) => f.remotePort === state.destPort);
    },
    list: async (id) => this.entries.has(id) ? this.states(id) : [],
    discovered: async (id) => {
      const e = this.entry(id);
      const eligible = planPortForwards({ scan: e.snapshot.scan, activeForwards: [], autoEnabled: true }).openPorts;
      return e.snapshot.scan.ports.map((p) => {
        const live = e.snapshot.activeForwards.find((f) => f.remotePort === p.port);
        return { ...p, forwarded: !!live, localPort: live?.localPort ?? null,
          intent: e.off.includes(p.port) ? 'force-off' : e.manual.includes(p.port) ? 'force-on' : null,
          name: e.names[p.port] ?? null, eligible: eligible.includes(p.port), lastError: e.snapshot.errors[p.port] ?? null };
      });
    },
    status: async (id) => {
      const e = this.entries.get(id);
      return e ? { scanning: e.scanning, lastScanAt: e.lastScanAt, lastScanOk: e.snapshot.scan.ok, lastError: e.snapshot.scan.error } : null;
    },
    setName: async (id, port, name) => { const e = this.entry(id); if (name?.trim()) e.names[port] = name.trim(); else delete e.names[port]; this.save(e); this.emit(id); return true; },
    setRemap: async (id, port, local) => {
      if (!Number.isInteger(local) || local < 1 || local > 65535) throw new Error('Choose a local TCP port between 1 and 65535.');
      const e = this.entry(id); e.pins[port] = local; this.save(e); await e.owner.closeAll(); return this.refresh(id);
    },
    clearRemap: async (id, port) => { const e = this.entry(id); delete e.pins[port]; this.save(e); await e.owner.closeAll(); return this.refresh(id); },
    setIntent: (id, port, intent) => this.intent(id, port, intent),
    togglePort: (id, port) => this.intent(id, port, this.entry(id).snapshot.activeForwards.some((f) => f.remotePort === port) ? 'force-off' : 'force-on'),
    isAutoEnabled: async (id) => this.entries.get(id)?.auto ?? false,
    onStates: (listener) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; },
  };
  async resume(): Promise<void> {
    await Promise.allSettled([...this.entries.keys()].map((id) => this.refresh(id)));
  }
  async close(id: string): Promise<void> {
    const entry = this.entries.get(id);
    if (!entry) return;
    await entry.owner.closeAll();
    this.entries.delete(id);
  }
  dispose(): void {
    clearInterval(this.timer); this.removeStopListener?.();
    for (const entry of this.entries.values()) void entry.owner.closeAll();
    this.entries.clear();
  }
}
