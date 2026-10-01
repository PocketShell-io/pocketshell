import { App as CapacitorApp } from '@capacitor/app';
import { Capacitor } from '@capacitor/core';
import { bridgeWarmUp, withTimeout } from '../native/bridgeReady';

/** Installed package identity, read from Android's PackageManager through @capacitor/app. */
export interface InstalledAppInfo {
  /** Android `versionName`, e.g. `0.5.5-12-gabc1234`, or `unknown` off-device. */
  versionName: string;
  /** Android `versionCode`, or null when the platform cannot report it. */
  versionCode: number | null;
  /** Installed application id, including any per-worktree debug suffix. */
  applicationId: string;
}

export const UNKNOWN_APP_INFO: Readonly<InstalledAppInfo> = {
  versionName: 'unknown',
  versionCode: null,
  applicationId: '',
};

/** The canonical store/sideload package ids; per-worktree test installs carry an extra suffix. */
const CANONICAL_APPLICATION_IDS = new Set(['com.pocketshell.app', 'com.pocketshell.app.release']);

export function isCanonicalInstall(applicationId: string): boolean {
  return CANONICAL_APPLICATION_IDS.has(applicationId);
}

export function isReleaseInstall(applicationId: string): boolean {
  return applicationId.endsWith('.release');
}

export type AppInfoOutcome =
  | { state: 'resolved'; info: InstalledAppInfo; attempts: number }
  | { state: 'timeout'; info: InstalledAppInfo; attempts: number };

type GetInfo = () => Promise<{ version?: string; build?: string; id?: string }>;

/**
 * Read the installed identity after the page's bridge warm-up, bounded: each
 * `App.getInfo()` gets `timeoutMs`, and a lost answer is asked again once. A
 * hang reports `timeout` (with unknown identity) instead of pending forever.
 */
export async function readInstalledAppInfoOutcome(options: {
  getInfo?: GetInfo;
  waitForBridge?: () => Promise<unknown>;
  native?: boolean;
  timeoutMs?: number;
  attempts?: number;
} = {}): Promise<AppInfoOutcome> {
  if (!(options.native ?? Capacitor.isNativePlatform())) return { state: 'resolved', info: { ...UNKNOWN_APP_INFO }, attempts: 0 };
  await (options.waitForBridge ?? bridgeWarmUp)();
  const getInfo: GetInfo = options.getInfo ?? (() => CapacitorApp.getInfo());
  const maxAttempts = options.attempts ?? 2;
  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    try {
      const info = await withTimeout(getInfo(), options.timeoutMs ?? 5_000);
      if (info === undefined) continue;
      const code = Number(info.build);
      return {
        state: 'resolved',
        attempts: attempt,
        info: {
          versionName: info.version?.trim() || 'unknown',
          versionCode: Number.isSafeInteger(code) && code > 0 ? code : null,
          applicationId: info.id ?? '',
        },
      };
    } catch {
      return { state: 'resolved', info: { ...UNKNOWN_APP_INFO }, attempts: attempt };
    }
  }
  return { state: 'timeout', info: { ...UNKNOWN_APP_INFO }, attempts: maxAttempts };
}

export async function readInstalledAppInfo(): Promise<InstalledAppInfo> {
  return (await readInstalledAppInfoOutcome()).info;
}

/**
 * Hand a vetted release URL to the system browser or download manager.
 * Capacitor routes a top-level navigation to a foreign host to an Android
 * `ACTION_VIEW` intent instead of loading it in the app WebView.
 */
export function openExternalUrl(url: string): void {
  if (Capacitor.isNativePlatform()) {
    window.location.assign(url);
    return;
  }
  window.open(url, '_blank', 'noopener,noreferrer');
}
