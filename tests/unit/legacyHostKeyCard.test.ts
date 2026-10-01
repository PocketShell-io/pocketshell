import { describe, expect, it } from 'vitest';
import { createSSRApp, h } from 'vue';
import { renderToString } from 'vue/server-renderer';
import type { PendingHostKeyDecision } from '@pocketshell/core';
import HostKeyTrustPrompt from '@ui/app/components/HostKeyTrustPrompt.vue';
import { hostKeyAnswer, hostKeyCard, hostKeyRefusalMessage } from '@/session/hostKeyCard';

// #2953: the legacy phone screen shows the shared host-key card. A CHANGED key
// is a refusal there too — the same verdict the shared shell gives.

const PRESENTED = { keyType: 'ssh-ed25519', keyB64: 'AQIDBA==', fingerprintSha256: 'SHA256:newKeyNewKeyNewKey' };
const TRUSTED = 'SHA256:oldKeyOldKeyOldKey';
const TARGET = { hostname: '10.0.2.2', port: '2246', username: 'testuser' };

const firstContact: PendingHostKeyDecision = {
  hostId: 'testuser@10.0.2.2:2246', hostLabel: '10.0.2.2', reason: 'unknown', presented: PRESENTED, previouslyTrusted: null,
};
const changed: PendingHostKeyDecision = {
  ...firstContact, reason: 'mismatch', previouslyTrusted: { kind: 'sha256-fingerprint', fingerprintSha256: TRUSTED },
};

function renderCard(decision: PendingHostKeyDecision): Promise<string> {
  const card = hostKeyCard(decision, TARGET);
  return renderToString(createSSRApp({
    render: () => h(HostKeyTrustPrompt, { request: card.request, trustedFingerprintSha256: card.trustedFingerprintSha256 }),
  }));
}

describe('legacy screen host-key card', () => {
  it('asks on first contact with the three shared answers', async () => {
    const html = await renderCard(firstContact);
    expect(html).toContain('data-testid="host-key-decision"');
    expect(html).toContain('not seen before');
    expect(html).toContain('data-testid="trust-host-key"');
    expect(html).toContain('data-testid="trust-host-key-once"');
    expect(hostKeyAnswer(firstContact, 'accept-always')).toBe('accept-always');
    expect(hostKeyAnswer(firstContact, 'accept-once')).toBe('accept-once');
  });

  it('refuses a changed key: both fingerprints, no first-contact wording, no trust action', async () => {
    const card = hostKeyCard(changed, TARGET);
    expect(card.trustedFingerprintSha256).toBe(TRUSTED);
    const html = await renderCard(changed);
    expect(html).toContain('data-testid="host-key-changed"');
    expect(html).not.toContain('not seen before');
    expect(html).not.toContain('Trust and remember');
    expect(html).not.toContain('data-testid="trust-host-key"');
    expect(html).not.toContain('data-testid="trust-host-key-once"');
    expect(html).toContain(TRUSTED);
    expect(html).toContain(PRESENTED.fingerprintSha256);
    // Even a stray accept is acted on as a refusal.
    expect(hostKeyAnswer(changed, 'accept-always')).toBe('reject');
    expect(hostKeyAnswer(changed, 'accept-once')).toBe('reject');
    expect(hostKeyRefusalMessage(changed)).toBe(
      `Host key for 10.0.2.2 has changed — connection refused. It now presents ${PRESENTED.fingerprintSha256}`
      + ` instead of the trusted ${TRUSTED}. No SSH connection remains open.`,
    );
    expect(hostKeyRefusalMessage(firstContact)).toBe('Host key was not trusted. No SSH connection remains open.');
  });
});
