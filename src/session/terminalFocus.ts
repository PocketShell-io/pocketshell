/**
 * Let a composer that gained focus during an asynchronous terminal attach keep it.
 * The terminal still receives focus when no prompt field is currently active.
 */
export async function focusTerminalUnlessComposerFocused(
  composerHasFocus: () => boolean,
  focusTerminal: () => void,
  afterUpdate: () => Promise<unknown> = () => Promise.resolve(),
): Promise<void> {
  await afterUpdate();
  if (!composerHasFocus()) focusTerminal();
}
