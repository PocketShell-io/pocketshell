<script setup lang="ts">
import {
  BACKGROUND_GRACE_OPTIONS,
  VOICE_LANGUAGE_OPTIONS,
  VOICE_SILENCE_MAX_SECONDS,
  VOICE_SILENCE_MIN_SECONDS,
  useAppSettings,
} from '../stores/appSettings';
import { useNavigationStore } from '../stores/navigation';
import { THEME_CHOICE_SYSTEM, THEMES } from '@pocketshell/ui';
import { AppIcon } from '@pocketshell/ui';

const settings = useAppSettings();
const navigation = useNavigationStore();

function setTerminalFontSize(event: Event) {
  settings.setTerminalFontSize((event.target as HTMLInputElement).value);
}

function setThemeChoice(event: Event) {
  settings.setThemeChoice((event.target as HTMLSelectElement).value);
}

function setGracePeriod(event: Event) {
  settings.setBackgroundGraceMs(Number((event.target as HTMLSelectElement).value));
}

function setVoiceLanguage(event: Event) {
  settings.setVoiceLanguage((event.target as HTMLSelectElement).value);
}

function setVoiceSilence(event: Event) {
  settings.setVoiceSilenceSeconds(Number((event.target as HTMLInputElement).value));
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
      <label class="settings-control" data-testid="voice-language-control">
        <span><strong>Dictation language</strong><small>Choose a language hint for Android's system speech recognizer.</small></span>
        <select :value="settings.voiceLanguage" data-testid="setting-voice-language" @change="setVoiceLanguage">
          <option v-for="option in VOICE_LANGUAGE_OPTIONS" :key="option.code" :value="option.code">{{ option.label }}</option>
        </select>
      </label>
      <p class="settings-note">Microphone access is requested only when you start dictating. Dictation always stops into an editable draft for review.</p>
    </section>
  </main>

  <main v-else-if="navigation.route === 'settings-advanced'" class="screen-content settings-screen" data-testid="advanced-settings-screen">
    <section class="panel settings-panel" aria-labelledby="advanced-settings-title">
      <p class="eyebrow">SETTINGS · COMPATIBILITY</p>
      <h1 id="advanced-settings-title">Advanced</h1>
      <p class="settings-copy">Compatibility options appear here only when their behavior is implemented and tested.</p>
      <label class="settings-control settings-control--stacked" data-testid="voice-silence-control">
        <span><strong>Recognizer silence window</strong><small>How long Android's recognizer waits through a pause before treating speech as complete. Dictation stays open until you tap Stop.</small></span>
        <span class="voice-silence-control__value">
          <input
            id="voice-silence-seconds"
            data-testid="setting-voice-silence-seconds"
            type="range"
            :min="VOICE_SILENCE_MIN_SECONDS"
            :max="VOICE_SILENCE_MAX_SECONDS"
            step="1"
            :value="settings.voiceSilenceSeconds"
            aria-label="Recognizer silence window in seconds"
            @input="setVoiceSilence"
          />
          <output for="voice-silence-seconds" data-testid="voice-silence-value">{{ settings.voiceSilenceSeconds }} s</output>
        </span>
      </label>
      <p class="settings-note">This setting is saved on this device and applies the next time dictation starts. Android providers may treat silence timing as advisory.</p>
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
.voice-silence-control__value {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 48px;
  align-items: center;
  gap: 12px;
}

.voice-silence-control__value input {
  width: 100%;
  accent-color: var(--accent);
}

.voice-silence-control__value output {
  color: var(--fg);
  font: 600 var(--fs-300)/1.2 var(--font-mono);
  text-align: right;
}
</style>
