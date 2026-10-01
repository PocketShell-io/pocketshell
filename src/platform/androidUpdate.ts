import {
  checkLatestRelease,
  isTrustedReleaseUrl,
  pickAndroidApkAsset,
  type ReleaseFetch,
  type UpdateCheckResult,
} from '@pocketshell/core';
import {
  isCanonicalInstall,
  isReleaseInstall,
  openExternalUrl,
  readInstalledAppInfo,
  type InstalledAppInfo,
} from './androidAppInfo';

/** The shared app's `api.update` seam (pocketshell-core packages/ui `app/api.ts`). */
export interface UpdateCapability {
  check(): Promise<UpdateCheckResult>;
  open(url: string): Promise<void>;
}

export interface AndroidUpdateDependencies {
  readAppInfo?: () => Promise<InstalledAppInfo>;
  fetcher?: ReleaseFetch;
  openUrl?: (url: string) => void;
  endpoint?: string;
}

/**
 * Android's update capability: core's GitHub release check against the
 * installed versionName, offering the APK for this install's flavour; and the
 * install handoff — the vetted download or release-notes URL goes to the
 * system browser/download manager through `ACTION_VIEW`. PocketShell never
 * installs an APK itself.
 */
export function createAndroidUpdateCapability(deps: AndroidUpdateDependencies = {}): UpdateCapability {
  const readAppInfo = deps.readAppInfo ?? readInstalledAppInfo;
  const fetcher = deps.fetcher ?? ((url, init) => globalThis.fetch(url, init));
  const openUrl = deps.openUrl ?? openExternalUrl;
  return {
    async check() {
      const info = await readAppInfo();
      const preferRelease = isReleaseInstall(info.applicationId);
      return checkLatestRelease({
        currentVersion: info.versionName,
        pickAsset: (assets) => pickAndroidApkAsset(assets, preferRelease),
        fetcher,
        ...(deps.endpoint ? { endpoint: deps.endpoint } : {}),
      });
    },
    async open(url: string) {
      if (!isTrustedReleaseUrl(url)) throw new Error('Only GitHub release links can be opened.');
      openUrl(url);
    },
  };
}

/**
 * The capability for this install, or undefined for a per-worktree test
 * install (a suffixed debug package): like the web platform, those omit the
 * update group, so packaged journeys never depend on GitHub's release feed.
 */
export function androidUpdateCapabilityFor(info: InstalledAppInfo, deps: AndroidUpdateDependencies = {}): UpdateCapability | undefined {
  return isCanonicalInstall(info.applicationId) ? createAndroidUpdateCapability({ ...deps, readAppInfo: async () => info }) : undefined;
}
