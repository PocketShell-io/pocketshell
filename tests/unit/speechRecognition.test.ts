import { describe, expect, it } from 'vitest';
import {
  DEFAULT_SPEECH_SILENCE_WINDOW_MS,
  MAX_SPEECH_SILENCE_WINDOW_MS,
  MIN_SPEECH_SILENCE_WINDOW_MS,
  sanitizeStartDictationOptions,
} from '../../src/native/speechRecognition';

describe('Android speech option boundary', () => {
  it('uses the 4-second silence default and preserves the explicit auto sentinel', () => {
    expect(sanitizeStartDictationOptions({ requestId: 'dictation-1', languageTag: ' auto ' })).toEqual({
      requestId: 'dictation-1',
      languageTag: 'auto',
      silenceWindowMs: DEFAULT_SPEECH_SILENCE_WINDOW_MS,
    });
  });

  it('rounds and clamps the silence window to the supported 2-to-60-second range', () => {
    expect(sanitizeStartDictationOptions({ requestId: 'short', silenceWindowMs: 1_999.6 }).silenceWindowMs)
      .toBe(MIN_SPEECH_SILENCE_WINDOW_MS);
    expect(sanitizeStartDictationOptions({ requestId: 'long', silenceWindowMs: 60_001 }).silenceWindowMs)
      .toBe(MAX_SPEECH_SILENCE_WINDOW_MS);
    expect(sanitizeStartDictationOptions({ requestId: 'fractional', silenceWindowMs: 2_500.4 }).silenceWindowMs)
      .toBe(2_500);
  });

  it('keeps a trimmed BCP-47 hint and defaults malformed untyped values safely', () => {
    expect(sanitizeStartDictationOptions({
      requestId: 'valid-language',
      languageTag: ' fr-FR ',
      silenceWindowMs: Number.NaN,
    })).toEqual({
      requestId: 'valid-language',
      languageTag: 'fr-FR',
      silenceWindowMs: DEFAULT_SPEECH_SILENCE_WINDOW_MS,
    });

    expect(sanitizeStartDictationOptions({
      requestId: 'invalid-language',
      languageTag: 'en--US',
      silenceWindowMs: '2500' as unknown as number,
    })).toEqual({
      requestId: 'invalid-language',
      silenceWindowMs: DEFAULT_SPEECH_SILENCE_WINDOW_MS,
    });
  });
});
