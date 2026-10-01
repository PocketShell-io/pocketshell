export interface PinnedCoreSource {
  revision: string;
  /** vendor/pocketshell-core/src/index.ts */
  sourceEntry: string;
  /** vendor/pocketshell-core/src */
  sourceRoot: string;
  /** vendor/pocketshell-core/packages/ui/src (the shared Vue UI package) */
  uiRoot: string;
}

export const PINNED_SUBMODULES: readonly string[];
export function readPinnedCore(repoRoot: string): PinnedCoreSource;
export function readGitlinkPaths(repoRoot: string): string[];
export function assertOnlyPinnedSubmodules(gitlinkPaths: readonly string[]): void;
export function readUpstreamCoreMain(repoRoot: string): string;
export function assertPinIsUpstreamMain(pinnedRevision: string, upstreamMainRevision: string): void;
