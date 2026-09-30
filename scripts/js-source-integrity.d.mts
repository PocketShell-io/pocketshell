export interface PinnedCoreSource {
  revision: string;
  sourceEntry: string;
}

export interface PinnedDesktopSource extends PinnedCoreSource {
  stylesEntry: string;
}

export function readPinnedCore(repoRoot: string): PinnedCoreSource;
export function readPinnedDesktop(repoRoot: string): PinnedDesktopSource;
