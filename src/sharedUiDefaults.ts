import { fontCssVariables, resolveTheme, THEME_CHOICE_DEFAULT } from '@pocketshell/ui';

/** PocketShell's shared dark palette, applied from the pinned desktop source. */
export const mobileTheme = resolveTheme(THEME_CHOICE_DEFAULT);

/** Android browser surfaces use the system monospace face as the safe fallback. */
export const mobileFontVariables = fontCssVariables(
  { monospaceFontFamily: null, terminalFontSize: 13, editorFontSize: 13 },
  'ui-monospace, monospace',
);
export const mobileMonoFontFamily = mobileFontVariables['--font-mono'];

/** Apply shared theme data while leaving phone layout and safe-area CSS local. */
export function applySharedUiDefaults(root: HTMLElement): void {
  for (const [name, value] of Object.entries(mobileTheme.tokens)) {
    root.style.setProperty(name, value);
  }
  root.style.colorScheme = mobileTheme.appearance;
  for (const [name, value] of Object.entries(mobileFontVariables)) {
    root.style.setProperty(name, value);
  }
}
