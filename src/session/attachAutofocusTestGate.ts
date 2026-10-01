export type AttachAutofocusTestGateSource =
  | 'terminal-enabled-watcher'
  | 'attach-resize'
  | 'attach-final-focus';

declare global {
  interface Window {
    /** Installed only by the packaged #2884 race regression journey. */
    __ps2884BeforeAttachAutofocus?: (source: AttachAutofocusTestGateSource) => Promise<void> | void;
  }
}

/** A dormant deterministic gate for holding attach autofocus during the packaged race journey. */
export function waitForAttachAutofocusTestGate(source: AttachAutofocusTestGateSource): Promise<void> | void {
  if (typeof window === 'undefined') return;
  return window.__ps2884BeforeAttachAutofocus?.(source);
}
