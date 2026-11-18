import { formatBytes, oversizeMessage } from '@pocketshell/core';

/**
 * Attachment and upload limits (#3191). Deliberately separate from the text
 * editor's read limit (`MAX_SFTP_FILE_BYTES` in ./files): an upload streams
 * through bounded chunks and never holds a whole file for editing, so a
 * normal phone photo or video must not be refused by an editor constraint.
 */
export const MAX_ATTACHMENT_FILE_BYTES = 100 * 1024 * 1024;
/** One picker or share selection: room for two full-size files. */
export const MAX_ATTACHMENT_BATCH_BYTES = 2 * MAX_ATTACHMENT_FILE_BYTES;
/** Bytes per native SFTP write call (SshCapabilityPlugin's per-call cap). */
export const ATTACHMENT_WRITE_CHUNK_BYTES = 512 * 1024;
/** Suffix of an upload in progress; renamed into place only once complete. */
export const ATTACHMENT_PART_SUFFIX = '.part';
/**
 * A `.part` file untouched for this long is an abandoned upload (the
 * connection dropped before its own cleanup could run). A live upload
 * rewrites its `.part` every acknowledged chunk (at most 512 KiB), so its
 * mtime stays fresh; the next upload to the same folder removes stale ones.
 */
export const STALE_ATTACHMENT_PART_MS = 10 * 60 * 1000;

const IMAGE_EXTENSIONS = /\.(jpe?g|png|gif|webp|heic|heif|bmp|avif|dng)$/i;
const VIDEO_EXTENSIONS = /\.(mp4|mov|m4v|webm|3gp|mkv)$/i;

/** "photo", "video" or "file": the noun a person would use for this pick. */
export function attachmentNoun(name: string, mimeType: string | null | undefined): 'photo' | 'video' | 'file' {
  const mime = (mimeType ?? '').toLowerCase();
  if (mime.startsWith('image/') || IMAGE_EXTENSIONS.test(name)) return 'photo';
  if (mime.startsWith('video/') || VIDEO_EXTENSIONS.test(name)) return 'video';
  return 'file';
}

/** "This photo is 120.0 MB; the limit is 100.0 MB." — never a raw byte count. */
export function attachmentTooLargeMessage(
  name: string,
  mimeType: string | null | undefined,
  sizeBytes: number,
  limitBytes = MAX_ATTACHMENT_FILE_BYTES,
): string {
  return `${oversizeMessage(sizeBytes, limitBytes, `This ${attachmentNoun(name, mimeType)}`)}.`;
}

/** The selection-wide refusal, in the same voice. */
export function attachmentBatchTooLargeMessage(limitBytes = MAX_ATTACHMENT_BATCH_BYTES): string {
  return `Together these files are over the ${formatBytes(limitBytes)} limit for one selection. Pick fewer files.`;
}
