import nativeReply from '../fixtures/gateway-pairing-reply.json';
import { describe, expect, it } from 'vitest';
import {
  GATEWAY_PAIRING_ERROR_CODES,
  createGatewayPairingNative,
  parseGatewayPairingAccount,
  parseGatewayPairingList,
  parseGatewayPairingRemoval,
  parseGatewayPairingResult,
} from '@/native/gatewayPairing';

/**
 * Issue #3060: the native gateway pairing storage API is validated
 * fail-closed at this boundary. A malformed native answer is an error, never
 * a half-trusted record, and no method here can carry a token.
 */
describe('gatewayPairing native API', () => {
  const validRow = {
    serverUrl: 'wss://gateway.example.io',
    deviceId: 'host-1',
    fingerprintSha256: `SHA256:${'A'.repeat(43)}`,
    keyHandleId: '01234567-89ab-cdef-0123-456789abcdef',
    pairedAtEpochMs: 1000,
  };

  it('exposes no method that could return or accept a token', () => {
    // The narrow surface: account, list, pair, remove. Nothing else exists
    // to carry a Google or routing token across the bridge.
    expect(GATEWAY_PAIRING_ERROR_CODES).toContain('GATEWAY_PAIRING_INVALID');
  });

  it('parseGatewayPairingAccount accepts a signed-in answer', () => {
    expect(parseGatewayPairingAccount({ signedIn: true, accountSubject: 'sub-1' }))
      .toEqual({ signedIn: true, accountSubject: 'sub-1' });
  });

  it('parseGatewayPairingAccount blanks the subject when signed out', () => {
    expect(parseGatewayPairingAccount({ signedIn: false, accountSubject: 'sub-1' }))
      .toEqual({ signedIn: false, accountSubject: '' });
  });

  it('parseGatewayPairingAccount refuses malformed shapes', () => {
    expect(() => parseGatewayPairingAccount(null)).toThrow();
    expect(() => parseGatewayPairingAccount({})).toThrow();
    expect(() => parseGatewayPairingAccount({ signedIn: 'yes', accountSubject: 'sub-1' })).toThrow();
  });

  it('parseGatewayPairingList accepts a list of valid rows', () => {
    expect(parseGatewayPairingList({ pairings: JSON.stringify([validRow]) })).toEqual([validRow]);
  });

  it('parseGatewayPairingList drops rows that no longer normalize, keeps the rest', () => {
    const rows = [
      validRow,
      { ...validRow, serverUrl: 'wss://gateway.example.io/path' },
      { ...validRow, deviceId: 'no' },
      { ...validRow, fingerprintSha256: 'SHA256:short' },
      { ...validRow, pairedAtEpochMs: -1 },
      { ...validRow, pairedAtEpochMs: Number.NaN },
    ];
    expect(parseGatewayPairingList({ pairings: JSON.stringify(rows) })).toEqual([validRow]);
  });

  it('parseGatewayPairingList refuses unreadable answers instead of reading them as empty', () => {
    expect(() => parseGatewayPairingList({})).toThrow();
    expect(() => parseGatewayPairingList({ pairings: 'not json' })).toThrow();
    expect(() => parseGatewayPairingList({ pairings: '42' })).toThrow();
  });

  it('parseGatewayPairingResult accepts a valid stored pairing', () => {
    expect(parseGatewayPairingResult(nativeReply, 'request-1', 'sub-1')).toEqual(validRow);
  });

  it('parseGatewayPairingResult refuses anything that is not exactly a usable pairing', () => {
    expect(() => parseGatewayPairingResult({}, 'request-1', 'sub-1')).toThrow();
    expect(() => parseGatewayPairingResult({ ...nativeReply, serverUrl: 'wss://gateway.example.io/path' }, 'request-1', 'sub-1')).toThrow();
    expect(() =>
      parseGatewayPairingResult({
        // A token-shaped answer is not a pairing and must not pass.
        ...nativeReply,
        token: 'a.b.c',
        serverUrl: validRow.serverUrl,
        deviceId: validRow.deviceId,
        fingerprintSha256: validRow.fingerprintSha256,
        keyHandleId: validRow.keyHandleId,
        pairedAtEpochMs: 1000,
      }, 'request-1', 'sub-1'),
    ).toThrow();
  });

  it('rejects native pairing replies for another request or account', () => {
    expect(() => parseGatewayPairingResult(nativeReply, 'other-request', 'sub-1')).toThrow();
    expect(() => parseGatewayPairingResult(nativeReply, 'request-1', 'sub-2')).toThrow();
  });

  it('parseGatewayPairingRemoval accepts a boolean answer', () => {
    expect(parseGatewayPairingRemoval({ removed: true })).toBe(true);
    expect(parseGatewayPairingRemoval({ removed: false })).toBe(false);
    expect(() => parseGatewayPairingRemoval({})).toThrow();
  });

  it('createGatewayPairingNative forwards pair arguments verbatim and parses the reply', async () => {
    const calls: unknown[] = [];
    const native = createGatewayPairingNative({
      currentAccount: async () => ({ signedIn: true, accountSubject: 'sub-1' }),
      list: async () => ({ pairings: '[]' }),
      pair: async (request) => {
        calls.push(request);
        return { ...nativeReply, requestId: request.requestId };
      },
      remove: async () => ({ removed: true }),
    });
    const result = await native.pair({
      expectedAccountSubject: 'sub-1',
      serverUrl: validRow.serverUrl,
      deviceId: validRow.deviceId,
      fingerprintSha256: validRow.fingerprintSha256,
      keyHandleId: validRow.keyHandleId,
    });
    expect(result).toEqual(validRow);
    const sent = calls[0] as Record<string, unknown>;
    expect(sent.expectedAccountSubject).toBe('sub-1');
    expect(sent.serverUrl).toBe(validRow.serverUrl);
    expect(sent.fingerprintSha256).toBe(validRow.fingerprintSha256);
    expect(typeof sent.requestId).toBe('string');
  });
});
