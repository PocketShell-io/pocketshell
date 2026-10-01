import { registerPlugin } from '@capacitor/core';

export interface NativeCrashReportFile {
  /** `yyyyMMdd-HHmmss-SSS[-n]`, the report file name without `.txt`. */
  id: string;
  fileName: string;
  /** Raw 0.5.x-format report text, bounded natively; JS redacts it. */
  text: string;
}

export interface NativeCrashReportsPlugin {
  list(): Promise<{ reports: NativeCrashReportFile[] }>;
  remove(options: { id: string }): Promise<{ id: string; removed: boolean }>;
  clear(): Promise<{ removed: number }>;
}

/** Reports written by the Java uncaught-exception recorder (NativeCrashRecorder). */
export const nativeCrashReports = registerPlugin<NativeCrashReportsPlugin>('NativeCrashReports');
