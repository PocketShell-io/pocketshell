import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { shellBottomInsets } from '../../src/native/keyboardInsets';

describe('shell bottom insets for the native IME state', () => {
  it('reserves the native IME overlap so the dock stays above a keyboard Android did not resize under', () => {
    expect(shellBottomInsets({ supported: true, imeVisible: true, safeBottomDp: 0, imeOverlapDp: 0 }))
      .toEqual({ safeAreaBottom: 0, imeOverlapBottom: 0 });
    expect(shellBottomInsets({ supported: true, imeVisible: true, safeBottomDp: 0, imeOverlapDp: 336.4 }))
      .toEqual({ safeAreaBottom: 0, imeOverlapBottom: 336 });
    expect(shellBottomInsets({ supported: true, imeVisible: false, safeBottomDp: 24, imeOverlapDp: 336 }))
      .toEqual({ safeAreaBottom: 24, imeOverlapBottom: 0 });
    expect(shellBottomInsets({ supported: true, imeVisible: true, safeBottomDp: 0, imeOverlapDp: Number.NaN }))
      .toEqual({ safeAreaBottom: 0, imeOverlapBottom: 0 });
    const styles = readFileSync(new URL('../../src/styles.css', import.meta.url), 'utf8');
    expect(styles).toContain('.app-shell[data-keyboard-visible="true"] { --android-shell-safe-bottom: var(--ime-overlap-bottom, 0px); }');
    const app = readFileSync(new URL('../../src/App.vue', import.meta.url), 'utf8');
    expect(app).toContain("setProperty('--ime-overlap-bottom', `${bottom.imeOverlapBottom}px`)");
  });
});
