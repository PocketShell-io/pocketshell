import { BUNDLED_MONO_STACK, fontCssVariables } from '@ui/fonts';
import { resolveTheme, THEME_CHOICE_DEFAULT } from '@ui/themes';

/** PocketShell's shared dark palette, applied from the pinned desktop source. */
export const mobileTheme = resolveTheme(THEME_CHOICE_DEFAULT);

/** Android browser surfaces use the bundled JetBrains Mono, then the platform monospace (#3138). */
export const mobileFontVariables = fontCssVariables(
  { monospaceFontFamily: null, terminalFontSize: 13, editorFontSize: 13 },
  BUNDLED_MONO_STACK,
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
