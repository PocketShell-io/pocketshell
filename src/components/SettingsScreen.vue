<script setup lang="ts">
import { BACKGROUND_GRACE_OPTIONS, useAppSettings } from '../stores/appSettings';
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
      <button class="settings-link" type="button" data-testid="open-ports" @click="navigation.open('ports')">
        <span class="settings-link__icon"><AppIcon name="arrow-right-left" /></span>
        <span><strong>Port forwarding</strong><small>Find remote services and open local tunnels</small></span>
        <AppIcon class="settings-link__chevron" name="arrow-right" :size="16" />
      </button>
      <button class="settings-link" type="button" data-testid="open-usage" @click="navigation.open('usage')">
        <span class="settings-link__icon"><AppIcon name="bar-chart-2" /></span>
        <span><strong>Provider usage</strong><small>Quota and login status from the connected host</small></span>
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
      <div class="settings-empty-state">
        <AppIcon name="tool" :size="16" />
        <div><strong>Dictation is not available in this build.</strong><p>Voice provider and language controls arrive with the JS composer work. This screen does not request microphone access.</p></div>
      </div>
      <p class="settings-note">Mapped from the previous Voice and Dictation language destinations.</p>
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
