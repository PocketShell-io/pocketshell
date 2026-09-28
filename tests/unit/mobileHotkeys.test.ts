import { createRenderer, defineComponent, h, nextTick, ref, type App } from 'vue';
import { describe, expect, it } from 'vitest';
import { HOTKEY_CTRL_PAGE_ROWS, HOTKEY_PALETTE_MAIN_SECTIONS } from '@pocketshell/core';
import MobileHotkeys from '../../src/components/MobileHotkeys.vue';
import mobileHotkeysSource from '../../src/components/MobileHotkeys.vue?raw';
import appSource from '../../src/App.vue?raw';
import { createMobileHotkeysActions, createMobileHotkeysState } from '../../src/components/mobileHotkeysModel';
import type { InlineDictationState } from '../../src/session/inlineDictation';

function makeHarness(enabled = true, holdThresholdMs = 500) {
  const state = createMobileHotkeysState(enabled);
  const sent: Array<{ bytes: number[]; key: string }> = [];
  const paletteChanges: boolean[] = [];
  const actions = createMobileHotkeysActions(state, {
    send: (bytes, key) => sent.push({ bytes: [...bytes], key }),
    paletteChange: (open) => paletteChanges.push(open),
  }, holdThresholdMs);
  return { state, sent, paletteChanges, actions };
}

describe('mobile fast-key behavior', () => {
  it('keeps icon actions in exact 48px slots and uses Kotlin-aligned mic state tints', () => {
    expect(mobileHotkeysSource).toContain('.mobile-hotkeys__key,\n.mobile-hotkeys__launcher,\n.mobile-hotkeys__page-tab {\n  display: inline-flex;\n  width: 48px;\n  min-width: 48px;\n  height: 48px;\n  min-height: 48px;\n  flex: 0 0 48px;');
    expect(mobileHotkeysSource).toContain('.mobile-hotkeys__bar button.mobile-hotkeys__composer-launcher :deep(svg) { width: 20px; height: 20px; }');
    expect(mobileHotkeysSource).toContain('.mobile-hotkeys__persistent-accessory :deep(.terminal-dictation-button[data-mic-state="idle"]) {\n  color: var(--fg);\n}');
    expect(mobileHotkeysSource).toContain('.mobile-hotkeys__persistent-accessory :deep(.terminal-dictation-button[data-mic-state="listening"]) {\n  border-color: var(--accent-dim);\n  background: var(--state-selected);\n  color: var(--accent);\n}');
    expect(mobileHotkeysSource).toContain('.mobile-hotkeys__persistent-accessory :deep(.terminal-dictation-button[data-mic-state="transcribing"]) {\n  border-color: var(--warning);\n  background: var(--state-selected);\n  color: var(--warning);\n}');
    expect(mobileHotkeysSource).toContain('.mobile-hotkeys__persistent-accessory :deep(.terminal-dictation-button[data-mic-state="error"]) {\n  border-color: var(--error);\n  background: var(--surface-2);\n  color: var(--error);\n}');
    expect(mobileHotkeysSource).toContain('.mobile-hotkeys__sheet {\n  display: flex;\n  width: 100%;\n  min-width: 0;\n  height: 96px;\n  min-height: 96px;\n  flex: 0 0 96px;');
    expect(mobileHotkeysSource).toContain('.mobile-hotkeys__catalog-scroll {\n  display: flex;\n  width: 100%;\n  height: 48px;\n  min-height: 48px;\n  flex: 0 0 48px;');
    expect(mobileHotkeysSource).toContain('.mobile-hotkeys__page-tabs { display: flex; height: 48px; flex: 0 0 auto;');
    expect(mobileHotkeysSource).toContain('.mobile-hotkeys__key--catalog {\n  flex-direction: column;');
  });

  it('uses one App-owned dictation status value for dock sizing and status-row visibility', () => {
    expect(appSource).toContain('const dictationStatusRowHeight = inlineDictationStatusVisible.value ? inlineDictationStatusRowHeightPx : 0;');
    expect(appSource).toContain(':show-inline-dictation-status="inlineDictationStatusVisible"');
  });

  it('keeps the controls inert off-live and resumes only after an explicit live state', () => {
    const { state, sent, paletteChanges, actions } = makeHarness(false);

    actions.sendKey('arrow-up');
    actions.togglePalette();
    actions.beginControlPointer('ctrl-c', { button: 0, isPrimary: true, pointerId: 1, timeStamp: 100 });
    actions.finishControlPointer(1, 800);
    expect(state).toMatchObject({ enabled: false, paletteOpen: false });
    expect(sent).toEqual([]);
    expect(paletteChanges).toEqual([]);

    actions.setEnabled(true);
    actions.sendKey('arrow-up');
    expect(sent).toEqual([{ bytes: [0x1b, 0x5b, 0x41], key: 'arrow-up' }]);
  });

  it('emits core catalog bytes and keeps the palette open until toggled or closed', () => {
    const { state, sent, paletteChanges, actions } = makeHarness();

    actions.sendKey('arrow-up');
    actions.sendKey('arrow-down');
    actions.sendKey('enter');
    actions.togglePalette();
    actions.sendKey('escape');
    expect(state).toMatchObject({ paletteOpen: true, page: 'main' });

    actions.showCtrlPage();
    actions.sendKey('ctrl-q');
    expect(state).toMatchObject({ paletteOpen: true, page: 'ctrl' });
    actions.closePalette();
    actions.togglePalette();
    expect(state).toMatchObject({ paletteOpen: true, page: 'main' });
    actions.sendKey('shift-tab');

    expect(sent).toEqual([
      { bytes: [0x1b, 0x5b, 0x41], key: 'arrow-up' },
      { bytes: [0x1b, 0x5b, 0x42], key: 'arrow-down' },
      { bytes: [0x0d], key: 'enter' },
      { bytes: [0x1b], key: 'escape' },
      { bytes: [0x11], key: 'ctrl-q' },
      { bytes: [0x1b, 0x5b, 0x5a], key: 'shift-tab' },
    ]);
    expect(paletteChanges).toEqual([true, false, true]);
  });

  it('renders core key categories and keeps the full QWERTY Ctrl catalog reachable on its page', async () => {
      const mounted = mountMobileHotkeys();
    try {
      expect(findByTestId(mounted.root, 'mobile-hotkeys-sheet')).toBeUndefined();
      const launcher = findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-launcher' });
      expect(launcher.props['aria-label']).toBe('More terminal keys');
      expect(findByTestId(mounted.root, 'mobile-hotkeys-launcher-label')).toBeUndefined();
      expect(findByTestId(mounted.root, 'mobile-hotkeys-enter-divider')).toBeDefined();
      const navigationParts = findAll(findByTestId(mounted.root, 'mobile-hotkeys-navigation')!, (node) =>
        node.props['data-testid'] === 'mobile-hotkeys-enter-divider'
        || (node.tag === 'button' && typeof node.props['data-key-id'] === 'string'));
      expect(navigationParts.map((node) => node.props['data-key-id'] ?? node.props['data-testid']))
        .toEqual(['arrow-up', 'arrow-down', 'mobile-hotkeys-enter-divider', 'enter']);
      click(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-launcher' }));
      await nextTick();
      expect(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-launcher' }).props['aria-label'])
        .toBe('Close terminal keys');

      const sheet = findByTestId(mounted.root, 'mobile-hotkeys-sheet');
      if (!sheet) throw new Error('The on-demand key catalog sheet did not mount');
      expect(sheet.props).toMatchObject({ role: 'region' });
      expect(sheet.props['aria-modal']).toBeUndefined();
      expect(sheet.props['aria-label']).toBe('Main key catalog');
      expect(findByTestId(mounted.root, 'mobile-hotkeys-sheet-title')?.text).toBe('Keys');
      expect(findByTestId(mounted.root, 'mobile-hotkeys-sheet-title')?.props.class).toBe('mobile-hotkeys__sheet-title');
      expect(findAll(mounted.root, (node) => node.tag === 'button' && typeof node.props['data-key-id'] === 'string'))
        .toHaveLength(3 + HOTKEY_PALETTE_MAIN_SECTIONS.reduce((count, section) => count + section.keys.length, 0));

      const mainPage = findByTestId(mounted.root, 'mobile-hotkeys-main-page');
      if (!mainPage) throw new Error('The fast keys main page did not mount');
      expect(findByTestId(mounted.root, 'mobile-hotkeys-main-scroll-hint')?.text.trim()).toBe('Swipe →');
      const mainTab = findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-back-main-page' });
      const ctrlTab = findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-open-ctrl-page' });
      expect(mainTab.text.trim()).toBe('Main');
      expect(mainTab.props['aria-pressed']).toBe(true);
      expect(mainTab.props['aria-label']).toBe('Select Main keys');
      expect(ctrlTab.text.trim()).toBe('Ctrl');
      expect(ctrlTab.props['aria-pressed']).toBe(false);
      expect(ctrlTab.props['aria-label']).toBe('Select Ctrl keys');
      expect(mainPage.props).toMatchObject({
        role: 'group',
        'aria-label': 'Common terminal keys',
      });
      expect(mainPage.props.class).toContain('mobile-hotkeys__main-keys');
      const mainKeys = findAll(mainPage, (node) => node.tag === 'button' && typeof node.props['data-key-id'] === 'string');
      expect(mainKeys.map((key) => key.props['data-key-id']))
        .toEqual(HOTKEY_PALETTE_MAIN_SECTIONS.flatMap((section) => section.keys.map((key) => key.id)));
      expect(mainKeys.map((key) => key.props['data-key-section']))
        .toEqual(HOTKEY_PALETTE_MAIN_SECTIONS.flatMap((section) => section.keys.map(() => section.title)));
      const mainRows = findAll(mainPage, (node) => node.props.class === 'mobile-hotkeys__main-row');
      expect(mainRows).toHaveLength(1);
      expect(mainRows.map((row) => findAll(row, (node) => node.tag === 'button'
        && typeof node.props['data-key-id'] === 'string').length)).toEqual([10]);

      click(ctrlTab);
      await nextTick();

      const ctrlPage = findByTestId(mounted.root, 'mobile-hotkeys-ctrl-page');
      if (!ctrlPage) throw new Error('The Ctrl key page did not mount');
      expect(findByTestId(mounted.root, 'mobile-hotkeys-main-scroll-hint')).toBeUndefined();
      expect(ctrlPage.props).toMatchObject({
        role: 'group',
        'aria-label': 'QWERTY Ctrl keys',
      });
      expect(ctrlPage.props.class).toContain('mobile-hotkeys__ctrl-grid');
      expect(findByTestId(mounted.root, 'mobile-hotkeys-sheet')?.props['aria-label'])
        .toBe('Ctrl key catalog');
      expect(findByTestId(mounted.root, 'mobile-hotkeys-sheet-title')?.text).toBe('Keys');
      expect(findByTestId(mounted.root, 'mobile-hotkeys-sheet-title')?.props.class).toBe('mobile-hotkeys__sheet-title');
      expect(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-back-main-page' }).props['aria-pressed']).toBe(false);
      expect(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-open-ctrl-page' }).props['aria-pressed']).toBe(true);
      const ctrlIds = findAll(ctrlPage, (node) => node.tag === 'button' && typeof node.props['data-key-id'] === 'string')
        .map((node) => node.props['data-key-id']);
      expect(ctrlIds).toEqual(HOTKEY_CTRL_PAGE_ROWS.flatMap((row) => row.map((key) => key.id)));
      expect(ctrlIds).toHaveLength(HOTKEY_CTRL_PAGE_ROWS.reduce((count, row) => count + row.length, 0));

      click(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-back-main-page' }));
      await nextTick();
      expect(findByTestId(mounted.root, 'mobile-hotkeys-main-page')).toBeDefined();
      expect(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-back-main-page' }).props['aria-pressed']).toBe(true);
      expect(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-open-ctrl-page' }).props['aria-pressed']).toBe(false);
    } finally {
      mounted.app.unmount();
    }
  });

  it('offers a labeled 48px Prompt launcher with an accessible route to the prompt draft', () => {
    const mounted = mountMobileHotkeys(false, false, false, true);
    try {
      const launcher = findButton(mounted.root, { 'data-testid': 'prompt-composer-launcher' });
      expect(launcher.props['aria-label']).toBe('Open prompt composer to type or dictate a prompt');
      expect(launcher.props.title).toBe('Open prompt composer to type or dictate a prompt');
      expect(launcher.props.class).toContain('mobile-hotkeys__composer-launcher');
      expect(findByTestId(mounted.root, 'prompt-composer-launcher-label')?.text).toBe('Prompt');
      expect(findAll(launcher, (node) => node.tag === 'svg')).toHaveLength(1);
      expect(findAll(launcher, (node) => node.tag === 'span').map((node) => node.text)).toEqual(['Prompt']);
      expect(mobileHotkeysSource).toContain('.mobile-hotkeys__composer-launcher {\n  flex-direction: column;\n  gap: 1px;\n  width: 48px;');
      click(launcher);
      expect(mounted.composerOpenRequests()).toBe(1);
      expect(mounted.composerOpenIntents()).toEqual(['compose']);
      expect(mounted.sent).toEqual([]);
    } finally {
      mounted.app.unmount();
    }
  });

  it('labels both dictation routes distinctly while keeping 48dp dock controls', async () => {
    const mounted = mountMobileHotkeys(false, true, false, true, undefined, true);
    try {
      const prompt = findButton(mounted.root, { 'data-testid': 'prompt-composer-launcher' });
      const promptDictation = findButton(mounted.root, { 'data-testid': 'prompt-dictation-launcher' });
      const terminalMic = findButton(mounted.root, { 'data-testid': 'inline-dictation-toggle' });
      const promptGroup = findByTestId(mounted.root, 'mobile-hotkeys-prompt-group');
      const terminalGroup = findByTestId(mounted.root, 'mobile-hotkeys-terminal-group');
      const bar = findAll(mounted.root, (node) => node.props.class === 'mobile-hotkeys__bar')[0];
      if (!bar) throw new Error('The persistent terminal bar did not mount');
      const barButtons = findAll(bar, (node) => node.tag === 'button');
      expect(barButtons.map((node) => node.props['data-testid'] ?? node.props['data-key-id']))
        .toEqual([
          'prompt-composer-launcher',
          'prompt-dictation-launcher',
          'arrow-up',
          'arrow-down',
          'enter',
          'mobile-hotkeys-launcher',
          'inline-dictation-toggle',
      ]);
      expect(prompt.props.title).toBe('Open prompt composer to type or dictate a prompt');
      expect(promptGroup?.props).toMatchObject({ role: 'group', 'aria-label': 'Prompt input' });
      expect(terminalGroup?.props).toMatchObject({ role: 'group', 'aria-label': 'Terminal controls' });
      expect(promptDictation.props).toMatchObject({
        'aria-label': 'Dictate a prompt and review it before Insert or Send',
        title: 'Dictate a prompt and review it before Insert or Send',
        'aria-haspopup': 'dialog',
      });
      expect(terminalMic.props['aria-label']).toBe('Dictate at terminal cursor');
      expect(findByTestId(mounted.root, 'mobile-hotkeys-launcher-label')).toBeUndefined();
      expect(findByTestId(mounted.root, 'prompt-composer-launcher-label')?.text).toBe('Prompt');
      expect(findByTestId(mounted.root, 'prompt-dictation-launcher-label')?.text).toBe('Dictate');
      expect(findByTestId(mounted.root, 'inline-dictation-dock-label')?.text).toBe('Cursor');
      expect(findAll(prompt, (node) => node.tag === 'svg')).toHaveLength(1);
      expect(findAll(prompt, (node) => node.tag === 'span').map((node) => node.text)).toEqual(['Prompt']);
      expect(findAll(promptDictation, (node) => node.tag === 'span').map((node) => node.text)).toEqual(['Dictate']);
      expect(findAll(terminalMic, (node) => node.tag === 'span').map((node) => node.text)).toEqual(['Cursor']);
      expect(findAll(promptDictation, (node) => node.tag === 'svg')).toHaveLength(1);
      expect(promptDictation.props.class).toBe('mobile-hotkeys__prompt-dictation');
      expect(mobileHotkeysSource).toContain('width: 48px;\n  min-width: 48px;\n  height: 48px;');
      expect(mobileHotkeysSource).toContain('flex-direction: column;\n  align-items: center;\n  justify-content: center;\n  gap: 1px;');
      const dock = findByTestId(mounted.root, 'mobile-hotkeys')!;
      const dictationTarget = {
        closest: (selector: string) => selector === 'button'
          || selector === '[data-testid="prompt-dictation-launcher"]' ? promptDictation : null,
      } as unknown as Element;
      dispatch(dock, 'Pointerdown', {
        target: dictationTarget,
        pointerId: 71,
        button: 0,
        isPrimary: true,
      });
      dispatch(dock, 'Click', { target: dictationTarget, detail: 1 });
      expect(mounted.keyboardOpenRequests()).toBe(0);
      const keysButton = findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-launcher' });
      expect(keysButton.props.title).toBe('More terminal keys');
      const keysGlyph = findAll(keysButton, (node) => node.tag === 'svg')[0];
      expect(keysGlyph?.props.class).toBe('mobile-hotkeys__keys-icon');
      click(keysButton);
      await nextTick();
      expect(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-launcher' }).props['aria-label'])
        .toBe('Close terminal keys');
      expect(findAll(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-launcher' }), (node) => node.tag === 'svg')[0])
        .toBe(keysGlyph);
      expect(prompt.props['aria-label']).not.toBe(terminalMic.props['aria-label']);
      expect(prompt.props['aria-label']).toContain('prompt');
      expect(terminalMic.props['aria-label']).toContain('terminal cursor');
      expect(findAll(mounted.root, (node) => node.tag === 'button' && node.props['data-testid'] === 'inline-dictation-toggle'))
        .toHaveLength(1);
      click(promptDictation);
      expect(mounted.composerOpenIntents()).toEqual(['dictate']);
      expect(mounted.sent).toEqual([]);
    } finally {
      mounted.app.unmount();
    }
  });

  it('shows an idle status strip for errors and warnings, not successful insertion feedback', () => {
    for (const tone of ['success', 'warning', 'error'] as const) {
      const mounted = mountMobileHotkeys(false, true, {
        phase: 'idle',
        preview: '',
        message: tone === 'success' ? 'Inserted at the cursor.' : 'Dictation failed.',
        tone,
      });
      try {
        expect(Boolean(findByTestId(mounted.root, 'inline-dictation-status-row'))).toBe(tone !== 'success');
        expect(findButton(mounted.root, { 'data-testid': 'inline-dictation-toggle' }).props['aria-label'])
          .toBe('Dictate at terminal cursor');
      } finally {
        mounted.app.unmount();
      }
    }
  });

  it('lets App force the same status-row visibility that it uses for terminal dock sizing', () => {
    const visible = mountMobileHotkeys(false, true, {
      phase: 'idle', preview: '', message: 'Inserted at the cursor.', tone: 'success',
    }, false, true);
    try {
      expect(findByTestId(visible.root, 'mobile-hotkeys')?.props['data-dictation-status-visible']).toBe(true);
      expect(findByTestId(visible.root, 'inline-dictation-status-row')).toBeDefined();
      expect(String(findByTestId(visible.root, 'mobile-hotkeys')?.props.class).split(' '))
        .toContain('mobile-hotkeys--dictation-status-open');
    } finally {
      visible.app.unmount();
    }

    const hidden = mountMobileHotkeys(false, true, {
      phase: 'listening', preview: 'words', message: '', tone: 'quiet',
    }, false, false);
    try {
      expect(findByTestId(hidden.root, 'mobile-hotkeys')?.props['data-dictation-status-visible']).toBe(false);
      expect(findByTestId(hidden.root, 'inline-dictation-status-row')).toBeUndefined();
      expect(String(findByTestId(hidden.root, 'mobile-hotkeys')?.props.class).split(' '))
        .not.toContain('mobile-hotkeys--dictation-status-open');
    } finally {
      hidden.app.unmount();
    }
  });

  it('keeps active dictation status above the persistent controls on both catalog pages', async () => {
    const mounted = mountMobileHotkeys(false, true, true);
    try {
      const dock = findByTestId(mounted.root, 'inline-dictation-bar');
      expect(findByTestId(mounted.root, 'inline-dictation-destination')).toBeUndefined();
      const closedDockChildren = dock?.children.filter((child) => 'tag' in child).map((child) => child.props.class);
      expect(closedDockChildren).toEqual([
        'mobile-hotkeys__dictation-status-row',
        'mobile-hotkeys__bar',
      ]);
      const statusRow = findByTestId(mounted.root, 'inline-dictation-status-row');
      expect(statusRow?.parent).toBe(dock);
      expect(findByTestId(mounted.root, 'inline-dictation-status')?.props).toMatchObject({
        role: 'status', 'aria-live': 'polite',
      });
      expect(findByTestId(mounted.root, 'inline-dictation-preview')?.props['aria-live']).toBe('off');
      expect(findByTestId(mounted.root, 'inline-dictation-mode-selector')).toBeUndefined();

      click(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-launcher' }));
      await nextTick();
      const dockChildren = dock?.children.filter((child) => 'tag' in child).map((child) => child.props.class);
      expect(dockChildren).toEqual([
        'mobile-hotkeys__dictation-status-row',
        'mobile-hotkeys__bar',
        'mobile-hotkeys__sheet',
      ]);
      const sheetHeader = findByTestId(mounted.root, 'mobile-hotkeys-sheet')?.children
        .find((child) => 'tag' in child && child.props.class === 'mobile-hotkeys__sheet-header');
      if (!sheetHeader || !('tag' in sheetHeader)) throw new Error('Catalog header disappeared');
      expect(sheetHeader?.children).not.toContain(statusRow);
      expect(findByTestId(mounted.root, 'mobile-hotkeys-navigation')).toBeDefined();
      click(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-open-ctrl-page' }));
      await nextTick();
      expect(findByTestId(mounted.root, 'mobile-hotkeys-ctrl-page')).toBeDefined();
      expect(findByTestId(mounted.root, 'inline-dictation-preview')?.text).toBe('git status');
      expect(mounted.sent).toEqual([]);

      const states: Array<{ state: InlineDictationState; label: string }> = [
        {
          state: { phase: 'stopping', preview: 'git status', message: '', tone: 'quiet' },
          label: 'Transcribing ·',
        },
        {
          state: { phase: 'idle', preview: '', message: 'Microphone permission denied.', tone: 'error' },
          label: 'Error ·',
        },
      ];
      for (const { state, label } of states) {
        const withStatus = mountMobileHotkeys(false, true, state);
        try {
          const status = findByTestId(withStatus.root, 'inline-dictation-status');
          expect(findByTestId(withStatus.root, 'inline-dictation-status-row')?.parent)
            .toBe(findByTestId(withStatus.root, 'inline-dictation-bar'));
          expect(status?.props).toMatchObject({ role: 'status', 'aria-live': 'polite' });
          expect(findAll(status!, (node) => node.props.class === 'mobile-hotkeys__dictation-destination')
            .map((node) => node.text).join('')).toBe('Terminal · ');
          expect(findAll(status!, (node) => node.props.class === 'mobile-hotkeys__dictation-phase')
            .map((node) => node.text).join('').trimEnd()).toBe(label);
          expect(findByTestId(withStatus.root, 'inline-dictation-destination')).toBeUndefined();
          if (state.preview) expect(findByTestId(withStatus.root, 'inline-dictation-preview')?.text).toBe(state.preview);
          else expect(findByTestId(withStatus.root, 'inline-dictation-message')?.text).toBe(state.message);
          expect(withStatus.sent).toEqual([]);
        } finally {
          withStatus.app.unmount();
        }
      }
    } finally {
      mounted.app.unmount();
    }
  });

  it('resolves Ctrl+C and Ctrl+D tap, hold, keyboard, and cancel without a duplicate tap', () => {
    const { sent, actions } = makeHarness();

    actions.togglePalette();
    actions.beginControlPointer('ctrl-c', { button: 0, isPrimary: true, pointerId: 1, timeStamp: 100 });
    expect(sent).toEqual([]); // No early single byte while a hold is still possible.
    actions.finishControlPointer(1, 280);
    actions.clickKey('ctrl-c', 1); // The browser's pointer-generated click is ignored.
    expect(sent).toEqual([{ bytes: [0x03], key: 'ctrl-c' }]);

    actions.beginControlPointer('ctrl-c', { button: 0, isPrimary: true, pointerId: 2, timeStamp: 400 });
    actions.finishControlPointer(2, 950);
    actions.clickKey('ctrl-c', 1);
    expect(sent.filter((send) => send.key === 'ctrl-c')).toEqual([
      { bytes: [0x03], key: 'ctrl-c' },
      { bytes: [0x03, 0x03], key: 'ctrl-c' },
    ]);

    actions.clickKey('ctrl-d', 0); // Keyboard and assistive-technology tap.
    actions.beginControlPointer('ctrl-d', { button: 0, isPrimary: true, pointerId: 3, timeStamp: 1100 });
    actions.cancelControlPointer(3);
    actions.clickKey('ctrl-d', 1);
    expect(sent.filter((send) => send.key === 'ctrl-d')).toEqual([{ bytes: [0x04], key: 'ctrl-d' }]);

    expect(actions.beginControlPointer('ctrl-c', {
      button: 2, isPrimary: true, pointerId: 5, timeStamp: 2000,
    })).toBe(false);
  });

  it('closes and cancels a pending hold on live-session loss, then starts on the main page again', () => {
    const { state, sent, paletteChanges, actions } = makeHarness();
    actions.togglePalette();
    actions.showCtrlPage();
    actions.beginControlPointer('ctrl-d', { button: 0, isPrimary: true, pointerId: 4, timeStamp: 100 });

    actions.setEnabled(false);
    actions.finishControlPointer(4, 900);
    expect(state).toMatchObject({ enabled: false, paletteOpen: false, page: 'ctrl' });
    expect(sent).toEqual([]);
    expect(paletteChanges).toEqual([true, false]);

    actions.setEnabled(true);
    actions.togglePalette();
    expect(state).toMatchObject({ enabled: true, paletteOpen: true, page: 'main' });
  });

  it('provides a direct close action for the app-level Back route', () => {
    const { state, paletteChanges, actions } = makeHarness();
    // The returned close action is the same method exposed by MobileHotkeys.vue.
    expect(typeof actions.closePalette).toBe('function');
    actions.openPalette();
    actions.closePalette();
    expect(state.paletteOpen).toBe(false);
    expect(paletteChanges).toEqual([true, false]);
  });

  it('keeps the persistent status and control slot layout available on both catalog pages', async () => {
    // Layout-only fixtures for the future #2896 mount; this does not exercise dictation behavior.
    const mounted = mountMobileHotkeys(true);
    try {
      expect(findByTestId(mounted.root, 'mobile-hotkeys-persistent-status')).toBeDefined();
      expect(findByTestId(mounted.root, 'status-slot-fixture')).toBeDefined();
      expect(findByTestId(mounted.root, 'control-slot-fixture')).toBeDefined();

      click(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-launcher' }));
      await nextTick();
      expect(findByTestId(mounted.root, 'mobile-hotkeys-main-page')).toBeDefined();
      expect(findByTestId(mounted.root, 'status-slot-fixture')).toBeDefined();
      expect(findByTestId(mounted.root, 'control-slot-fixture')).toBeDefined();
      click(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-open-ctrl-page' }));
      await nextTick();

      expect(findByTestId(mounted.root, 'status-slot-fixture')).toBeDefined();
      expect(findByTestId(mounted.root, 'control-slot-fixture')).toBeDefined();
      expect(findByTestId(mounted.root, 'mobile-hotkeys-ctrl-page')).toBeDefined();
    } finally {
      mounted.app.unmount();
    }
  });

  it('wires the mounted fast-key controls to core bytes and live-state transitions', async () => {
    const mounted = mountMobileHotkeys();
    try {
      click(findButton(mounted.root, { 'data-key-id': 'arrow-up' }));
      click(findButton(mounted.root, { 'data-key-id': 'arrow-down' }));
      click(findButton(mounted.root, { 'data-key-id': 'enter' }));
      click(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-launcher' }));
      await nextTick();
      expect(findByTestId(mounted.root, 'mobile-hotkeys-main-page')).toBeDefined();
      click(findButton(mounted.root, { 'data-key-id': 'escape' }));
      click(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-open-ctrl-page' }));
      await nextTick();
      expect(findByTestId(mounted.root, 'mobile-hotkeys-ctrl-page')).toBeDefined();
      click(findButton(mounted.root, { 'data-key-id': 'ctrl-q' }));
      click(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-back-main-page' }));
      await nextTick();
      expect(findByTestId(mounted.root, 'mobile-hotkeys-main-page')).toBeDefined();
      click(findButton(mounted.root, { 'aria-label': 'Close terminal keys' }));
      await nextTick();

      expect(mounted.paletteChanges).toEqual([true, false]);
      expect(mounted.sent.map(({ key, bytes }) => [key, [...bytes]])).toEqual([
        ['arrow-up', [0x1b, 0x5b, 0x41]],
        ['arrow-down', [0x1b, 0x5b, 0x42]],
        ['enter', [0x0d]],
        ['escape', [0x1b]],
        ['ctrl-q', [0x11]],
      ]);
      expect(mounted.sent.every(({ bytes }) => bytes instanceof Uint8Array)).toBe(true);

      click(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-launcher' }));
      await nextTick();
      const gestures: Array<{ key: string; gesture: 'tap' | 'hold' | 'cancel' }> = [
        { key: 'ctrl-c', gesture: 'tap' },
        { key: 'ctrl-c', gesture: 'hold' },
        { key: 'ctrl-c', gesture: 'cancel' },
        { key: 'ctrl-d', gesture: 'tap' },
        { key: 'ctrl-d', gesture: 'hold' },
        { key: 'ctrl-d', gesture: 'cancel' },
      ];
      for (const [index, { key, gesture }] of gestures.entries()) {
        const control = findButton(mounted.root, { 'data-key-id': key });
        const start = 1000 + index * 1000;
        pointer(control, 'pointerdown', { pointerId: index + 1, timeStamp: start });
        if (gesture === 'tap') {
          pointer(control, 'pointerup', { pointerId: index + 1, timeStamp: start + 120 });
          click(control, 1);
        } else if (gesture === 'hold') {
          pointer(control, 'pointerup', { pointerId: index + 1, timeStamp: start + 600 });
          click(control, 1);
        } else {
          pointer(control, 'pointercancel', { pointerId: index + 1, timeStamp: start + 120 });
          click(control, 1);
        }
      }
      expect(mounted.sent.slice(5).map(({ key, bytes }) => [key, [...bytes]])).toEqual([
        ['ctrl-c', [0x03]],
        ['ctrl-c', [0x03, 0x03]],
        ['ctrl-d', [0x04]],
        ['ctrl-d', [0x04, 0x04]],
      ]);

      expect(findByTestId(mounted.root, 'mobile-hotkeys-main-page')).toBeDefined();
      const pendingCtrlC = findButton(mounted.root, { 'data-key-id': 'ctrl-c' });
      pointer(pendingCtrlC, 'pointerdown', { pointerId: 99, timeStamp: 8000 });
      mounted.setEnabled(false);
      await nextTick();
      pointer(pendingCtrlC, 'pointerup', { pointerId: 99, timeStamp: 9000 });

      const slot = findByTestId(mounted.root, 'mobile-hotkeys');
      expect(slot?.props['data-enabled']).toBe(false);
      expect(slot?.props['data-palette-open']).toBe(false);
      expect(findByTestId(mounted.root, 'mobile-hotkeys-ctrl-page')).toBeUndefined();
      for (const button of findAll(mounted.root, (node) => node.tag === 'button')) {
        expect(button.props.disabled).toBe(true);
      }
      click(findButton(mounted.root, { 'data-key-id': 'arrow-up' }));
      expect(mounted.sent).toHaveLength(9);
      expect(mounted.paletteChanges).toEqual([true, false, true, false]);
    } finally {
      mounted.app.unmount();
    }
  });
});

type TestElement = {
  tag: string;
  props: Record<string, unknown>;
  children: TestNode[];
  parent: TestElement | null;
  text: string;
  setPointerCapture(pointerId: number): void;
};
type TestText = { text: string; parent: TestElement | null; kind: 'text' | 'comment' };
type TestNode = TestElement | TestText;

const renderer = createRenderer<TestNode, TestElement>({
  createElement: (tag) => ({
    tag,
    props: {},
    children: [],
    parent: null,
    text: '',
    setPointerCapture: () => {},
  }),
  createText: (text) => ({ text, parent: null, kind: 'text' }),
  createComment: (text) => ({ text, parent: null, kind: 'comment' }),
  setText: (node, text) => { node.text = text; },
  setElementText: (node, text) => {
    node.text = text;
    for (const child of node.children) child.parent = null;
    node.children = [];
  },
  patchProp: (node, key, _previous, next) => { node.props[key] = next; },
  insert: (node, parent, anchor) => {
    if (node.parent) {
      const previousIndex = node.parent.children.indexOf(node);
      if (previousIndex >= 0) node.parent.children.splice(previousIndex, 1);
    }
    node.parent = parent;
    const anchorIndex = anchor ? parent.children.indexOf(anchor) : -1;
    parent.children.splice(anchorIndex < 0 ? parent.children.length : anchorIndex, 0, node);
  },
  remove: (node) => {
    if (!node.parent) return;
    const index = node.parent.children.indexOf(node);
    if (index >= 0) node.parent.children.splice(index, 1);
    node.parent = null;
  },
  parentNode: (node) => node.parent,
  nextSibling: (node) => {
    if (!node.parent) return null;
    const index = node.parent.children.indexOf(node);
    return node.parent.children[index + 1] ?? null;
  },
});

function mountMobileHotkeys(
  withPersistentSlots = false,
  dictationAvailable = false,
  activeDictationStatus: boolean | InlineDictationState = false,
  promptComposerAvailable = false,
  showInlineDictationStatus?: boolean,
  keyboardVisible = false,
) {
  const root: TestElement = {
    tag: 'root', props: {}, children: [], parent: null, text: '', setPointerCapture: () => {},
  };
  const enabled = ref(true);
  const sent: Array<{ bytes: Uint8Array; key: string }> = [];
  const paletteChanges: boolean[] = [];
  let keyboardOpenRequests = 0;
  const composerOpenIntents: Array<'compose' | 'dictate'> = [];
  const Host = defineComponent({
    setup: () => {
      const dictationState = activeDictationStatus === true
        ? {
            phase: 'listening' as const,
            preview: 'git status',
            message: '',
            tone: 'quiet' as const,
          }
        : typeof activeDictationStatus === 'object' ? activeDictationStatus : undefined;
      const slots = {
        ...(withPersistentSlots ? {
          'persistent-status': () => h('span', { 'data-testid': 'status-slot-fixture' }, 'One line of status'),
          'persistent-controls': () => h('button', { 'data-testid': 'control-slot-fixture', 'aria-label': 'Future control' }, '●'),
        } : {}),
        ...(dictationAvailable ? {
          'persistent-accessory': () => h('button', {
            'data-testid': 'inline-dictation-toggle',
            class: 'terminal-dictation-button',
            'aria-label': 'Dictate at terminal cursor',
          }, [h('span', { 'data-testid': 'inline-dictation-dock-label' }, 'Cursor')]),
        } : {}),
      };
      return () => h(MobileHotkeys, {
        enabled: enabled.value,
        dictationAvailable,
        keyboardVisible,
        promptComposerAvailable,
        ...(showInlineDictationStatus === undefined ? {} : { showInlineDictationStatus }),
        dictationState,
        holdThresholdMs: 500,
        onSend: (bytes: Uint8Array, key: string) => sent.push({ bytes, key }),
        onPaletteChange: (open: boolean) => paletteChanges.push(open),
        onKeepKeyboardOpen: () => { keyboardOpenRequests += 1; },
        onOpenComposer: (intent: 'compose' | 'dictate') => { composerOpenIntents.push(intent); },
      }, slots);
    },
  });
  const app = renderer.createApp(Host) as App;
  app.mount(root as unknown as Element);
  return { root, app, sent, paletteChanges, composerOpenRequests: () => composerOpenIntents.length,
    composerOpenIntents: () => [...composerOpenIntents],
    keyboardOpenRequests: () => keyboardOpenRequests,
    setEnabled: (value: boolean) => { enabled.value = value; } };
}

function findAll(root: TestElement, predicate: (node: TestElement) => boolean): TestElement[] {
  const matches: TestElement[] = [];
  const visit = (node: TestNode): void => {
    if (!('tag' in node)) return;
    if (predicate(node)) matches.push(node);
    for (const child of node.children) visit(child);
  };
  for (const child of root.children) visit(child);
  return matches;
}

function findByTestId(root: TestElement, testId: string): TestElement | undefined {
  return findAll(root, (node) => node.props['data-testid'] === testId)[0];
}

function findButton(root: TestElement, attributes: Record<string, unknown>): TestElement {
  const match = findAll(root, (node) => node.tag === 'button' && Object.entries(attributes)
    .every(([key, value]) => node.props[key] === value))[0];
  if (!match) throw new Error(`Could not find mounted button: ${JSON.stringify(attributes)}`);
  return match;
}

function dispatch(node: TestElement, eventName: string, fields: Record<string, unknown>): void {
  const handler = node.props[`on${eventName[0].toUpperCase()}${eventName.slice(1)}`];
  if (typeof handler !== 'function') throw new Error(`No ${eventName} handler on <${node.tag}>`);
  handler({ currentTarget: node, target: node, preventDefault: () => {}, ...fields });
}

function click(node: TestElement, detail = 0): void {
  dispatch(node, 'Click', { detail });
}

function pointer(node: TestElement, eventName: string, fields: { pointerId: number; timeStamp: number }): void {
  dispatch(node, eventName[0].toUpperCase() + eventName.slice(1), {
    button: 0,
    isPrimary: true,
    ...fields,
  });
}
