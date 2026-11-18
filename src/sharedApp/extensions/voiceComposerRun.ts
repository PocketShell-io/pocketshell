import { App as CapacitorApp } from '@capacitor/app';
import { createSharedDictationController } from '@/session/dictationController';
import { readAppSettings, VOICE_LANGUAGE_AUTO } from '@/stores/appSettings';
import { createVoiceComposerSession } from './voiceComposerSession';

/**
 * The app's one voice-composer run (#3128): Android's SpeechRecognizer behind
 * core's dictation policy, with the language and silence window from the
 * phone's voice settings.
 */
export const voiceComposer = createVoiceComposerSession({
  createController: () => {
    const settings = readAppSettings();
    return createSharedDictationController({
      ...(settings.voiceLanguage === VOICE_LANGUAGE_AUTO ? {} : { languageTag: settings.voiceLanguage }),
      silenceWindowMs: settings.voiceSilenceSeconds * 1_000,
    });
  },
  onAppStateChange: (listener) => {
    void CapacitorApp.addListener('appStateChange', ({ isActive }) => listener(isActive)).catch(() => {
      // Browser previews have no Android lifecycle plugin.
    });
  },
});
