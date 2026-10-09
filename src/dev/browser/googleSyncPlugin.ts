/**
 * Browser dev mode: a stand-in for the native GoogleSync plugin (#3020) so the
 * account button, sign-in and the "From your account" picker group (#3063)
 * can be looked at without a phone. Sign-in is instant (no chooser), and the
 * account holds an envelope the page encrypts on first read with
 * {@link DEV_SYNC_PASSPHRASE}, so unlocking works exactly as on a phone.
 * Nothing here holds a token; the real plugin never hands one to JS either.
 */
import { encryptToEnvelope } from '../../sync/syncCrypto';
import type { DevPlugin } from './nativeBridge';

export const DEV_SYNC_PASSPHRASE = 'dev passphrase';
export const DEV_SYNC_EMAIL = 'dev@example.com';

/** The account's hosts: one the mock host source also has, two only the account has. */
export const DEV_SYNC_ACCOUNT = {
  hosts: [
    { name: 'mock-devbox', hostname: 'devbox.mock', port: 22, user: 'dev' },
    { name: 'hetzner', hostname: '135.181.114.209', port: 22, user: 'alexey', identityFile: '~/.ssh/id_ed25519' },
    { name: 'laptop', hostname: 'laptop.lan', port: 2222, user: 'alexey' },
  ],
};

export function createGoogleSyncPlugin(options: { iterations?: number } = {}): DevPlugin {
  let signedIn = false;
  let slot: { version: number; data: string } | null = null;
  const status = () => ({ signedIn, email: signedIn ? DEV_SYNC_EMAIL : null, packageName: 'com.pocketshell.app.dev' });
  return {
    methods: {
      status: () => status(),
      signIn: () => {
        signedIn = true;
        return status();
      },
      signOut: () => {
        signedIn = false;
        return status();
      },
      request: async (request) => {
        if (!signedIn) return { status: 401, body: '{"message":"Unauthorized"}' };
        slot ??= {
          version: 1,
          data: await encryptToEnvelope(JSON.stringify(DEV_SYNC_ACCOUNT), DEV_SYNC_PASSPHRASE, options.iterations ?? 1000),
        };
        if (request.method === 'GET') return { status: 200, body: JSON.stringify({ slot: 'main', ...slot }) };
        const body = JSON.parse(String(request.body)) as { data: string; version: number };
        if (body.version !== slot.version) {
          return { status: 409, body: JSON.stringify({ message: 'version conflict', currentVersion: slot.version }) };
        }
        slot = { version: slot.version + 1, data: body.data };
        return { status: 200, body: JSON.stringify({ slot: 'main', version: slot.version }) };
      },
    },
  };
}
