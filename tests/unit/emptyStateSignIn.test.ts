import { describe, expect, it, vi } from 'vitest';
import { createSSRApp, h } from 'vue';
import { renderToString } from 'vue/server-renderer';
import EmptyStateSignIn from '../../src/components/EmptyStateSignIn.vue';
import emptyStateSignInSource from '../../src/components/EmptyStateSignIn.vue?raw';
import appSource from '../../src/App.vue?raw';

vi.mock('@ui/components/AppIcon.vue', () => ({ default: { render: () => null } }));

// #3047: Google sign-in shipped in #3020 but lived only under Settings →
// Account & sync, so a fresh phone's easiest path to its hosts was invisible.
// The pointer is navigation only; sign-in itself stays in the Account screen.

function renderPointer(props: { hasHosts: boolean; signedIn: boolean | null }): Promise<string> {
  return renderToString(createSSRApp({ render: () => h(EmptyStateSignIn, props) }));
}

describe('first-run sign-in affordance', () => {
  it('shows the sign-in pointer only on an empty, signed-out phone', async () => {
    const shown = await renderPointer({ hasHosts: false, signedIn: false });
    expect(shown).toContain('data-testid="empty-state-sign-in"');
    expect(shown).toContain('Sign in with Google to restore your synced hosts');
    expect(shown).toContain('Account &amp; sync');
    expect(await renderPointer({ hasHosts: false, signedIn: true })).not.toContain('data-testid="empty-state-sign-in"');
    // Sign-in state unknown (no status yet) stays hidden rather than flashing.
    expect(await renderPointer({ hasHosts: false, signedIn: null })).not.toContain('data-testid="empty-state-sign-in"');
    expect(await renderPointer({ hasHosts: true, signedIn: false })).not.toContain('data-testid="empty-state-sign-in"');
  });

  it('points at the shipped Account & sync screen and keeps sign-in in #3020', () => {
    // Navigation only: the component emits; the app opens the existing route.
    expect(emptyStateSignInSource).toContain("emit('signIn')");
    expect(emptyStateSignInSource).not.toContain('googleSync');
    expect(appSource).toContain("import EmptyStateSignIn from './components/EmptyStateSignIn.vue'");
    expect(appSource).toContain("@sign-in=\"navigation.open('settings-account')\"");
    // Sign-in state refreshes wherever the synced host copy does; unknown stays hidden.
    expect(appSource).toContain('const accountSignedIn = ref<boolean | null>(null)');
    expect(appSource).toContain('async function refreshAccountState()');
    expect(appSource).toContain('(await androidSync().status()).signedIn');
    expect(appSource).toContain("if (route === 'home') void refreshAccountState();");
  });
});
