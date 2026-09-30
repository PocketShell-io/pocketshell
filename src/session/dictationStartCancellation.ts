/** Records a stop request made while Android is still opening a dictation session. */
export function createDictationStartCancellation() {
  let startPending = false;
  let stopRequested = false;

  return {
    begin() {
      startPending = true;
      stopRequested = false;
    },
    requestStop() {
      if (startPending) stopRequested = true;
    },
    takeStopRequest(): boolean {
      if (!startPending) return false;
      const shouldStop = stopRequested;
      startPending = false;
      stopRequested = false;
      return shouldStop;
    },
    clear() {
      startPending = false;
      stopRequested = false;
    },
  };
}
