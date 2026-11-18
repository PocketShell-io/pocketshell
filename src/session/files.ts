import {
  classifyFileByName,
  classifyFileBytes,
  composeAttachmentFilename,
  decideAttachmentStage,
  evaluateFileEditSave,
  formatBytes,
  isEditableFileKind,
  isRemotePathWithin,
  joinRemoteChildPath,
  looksLikeRemoteText,
  normalizeRemotePath,
  parentRemotePath,
  planAttachmentRetention as planCoreAttachmentRetention,
  remotePathName,
  resolveRemotePath,
  sanitizeAttachmentScope,
  sanitizeFilename,
  sortFileEntries,
  type AttachmentRetentionPolicy,
  type AttachmentStageDecision,
  type FileClassification,
  type FileEditSaveVerdict,
  type RemoteFileMetadata,
} from '@pocketshell/core';
import {
  ATTACHMENT_PART_SUFFIX,
  ATTACHMENT_WRITE_CHUNK_BYTES,
  MAX_ATTACHMENT_FILE_BYTES,
  STALE_ATTACHMENT_PART_MS,
  attachmentTooLargeMessage,
} from './attachmentLimits';
import type {
  NativeSshCapabilityPlugin,
  SshCapabilityPlugin,
  SshConnectionRef,
  SshSftpEntry,
} from '../native/sshCapability';

/**
 * The text editor's read/save limit. Attachment uploads have their own,
 * much larger limit in ./attachmentLimits and stream in chunks (#3191).
 */
export const MAX_SFTP_FILE_BYTES = 512 * 1024;
export const DEFAULT_FILE_READ_BYTES = MAX_SFTP_FILE_BYTES;

export type FileWorkspaceEntryType = 'directory' | 'file' | 'symlink' | 'other';

export interface FileWorkspaceEntry {
  path: string;
  name: string;
  type: FileWorkspaceEntryType;
  isDirectory: boolean;
  sizeBytes: number;
  modifiedEpochMs: number;
  classification: FileClassification | null;
}

export interface FileWorkspaceConfiguration {
  connection: SshConnectionRef;
  /** Lexical browse boundary, normally the remote home directory. */
  rootDirectory: string;
  initialDirectory?: string;
  homeDirectory?: string | null;
  /** False means this controller's SSH generation has gone stale. */
  isCurrent?: () => boolean;
  /** Test seam and operation correlation hook; values must be unique. */
  nextRequestId?: () => string;
}

export interface FileWorkspaceListing {
  path: string;
  entries: FileWorkspaceEntry[];
}

export interface FileReadResult {
  path: string;
  bytes: Uint8Array;
  classification: FileClassification;
  metadata: RemoteFileMetadata;
}

export interface FileEditSnapshot {
  path: string;
  text: string;
  classification: FileClassification;
  metadata: RemoteFileMetadata;
}

export type FileEditSaveResult =
  | { status: 'saved'; path: string; bytesWritten: number }
  | { status: 'conflict'; path: string; verdict: Exclude<FileEditSaveVerdict, 'unchanged'> };

export interface FileUploadResult {
  path: string;
  bytesWritten: number;
}

/**
 * One file to upload as an attachment, read lazily in bounded chunks (#3191).
 * `read` returns up to `maxBytes` from `offset`, and fewer only at the end.
 */
export interface AttachmentSource {
  name: string;
  mimeType?: string | null;
  /** The size the source reported up front, or null when unknown. */
  sizeBytes: number | null;
  read(offset: number, maxBytes: number): Promise<{ bytes: Uint8Array; eof: boolean }>;
}

/** An in-memory attachment (a pasted image, an annotated drawing). */
export function bytesAttachmentSource(name: string, bytes: Uint8Array, mimeType: string | null = null): AttachmentSource {
  return {
    name,
    mimeType,
    sizeBytes: bytes.byteLength,
    async read(offset, maxBytes) {
      const end = Math.min(bytes.byteLength, offset + maxBytes);
      return { bytes: bytes.subarray(offset, end), eof: end >= bytes.byteLength };
    },
  };
}

/** Thrown inside an upload the user cancelled; its partial file is removed. */
export class AttachmentUploadCancelled extends Error {
  constructor() {
    super('Upload cancelled.');
    this.name = 'AttachmentUploadCancelled';
  }
}

export interface StagedAttachment {
  path: string;
  filename: string;
  sourceName: string;
  sizeBytes: number;
}

export interface AttachmentStageFailure {
  sourceName: string;
  /** The source's position in the requested batch. */
  sourceIndex: number;
  message: string;
  /** True when the user cancelled this file; not an error to show. */
  cancelled?: true;
}

export interface AttachmentStageResult {
  directory: string;
  decision: AttachmentStageDecision<StagedAttachment>;
  failures: AttachmentStageFailure[];
}

export interface AttachmentRetentionPlan {
  directory: string;
  pathsToDelete: string[];
}

export type FileWorkspaceErrorCode =
  | 'invalid-path'
  | 'outside-root'
  | 'stale-generation'
  | 'invalid-request-id'
  | 'invalid-response'
  | 'invalid-entry'
  | 'not-found'
  | 'not-file'
  | 'not-directory'
  | 'symlink-unsupported'
  | 'file-too-large'
  | 'file-changed'
  | 'not-editable'
  | 'binary-content'
  | 'invalid-utf8'
  | 'file-exists'
  | 'invalid-content';

export class FileWorkspaceError extends Error {
  constructor(readonly code: FileWorkspaceErrorCode, message: string) {
    super(message);
    this.name = 'FileWorkspaceError';
  }
}

/**
 * Bind portable file policy to the native SFTP byte-I/O capability. Paths are
 * normalized and confined lexically under rootDirectory. The native adapter
 * also canonicalizes every operation under the configured root and rejects
 * symbolic links and special files. SFTP has no portable no-follow open or
 * metadata compare-and-swap; this cannot coordinate an external process that
 * mutates the same inode after the native handle check.
 */
export function createFileWorkspaceService(
  capability: SshCapabilityPlugin,
  configuration: FileWorkspaceConfiguration,
) {
  let requestSequence = 0;
  let currentDirectory: string;

  const homeDirectory = configuration.homeDirectory ?? null;
  const rootDirectory = resolveRemotePath(configuration.rootDirectory, '/', homeDirectory);
  if (rootDirectory == null) throw new FileWorkspaceError('invalid-path', 'The file workspace root is invalid.');
  const rootPath = normalizeRemotePath(rootDirectory);
  if (rootPath == null) throw new FileWorkspaceError('invalid-path', 'The file workspace root is invalid.');
  const normalizedRoot: string = rootPath;
  const fileCapability = capability as SshCapabilityPlugin &
    Pick<NativeSshCapabilityPlugin, 'sftpWriteIfUnchanged' | 'sftpWriteChunk'>;
  currentDirectory = resolvePath(configuration.initialDirectory ?? normalizedRoot, normalizedRoot);

  function assertCurrent(): void {
    if (configuration.isCurrent?.() === false) {
      throw new FileWorkspaceError('stale-generation', 'The SSH connection generation is no longer current.');
    }
  }

  function nextRequestId(): string {
    assertCurrent();
    const requestId = configuration.nextRequestId?.() ?? defaultRequestId(++requestSequence);
    if (typeof requestId !== 'string' || requestId.trim() === '') {
      throw new FileWorkspaceError('invalid-request-id', 'The file request could not be started. Try again.');
    }
    return requestId;
  }

  function resolvePath(path: string, base = currentDirectory): string {
    const resolved = resolveRemotePath(path, base, homeDirectory);
    if (resolved == null) throw new FileWorkspaceError('invalid-path', 'The remote path cannot be resolved safely.');
    if (!isRemotePathWithin(normalizedRoot, resolved)) {
      throw new FileWorkspaceError('outside-root', 'The remote path is outside the configured file workspace root.');
    }
    return resolved;
  }

  function fileParts(path: string): { path: string; directory: string; name: string } {
    const resolved = resolvePath(path);
    const name = remotePathName(resolved);
    const directory = parentRemotePath(resolved);
    if (resolved === '/' || name == null || name === '/' || directory == null) {
      throw new FileWorkspaceError('invalid-path', 'A file path must name a child of a remote directory.');
    }
    return { path: resolved, directory, name };
  }

  function requestOptions(path: string) {
    return { ...configuration.connection, requestId: nextRequestId(), rootPath: normalizedRoot, path };
  }

  async function checkedResponse<T extends { requestId: string }>(
    pending: Promise<T>,
    requestId: string,
  ): Promise<T> {
    const response = await pending;
    assertCurrent();
    if (typeof response !== 'object' || response === null || response.requestId !== requestId) {
      throw new FileWorkspaceError('invalid-response', 'The server sent an unexpected reply. Try again.');
    }
    return response;
  }

  function entryType(entry: SshSftpEntry): FileWorkspaceEntryType {
    const richer = entry as SshSftpEntry & {
      type?: unknown;
      isSymlink?: unknown;
      isSymbolicLink?: unknown;
    };
    if (richer.type === 'symlink' || richer.isSymlink === true || richer.isSymbolicLink === true) {
      return 'symlink';
    }
    if (richer.type === 'other') return 'other';
    if (richer.type === 'directory' || richer.type === 'dir') {
      return entry.isDirectory === true ? 'directory' : 'other';
    }
    if (richer.type === 'file') return entry.isDirectory === false ? 'file' : 'other';
    if (richer.type != null) return 'other';
    return 'other';
  }

  function entrySize(entry: SshSftpEntry): number {
    return Number.isFinite(entry.sizeBytes) && entry.sizeBytes >= 0 ? entry.sizeBytes : Number.NaN;
  }

  function entryModified(entry: SshSftpEntry): number {
    return Number.isFinite(entry.modifiedEpochMs) && entry.modifiedEpochMs > 0
      ? entry.modifiedEpochMs
      : 0;
  }

  function mapEntry(directory: string, entry: SshSftpEntry): FileWorkspaceEntry {
    if (typeof entry.name !== 'string') {
      throw new FileWorkspaceError('invalid-entry', 'The folder listing contained an unreadable name.');
    }
    const child = joinRemoteChildPath(directory, entry.name);
    if (!child.ok) {
      throw new FileWorkspaceError('invalid-entry', `The folder listing contained an unsafe file name (${child.reason}).`);
    }
    const type = entryType(entry);
    return {
      path: child.path,
      name: entry.name,
      type,
      isDirectory: type === 'directory',
      sizeBytes: entrySize(entry),
      modifiedEpochMs: entryModified(entry),
      classification: type === 'file' ? classifyFileByName(entry.name) : null,
    };
  }

  async function listDirectoryAt(path: string): Promise<FileWorkspaceListing> {
    const directory = resolvePath(path);
    const options = requestOptions(directory);
    const response = await checkedResponse(capability.sftpList(options), options.requestId);
    if (!Array.isArray(response.entries)) {
      throw new FileWorkspaceError('invalid-response', 'The folder listing could not be read.');
    }

    const mapped: FileWorkspaceEntry[] = [];
    const seenNames = new Set<string>();
    for (const rawEntry of response.entries as unknown[]) {
      if (typeof rawEntry !== 'object' || rawEntry === null) {
        throw new FileWorkspaceError('invalid-entry', 'The folder listing contained an unreadable entry.');
      }
      const entry = rawEntry as SshSftpEntry;
      if (entry.name === '.' || entry.name === '..') continue;
      if (typeof entry.name === 'string' && seenNames.has(entry.name)) {
        throw new FileWorkspaceError('invalid-entry', 'The folder listing contained the same name twice.');
      }
      mapped.push(mapEntry(directory, entry));
      seenNames.add(entry.name);
    }
    const sortable = mapped.map((entry) => ({
      entry,
      name: entry.name,
      type: entry.type === 'directory' ? 'dir' as const : 'file' as const,
    }));
    const entries = sortFileEntries(sortable).map(({ entry }) => entry);
    return { path: directory, entries };
  }

  async function entryAt(path: string): Promise<FileWorkspaceEntry | null> {
    const parts = fileParts(path);
    const listing = await listDirectoryAt(parts.directory);
    return listing.entries.find((entry) => entry.name === parts.name) ?? null;
  }

  function assertNotSymlink(entry: FileWorkspaceEntry): void {
    if (entry.type === 'symlink') {
      throw new FileWorkspaceError('symlink-unsupported', 'Symbolic links cannot be opened safely here.');
    }
    if (entry.type === 'other') {
      throw new FileWorkspaceError('not-file', 'The remote entry type cannot be verified safely.');
    }
  }

  function metadataOf(entry: FileWorkspaceEntry): RemoteFileMetadata {
    return {
      isDirectory: entry.isDirectory,
      sizeBytes: entry.sizeBytes,
      modifiedEpochMs: entry.modifiedEpochMs,
    };
  }

  function decodeBase64(value: unknown): Uint8Array {
    if (typeof value !== 'string' || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)) {
      throw new FileWorkspaceError('invalid-response', 'The server sent file contents that could not be read.');
    }
    let binary: string;
    try {
      binary = atob(value);
    } catch {
      throw new FileWorkspaceError('invalid-response', 'The server sent file contents that could not be read.');
    }
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
    return bytes;
  }

  function encodeBase64(bytes: Uint8Array): string {
    let binary = '';
    const chunkBytes = 0x8000;
    for (let offset = 0; offset < bytes.length; offset += chunkBytes) {
      const chunk = bytes.subarray(offset, Math.min(offset + chunkBytes, bytes.length));
      binary += String.fromCharCode(...chunk);
    }
    return btoa(binary);
  }

  function checkByteLimit(bytes: Uint8Array): void {
    if (!(bytes instanceof Uint8Array)) {
      throw new FileWorkspaceError('invalid-content', 'SFTP content must be a byte array.');
    }
    if (bytes.byteLength > MAX_SFTP_FILE_BYTES) {
      throw new FileWorkspaceError(
        'file-too-large',
        `This file is larger than the ${formatBytes(MAX_SFTP_FILE_BYTES)} transfer limit.`,
      );
    }
  }

  async function writeBytes(path: string, bytes: Uint8Array, createOnly = true): Promise<FileUploadResult> {
    checkByteLimit(bytes);
    const options = { ...requestOptions(path), createOnly, dataBase64: encodeBase64(bytes) };
    const response = await checkedResponse(capability.sftpWrite(options), options.requestId);
    if (!Number.isSafeInteger(response.bytesWritten) || response.bytesWritten !== bytes.byteLength) {
      throw new FileWorkspaceError('invalid-response', 'The server did not confirm the whole file was written.');
    }
    return { path, bytesWritten: response.bytesWritten };
  }

  async function readFile(path: string, maxBytes = DEFAULT_FILE_READ_BYTES): Promise<FileReadResult> {
    if (!Number.isSafeInteger(maxBytes) || maxBytes < 1 || maxBytes > MAX_SFTP_FILE_BYTES) {
      throw new FileWorkspaceError('file-too-large', `The read limit must be between 1 byte and ${formatBytes(MAX_SFTP_FILE_BYTES)}.`);
    }
    const parts = fileParts(path);
    const entry = await entryAt(parts.path);
    if (entry == null) throw new FileWorkspaceError('not-found', 'The remote file does not exist.');
    assertNotSymlink(entry);
    if (entry.type !== 'file') throw new FileWorkspaceError('not-file', 'The remote path is not a regular file.');
    if (!Number.isFinite(entry.sizeBytes)) {
      throw new FileWorkspaceError('invalid-response', 'The remote file size could not be verified.');
    }
    if (entry.sizeBytes > maxBytes) {
      throw new FileWorkspaceError('file-too-large', `The remote file exceeds the ${maxBytes} byte read limit.`);
    }

    const options = { ...requestOptions(parts.path), maxBytes };
    const response = await checkedResponse(capability.sftpRead(options), options.requestId);
    const bytes = decodeBase64(response.dataBase64);
    if (bytes.byteLength !== entry.sizeBytes) {
      throw new FileWorkspaceError('file-changed', 'The remote file size changed while it was being read.');
    }
    return {
      path: parts.path,
      bytes,
      classification: classifyFileBytes(classifyFileByName(parts.path), bytes),
      metadata: metadataOf(entry),
    };
  }

  async function loadTextForEdit(path: string, maxBytes = DEFAULT_FILE_READ_BYTES): Promise<FileEditSnapshot> {
    const loaded = await readFile(path, maxBytes);
    if (!isEditableFileKind(loaded.classification.kind)) {
      throw new FileWorkspaceError('not-editable', 'This file type does not support text editing.');
    }
    if (!looksLikeRemoteText(loaded.bytes)) {
      throw new FileWorkspaceError('binary-content', 'The file contents do not look like safe UTF-8 text.');
    }

    let text: string;
    try {
      text = new TextDecoder('utf-8', { fatal: true }).decode(loaded.bytes);
    } catch {
      throw new FileWorkspaceError('invalid-utf8', 'The remote text file is not valid UTF-8.');
    }

    const current = await entryAt(loaded.path);
    if (current == null || current.type !== 'file') {
      if (current?.type === 'symlink') assertNotSymlink(current);
      throw new FileWorkspaceError('file-changed', 'The remote file changed while it was being opened for editing.');
    }
    const verdict = evaluateFileEditSave(loaded.metadata, metadataOf(current));
    if (verdict !== 'unchanged') {
      throw new FileWorkspaceError('file-changed', 'The remote file changed while it was being opened for editing.');
    }
    return {
      path: loaded.path,
      text,
      classification: loaded.classification,
      metadata: metadataOf(current),
    };
  }

  async function ensureDirectory(directory: string): Promise<void> {
    const parts = fileParts(directory);
    const existing = await entryAt(parts.path);
    if (existing != null) {
      assertNotSymlink(existing);
      if (!existing.isDirectory) {
        throw new FileWorkspaceError('not-directory', 'The attachment staging path is not a directory.');
      }
      return;
    }
    const options = requestOptions(parts.path);
    await checkedResponse(capability.sftpMkdir(options), options.requestId);
  }

  /**
   * Stream one attachment to `<path>.part` in bounded chunks, then rename it
   * into place. A failure or cancellation removes the partial file, so the
   * host never keeps a truncated attachment under its final name.
   */
  async function uploadStreamed(
    path: string,
    source: AttachmentSource,
    maxFileBytes: number,
    isCancelled: () => boolean,
    onProgress: (bytesWritten: number) => void,
  ): Promise<number> {
    const partPath = `${path}${ATTACHMENT_PART_SUFFIX}`;
    let offset = 0;
    let created = false;
    try {
      for (;;) {
        if (isCancelled()) throw new AttachmentUploadCancelled();
        const chunk = await source.read(offset, ATTACHMENT_WRITE_CHUNK_BYTES);
        assertCurrent();
        if (!(chunk?.bytes instanceof Uint8Array) || chunk.bytes.byteLength > ATTACHMENT_WRITE_CHUNK_BYTES) {
          throw new FileWorkspaceError('invalid-content', 'The file could not be read. Pick it again.');
        }
        const length = chunk.bytes.byteLength;
        if (offset + length > maxFileBytes) {
          throw new FileWorkspaceError('file-too-large', attachmentTooLargeMessage(source.name, source.mimeType, offset + length, maxFileBytes));
        }
        if (length > 0 || !created) {
          if (isCancelled()) throw new AttachmentUploadCancelled();
          const options = { ...requestOptions(partPath), offset, dataBase64: encodeBase64(chunk.bytes) };
          const response = await checkedResponse(fileCapability.sftpWriteChunk(options), options.requestId);
          created = true;
          if (response.bytesWritten !== length || response.sizeBytes !== offset + length) {
            throw new FileWorkspaceError('invalid-response', 'The server did not confirm the whole file was written.');
          }
          offset += length;
          onProgress(offset);
        }
        if (chunk.eof === true) break;
        if (length === 0) throw new FileWorkspaceError('invalid-content', 'The file stopped before it was complete. Pick it again.');
      }
      if (source.sizeBytes != null && offset !== source.sizeBytes) {
        throw new FileWorkspaceError('file-changed', 'The selected file changed while it was uploading. Pick it again.');
      }
      if (isCancelled()) throw new AttachmentUploadCancelled();
      const options = { ...requestOptions(partPath), destination: path };
      await checkedResponse(capability.sftpRename(options), options.requestId);
      created = false;
      return offset;
    } catch (error) {
      if (created) await removeQuietly(partPath);
      throw error;
    }
  }

  async function removeQuietly(path: string): Promise<void> {
    try {
      const options = requestOptions(path);
      await checkedResponse(capability.sftpDelete(options), options.requestId);
    } catch {
      // Best-effort: a dropped connection cannot remove it here. The next
      // upload to this folder sweeps it once stale (sweepStaleParts).
    }
  }

  /**
   * Remove `.part` files an earlier upload abandoned (a dropped connection
   * leaves one its own cleanup could not delete). Only regular files older
   * than STALE_ATTACHMENT_PART_MS: a live upload refreshes its mtime on every
   * chunk, and this batch's own `.part` names do not exist yet. Best-effort.
   */
  async function sweepStaleParts(entries: readonly FileWorkspaceEntry[], nowMillis: number): Promise<Set<string>> {
    const removed = new Set<string>();
    for (const entry of entries) {
      if (entry.type !== 'file' || !entry.name.endsWith(ATTACHMENT_PART_SUFFIX)) continue;
      if (!(entry.modifiedEpochMs > 0) || nowMillis - entry.modifiedEpochMs < STALE_ATTACHMENT_PART_MS) continue;
      try {
        const options = requestOptions(entry.path);
        await checkedResponse(capability.sftpDelete(options), options.requestId);
        removed.add(entry.name);
      } catch {
        assertCurrent();
      }
    }
    return removed;
  }

  async function uploadAttachmentBatch(input: {
    directory: string;
    scopeKey: string;
    timestamp: string;
    attachments: readonly AttachmentSource[];
    /** Whole batch cancelled (the composer was discarded). */
    isCancelled?: () => boolean;
    /** One file cancelled from its chip; the rest of the batch continues. */
    isSourceCancelled?: (index: number) => boolean;
    /** Acknowledged bytes of one file, after each native write. */
    onProgress?: (index: number, bytesWritten: number) => void;
    /** Called when a file starts uploading. */
    onStart?: (index: number) => void;
    maxFileBytes?: number;
    /** Wall clock for the stale `.part` sweep (test seam). */
    nowMillis?: number;
  }): Promise<AttachmentStageResult> {
    const maxFileBytes = input.maxFileBytes ?? MAX_ATTACHMENT_FILE_BYTES;
    const base = resolvePath(input.directory);
    const scope = sanitizeAttachmentScope(input.scopeKey);
    const folder = joinRemoteChildPath(base, scope);
    if (!folder.ok) {
      throw new FileWorkspaceError('invalid-path', 'The attachment staging directory is invalid.');
    }
    if (!isRemotePathWithin(normalizedRoot, folder.path)) {
      throw new FileWorkspaceError('invalid-path', 'The attachment staging directory is invalid.');
    }
    const directory = folder.path;
    const attempts: Array<{ kind: 'uploaded'; attachment: StagedAttachment } | { kind: 'failed' }> = [];
    const failures: AttachmentStageFailure[] = [];

    if (input.isCancelled?.() === true) {
      return { directory, decision: decideAttachmentStage([], true), failures };
    }
    if (input.attachments.length === 0) {
      return { directory, decision: decideAttachmentStage([]), failures };
    }
    const prepared = input.attachments.map((source, index) => ({
      source,
      index,
      filename: composeAttachmentFilename(input.timestamp, index, sanitizeFilename(source.name)),
    }));
    await ensureDirectory(directory);
    const initialListing = await listDirectoryAt(directory);
    const swept = await sweepStaleParts(initialListing.entries, input.nowMillis ?? Date.now());
    const occupiedNames = new Set(initialListing.entries.map((entry) => entry.name).filter((name) => !swept.has(name)));

    for (const { source, index, filename } of prepared) {
      if (input.isCancelled?.() === true) {
        return { directory, decision: decideAttachmentStage(attempts, true), failures };
      }
      const fail = (message: string) => {
        attempts.push({ kind: 'failed' });
        failures.push({ sourceName: source.name, sourceIndex: index, message });
      };

      const child = joinRemoteChildPath(directory, filename);
      if (!child.ok || !isRemotePathWithin(normalizedRoot, child.path)) {
        fail('The attachment name could not be made into a safe remote path.');
        continue;
      }
      if (occupiedNames.has(filename) || occupiedNames.has(`${filename}${ATTACHMENT_PART_SUFFIX}`)) {
        fail('An attachment with this generated name already exists.');
        continue;
      }
      if (typeof source?.read !== 'function') {
        fail('Attachment content could not be read.');
        continue;
      }
      if (source.sizeBytes != null && source.sizeBytes > maxFileBytes) {
        fail(attachmentTooLargeMessage(source.name, source.mimeType, source.sizeBytes, maxFileBytes));
        continue;
      }
      const sourceCancelled = () => input.isCancelled?.() === true || input.isSourceCancelled?.(index) === true;

      try {
        input.onStart?.(index);
        const bytesWritten = await uploadStreamed(child.path, source, maxFileBytes, sourceCancelled,
          (written) => input.onProgress?.(index, written));
        attempts.push({
          kind: 'uploaded',
          attachment: { path: child.path, filename, sourceName: source.name, sizeBytes: bytesWritten },
        });
        occupiedNames.add(filename);
      } catch (error) {
        assertCurrent();
        attempts.push({ kind: 'failed' });
        if (error instanceof AttachmentUploadCancelled) {
          if (input.isCancelled?.() === true) {
            return { directory, decision: decideAttachmentStage(attempts, true), failures };
          }
          failures.push({ sourceName: source.name, sourceIndex: index, message: error.message, cancelled: true });
        } else {
          failures.push({ sourceName: source.name, sourceIndex: index, message: errorMessage(error) });
        }
      }
    }

    return { directory, decision: decideAttachmentStage(attempts), failures };
  }

  async function planRetention(
    directory: string,
    nowMillis: number,
    policy?: AttachmentRetentionPolicy,
  ): Promise<AttachmentRetentionPlan> {
    const listing = await listDirectoryAt(directory);
    const retentionEntries = listing.entries.map((entry) => ({
      path: entry.path,
      name: entry.name,
      type: entry.type === 'directory'
        ? 'dir' as const
        : entry.type === 'symlink'
          ? 'symlink' as const
          : entry.type === 'other'
            ? 'other' as const
            : 'file' as const,
      modifyTime: entry.modifiedEpochMs,
    }));
    const plan = planCoreAttachmentRetention(retentionEntries, nowMillis, policy);
    return { directory: listing.path, pathsToDelete: plan.delete.map((entry) => entry.path) };
  }

  return {
    get rootDirectory() { return normalizedRoot; },
    get currentDirectory() { return currentDirectory; },
    resolvePath,
    async listDirectory(path = currentDirectory): Promise<FileWorkspaceListing> {
      return listDirectoryAt(path);
    },
    async navigate(path: string): Promise<FileWorkspaceListing> {
      const listing = await listDirectoryAt(path);
      currentDirectory = listing.path;
      return listing;
    },
    readFile,
    loadTextForEdit,
    async saveText(snapshot: FileEditSnapshot, text: string): Promise<FileEditSaveResult> {
      const parts = fileParts(snapshot.path);
      if (snapshot.metadata.isDirectory || snapshot.metadata.modifiedEpochMs <= 0) {
        return { status: 'conflict', path: parts.path, verdict: 'changed' };
      }
      const bytes = new TextEncoder().encode(text);
      checkByteLimit(bytes);
      const options = {
        ...requestOptions(parts.path),
        expectedMetadata: {
          isDirectory: false as const,
          sizeBytes: snapshot.metadata.sizeBytes,
          modifiedEpochMs: snapshot.metadata.modifiedEpochMs,
        },
        dataBase64: encodeBase64(bytes),
      };
      const response = await checkedResponse(
        fileCapability.sftpWriteIfUnchanged(options),
        options.requestId,
      );
      if (response.status === 'conflict') {
        if (response.verdict !== 'missing' && response.verdict !== 'changed') {
          throw new FileWorkspaceError('invalid-response', 'The server sent an unexpected reply while saving. Try again.');
        }
        return { status: 'conflict', path: parts.path, verdict: response.verdict };
      }
      if (response.status !== 'written' || !Number.isSafeInteger(response.bytesWritten) || response.bytesWritten !== bytes.byteLength) {
        throw new FileWorkspaceError('invalid-response', 'The server did not confirm the save. Try again.');
      }
      return { status: 'saved', path: parts.path, bytesWritten: response.bytesWritten };
    },
    async writeFile(path: string, bytes: Uint8Array, overwrite = false): Promise<FileUploadResult> {
      const parts = fileParts(path);
      const existing = await entryAt(parts.path);
      if (existing != null) {
        assertNotSymlink(existing);
        if (existing.type !== 'file') throw new FileWorkspaceError('not-file', 'The upload target is not a regular file.');
        if (!overwrite) throw new FileWorkspaceError('file-exists', 'The remote upload target already exists.');
      }
      return writeBytes(parts.path, bytes, !overwrite);
    },
    stageAttachments: uploadAttachmentBatch,
    planAttachmentRetention: planRetention,
  };
}

function defaultRequestId(sequence: number): string {
  const random = globalThis.crypto?.randomUUID?.() ?? Math.random().toString(36).slice(2, 12);
  return `files-${Date.now().toString(36)}-${sequence.toString(36)}-${random}`;
}

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message !== '') return error.message;
  return String(error);
}
