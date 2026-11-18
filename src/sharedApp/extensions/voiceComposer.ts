import type { AppExtensions } from '@ui/app/extensions';
import VoiceComposerLauncher from './VoiceComposerLauncher.vue';
import ComposerDictation from './ComposerDictation.vue';

/**
 * The voice composer's contributions to the phone's extension set (#3128),
 * spread into main.ts's one `provideExtensions` call: the one-tap mic beside
 * the closed composer's toggle (`composer.launcher`; the #3122 compact
 * composer bar will host it once that layout is signed off) and the dictation
 * panel inside the open composer (`composer.inputSources`). Both slots are the
 * voice composer's own; nothing else on Android contributes to them.
 */
export const voiceComposerExtensions = {
  'composer.launcher': [{ id: 'voice-composer', component: VoiceComposerLauncher }],
  'composer.inputSources': [{ id: 'voice-composer-dictation', component: ComposerDictation }],
} satisfies AppExtensions;
