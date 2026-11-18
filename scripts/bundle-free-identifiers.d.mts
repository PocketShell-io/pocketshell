import type { Plugin } from 'vite';

export interface FreeIdentifier {
  name: string;
  write: boolean;
  line: number;
  column: number;
}

export function findFreeIdentifiers(code: string): FreeIdentifier[];
export function assertNoFreeIdentifiers(bundle: Record<string, { type: string; fileName: string; code?: string }>): void;
export function refuseFreeIdentifiers(): Plugin;
