import * as Vue from 'vue';
import { createRenderer, nextTick, ssrContextKey } from 'vue';
import { compileScript, compileTemplate, parse } from '@vue/compiler-sfc';
import { describe, expect, it, vi } from 'vitest';
import { CredentialKeyManager, type SshKeyHostReference, type SshKeyReferenceStore } from '../../src/credentials/keyManagement';
import { listSshKeys, parseSshKeyMetadata, type NativeSshKeyVaultPlugin, type SshKeyMetadata } from '../../src/native/sshKeyVault';
import { createLegacySshKeyReferenceStore } from '../../src/migration/legacySshKeyReferences';
import { IMPORT_RECORD_ID, type ImportPersistence, type ImportRecord } from '../../src/migration/installedDataMigration';
import SshKeysScreen from '../../src/components/SshKeysScreen.vue';
import sshKeysScreenSource from '../../src/components/SshKeysScreen.vue?raw';

vi.mock('@ui/components/AppIcon.vue', () => ({ default: { render: () => null } }));

const sshKeysDescriptor = parse(sshKeysScreenSource, { filename: 'src/components/SshKeysScreen.vue' }).descriptor;
const sshKeysScript = compileScript(sshKeysDescriptor, { id: 'ssh-keys-screen-unit' });
const sshKeysTemplate = compileTemplate({
  source: sshKeysDescriptor.template!.content,
  filename: 'src/components/SshKeysScreen.vue',
  id: 'ssh-keys-screen-unit',
  compilerOptions: { bindingMetadata: sshKeysScript.bindings },
});
const vueImports = sshKeysTemplate.code.match(/^import \{([^}]+)\} from "vue"\s*$/m)?.[1];
if (!vueImports) throw new Error('Could not compile the SSH key screen template for the interaction test.');
const vueBindings = vueImports.split(',').map((entry) => {
  const [name, alias] = entry.trim().split(/\s+as\s+/);
  return alias ? `${name}: ${alias}` : name;
}).join(', ');
const renderBody = sshKeysTemplate.code
  .replace(/^import \{[^}]+\} from "vue"\s*$/m, '')
  .replace('export function render', 'function render');
const clientRender = new Function('Vue', `const { ${vueBindings} } = Vue;\n${renderBody}\nreturn render;`)(
  { ...Vue, vModelText: {}, vModelSelect: {} },
) as () => unknown;
const clientRenderedSshKeysScreen = {
  ...SshKeysScreen,
  render: clientRender,
};

interface HostNode {
  type: string;
  text?: string;
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
      const previousIndex = node.parent.children.indexOf(node);
      if (previousIndex >= 0) node.parent.children.splice(previousIndex, 1);
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

function findByTestId(root: HostNode, testId: string): HostNode | undefined {
  if (root.props['data-testid'] === testId) return root;
  for (const child of root.children) {
    const match = findByTestId(child, testId);
    if (match) return match;
  }
  return undefined;
}

function nodeText(root: HostNode): string {
  return `${root.text ?? ''}${root.children.map(nodeText).join('')}`;
}

async function clickByTestId(root: HostNode, testId: string): Promise<void> {
  const node = findByTestId(root, testId);
  expect(node, `expected rendered control ${testId}`).toBeDefined();
  const handler = node!.props.onClick;
  expect(handler, `expected ${testId} to have a click handler`).toBeDefined();
  if (Array.isArray(handler)) {
    for (const callback of handler) await (callback as (event: Event) => unknown)(new Event('click'));
  } else {
    await (handler as (event: Event) => unknown)(new Event('click'));
  }
  await nextTick();
}

const metadata: SshKeyMetadata = {
  handleId: '00000000-0000-4000-8000-000000000001',
  label: 'work laptop',
  algorithm: 'ssh-ed25519',
  fingerprintSha256: `SHA256:${'A'.repeat(43)}`,
  passphraseRequired: true,
  createdAt: 1_700_000_000_000,
};

function nativeVault(overrides: Partial<NativeSshKeyVaultPlugin> = {}): NativeSshKeyVaultPlugin {
  return {
    listKeys: vi.fn(async () => ({ keys: [metadata] })),
    pickKeyDocument: vi.fn(async () => ({ cancelled: true })),
    importPickedKey: vi.fn(async () => metadata),
    importDocumentUri: vi.fn(async () => metadata),
    generateKey: vi.fn(async () => metadata),
    deleteKey: vi.fn(async () => ({ deleted: true })),
    importLegacyKeys: vi.fn(async () => ({ keys: [], failures: [] })),
    ...overrides,
  } as unknown as NativeSshKeyVaultPlugin;
}

function legacyRecordPersistence(): { persistence: ImportPersistence; read: () => ImportRecord } {
  let current: ImportRecord = {
    id: IMPORT_RECORD_ID,
    status: 'complete',
    importedAt: 1_700_000_000_000,
    warnings: [],
    credentialHandles: {
      '7': metadata.handleId,
      '8': '00000000-0000-4000-8000-000000000008',
      '9': metadata.handleId,
    },
    credentialHandleTombstones: [],
    snapshot: {
      database: {
        tables: {
          hosts: [
            { id: 41, name: 'devbox', hostname: 'dev.example', keyId: 7 },
            { id: 42, name: 'old host', hostname: 'old.example', keyId: 8 },
          ],
        },
      },
    } as unknown as ImportRecord['snapshot'],
  };
  const persistence: ImportPersistence = {
    async readRecord() { return current; },
    async stage(record) { current = record; },
    async updateRecord(record) { current = record; },
    async compareAndSetCredentialHandles(expected, next, tombstones) {
      if (JSON.stringify(current.credentialHandles ?? {}) !== JSON.stringify(expected)) return false;
      if (tombstones && JSON.stringify(current.credentialHandleTombstones ?? []) !== JSON.stringify(tombstones.expected)) return false;
      current = {
        ...current,
        credentialHandles: { ...next },
        ...(tombstones ? { credentialHandleTombstones: [...tombstones.next] } : {}),
      };
      return true;
    },
    async markComplete() {},
  };
  return { persistence, read: () => current };
}

describe('opaque SSH key handles', () => {
  it('accepts only public metadata and rejects any key bytes or secret fields', () => {
    expect(parseSshKeyMetadata(metadata)).toEqual(metadata);
    expect(() => parseSshKeyMetadata({ ...metadata, privateKeyPem: '-----BEGIN PRIVATE KEY-----' })).toThrow(/invalid public metadata/i);
    expect(() => parseSshKeyMetadata({ ...metadata, secret: 'passphrase' })).toThrow(/invalid public metadata/i);
  });

  it('rejects duplicate handles or fingerprints returned by the native list boundary', async () => {
    const duplicate = { ...metadata, handleId: '00000000-0000-4000-8000-000000000002' };
    await expect(listSshKeys(nativeVault({
      listKeys: vi.fn(async () => ({ keys: [metadata, duplicate] })),
    }))).rejects.toThrow(/duplicate key metadata/i);
    await expect(listSshKeys(nativeVault({
      listKeys: vi.fn(async () => ({ keys: [metadata, { ...metadata, fingerprintSha256: `SHA256:${'B'.repeat(43)}` }] })),
    }))).rejects.toThrow(/duplicate key metadata/i);
  });

  it('asks before detaching saved-host references and deletes only after confirmation', async () => {
    const hosts: SshKeyHostReference[] = [{ hostId: '41', hostLabel: 'devbox' }];
    const references: SshKeyReferenceStore = {
      list: vi.fn(async () => hosts),
      detach: vi.fn(async () => true),
      restore: vi.fn(async () => true),
    };
    const vault = nativeVault();
    const manager = new CredentialKeyManager(vault, references);

    await expect(manager.delete(metadata.handleId)).resolves.toEqual({
      status: 'confirmation-required',
      affectedHosts: hosts,
    });
    expect(vault.deleteKey).not.toHaveBeenCalled();

    await expect(manager.delete(metadata.handleId, hosts)).resolves.toEqual({
      status: 'deleted',
      affectedHosts: hosts,
    });
    expect(references.detach).toHaveBeenCalledWith(metadata.handleId, hosts);
    expect(vault.deleteKey).toHaveBeenCalledWith({
      handleId: metadata.handleId,
      fingerprintSha256: metadata.fingerprintSha256,
    });
  });

  it('shows affected hosts in the delete dialog and waits for the explicit delete action', async () => {
    const hosts: SshKeyHostReference[] = [{ hostId: '41', hostLabel: 'devbox' }];
    const references: SshKeyReferenceStore = {
      list: vi.fn(async () => hosts),
      detach: vi.fn(async () => true),
      restore: vi.fn(async () => true),
    };
    const vault = nativeVault();
    const manager = new CredentialKeyManager(vault, references);
    const deleteSpy = vi.spyOn(manager, 'delete');
    const onSelect = vi.fn();
    const root = hostNode('root');
    const app = renderer.createApp(clientRenderedSshKeysScreen, {
      manager,
      keys: [metadata],
      selectedHandleId: metadata.handleId,
      loadError: '',
      onSelect,
      onRefresh: vi.fn(),
    });
    app.provide(ssrContextKey, { modules: new Set<string>() });
    app.component('AppIcon', { render: () => null });
    app.mount(root);

    try {
      await clickByTestId(root, `delete-ssh-key-${metadata.handleId}`);
      const dialog = findByTestId(root, 'ssh-key-delete-confirmation');
      expect(dialog).toBeDefined();
      expect(nodeText(dialog!)).toContain('devbox');
      expect(nodeText(dialog!)).toContain('Remove associations and delete key');
      expect(vault.deleteKey).not.toHaveBeenCalled();
      expect(references.detach).not.toHaveBeenCalled();

      await clickByTestId(root, 'cancel-delete-ssh-key');
      expect(findByTestId(root, 'ssh-key-delete-confirmation')).toBeUndefined();
      expect(vault.deleteKey).not.toHaveBeenCalled();
      expect(references.detach).not.toHaveBeenCalled();

      await clickByTestId(root, `delete-ssh-key-${metadata.handleId}`);
      await clickByTestId(root, 'confirm-delete-ssh-key');
      expect(deleteSpy).toHaveBeenCalledTimes(3);
      expect(deleteSpy).toHaveBeenLastCalledWith(metadata.handleId, hosts);
      expect(references.detach).toHaveBeenCalledWith(metadata.handleId, hosts);
      expect(vault.deleteKey).toHaveBeenCalledWith({
        handleId: metadata.handleId,
        fingerprintSha256: metadata.fingerprintSha256,
      });
      expect(onSelect).toHaveBeenCalledWith('');
      expect(findByTestId(root, 'ssh-key-delete-confirmation')).toBeUndefined();
    } finally {
      app.unmount();
    }
  });

  it('restores host references when native deletion fails before commit', async () => {
    const hosts: SshKeyHostReference[] = [{ hostId: '41', hostLabel: 'devbox' }];
    const references: SshKeyReferenceStore = {
      list: vi.fn(async () => hosts),
      detach: vi.fn(async () => true),
      restore: vi.fn(async () => true),
    };
    const manager = new CredentialKeyManager(nativeVault({
      deleteKey: vi.fn(async () => { throw new Error('metadata commit failed'); }),
    }), references);

    await expect(manager.delete(metadata.handleId, hosts)).rejects.toThrow('metadata commit failed');
    expect(references.restore).toHaveBeenCalledWith(metadata.handleId, hosts);
  });

  it('requires a fresh confirmation when host references change while the dialog is open', async () => {
    const original: SshKeyHostReference[] = [{ hostId: '41', hostLabel: 'devbox' }];
    const updated: SshKeyHostReference[] = [
      ...original,
      { hostId: '42', hostLabel: 'staging host' },
    ];
    const references: SshKeyReferenceStore = {
      list: vi.fn().mockResolvedValueOnce(original).mockResolvedValueOnce(updated),
      detach: vi.fn(async () => true),
      restore: vi.fn(async () => true),
    };
    const vault = nativeVault();
    const manager = new CredentialKeyManager(vault, references);

    await expect(manager.delete(metadata.handleId)).resolves.toEqual({
      status: 'confirmation-required',
      affectedHosts: original,
    });
    await expect(manager.delete(metadata.handleId, original)).resolves.toEqual({
      status: 'confirmation-required',
      affectedHosts: updated,
    });
    expect(references.detach).not.toHaveBeenCalled();
    expect(vault.deleteKey).not.toHaveBeenCalled();
  });

  it('runs reference retirement for a legacy key with no saved host associations', async () => {
    const references: SshKeyReferenceStore = {
      list: vi.fn(async () => []),
      detach: vi.fn(async () => true),
      restore: vi.fn(async () => true),
      commitDelete: vi.fn(),
    };
    const vault = nativeVault();
    const manager = new CredentialKeyManager(vault, references);

    await expect(manager.delete(metadata.handleId)).resolves.toEqual({
      status: 'deleted',
      affectedHosts: [],
    });
    expect(references.detach).toHaveBeenCalledWith(metadata.handleId, []);
    expect(references.commitDelete).toHaveBeenCalledWith(metadata.handleId);
  });

  it('does not cache a selected handle or passphrase in key metadata', async () => {
    const manager = new CredentialKeyManager(nativeVault());
    const credential = manager.keyCredential(metadata.handleId, 'one-attempt-only');
    expect(credential).toEqual({ kind: 'key-handle', handleId: metadata.handleId, passphrase: 'one-attempt-only' });
    expect(JSON.stringify(metadata)).not.toContain('one-attempt-only');
    expect(JSON.stringify(metadata)).not.toContain('PRIVATE KEY');
  });

  it('detaches imported-host references by CAS and clears handles absent from the vault', async () => {
    const state = legacyRecordPersistence();
    const references = createLegacySshKeyReferenceStore(state.persistence);
    const initial = await references.list(metadata.handleId);
    expect(initial).toEqual([{ hostId: '41', hostLabel: 'devbox' }]);

    await expect(references.detach(metadata.handleId, initial)).resolves.toBe(true);
    expect(state.read().credentialHandles?.['7']).toBeNull();
    expect(state.read().credentialHandles?.['9']).toBeNull();
    expect(state.read().credentialHandleTombstones).toEqual([7, 9]);
    await expect(references.restore(metadata.handleId, initial)).resolves.toBe(true);
    expect(state.read().credentialHandles?.['7']).toBe(metadata.handleId);
    expect(state.read().credentialHandles?.['9']).toBe(metadata.handleId);
    expect(state.read().credentialHandleTombstones).toEqual([]);

    expect(await references.detach(metadata.handleId, initial)).toBe(true);
    expect(state.read().credentialHandles?.['7']).toBeNull();
    expect(state.read().credentialHandleTombstones).toEqual([7, 9]);
    await expect(references.restore(metadata.handleId, initial)).resolves.toBe(true);

    await references.reconcileMissing?.(new Set([metadata.handleId]));
    expect(state.read().credentialHandles?.['8']).toBeNull();
    expect(await references.list('00000000-0000-4000-8000-000000000008')).toEqual([]);
  });

  it('tombstones deleted migrated keys even when no saved host currently references them', async () => {
    const state = legacyRecordPersistence();
    state.read().snapshot.database.tables.hosts = [];
    const references = createLegacySshKeyReferenceStore(state.persistence);

    await expect(references.list(metadata.handleId)).resolves.toEqual([]);
    await expect(references.detach(metadata.handleId, [])).resolves.toBe(true);
    expect(state.read().credentialHandles?.['7']).toBeNull();
    expect(state.read().credentialHandles?.['9']).toBeNull();
    expect(state.read().credentialHandleTombstones).toEqual([7, 9]);
    expect(await references.restore(metadata.handleId, [])).toBe(true);
    expect(state.read().credentialHandles?.['7']).toBe(metadata.handleId);
    expect(state.read().credentialHandleTombstones).toEqual([]);
  });

  it('reconciles a fresh signed snapshot without a hosts table', async () => {
    const state = legacyRecordPersistence();
    delete (state.read().snapshot.database.tables as Record<string, unknown>).hosts;
    const references = createLegacySshKeyReferenceStore(state.persistence);

    await expect(references.reconcileMissing?.(new Set([metadata.handleId]))).resolves.toBeUndefined();
    await expect(references.list(metadata.handleId)).resolves.toEqual([]);
  });
});
