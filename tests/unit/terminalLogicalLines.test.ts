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
  mode: 'equals' | 'endsWith' | 'includes',
  columns?: number,
  occupiedColumns?: number[],
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
  it('uses measured full DOM cells for the real emoji-prefixed composer Insert echo', () => {
    const rows = ["🙂^[[201~$ printf '%s' 'PS2857_INSERT_j", "s2891-c8cf-v2' > /tmp/js2891-c8cf-v2-b", 'ytes-insert.marker '];
    const text = rows.join('\n');
    const marker = 'PS2857_INSERT_js2891-c8cf-v2';
    expect(rows[0]).toHaveLength(39);
    expect(terminalHasLine(text, marker, 'includes', 38)).toBe(false);
    expect(terminalHasLine(text, marker, 'includes', 38, [38.03, 38.03, 19])).toBe(true);
    // A short or overflowing physical row cannot be treated as a wrap.
    expect(terminalHasLine(text, marker, 'includes', 38, [37, 38.03, 19])).toBe(false);
    expect(terminalHasLine(text, marker, 'includes', 38, [39, 38.03, 19])).toBe(false);
    expect(terminalHasLine(text, marker + '-wrong', 'includes', 38, [38.03, 38.03, 19])).toBe(false);
  });

  it('finds the composer Insert echo and dictation readiness across cursor-redrawn rows (CI attempt 3)', () => {
    const composer = "^[[200~alpha\nβeta\n🙂^[[201~$ printf '%s' 'PS2857_INSERT_\njs2891-40109489424-3' > /tmp/js2891-40\n109489424-3-bytes-insert.marker ";
    const dictation = '2884_DICTATION_DONE_js2884-40109489424\n-3\\n\')\nPS2884_DICTATION_READY_js2884-40109489\n424-3\n ';
    expect(terminalHasLine(composer, 'PS2857_INSERT_js2891-40109489424-3', 'includes', 38)).toBe(true);
    expect(terminalHasLine(dictation, 'PS2884_DICTATION_READY_js2884-40109489424-3', 'includes', 38)).toBe(true);
    expect(terminalHasLine(composer, 'PS2857_INSERT_js2891-40109489424-4', 'includes', 38)).toBe(false);
    expect(terminalHasLine(dictation, 'PS2884_DICTATION_READY_js2884-40109489424-4', 'includes', 38)).toBe(false);
  });

  it('uses the actual column count for substring waits, never an arbitrary shorter widest row', () => {
    const text = 'prefix PS_READY_\nrun123 suffix';
    expect(terminalHasLine(text, 'PS_READY_run123', 'includes', 38)).toBe(false);
    expect(terminalHasLine('prefix PS_READY_run123 suffix', 'PS_READY_run123', 'includes', 38)).toBe(true);
    expect(terminalHasLine('prefix PS_READY_\n\nrun123', 'PS_READY_run123', 'includes', 16)).toBe(false);
  });

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

  it('does not join a row shorter than the widest row (a hard newline) to the next', () => {
    // The host printed two lines; together they spell the expected token, but
    // the first is not full width, so it is not a soft wrap.
    const text = '$ echo a-full-width-row-that-sets-the-width-x\nPS2936_42_r\n_a1\n$ ';
    expect(terminalHasLine(text, 'PS2936_42_r_a1', 'equals')).toBe(false);
    expect(terminalHasLine(text, 'PS2936_42_r_a1', 'endsWith')).toBe(false);
  });

  it('does not glue a full-width row to the prompt after it', () => {
    // The old width join turned this into one "line" ending in "$  ".
    const text = `PS2936_42_${SEED8}_a1\n$  `;
    expect(terminalHasLine(text, `PS2936_42_${SEED8}_a1`, 'equals')).toBe(true);
  });
});
