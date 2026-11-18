import type { Router } from 'vue-router';
import { handleBack } from '@ui/app/backNavigation';

/**
 * The Android Back button (#3140): ask the shared app first; only when it has
 * nothing left to go back to (the host list root) leave the app. Leaving means
 * `minimize`, never exit, so a live SSH session survives.
 */
export function handleAndroidBack(router: Router, minimize: () => void): void {
  if (handleBack(router)) return;
  minimize();
}
