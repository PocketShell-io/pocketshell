import { defineStore } from 'pinia';

export const DIAGNOSTICS_STORAGE_KEY = 'pocketshell.js.diagnostics.v1';
export const MAX_DIAGNOSTIC_EVENTS = 100;

export const DIAGNOSTIC_KINDS = [
  'app-started',
  'app-backgrounded',
  'app-foregrounded',
  'build-verified',
  'build-verification-failed',
  'ssh-connect-failed',
  'ssh-operation-failed',
  'ssh-bridge-failed',
  'resource-snapshot-failed',
] as const;

export type DiagnosticKind = (typeof DIAGNOSTIC_KINDS)[number];

export interface DiagnosticEvent {
  id: string;
  at: number;
  kind: DiagnosticKind;
  operation: string;
  code: string;
}

export interface DiagnosticStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem?(key: string): void;
}

const OPERATIONS = new Set([
  'startup', 'assets', 'connect', 'accept-host-key', 'refresh-sessions', 'create-session',
  'attach-session', 'send-terminal-input', 'resize-terminal', 'resource-snapshot', 'lifecycle',
]);
const KINDS = new Set<string>(DIAGNOSTIC_KINDS);
const ERROR_CODE = /^[A-Z][A-Z0-9_]{0,31}$/;

function browserStorage(): DiagnosticStorage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}

function safeKind(value: unknown): DiagnosticKind {
  return typeof value === 'string' && KINDS.has(value) ? value as DiagnosticKind : 'ssh-operation-failed';
}

function safeOperation(value: unknown): string {
  return typeof value === 'string' && OPERATIONS.has(value) ? value : 'lifecycle';
}

function safeCode(value: unknown): string {
  return typeof value === 'string' && ERROR_CODE.test(value) ? value : 'UNAVAILABLE';
}

export function parseDiagnosticEvents(raw: unknown): DiagnosticEvent[] {
  if (!Array.isArray(raw)) return [];
  const events: DiagnosticEvent[] = [];
  for (const item of raw.slice(-MAX_DIAGNOSTIC_EVENTS)) {
    if (typeof item !== 'object' || item === null) continue;
    const entry = item as Record<string, unknown>;
    if (typeof entry.at !== 'number' || !Number.isFinite(entry.at)) continue;
    if (typeof entry.id !== 'string' || entry.id.length > 48) continue;
    events.push({
      id: entry.id,
      at: entry.at,
      kind: safeKind(entry.kind),
      operation: safeOperation(entry.operation),
      code: safeCode(entry.code),
    });
  }
  return events;
}

export function readDiagnosticEvents(storage: DiagnosticStorage | null = browserStorage()): DiagnosticEvent[] {
  if (!storage) return [];
  try {
    const serialized = storage.getItem(DIAGNOSTICS_STORAGE_KEY);
    return serialized === null ? [] : parseDiagnosticEvents(JSON.parse(serialized));
  } catch {
    return [];
  }
}

export function appendDiagnosticEvent(
  event: Pick<DiagnosticEvent, 'kind' | 'operation' | 'code'>,
  storage: DiagnosticStorage | null = browserStorage(),
  now = Date.now(),
): DiagnosticEvent {
  const next: DiagnosticEvent = {
    id: `${now.toString(36)}-${Math.random().toString(36).slice(2, 8)}`,
    at: now,
    kind: safeKind(event.kind),
    operation: safeOperation(event.operation),
    code: safeCode(event.code),
  };
  if (!storage) return next;
  try {
    const events = [...readDiagnosticEvents(storage), next].slice(-MAX_DIAGNOSTIC_EVENTS);
    storage.setItem(DIAGNOSTICS_STORAGE_KEY, JSON.stringify(events));
  } catch {
    // Diagnostics are best-effort; they never block the connection or UI path.
  }
  return next;
}

export function clearDiagnosticEvents(storage: DiagnosticStorage | null = browserStorage()): void {
  if (!storage) return;
  try {
    if (storage.removeItem) storage.removeItem(DIAGNOSTICS_STORAGE_KEY);
    else storage.setItem(DIAGNOSTICS_STORAGE_KEY, '[]');
  } catch {
    // A failed local clear must not affect transport state.
  }
}

export function makeDiagnosticExport(events: readonly DiagnosticEvent[], exportedAt = Date.now()): string {
  return JSON.stringify({
    schema: 1,
    exportedAt: new Date(exportedAt).toISOString(),
    privacy: 'Contains local event times, failure categories, operations, and normalized error codes only.',
    events: parseDiagnosticEvents(events),
  }, null, 2);
}

export const useDiagnosticsStore = defineStore('diagnostics', {
  state: () => ({ events: readDiagnosticEvents() }),
  actions: {
    record(kind: DiagnosticKind, operation: string, code = 'UNAVAILABLE') {
      const event = appendDiagnosticEvent({ kind, operation, code });
      this.events = [...this.events, event].slice(-MAX_DIAGNOSTIC_EVENTS);
      return event;
    },
    clear() {
      clearDiagnosticEvents();
      this.events = [];
    },
  },
});
