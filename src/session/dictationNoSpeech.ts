import type { DictationNoSpeech } from '@pocketshell/core';

/**
 * Wording for the shared "listening without recognized text" signal (#3062),
 * used by the prompt composer and the inline terminal dictation bar alike.
 * The audio evidence comes from Android's coarse sound/silence flag. After
 * words were already recognized in this dictation the mic evidently works,
 * so the message reads as a pause, never as "no sound from the mic".
 */
export interface NoSpeechWarningCopy {
  title: string;
  detail: string;
  /** Compact detail for the 40px inline terminal band; the full text goes in its aria-label. */
  shortDetail: string;
}

export const NO_SPEECH_WARNING_TITLE = 'Not hearing words';

type NoSpeechKind = 'paused' | DictationNoSpeech['audio'];

const COPY: Record<NoSpeechKind, Omit<NoSpeechWarningCopy, 'title'>> = {
  paused: { detail: 'Paused? Keep speaking or tap Stop.', shortDetail: 'Keep speaking or Stop' },
  unknown: { detail: 'Check the mic or try again.', shortDetail: 'Check the mic' },
  silent: { detail: 'No sound from the mic. Check it or try again.', shortDetail: 'No sound from mic' },
  sound: { detail: 'Sound, but no words. Check the language or speak closer.', shortDetail: 'Sound, no words' },
};

export function noSpeechWarningCopy(noSpeech: DictationNoSpeech): NoSpeechWarningCopy {
  const kind: NoSpeechKind = noSpeech.afterText ? 'paused' : noSpeech.audio;
  return { title: NO_SPEECH_WARNING_TITLE, ...COPY[kind] };
}
