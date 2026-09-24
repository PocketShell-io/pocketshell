<script setup lang="ts">
import { ref, watch } from 'vue';
import { BACKGROUND_GRACE_OPTIONS, useAppSettings } from '../stores/appSettings';
import { useNavigationStore } from '../stores/navigation';
import { sanitizeLanguageTag } from '../native/speechRecognition';
import { THEME_CHOICE_SYSTEM, THEMES } from '@pocketshell/ui';
import { AppIcon } from '@pocketshell/ui';

const settings = useAppSettings();
const navigation = useNavigationStore();
const dictationLanguageDraft = ref(settings.dictationLanguageTag);
const dictationLanguageError = ref('');
const dictationSilenceSeconds = ref(settings.dictationSilenceWindowMs / 1_000);

watch(() => settings.dictationLanguageTag, (languageTag) => {
  dictationLanguageDraft.value = languageTag;
  dictationLanguageError.value = '';
});

watch(() => settings.dictationSilenceWindowMs, (milliseconds) => {
  dictationSilenceSeconds.value = milliseconds / 1_000;
});

function setTerminalFontSize(event: Event) {
  settings.setTerminalFontSize((event.target as HTMLInputElement).value);
}

function setThemeChoice(event: Event) {
  settings.setThemeChoice((event.target as HTMLSelectElement).value);
}

function setGracePeriod(event: Event) {
  settings.setBackgroundGraceMs(Number((event.target as HTMLSelectElement).value));
}

function setDictationLanguageTag(event: Event) {
  const value = (event.target as HTMLInputElement).value;
  const languageTag = sanitizeLanguageTag(value);
  if (languageTag === undefined) {
    dictationLanguageDraft.value = settings.dictationLanguageTag;
    dictationLanguageError.value = 'Enter “auto” or a valid BCP-47 language tag, such as en-US.';
    return;
  }
  settings.setDictationLanguageTag(languageTag);
  dictationLanguageDraft.value = languageTag;
  dictationLanguageError.value = '';
}

function updateDictationSilenceDraft(event: Event) {
  dictationSilenceSeconds.value = Number((event.target as HTMLInputElement).value);
}

function setDictationSilenceWindow(event: Event) {
  settings.setDictationSilenceWindowMs(Number((event.target as HTMLInputElement).value) * 1_000);
}
</script>

<template>
  <main v-if="navigation.route === 'settings'" class="screen-content settings-screen" data-testid="settings-screen">
    <section class="panel settings-panel" aria-labelledby="settings-title">
      <p class="eyebrow">PREFERENCES</p>
      <h1 id="settings-title">Settings</h1>
      <p class="settings-copy">Choose how PocketShell looks and how long a live terminal rides through an app switch.</p>

      <label class="settings-control">
        <span><strong>Theme</strong><small>Uses the pinned desktop palette and terminal colors.</small></span>
        <select :value="settings.themeChoice" data-testid="setting-theme" @change="setThemeChoice">
          <option :value="THEME_CHOICE_SYSTEM">Follow device</option>
          <option v-for="theme in THEMES" :key="theme.id" :value="theme.id">{{ theme.label }}</option>
        </select>
      </label>

      <button class="settings-link" type="button" data-testid="open-terminal-settings" @click="navigation.open('settings-terminal')">
        <span class="settings-link__icon"><AppIcon name="terminal" /></span>
        <span><strong>Terminal</strong><small>Text size and display options</small></span>
        <AppIcon class="settings-link__chevron" name="arrow-right" :size="16" />
      </button>
      <button class="settings-link" type="button" data-testid="open-connection-settings" @click="navigation.open('settings-connections')">
        <span class="settings-link__icon"><AppIcon name="zap" /></span>
        <span><strong>Connections</strong><small>Background grace period</small></span>
        <AppIcon class="settings-link__chevron" name="arrow-right" :size="16" />
      </button>
      <button class="settings-link" type="button" data-testid="open-voice-settings" @click="navigation.open('settings-voice')">
        <span class="settings-link__icon"><AppIcon name="tool" /></span>
        <span><strong>Voice</strong><small>Dictation settings</small></span>
        <AppIcon class="settings-link__chevron" name="arrow-right" :size="16" />
      </button>
      <button class="settings-link" type="button" data-testid="open-advanced-settings" @click="navigation.open('settings-advanced')">
        <span class="settings-link__icon"><AppIcon name="settings" /></span>
        <span><strong>Advanced</strong><small>Compatibility and account sync status</small></span>
        <AppIcon class="settings-link__chevron" name="arrow-right" :size="16" />
      </button>
      <button class="settings-link" type="button" data-testid="open-diagnostics" @click="navigation.open('diagnostics')">
        <span class="settings-link__icon"><AppIcon name="bar-chart-2" /></span>
        <span><strong>Diagnostics</strong><small>Review local connection and build events</small></span>
        <AppIcon class="settings-link__chevron" name="arrow-right" :size="16" />
      </button>
      <button class="settings-link" type="button" data-testid="open-about" @click="navigation.open('about')">
        <span class="settings-link__icon"><AppIcon name="file" /></span>
        <span><strong>About PocketShell</strong><small>Build identity and update status</small></span>
        <AppIcon class="settings-link__chevron" name="arrow-right" :size="16" />
      </button>
    </section>
  </main>

  <main v-else-if="navigation.route === 'settings-terminal'" class="screen-content settings-screen" data-testid="terminal-settings-screen">
    <section class="panel settings-panel" aria-labelledby="terminal-settings-title">
      <p class="eyebrow">SETTINGS · TERMINAL</p>
      <h1 id="terminal-settings-title">Terminal</h1>
      <p class="settings-copy">The same terminal palette is used in the interface and live SSH terminal.</p>
      <label class="settings-control settings-control--stacked">
        <span><strong>Terminal text size</strong><small>Changes apply immediately to xterm and its layout.</small></span>
        <span class="font-size-control">
          <button class="small-action" type="button" aria-label="Decrease terminal text size" :disabled="settings.terminalFontSize <= 8" @click="settings.setTerminalFontSize(settings.terminalFontSize - 1)">−</button>
          <output data-testid="terminal-font-size">{{ settings.terminalFontSize }} px</output>
          <button class="small-action" type="button" aria-label="Increase terminal text size" :disabled="settings.terminalFontSize >= 32" @click="settings.setTerminalFontSize(settings.terminalFontSize + 1)">+</button>
          <input
            class="font-size-input"
            data-testid="terminal-font-size-input"
            type="number"
            inputmode="numeric"
            min="8"
            max="32"
            step="1"
            :value="settings.terminalFontSize"
            aria-label="Terminal text size in pixels"
            @change="setTerminalFontSize"
          />
        </span>
      </label>
      <div class="settings-preview" aria-label="Terminal text preview">$ pocketshell sessions</div>
    </section>
  </main>

  <main v-else-if="navigation.route === 'settings-connections'" class="screen-content settings-screen" data-testid="connection-settings-screen">
    <section class="panel settings-panel" aria-labelledby="connection-settings-title">
      <p class="eyebrow">SETTINGS · LIFECYCLE</p>
      <h1 id="connection-settings-title">Connections</h1>
      <p class="settings-copy">A live SSH terminal can stay connected briefly while you switch apps. When the grace period ends, PocketShell closes the phone connection; the remote session continues on the host.</p>
      <label class="settings-control settings-control--stacked">
        <span><strong>Keep connection for</strong><small>Applied the next time a live terminal moves to the background.</small></span>
        <select :value="settings.backgroundGraceMs" data-testid="setting-background-grace" @change="setGracePeriod">
          <option v-for="option in BACKGROUND_GRACE_OPTIONS" :key="option.milliseconds" :value="option.milliseconds">{{ option.label }}</option>
        </select>
      </label>
      <p class="settings-note">PocketShell does not keep the phone connection open indefinitely in the background.</p>
    </section>
  </main>

  <main v-else-if="navigation.route === 'settings-voice'" class="screen-content settings-screen" data-testid="voice-settings-screen">
    <section class="panel settings-panel" aria-labelledby="voice-settings-title">
      <p class="eyebrow">SETTINGS · INPUT</p>
      <h1 id="voice-settings-title">Voice</h1>
      <p class="settings-copy">Choose how Android recognizes speech. PocketShell asks for microphone access only when you start dictating.</p>
      <label class="settings-control settings-control--stacked">
        <span>
          <strong id="dictation-language-label">Dictation language</strong>
          <small id="dictation-language-help">Use auto for the device language, or enter a BCP-47 tag supported by Android’s speech recognizer.</small>
        </span>
        <input
          v-model="dictationLanguageDraft"
          class="dictation-language-input"
          data-testid="setting-dictation-language"
          type="text"
          inputmode="text"
          autocomplete="off"
          autocapitalize="off"
          spellcheck="false"
          maxlength="64"
          placeholder="auto or en-US"
          aria-labelledby="dictation-language-label"
          :aria-describedby="dictationLanguageError ? 'dictation-language-help dictation-language-error' : 'dictation-language-help'"
          :aria-invalid="dictationLanguageError ? 'true' : 'false'"
          @change="setDictationLanguageTag"
        />
        <span v-if="dictationLanguageError" id="dictation-language-error" class="dictation-setting-error" role="alert">{{ dictationLanguageError }}</span>
      </label>
      <label class="settings-control settings-control--stacked">
        <span>
          <strong>Recognition silence window</strong>
          <small>How long Android waits through a pause before returning a transcript. Dictation keeps listening until you tap Stop.</small>
        </span>
        <span class="dictation-silence-control">
          <input
            class="dictation-silence-range"
            data-testid="setting-dictation-silence"
            type="range"
            min="2"
            max="60"
            step="1"
            :value="dictationSilenceSeconds"
            :aria-valuetext="`${dictationSilenceSeconds} seconds`"
            aria-label="Recognition silence window in seconds"
            @input="updateDictationSilenceDraft"
            @change="setDictationSilenceWindow"
          />
          <output data-testid="dictation-silence-value" aria-live="polite">{{ dictationSilenceSeconds }} seconds</output>
        </span>
      </label>
      <p class="settings-note">The silence window is saved on this device. The transcript always remains an editable draft until you choose Send.</p>
    </section>
  </main>

  <main v-else-if="navigation.route === 'settings-advanced'" class="screen-content settings-screen" data-testid="advanced-settings-screen">
    <section class="panel settings-panel" aria-labelledby="advanced-settings-title">
      <p class="eyebrow">SETTINGS · COMPATIBILITY</p>
      <h1 id="advanced-settings-title">Advanced</h1>
      <p class="settings-copy">Compatibility options will appear here only when their behavior is implemented and tested.</p>
      <button class="settings-link" type="button" data-testid="open-account-sync" @click="navigation.open('settings-account')">
        <span class="settings-link__icon"><AppIcon name="folder" /></span>
        <span><strong>Account sync</strong><small>Not connected · sync work is pending</small></span>
        <AppIcon class="settings-link__chevron" name="arrow-right" :size="16" />
      </button>
      <p class="settings-note">No unimplemented switch can change connection or retry behavior.</p>
    </section>
  </main>

  <main v-else-if="navigation.route === 'settings-account'" class="screen-content settings-screen" data-testid="account-settings-screen">
    <section class="panel settings-panel" aria-labelledby="account-settings-title">
      <p class="eyebrow">SETTINGS · SYNC</p>
      <h1 id="account-settings-title">Account sync</h1>
      <div class="settings-empty-state">
        <AppIcon name="folder" :size="16" />
        <div><strong>Account sync is not connected.</strong><p>Sign-in, credential storage, and merge behavior are being implemented separately. No account details are stored or requested here.</p></div>
      </div>
    </section>
  </main>
</template>

<style scoped>
.dictation-language-input {
  width: 100%;
  min-height: 48px;
  border: 1px solid var(--border-strong);
  border-radius: var(--r-md);
  background: var(--bg);
  padding: 0 11px;
  color: var(--fg);
  font: 16px var(--font-ui);
}

.dictation-language-input:focus-visible,
.dictation-silence-range:focus-visible {
  border-color: var(--accent);
  outline: 2px solid var(--accent-soft);
  outline-offset: 1px;
}

.dictation-setting-error {
  color: var(--error);
  font-size: var(--fs-200);
  line-height: 1.45;
}

.dictation-silence-control {
  display: grid !important;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px !important;
}

.dictation-silence-range {
  width: 100%;
  min-height: 48px;
  margin: 0;
  accent-color: var(--accent);
}

.dictation-silence-control output {
  min-width: 82px;
  color: var(--fg);
  font: 600 var(--fs-200)/1.2 var(--font-mono);
  text-align: right;
}
</style>
