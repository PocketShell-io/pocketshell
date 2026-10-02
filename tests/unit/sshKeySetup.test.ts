import * as Vue from 'vue';
import { createRenderer, nextTick, ssrContextKey } from 'vue';
import { compileScript, compileTemplate, parse } from '@vue/compiler-sfc';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CredentialKeyManager } from '../../src/credentials/keyManagement';
import {
  AUTHORIZED_KEY_INSTALL_COMMAND_CHARSET,
  buildAuthorizedKeyInstallCommand,
  installPublicKeyOnHost,
  liveAuthorizedKeyInstallHost,
  parseAuthorizedKeyInstallOutput,
} from '../../src/credentials/authorizedKeys';
import { parseSshPublicKeyLine, type NativeSshKeyVaultPlugin, type SshKeyMetadata } from '../../src/native/sshKeyVault';
import SshKeysScreen from '../../src/components/SshKeysScreen.vue';
import sshKeysScreenSource from '../../src/components/SshKeysScreen.vue?raw';
import appSource from '../../src/App.vue?raw';

vi.mock('@ui/components/AppIcon.vue', () => ({ default: { render: () => null } }));

// The same client-render harness as sshKeyVault.test.ts: the real component
// script with its template compiled for a host-object renderer.
const descriptor = parse(sshKeysScreenSource, { filename: 'src/components/SshKeysScreen.vue' }).descriptor;
const script = compileScript(descriptor, { id: 'ssh-key-setup-unit' });
const template = compileTemplate({
  source: descriptor.template!.content,
  filename: 'src/components/SshKeysScreen.vue',
  id: 'ssh-key-setup-unit',
  compilerOptions: { bindingMetadata: script.bindings },
});
const vueImports = template.code.match(/^import \{([^}]+)\} from "vue"\s*$/m)?.[1];
if (!vueImports) throw new Error('Could not compile the SSH key screen template.');
const vueBindings = vueImports.split(',').map((entry) => {
  const [name, alias] = entry.trim().split(/\s+as\s+/);
  return alias ? `${name}: ${alias}` : name;
}).join(', ');
const renderBody = template.code
  .replace(/^import \{[^}]+\} from "vue"\s*$/m, '')
  .replace('export function render', 'function render');
const clientRender = new Function('Vue', `const { ${vueBindings} } = Vue;\n${renderBody}\nreturn render;`)(
  { ...Vue, vModelText: {}, vModelSelect: {} },
) as () => unknown;
const Screen = { ...SshKeysScreen, render: clientRender };

interface HostNode {
  type: string;
  text?: string;
  value?: string;
  props: Record<string, unknown>;
  children: HostNode[];
  parent: HostNode | null;
}

function hostNode(type: string, text?: string): HostNode {
  return { type, text, props: {}, children: [], parent: null };
}

const renderer = createRenderer<HostNode, HostNode>({
  createElement: (type) => hostNode(type),
  createText: (text) => hostNode('#text', text),
  createComment: (text) => hostNode('#comment', text),
  setText: (node, text) => { node.text = text; },
  setElementText: (node, text) => { node.text = text; node.children = []; },
  patchProp: (node, key, _previous, next) => {
    if (next == null) delete node.props[key];
    else node.props[key] = next;
  },
  insert: (node, parent, anchor) => {
    if (node.parent) {
      const previous = node.parent.children.indexOf(node);
      if (previous >= 0) node.parent.children.splice(previous, 1);
    }
    const anchorIndex = anchor ? parent.children.indexOf(anchor) : -1;
    if (anchorIndex < 0) parent.children.push(node);
    else parent.children.splice(anchorIndex, 0, node);
    node.parent = parent;
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

function find(root: HostNode, testId: string): HostNode | undefined {
  if (root.props['data-testid'] === testId) return root;
  for (const child of root.children) {
    const match = find(child, testId);
    if (match) return match;
  }
  return undefined;
}

function text(root: HostNode): string {
  return `${root.text ?? ''}${root.children.map(text).join('')}`;
}

async function settle() {
  for (let index = 0; index < 5; index += 1) {
    await Promise.resolve();
    await nextTick();
  }
}

async function fire(root: HostNode, testId: string, event = 'onClick'): Promise<void> {
  const node = find(root, testId);
  expect(node, `expected rendered control ${testId}`).toBeDefined();
  const handler = node!.props[event];
  expect(handler, `expected ${testId} to handle ${event}`).toBeDefined();
  const callbacks = Array.isArray(handler) ? handler : [handler];
  for (const callback of callbacks) await (callback as (event: Event) => unknown)(new Event(event.slice(2).toLowerCase()));
  await settle();
}

const ED25519_LINE = 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIN7osVCLDIy5aFOk8IaZk040AFw1V+YDXgr8L+zQE/5k Generated key';
const PASTED_PRIVATE_KEY = '-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n-----END OPENSSH PRIVATE KEY-----\n';

const metadata: SshKeyMetadata = {
  handleId: '00000000-0000-4000-8000-000000000021',
  label: 'Generated key',
  algorithm: 'ssh-ed25519',
  fingerprintSha256: `SHA256:${'C'.repeat(43)}`,
  passphraseRequired: false,
  createdAt: 1_700_000_000_000,
};

function nativeVault(overrides: Partial<NativeSshKeyVaultPlugin> = {}): NativeSshKeyVaultPlugin {
  return {
    listKeys: vi.fn(async () => ({ keys: [metadata] })),
    pickKeyDocument: vi.fn(async () => ({ cancelled: true })),
    importPickedKey: vi.fn(async () => metadata),
    importDocumentUri: vi.fn(async () => metadata),
    importKeyText: vi.fn(async () => metadata),
    publicKey: vi.fn(async () => ({ handleId: metadata.handleId, publicKey: ED25519_LINE })),
    copyPublicKey: vi.fn(async () => ({ copied: true })),
    sharePublicKey: vi.fn(async () => ({ shared: true })),
    generateKey: vi.fn(async () => metadata),
    deleteKey: vi.fn(async () => ({ deleted: true })),
    importLegacyKeys: vi.fn(async () => ({ keys: [], failures: [] })),
    ...overrides,
  } as unknown as NativeSshKeyVaultPlugin;
}

function mount(props: Record<string, unknown>) {
  const root = hostNode('root');
  const app = renderer.createApp(Screen, { loadError: '', selectedHandleId: '', keys: [], ...props });
  app.provide(ssrContextKey, { modules: new Set<string>() });
  app.mount(root);
  return { root, unmount: () => app.unmount() };
}

describe('mobile SSH key setup (#3021)', () => {
  it('sends pasted key text straight to the native vault and returns only public metadata', async () => {
    const vault = nativeVault();
    const manager = new CredentialKeyManager(vault);
    await expect(manager.importText({ text: PASTED_PRIVATE_KEY, label: '  ', passphrase: 'pass' })).resolves.toEqual(metadata);
    expect(vault.importKeyText).toHaveBeenCalledWith({ text: PASTED_PRIVATE_KEY, label: 'Pasted SSH key', passphrase: 'pass' });

    await expect(manager.importText({ text: '  \n', label: 'x' })).rejects.toThrow(/paste a private key/i);
    expect(vault.importKeyText).toHaveBeenCalledTimes(1);

    const leaky = new CredentialKeyManager(nativeVault({
      importKeyText: vi.fn(async () => ({ ...metadata, privateKeyPem: PASTED_PRIVATE_KEY })),
    }));
    await expect(leaky.importText({ text: PASTED_PRIVATE_KEY, label: 'x' })).rejects.toThrow(/invalid public metadata/i);
  });

  it('accepts only one-line Ed25519/RSA public keys from the native boundary', () => {
    expect(parseSshPublicKeyLine({ publicKey: ED25519_LINE }, 'ssh-ed25519')).toBe(ED25519_LINE);
    const rsa = `ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQ${'D'.repeat(300)}== laptop`;
    expect(parseSshPublicKeyLine({ publicKey: rsa }, 'ssh-rsa')).toBe(rsa);
    for (const bad of [
      `${ED25519_LINE}\nssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIN7osVCLDIy5aFOk evil`,
      PASTED_PRIVATE_KEY,
      'ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTY= x',
      'ssh-ed25519 not-base64! x',
      'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIN7osVCLDIy5aFOk8IaZk040AFw1V+YDXgr8L+zQE/5k',
    ]) {
      expect(() => parseSshPublicKeyLine({ publicKey: bad })).toThrow(/invalid public key/i);
    }
    expect(() => parseSshPublicKeyLine({ publicKey: ED25519_LINE }, 'ssh-rsa')).toThrow(/invalid public key/i);
  });

  it('clears the pasted private key from the field after a successful import', async () => {
    const vault = nativeVault();
    const onSelect = vi.fn();
    const view = mount({ manager: new CredentialKeyManager(vault), onSelect, onRefresh: vi.fn() });
    try {
      const textarea = find(view.root, 'ssh-key-paste-text')!;
      expect(textarea, 'paste is the default way to add a key').toBeDefined();
      expect(find(view.root, 'import-pasted-ssh-key')!.props.disabled).toBe(true);
      textarea.value = PASTED_PRIVATE_KEY;
      await fire(view.root, 'ssh-key-paste-text', 'onInput');
      expect(find(view.root, 'import-pasted-ssh-key')!.props.disabled).toBe(false);
      await fire(view.root, 'import-pasted-ssh-key');
      expect(vault.importKeyText).toHaveBeenCalledWith(expect.objectContaining({ text: PASTED_PRIVATE_KEY }));
      expect(textarea.value).toBe('');
      expect(onSelect).toHaveBeenCalledWith(metadata.handleId);
      expect(text(view.root)).not.toContain('PRIVATE KEY');
    } finally {
      view.unmount();
    }
  });

  it('clears the pasted text and explains the error when native validation rejects it', async () => {
    const vault = nativeVault({
      importKeyText: vi.fn(async () => { throw new Error('This is a public key. Paste the private key instead; it starts with a BEGIN line.'); }),
    });
    const view = mount({ manager: new CredentialKeyManager(vault), onSelect: vi.fn(), onRefresh: vi.fn() });
    try {
      const textarea = find(view.root, 'ssh-key-paste-text')!;
      textarea.value = ED25519_LINE;
      await fire(view.root, 'ssh-key-paste-text', 'onInput');
      await fire(view.root, 'import-pasted-ssh-key');
      expect(textarea.value).toBe('');
      const error = text(find(view.root, 'ssh-key-error')!);
      expect(error).toContain('This is a public key');
      expect(error).toContain('paste it again');
    } finally {
      view.unmount();
    }
  });

  it('keeps a generated key on screen with its public key, copy and share', async () => {
    const vault = nativeVault();
    const onSelect = vi.fn();
    const view = mount({ manager: new CredentialKeyManager(vault), keys: [metadata], onSelect, onRefresh: vi.fn() });
    try {
      await fire(view.root, 'ssh-key-generate-tab');
      await fire(view.root, 'generate-ssh-key');
      expect(onSelect).toHaveBeenCalledWith(metadata.handleId, true);
      expect(text(find(view.root, `ssh-public-key-${metadata.handleId}`)!)).toBe(ED25519_LINE);
      expect(find(view.root, `install-ssh-public-key-${metadata.handleId}`)).toBeUndefined();
      expect(find(view.root, `ssh-public-key-install-hint-${metadata.handleId}`)).toBeDefined();
      await fire(view.root, `copy-ssh-public-key-${metadata.handleId}`);
      expect(vault.copyPublicKey).toHaveBeenCalledWith({ handleId: metadata.handleId });
      expect(text(find(view.root, 'ssh-public-key-message')!)).toContain('copied');
      await fire(view.root, `share-ssh-public-key-${metadata.handleId}`);
      expect(vault.sharePublicKey).toHaveBeenCalledWith({ handleId: metadata.handleId });
    } finally {
      view.unmount();
    }
  });

  it('shows the public key of an imported key from its row', async () => {
    const imported = { ...metadata, label: 'Imported key', passphraseRequired: true };
    const vault = nativeVault();
    const view = mount({ manager: new CredentialKeyManager(vault), keys: [imported], onSelect: vi.fn(), onRefresh: vi.fn() });
    try {
      expect(find(view.root, `ssh-public-key-panel-${imported.handleId}`)).toBeUndefined();
      await fire(view.root, `toggle-ssh-public-key-${imported.handleId}`);
      expect(vault.publicKey).toHaveBeenCalledWith({ handleId: imported.handleId });
      expect(text(find(view.root, `ssh-public-key-${imported.handleId}`)!)).toBe(ED25519_LINE);
      await fire(view.root, `toggle-ssh-public-key-${imported.handleId}`);
      expect(find(view.root, `ssh-public-key-panel-${imported.handleId}`)).toBeUndefined();
    } finally {
      view.unmount();
    }
  });

  it('asks for the passphrase when the native vault needs it to read a public key', async () => {
    const needsPassphrase = Object.assign(new Error('Enter this key\'s passphrase to show its public key.'), { code: 'KEY_PASSPHRASE_REQUIRED' });
    const publicKey = vi.fn()
      .mockRejectedValueOnce(needsPassphrase)
      .mockResolvedValueOnce({ handleId: metadata.handleId, publicKey: ED25519_LINE });
    const vault = nativeVault({ publicKey });
    const view = mount({ manager: new CredentialKeyManager(vault), keys: [metadata], onSelect: vi.fn(), onRefresh: vi.fn() });
    try {
      await fire(view.root, `toggle-ssh-public-key-${metadata.handleId}`);
      const field = find(view.root, `ssh-public-key-passphrase-${metadata.handleId}`);
      expect(field).toBeDefined();
      expect(text(find(view.root, 'ssh-public-key-error')!)).toContain('passphrase');
    } finally {
      view.unmount();
    }
  });

  it('installs on the connected host only after confirmation and reports an existing key', async () => {
    const install = vi.fn()
      .mockResolvedValueOnce('installed')
      .mockResolvedValueOnce('already-present');
    const view = mount({
      manager: new CredentialKeyManager(nativeVault()),
      keys: [metadata],
      hostInstall: { hostLabel: 'testuser@fixture', install },
      onSelect: vi.fn(),
      onRefresh: vi.fn(),
    });
    try {
      await fire(view.root, `toggle-ssh-public-key-${metadata.handleId}`);
      expect(text(find(view.root, `install-ssh-public-key-${metadata.handleId}`)!)).toContain('testuser@fixture');
      await fire(view.root, `install-ssh-public-key-${metadata.handleId}`);
      expect(text(find(view.root, 'ssh-key-install-confirmation')!)).toContain('authorized_keys');
      expect(install).not.toHaveBeenCalled();
      await fire(view.root, 'cancel-install-ssh-key');
      expect(find(view.root, 'ssh-key-install-confirmation')).toBeUndefined();
      expect(install).not.toHaveBeenCalled();

      await fire(view.root, `install-ssh-public-key-${metadata.handleId}`);
      await fire(view.root, 'confirm-install-ssh-key');
      expect(install).toHaveBeenCalledWith(ED25519_LINE);
      expect(text(find(view.root, 'ssh-public-key-message')!)).toContain('Installed on testuser@fixture');

      await fire(view.root, `install-ssh-public-key-${metadata.handleId}`);
      await fire(view.root, 'confirm-install-ssh-key');
      expect(text(find(view.root, 'ssh-public-key-message')!)).toContain('already in ~/.ssh/authorized_keys');
    } finally {
      view.unmount();
    }
  });

  it('names the live connection, not the edited host form, in the install button and confirmation', async () => {
    // Connected to A; the user has since typed B into the still-editable form.
    const snapshotA = { hostId: 'alexey@a.example:2201', hostLabel: 'a.example', connectionId: 'conn-a', generationId: 'gen-1' };
    const dialedA = { hostId: 'alexey@a.example:2201', username: 'alexey', hostname: 'a.example', port: 2201 };
    const formB = { hostname: 'b.example', port: '22', username: 'root' };
    const live = liveAuthorizedKeyInstallHost(snapshotA, dialedA)!;
    expect(live).toEqual({ connection: { connectionId: 'conn-a', generationId: 'gen-1' }, hostLabel: 'alexey@a.example:2201' });
    // A controller that dialed some other host id falls back to the snapshot's own label;
    // no live connection means no install target at all.
    expect(liveAuthorizedKeyInstallHost(snapshotA, { ...dialedA, hostId: `${formB.username}@${formB.hostname}:22` })!.hostLabel).toBe('a.example');
    expect(liveAuthorizedKeyInstallHost({ ...snapshotA, connectionId: null }, dialedA)).toBeNull();

    // App wires the target from the live snapshot + dialed host, never the form draft.
    const appTarget = /const sshKeyHostInstall = computed[\s\S]*?\n\}\);/.exec(appSource)?.[0] ?? '';
    expect(appTarget).toContain('liveAuthorizedKeyInstallHost(connectionSnapshot.value, dialedHost.value)');
    expect(appTarget).toContain('live.connection');
    expect(appTarget).not.toMatch(/hostDraft|currentConnectionRef/);
    expect(appSource).toMatch(/dialedHost\.value = \{ hostId: host\.hostId, username: host\.username, hostname: host\.hostname, port: host\.port \};\n\s+let result;\n\s+try \{\n\s+result = await next\.connect\(host\);/);

    const install = vi.fn().mockResolvedValue('installed');
    const view = mount({
      manager: new CredentialKeyManager(nativeVault()),
      keys: [metadata],
      hostInstall: { hostLabel: live.hostLabel, install },
      onSelect: vi.fn(),
      onRefresh: vi.fn(),
    });
    try {
      await fire(view.root, `toggle-ssh-public-key-${metadata.handleId}`);
      const button = text(find(view.root, `install-ssh-public-key-${metadata.handleId}`)!);
      expect(button).toContain('alexey@a.example:2201');
      expect(button).not.toContain('b.example');
      await fire(view.root, `install-ssh-public-key-${metadata.handleId}`);
      const confirmation = text(find(view.root, 'ssh-key-install-confirmation')!);
      expect(confirmation).toContain('alexey@a.example:2201');
      expect(confirmation).not.toContain('b.example');
      await fire(view.root, 'confirm-install-ssh-key');
      expect(install).toHaveBeenCalledWith(ED25519_LINE);
      expect(text(find(view.root, 'ssh-public-key-message')!)).toContain('Installed on alexey@a.example:2201');
    } finally {
      view.unmount();
    }
  });

  // authorized_keys install command, executed for real by a local POSIX sh.
  const homes: string[] = [];
  afterEach(() => {
    for (const home of homes.splice(0)) rmSync(home, { recursive: true, force: true });
  });

  function freshHome(): string {
    const home = mkdtempSync(join(tmpdir(), 'pocketshell-authorized-keys-'));
    homes.push(home);
    return home;
  }

  // Run exactly what the exec channel sends, parsed by a login shell first.
  function runRemote(command: string, home: string, shell = '/bin/sh') {
    // cwd is the fresh home too, so a relative canary a label manages to
    // `touch` would land where readdirSync(home) sees it.
    const result = spawnSync(shell, ['-c', command], { cwd: home, env: { HOME: home, PATH: process.env.PATH ?? '/usr/bin:/bin' }, encoding: 'utf8' });
    return { exitCode: result.status, stdout: result.stdout, stderr: result.stderr, timedOut: false };
  }

  it('creates ~/.ssh/authorized_keys with private permissions and never duplicates the key', () => {
    const home = freshHome();
    const command = buildAuthorizedKeyInstallCommand(ED25519_LINE);
    expect(parseAuthorizedKeyInstallOutput(runRemote(command, home))).toBe('installed');
    const file = join(home, '.ssh', 'authorized_keys');
    expect(readFileSync(file, 'utf8')).toBe(`${ED25519_LINE}\n`);
    expect(statSync(file).mode & 0o777).toBe(0o600);
    expect(statSync(join(home, '.ssh')).mode & 0o777).toBe(0o700);
    expect(parseAuthorizedKeyInstallOutput(runRemote(command, home, 'bash'))).toBe('already-present');
    expect(readFileSync(file, 'utf8')).toBe(`${ED25519_LINE}\n`);
  });

  it('treats the same key with options or another comment as present and keeps the last line intact', () => {
    const home = freshHome();
    mkdirSync(join(home, '.ssh'), { mode: 0o700 });
    const file = join(home, '.ssh', 'authorized_keys');
    const [type, blob] = ED25519_LINE.split(' ');
    writeFileSync(file, `# ${type} ${blob} commented out\nfrom="10.0.0.1" ${type} ${blob} old-laptop`, { mode: 0o600 });
    expect(parseAuthorizedKeyInstallOutput(runRemote(buildAuthorizedKeyInstallCommand(ED25519_LINE), home))).toBe('already-present');

    writeFileSync(file, 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAICOvqDO4DOoI9EDsD+upeyONJ40vO/z7oiZTFYZJU26S other', { mode: 0o600 });
    expect(parseAuthorizedKeyInstallOutput(runRemote(buildAuthorizedKeyInstallCommand(ED25519_LINE), home))).toBe('installed');
    expect(readFileSync(file, 'utf8').split('\n')).toEqual([
      'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAICOvqDO4DOoI9EDsD+upeyONJ40vO/z7oiZTFYZJU26S other',
      ED25519_LINE,
      '',
    ]);

    // A key that is only present inside a comment line is not authorized yet.
    writeFileSync(file, `# ${type} ${blob} commented out\n`, { mode: 0o600 });
    expect(parseAuthorizedKeyInstallOutput(runRemote(buildAuthorizedKeyInstallCommand(ED25519_LINE), home))).toBe('installed');
  });

  it('keeps hostile key labels out of the login shell parser and installs them as data', () => {
    const blob = 'AAAAC3NzaC1lZDI1NTE5AAAAIN7osVCLDIy5aFOk8IaZk040AFw1V+YDXgr8L+zQE/5k';
    const labels = [
      "Alexey's phone",
      "x'; touch PWNED; echo '",
      "x\\'; touch PWNED; echo \\'",
      'x"; touch PWNED; echo "',
      '$(touch PWNED) `touch PWNED` (touch PWNED)',
      '${HOME} $status !! %1 * ? [a] {a,b} ~ <in >out 2>&1 | ; && &',
      'a\\b\\\\c\\nd\\\'e\\"f\\',
      'Телефон Алексея — ключ 🔑',
    ];
    for (const label of labels) {
      const line = `ssh-ed25519 ${blob} ${label}`;
      const command = buildAuthorizedKeyInstallCommand(line);
      // sshd hands this to the user's login shell, which may be fish or csh:
      // nothing in it may be quoting, escaping, expansion or a separator.
      expect(command).toMatch(AUTHORIZED_KEY_INSTALL_COMMAND_CHARSET);
      expect(command).toMatch(/^echo [A-Za-z0-9+/]+={0,2} \| base64 -d \| \/bin\/sh$/);
      for (const shell of ['/bin/sh', 'bash', 'dash']) {
        const home = freshHome();
        expect(parseAuthorizedKeyInstallOutput(runRemote(command, home, shell))).toBe('installed');
        expect(parseAuthorizedKeyInstallOutput(runRemote(command, home, shell))).toBe('already-present');
        expect(readFileSync(join(home, '.ssh', 'authorized_keys'), 'utf8')).toBe(`${line}\n`);
        expect(readdirSync(home)).toEqual(['.ssh']);
      }
    }
    for (const label of ['a\ntouch PWNED', 'a\rb']) {
      expect(() => buildAuthorizedKeyInstallCommand(`ssh-ed25519 ${blob} ${label}`)).toThrow(/invalid public key/i);
    }
    expect(() => buildAuthorizedKeyInstallCommand(`${ED25519_LINE}\nssh-rsa AAAA evil`)).toThrow(/invalid public key/i);
  });

  it('surfaces timeouts and host failures instead of claiming success', () => {
    expect(() => parseAuthorizedKeyInstallOutput({ exitCode: null, stdout: '', stderr: '', timedOut: true })).toThrow(/timed out/);
    expect(() => parseAuthorizedKeyInstallOutput({ exitCode: 1, stdout: '', stderr: 'mkdir: Permission denied\n', timedOut: false }))
      .toThrow(/Permission denied/);
    expect(() => parseAuthorizedKeyInstallOutput({ exitCode: 0, stdout: 'motd noise\n', stderr: '', timedOut: false })).toThrow(/could not add/);
  });

  it('runs over the checked exec of the live connection and refuses a mismatched reply', async () => {
    const connection = { connectionId: 'c1', generationId: 'g1' };
    const exec = vi.fn(async (options: { requestId: string; command: string }) => ({
      ...connection,
      requestId: options.requestId,
      exitCode: 0,
      stdout: 'POCKETSHELL_AUTHORIZED_KEY_INSTALLED\n',
      stderr: '',
      timedOut: false,
    }));
    await expect(installPublicKeyOnHost({ exec }, connection, ED25519_LINE, 'req-1')).resolves.toBe('installed');
    expect(exec).toHaveBeenCalledWith(expect.objectContaining({
      ...connection,
      requestId: 'req-1',
      command: buildAuthorizedKeyInstallCommand(ED25519_LINE),
    }));
    const stale = vi.fn(async () => ({ ...connection, generationId: 'old', requestId: 'req-2', exitCode: 0, stdout: 'POCKETSHELL_AUTHORIZED_KEY_INSTALLED', stderr: '', timedOut: false }));
    await expect(installPublicKeyOnHost({ exec: stale }, connection, ED25519_LINE, 'req-2')).rejects.toThrow(/different request or connection/i);
  });
});
