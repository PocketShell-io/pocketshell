import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const styles = readFileSync(new URL('../../src/styles.css', import.meta.url), 'utf8');

describe('mobile control long-press hardening', () => {
  it('keeps dock, launcher, dictation and Composer buttons out of text selection and the touch callout', () => {
    const rule = styles.match(/\.mobile-hotkeys button,\s*\.composer-panel button,\s*\.terminal-dictation-button\s*\{([^}]*)\}/);
    expect(rule, 'shared mobile-control selection rule').not.toBeNull();
    const body = rule![1];
    expect(body).toMatch(/(^|[^-])user-select:\s*none/);
    expect(body).toMatch(/-webkit-user-select:\s*none/);
    expect(body).toMatch(/-webkit-touch-callout:\s*none/);
  });
});
