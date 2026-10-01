import { App as CapacitorApp } from '@capacitor/app';
import { Capacitor } from '@capacitor/core';

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

export async function readInstalledAppInfo(): Promise<InstalledAppInfo> {
  if (!Capacitor.isNativePlatform()) return { ...UNKNOWN_APP_INFO };
  try {
    const info = await CapacitorApp.getInfo();
    const code = Number(info.build);
    return {
      versionName: info.version?.trim() || 'unknown',
      versionCode: Number.isSafeInteger(code) && code > 0 ? code : null,
      applicationId: info.id ?? '',
    };
  } catch {
    return { ...UNKNOWN_APP_INFO };
  }
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
