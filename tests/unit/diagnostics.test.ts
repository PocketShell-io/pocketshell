import { describe, expect, it } from 'vitest';
import {
  DIAGNOSTICS_STORAGE_KEY,
  MAX_DIAGNOSTIC_EVENTS,
  appendDiagnosticEvent,
  makeDiagnosticExport,
  parseDiagnosticEvents,
  readDiagnosticEvents,
  type DiagnosticStorage,
} from '../../src/diagnostics';

class MemoryStorage implements DiagnosticStorage {
  values = new Map<string, string>();
  getItem(key: string) { return this.values.get(key) ?? null; }
  setItem(key: string, value: string) { this.values.set(key, value); }
  removeItem(key: string) { this.values.delete(key); }
}

describe('local diagnostics', () => {
  it('stores a bounded event log and normalizes untrusted event metadata', () => {
    const storage = new MemoryStorage();
    for (let index = 0; index < MAX_DIAGNOSTIC_EVENTS + 5; index += 1) {
      appendDiagnosticEvent({ kind: 'ssh-bridge-failed', operation: 'connect', code: 'CONNECTION_FAILED' }, storage, index);
    }

    expect(readDiagnosticEvents(storage)).toHaveLength(MAX_DIAGNOSTIC_EVENTS);
    expect(parseDiagnosticEvents([{
      id: '/home/alexey/secret-key.pem',
      at: 1,
      kind: 'ssh-bridge-failed',
      operation: '/home/alexey/secret-key.pem',
      code: 'SECRET_HOSTNAME',
      host: 'dev.example',
      message: '-----BEGIN OPENSSH PRIVATE KEY-----',
    }])).toEqual([{
      id: 'event-1',
      at: 1,
      kind: 'ssh-bridge-failed',
      operation: 'lifecycle',
      code: 'UNAVAILABLE',
    }]);
    expect(storage.getItem(DIAGNOSTICS_STORAGE_KEY)).not.toContain('secret-key.pem');
  });

  it('exports only reviewed, normalized diagnostic fields', () => {
    const event = appendDiagnosticEvent({ kind: 'resource-snapshot-failed', operation: 'resource-snapshot', code: 'INVALID_ARGUMENT' }, null, 1);
    const exported = makeDiagnosticExport([event], 2);

    expect(exported).toContain('INVALID_ARGUMENT');
    expect(exported).toContain('Contains local event times');
    expect(exported).not.toContain('host');
    expect(exported).not.toContain('privateKey');
    expect(exported).not.toContain('message');
  });
});
