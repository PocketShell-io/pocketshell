import type { AttachmentSource } from '@pocketshell/core';

export type ByteAttachmentSource = Extract<AttachmentSource, { kind: 'bytes' }>;

export interface PendingComposerAttachment {
  id: string;
  order: number;
  source: ByteAttachmentSource;
}

export interface StagedComposerAttachment {
  id: string;
  order: number;
  name: string;
  path: string;
  sizeBytes: number;
}

export interface AttachmentStageSuccess {
  id: string;
  path: string;
  name: string;
  sizeBytes: number;
}

export interface AttachmentStageFailure {
  id: string;
  name: string;
  message: string;
}

export interface ComposerAttachmentStageResult {
  staged: AttachmentStageSuccess[];
  failures: AttachmentStageFailure[];
}

/** Append staged paths only when sending, leaving the editable draft untouched. */
export function appendAttachmentPaths(draft: string, paths: readonly string[]): string {
  if (paths.length === 0) return draft;
  const block = `Attached files:${paths.map((path) => `\n- ${path}`).join('')}`;
  if (draft.trim() === '') return block;
  if (draft.endsWith('\n\n')) return draft + block;
  if (draft.endsWith('\n')) return draft + `\n${block}`;
  return draft + `\n\n${block}`;
}

let lastTimestampSecond = -1;

/** Produce the host-compatible yyyyMMdd-HHmmss filename prefix without collisions. */
export function nextAttachmentTimestamp(now = new Date()): string {
  let second = Math.floor(now.getTime() / 1000);
  if (second <= lastTimestampSecond) second = lastTimestampSecond + 1;
  lastTimestampSecond = second;
  const date = new Date(second * 1000);
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${date.getUTCFullYear()}${pad(date.getUTCMonth() + 1)}${pad(date.getUTCDate())}-${pad(date.getUTCHours())}${pad(date.getUTCMinutes())}${pad(date.getUTCSeconds())}`;
}
