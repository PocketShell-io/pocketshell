let composerPointerIntentEpoch = 0;

/** Record a user pointer interaction before Android WebView updates activeElement. */
export function noteComposerPointerIntent(): void {
  composerPointerIntentEpoch += 1;
}

/** Capture composer intent for one pending asynchronous terminal focus. */
export function getComposerPointerIntentEpoch(): number {
  return composerPointerIntentEpoch;
}

/**
 * Preserve composer ownership acquired during asynchronous terminal attach,
 * including a pointer intent that arrives before WebView updates activeElement.
 * The terminal still receives focus when the composer had no new user input.
 */
export async function focusTerminalUnlessComposerFocused(
  composerHasFocus: () => boolean,
  focusTerminal: () => void,
  afterUpdate: () => Promise<unknown> = () => Promise.resolve(),
  composerPointerIntentChanged: () => boolean = () => false,
): Promise<void> {
  await afterUpdate();
  if (!composerHasFocus() && !composerPointerIntentChanged()) focusTerminal();
}
