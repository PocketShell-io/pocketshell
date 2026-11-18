import { shallowRef } from 'vue';

/**
 * Per-file upload chips for the composer (#3191, an Android-only surface per
 * #2941). Each file of an in-flight batch shows its acknowledged bytes and a
 * Cancel; a file refused before upload (over the size limit) shows why, in
 * plain words, until dismissed. Finished files leave: they are composer
 * tiles by then.
 */
export type AndroidUploadChipState = 'queued' | 'uploading' | 'rejected';

export interface AndroidUploadChip {
  id: string;
  name: string;
  mimeType: string | null;
  sizeBytes: number | null;
  /** Bytes the host acknowledged (native SFTP write replies only). */
  written: number;
  state: AndroidUploadChipState;
  /** Readable reason, for a rejected file. */
  message: string | null;
}

export const androidUploads = shallowRef<readonly AndroidUploadChip[]>([]);

const cancelled = new Set<string>();
let sequence = 0;

function nextId(): string {
  sequence += 1;
  return `upload-${sequence}`;
}

function replace(id: string, patch: Partial<AndroidUploadChip>): void {
  androidUploads.value = androidUploads.value.map((chip) => (chip.id === id ? { ...chip, ...patch } : chip));
}

/** Add queued chips for a batch about to upload; returns their ids in order. */
export function queueUploads(files: readonly { name: string; mimeType: string | null; sizeBytes: number | null }[]): string[] {
  const chips = files.map((file): AndroidUploadChip => ({
    id: nextId(), name: file.name, mimeType: file.mimeType, sizeBytes: file.sizeBytes,
    written: 0, state: 'queued', message: null,
  }));
  androidUploads.value = [...androidUploads.value, ...chips];
  return chips.map((chip) => chip.id);
}

export function markUploading(id: string): void {
  replace(id, { state: 'uploading' });
}

export function reportUploadProgress(id: string, written: number): void {
  replace(id, { written, state: 'uploading' });
}

/** The file finished, failed, or was cancelled: its chip leaves. */
export function finishUpload(id: string): void {
  cancelled.delete(id);
  androidUploads.value = androidUploads.value.filter((chip) => chip.id !== id);
}

/** Files refused before any byte was sent, shown with their reason. */
export function rejectFiles(files: readonly { name: string; message: string; mimeType?: string | null; sizeBytes?: number | null }[]): void {
  if (files.length === 0) return;
  androidUploads.value = [...androidUploads.value, ...files.map((file): AndroidUploadChip => ({
    id: nextId(), name: file.name, mimeType: file.mimeType ?? null, sizeBytes: file.sizeBytes ?? null,
    written: 0, state: 'rejected', message: file.message,
  }))];
}

/** Cancel a queued or uploading file. The uploader removes its partial file. */
export function cancelUpload(id: string): void {
  const chip = androidUploads.value.find((entry) => entry.id === id);
  if (!chip || chip.state === 'rejected') return;
  cancelled.add(id);
  replace(id, { message: 'Cancelling…' });
}

export function isUploadCancelled(id: string): boolean {
  return cancelled.has(id);
}

export function dismissChip(id: string): void {
  androidUploads.value = androidUploads.value.filter((chip) => chip.id !== id || chip.state !== 'rejected');
}

/** Rejections from an earlier pick are stale once the user picks again. */
export function clearRejected(): void {
  androidUploads.value = androidUploads.value.filter((chip) => chip.state !== 'rejected');
}

/** Test seam: forget every chip and cancellation. */
export function resetAndroidUploads(): void {
  cancelled.clear();
  androidUploads.value = [];
}
