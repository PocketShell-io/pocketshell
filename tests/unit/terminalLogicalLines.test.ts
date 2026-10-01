import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/**
 * The shared-app journey's terminal line matcher, replayed against texts
 * captured from real API 35 runs (#2949 review). The journey evaluates this
 * same file in the WebView, so a red here is a red there.
 */
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../android/app/src/androidTest/assets/terminal-logical-lines.js', import.meta.url)),
  'utf8',
);
const terminalHasLine = new Function(`return ${SOURCE.replace(/^\/\*[\s\S]*?\*\/\s*/, '')};`)() as (
  text: string,
  expected: string,
  mode: 'equals' | 'endsWith',
) => boolean;

/** A run-tagged id the runner makes: `ps2936-<epoch>-<$RANDOM>`, 1-5 digits. */
const RUN3 = 'ps2936-1790835437-4207';
const RUN4 = 'ps2936-1790835707-24437';
const SEED8 = 'ps2936-1790839010-6608';

/** run3 (35 columns): the `_a1` output line exactly fills a row, the late line wraps. */
const RUN3_TEXT =
  `$ echo PS2936_$((6*7))_ps2936-17908\n35437-4207_a1\nPS2936_42_${RUN3}_a1\n` +
  `$ (sleep 3; echo PS2936_LATE_$((6*7\n))_${RUN3}) &\n$ PS2936_LATE_42_ps2936-1790835437-\n4207\n`;
/** run4 (35 columns): a 5-digit id, so the `_a1` output line wraps by one character. */
const RUN4_TEXT = `$ echo PS2936_$((6*7))_ps2936-17908\n35707-24437_a1\nPS2936_42_ps2936-1790835707-24437_a\n1\n$  `;
/** The reviewer's RANDOM=8 run: the expected line is on screen, exactly full width, then the prompt. */
const SEED8_TEXT = `$ echo PS2936_$((6*7))_ps2936-17908\n39010-6608_a1\nPS2936_42_${SEED8}_a1\n$  `;

describe('terminal logical-line matcher', () => {
  it('matches an exactly full-width line that is not wrapped (4-digit run id, RANDOM=8)', () => {
    expect(`PS2936_42_${SEED8}_a1`).toHaveLength(35);
    expect(terminalHasLine(SEED8_TEXT, `PS2936_42_${SEED8}_a1`, 'equals')).toBe(true);
    expect(terminalHasLine(RUN3_TEXT, `PS2936_42_${RUN3}_a1`, 'equals')).toBe(true);
  });

  it('matches a soft-wrapped line (5-digit run id) and a wrapped line sharing the prompt row', () => {
    expect(terminalHasLine(RUN4_TEXT, `PS2936_42_${RUN4}_a1`, 'equals')).toBe(true);
    expect(terminalHasLine(RUN3_TEXT, `PS2936_LATE_42_${RUN3}`, 'endsWith')).toBe(true);
  });

  it('matches a short line on a terminal whose widest row is not a wrap', () => {
    const text = '$ echo PS2936_$((6*7))_r_a1\nPS2936_42_r_a1\n$ ';
    expect(terminalHasLine(text, 'PS2936_42_r_a1', 'equals')).toBe(true);
  });

  it('stays red for a line that is not there: wrong token, prefix only, or only the echoed command', () => {
    for (const [text, id] of [[RUN3_TEXT, RUN3], [RUN4_TEXT, RUN4], [SEED8_TEXT, SEED8]] as const) {
      expect(terminalHasLine(text, `PS2936_42_${id}_a2`, 'equals')).toBe(false);
      expect(terminalHasLine(text, `PS2936_42_${id}`, 'equals')).toBe(false);
      expect(terminalHasLine(text, `PS2936_LATE_42_${id}_x`, 'endsWith')).toBe(false);
    }
    // The arithmetic is only evaluated by the host: the echoed command never matches.
    expect(terminalHasLine(SEED8_TEXT, `echo PS2936_$((6*7))_${SEED8}_a1`, 'equals')).toBe(false);
    expect(terminalHasLine('$ echo PS2936_42_x_a1\n$ ', 'PS2936_42_x_a1', 'equals')).toBe(false);
  });

  it('does not glue a full-width row to the prompt after it', () => {
    // The old width join turned this into one "line" ending in "$  ".
    const text = `PS2936_42_${SEED8}_a1\n$  `;
    expect(terminalHasLine(text, `PS2936_42_${SEED8}_a1`, 'equals')).toBe(true);
  });
});
