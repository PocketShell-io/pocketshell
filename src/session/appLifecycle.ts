export interface AppLifecycleController {
  getSnapshot(): { phase: string };
  enterBackground(graceMs: number): Promise<void>;
  returnToForeground(): Promise<void>;
}

export interface AppLifecycleOptions {
  getController: () => AppLifecycleController | null;
  getBackgroundGraceMs: () => number;
  onError: (error: unknown) => void;
}

/** Serialize native app-state transitions and reconcile them to the latest state. */
export function createAppLifecycleHandler({
  getController,
  getBackgroundGraceMs,
  onError,
}: AppLifecycleOptions): (isActive: boolean) => void {
  let appIsActive = true;
  let transitionQueue: Promise<void> = Promise.resolve();

  return (isActive) => {
    appIsActive = isActive;
    transitionQueue = transitionQueue
      .catch(() => undefined)
      .then(async () => {
        const active = getController();
        if (!active) return;

        if (!appIsActive && active.getSnapshot().phase === 'live') {
          await active.enterBackground(getBackgroundGraceMs());
        }

        // Foreground can arrive before enterBackground resolves. Recheck the
        // latest state after awaiting it so the active app cannot remain in the
        // background phase with its PTY grace close still scheduled.
        if (getController() === active && appIsActive && active.getSnapshot().phase === 'background') {
          await active.returnToForeground();
        }
      })
      .catch(onError);
  };
}
