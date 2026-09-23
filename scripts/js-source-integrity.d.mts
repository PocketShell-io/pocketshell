export interface PinnedCoreSource {
  revision: string;
  sourceEntry: string;
}

export function readPinnedCore(repoRoot: string): PinnedCoreSource;
