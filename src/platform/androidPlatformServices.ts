import type { App } from 'vue';
import { createAndroidDiagnostics, type AndroidDiagnosticsCapability } from './androidDiagnostics';
import { androidUpdateCapabilityFor, type UpdateCapability } from './androidUpdate';
import { isCanonicalInstall, readInstalledAppInfo, type InstalledAppInfo } from './androidAppInfo';

/**
 * The Android implementations of the shared app's optional platform seams
 * (pocketshell-core packages/ui `app/api.ts`): `app.info`, `update`,
 * `diagnostics` and the `diag.log` sink. #2936 hands these to `provideApi`
 * when Android mounts the shared app; until then they run here so runtime
 * errors are captured from launch.
 */
export interface AndroidPlatformServices {
  appInfo(): Promise<InstalledAppInfo>;
  diagnostics: AndroidDiagnosticsCapability;
  update(): Promise<UpdateCapability | undefined>;
}

/** Instrumentation-only query parameter that requests the diagnostics probe. */
export const PROBE_QUERY_PARAMETER = 'ps2861Probe';

interface ProbeState {
  installedAt: number;
  infoState: 'pending' | 'resolved' | 'failed';
  infoAt?: number;
  applicationId?: string;
  exposedAt?: number;
  error?: string;
}

type ProbeWindow = Window & { __ps2861PlatformServices?: AndroidPlatformServices; __ps2861ProbeState?: ProbeState };

let installed: AndroidPlatformServices | null = null;

export function androidPlatformServices(): AndroidPlatformServices | null {
  return installed;
}

/**
 * Create the services and capture unhandled errors the way core's
 * `installDiagCapture` does (Vue pipeline, unhandled rejections, window
 * errors that carry an Error) into the diagnostics sink.
 */
export function installAndroidPlatformServices(app: App, target: Window): AndroidPlatformServices {
  let info: Promise<InstalledAppInfo> | null = null;
  const appInfo = () => (info ??= readInstalledAppInfo());
  let version = 'unknown';
  void appInfo().then((resolved) => { version = resolved.versionName; });
  const diagnostics = createAndroidDiagnostics({ appVersion: () => version });
  const services: AndroidPlatformServices = {
    appInfo,
    diagnostics,
    update: async () => androidUpdateCapabilityFor(await appInfo()),
  };

  const record = (kind: string, error: unknown) => {
    try {
      diagnostics.log({
        kind,
        message: error instanceof Error ? error.message : String(error),
        ...(error instanceof Error && error.stack ? { stack: error.stack } : {}),
        ...(error instanceof Error ? { errorName: error.name } : {}),
      });
    } catch {
      // Reporting runs because something already failed; it must not throw.
    }
  };
  const previous = app.config.errorHandler;
  app.config.errorHandler = (error, instance, detail) => {
    record('render', error);
    if (previous) previous(error, instance, detail);
    else console.error(error);
  };
  target.addEventListener('unhandledrejection', (event) => record('unhandledrejection', event.reason));
  target.addEventListener('error', (event) => {
    if (event.error) record('error', event.error);
  });

  // Packaged-journey probe: exposed only on a per-worktree test install (a
  // suffixed debug package) AND when the page was loaded with
  // `?ps2861Probe=1`. The request rides in the navigation URL rather than in
  // localStorage: a localStorage write made just before `location.reload()`
  // is committed asynchronously and can be missing from the reloaded page,
  // which left J16 waiting for a probe that was never requested (#2861
  // round-3 review). The canonical install never exposes it.
  let probeRequested = false;
  try {
    probeRequested = new URLSearchParams(target.location.search).get(PROBE_QUERY_PARAMETER) === '1';
  } catch {
    // A page without a readable location leaves the probe off.
  }
  if (probeRequested) {
    // The probe's own lifecycle, so a journey that waits for it can say which
    // step it stopped at instead of only timing out (#2861 round-3 review).
    const state: ProbeState = { installedAt: Date.now(), infoState: 'pending' };
    (target as ProbeWindow).__ps2861ProbeState = state;
    void appInfo().then((resolved) => {
      state.infoState = 'resolved';
      state.infoAt = Date.now();
      state.applicationId = resolved.applicationId;
      if (!isCanonicalInstall(resolved.applicationId)) {
        (target as ProbeWindow).__ps2861PlatformServices = services;
        state.exposedAt = Date.now();
      }
    }, (error: unknown) => {
      state.infoState = 'failed';
      state.infoAt = Date.now();
      state.error = error instanceof Error ? error.name : 'error';
    });
  }
  installed = services;
  return services;
}
