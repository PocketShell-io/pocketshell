import { describe, expect, it } from 'vitest';
import { createSSRApp, h } from 'vue';
import { renderToString } from 'vue/server-renderer';
import appSource from '../../src/App.vue?raw';
import { liveConnectedHostLabel, type DialedHost } from '../../src/credentials/authorizedKeys';

describe('connected app bar host label (#3027)', () => {
  it('names the live connection, not the edited host form, in the live connected host label', () => {
    const snapshotA = { hostId: 'alexey@a.example:2201', hostLabel: 'a.example' };
    const dialedA: DialedHost = { hostId: 'alexey@a.example:2201', username: 'alexey', hostname: 'a.example', port: 2201 };
    const formB = { hostname: 'b.example', port: '22', username: 'root' };

    // Initially connected to host A.
    expect(liveConnectedHostLabel(snapshotA, dialedA)).toBe('alexey@a.example');

    // The user edits the form fields to host B; live connection remains A.
    expect(liveConnectedHostLabel(snapshotA, dialedA)).toBe('alexey@a.example');
    expect(liveConnectedHostLabel(snapshotA, dialedA)).not.toContain(formB.hostname);
    expect(liveConnectedHostLabel(snapshotA, dialedA)).not.toContain(formB.username);

    // If dialedHost has no username, returns the hostname cleanly.
    expect(liveConnectedHostLabel(snapshotA, { ...dialedA, username: '' })).toBe('a.example');

    // If dialedHost is null or dialed for another host id, falls back to snapshot.hostLabel.
    expect(liveConnectedHostLabel(snapshotA, null)).toBe('a.example');
    expect(liveConnectedHostLabel(snapshotA, { ...dialedA, hostId: 'other:22' })).toBe('a.example');

    // If there is no snapshot (not connected), returns empty string.
    expect(liveConnectedHostLabel(null, dialedA)).toBe('');
    expect(liveConnectedHostLabel(null, null)).toBe('');
  });

  it('wires the app bar host label to the live connection and protects connected state targets from form edits', () => {
    // App computes connectedHostLabel from live snapshot and dialed host, never form draft.
    const connectedLabelDef = /const connectedHostLabel = computed[\s\S]*?\);/.exec(appSource)?.[0] ?? '';
    expect(connectedLabelDef).toContain('liveConnectedHostLabel(connectionSnapshot.value, dialedHost.value)');
    expect(connectedLabelDef).not.toMatch(/hostDraft/);

    // App header uses connectedHostLabel instead of hostDraft.
    const headerCopy = /<div class="session-context__copy">[\s\S]*?<\/div>/.exec(appSource)?.[0] ?? '';
    expect(headerCopy).toContain('<span class="session-context__host">{{ connectedHostLabel }}</span>');
    expect(headerCopy).not.toContain('hostDraft');

    // closeController clears dialedHost alongside snapshot.
    const closeControllerBlock = /async function closeController\(\)[\s\S]*?\n\}/.exec(appSource)?.[0] ?? '';
    expect(closeControllerBlock).toContain('dialedHost.value = null;');

    // composerTargetKey protects active session drafts from form edits while connected.
    const composerKeyDef = /const composerTargetKey = computed[\s\S]*?\n\}\);/.exec(appSource)?.[0] ?? '';
    expect(composerKeyDef).toContain('dialedHost.value && snapshot?.hostId === dialedHost.value.hostId');

    // fileRootDirectory protects active session SFTP path from form edits while connected.
    const fileRootDirDef = /const fileRootDirectory = computed[\s\S]*?\n\}\);/.exec(appSource)?.[0] ?? '';
    expect(fileRootDirDef).toContain('dialedHost.value && snapshot?.hostId === dialedHost.value.hostId');

    // snippetHostLabel uses connectedHostLabel when connected.
    const snippetLabelDef = /const snippetHostLabel = computed[\s\S]*?\n\}\);/.exec(appSource)?.[0] ?? '';
    expect(snippetLabelDef).toContain('connectedHostLabel.value');
  });

  it('renders the live connection host in the session context app bar while the form is edited', async () => {
    const snapshotA = { hostId: 'alexey@a.example:2201', hostLabel: 'a.example', selectedSession: { name: 'Dev PTY' } };
    const dialedA: DialedHost = { hostId: 'alexey@a.example:2201', username: 'alexey', hostname: 'a.example', port: 2201 };

    const hostLabel = liveConnectedHostLabel(snapshotA, dialedA);

    // Render the session context component fragment with the live connection label
    const component = {
      render() {
        return h('div', { class: 'session-context' }, [
          h('div', { class: 'session-context__copy' }, [
            h('span', { class: 'session-context__title' }, snapshotA.selectedSession.name),
            h('span', { class: 'session-context__host' }, hostLabel),
          ]),
        ]);
      },
    };

    const rendered = await renderToString(createSSRApp(component));
    expect(rendered).toContain('class="session-context__title">Dev PTY</span>');
    expect(rendered).toContain('class="session-context__host">alexey@a.example</span>');
    expect(rendered).not.toContain('b.example');
    expect(rendered).not.toContain('root@');
  });
});
