<script setup lang="ts">
// The shared app's root on Android: the router outlet plus the one place the
// theme and typography settings become custom properties on <html> — the
// same two watchers desktop's and web's App.vue run, so a settings change
// repaints identically on all three platforms. (Moving this root into the
// shared app is stage A2 of the #2936 plan.)
import { watchEffect } from 'vue';
import { fontCssVariables } from '@ui/fonts';
import { resolveTheme } from '@ui/themes';
import { useSettingsStore } from '@ui/app/stores/settings';
import DiagBanner from '@ui/app/components/DiagBanner.vue';

const settings = useSettingsStore();

watchEffect(() => {
  const theme = resolveTheme(settings.theme);
  const el = document.documentElement;
  el.dataset['theme'] = theme.id;
  el.style.colorScheme = theme.appearance;
  for (const [name, value] of Object.entries(theme.tokens)) {
    el.style.setProperty(name, value);
  }
});

watchEffect(() => {
  const vars = fontCssVariables(
    {
      monospaceFontFamily: settings.monospaceFontFamily,
      terminalFontSize: settings.terminalFontSize,
      editorFontSize: settings.editorFontSize,
    },
    'ui-monospace, monospace',
  );
  for (const [name, value] of Object.entries(vars)) {
    document.documentElement.style.setProperty(name, value);
  }
});
</script>

<template>
  <DiagBanner />
  <RouterView />
</template>

<style>
* {
  box-sizing: border-box;
}
html,
body,
#app {
  height: 100%;
  margin: 0;
}
body {
  background: var(--bg);
  color: var(--fg);
  font-family: var(--font-ui);
  font-size: var(--fs-300);
  line-height: var(--lh-300);
  -webkit-font-smoothing: antialiased;
  text-rendering: optimizeLegibility;
  /* The WebView draws edge to edge; keep content out of the system bars. */
  padding: env(safe-area-inset-top) env(safe-area-inset-right) env(safe-area-inset-bottom) env(safe-area-inset-left);
}
#app {
  display: flex;
  flex-direction: column;
}
</style>
