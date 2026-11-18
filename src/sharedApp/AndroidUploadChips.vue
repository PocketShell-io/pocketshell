<script setup lang="ts">
// The phone's upload chips (#3191), contributed to the shared composer's
// `composer.accessory` slot (D42: upload progress is Android-only, #2941).
// One chip per file: acknowledged bytes and Cancel while it uploads, or the
// plain-words reason a file was refused before upload.
import { computed } from 'vue';
import { formatBytes } from '@pocketshell/core';
import AppIcon from '@ui/components/AppIcon.vue';
import {
  androidUploads,
  cancelUpload,
  dismissChip,
  type AndroidUploadChip,
} from '@/platform/android/attachmentUploads';

const active = computed(() => androidUploads.value.filter((chip) => chip.state !== 'rejected'));
const rejected = computed(() => androidUploads.value.filter((chip) => chip.state === 'rejected'));
const batchWritten = computed(() => active.value.reduce((sum, chip) => sum + chip.written, 0));
const batchTotal = computed(() => active.value.reduce((sum, chip) => sum + (chip.sizeBytes ?? chip.written), 0));

function progressText(chip: AndroidUploadChip): string {
  if (chip.message) return chip.message;
  if (chip.state === 'queued') return 'Waiting…';
  return chip.sizeBytes != null
    ? `${formatBytes(chip.written)} of ${formatBytes(chip.sizeBytes)}`
    : `${formatBytes(chip.written)} sent`;
}
</script>

<template>
  <div v-if="androidUploads.length" class="upload-chips" data-testid="android-upload-chips">
    <div
      v-if="active.length"
      class="upload-batch"
      role="status"
      aria-live="polite"
      data-testid="android-upload-progress"
      :data-written="batchWritten"
      :data-total="batchTotal"
      :data-file-count="active.length"
    >
      <div
        v-for="chip in active"
        :key="chip.id"
        class="upload-chip"
        data-testid="android-upload-chip"
        :data-state="chip.state"
        :data-name="chip.name"
        :data-written="chip.written"
      >
        <div class="chip-text">
          <span class="chip-name">{{ chip.name }}</span>
          <span class="chip-detail">{{ progressText(chip) }}</span>
          <progress
            class="chip-bar"
            :value="chip.written"
            :max="chip.sizeBytes ?? Math.max(chip.written, 1)"
            :aria-label="`Uploading ${chip.name}`"
          />
        </div>
        <button
          type="button"
          class="chip-action"
          data-testid="android-upload-cancel"
          :aria-label="`Cancel upload of ${chip.name}`"
          :disabled="chip.message !== null"
          @click="cancelUpload(chip.id)"
        >
          <AppIcon name="close" />
        </button>
      </div>
    </div>
    <div
      v-for="chip in rejected"
      :key="chip.id"
      class="upload-chip upload-chip--rejected"
      role="alert"
      data-testid="android-upload-chip"
      data-state="rejected"
      :data-name="chip.name"
    >
      <div class="chip-text">
        <span class="chip-name">{{ chip.name }}</span>
        <span class="chip-detail" data-testid="android-upload-error">{{ chip.message }}</span>
      </div>
      <button
        type="button"
        class="chip-action"
        data-testid="android-upload-dismiss"
        :aria-label="`Dismiss ${chip.name}`"
        @click="dismissChip(chip.id)"
      >
        <AppIcon name="close" />
      </button>
    </div>
  </div>
</template>

<style scoped>
.upload-chips {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  margin: 0 0 var(--sp-2);
}
.upload-batch {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.upload-chip {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  min-width: 0;
  padding: var(--sp-1) var(--sp-1) var(--sp-1) var(--sp-3);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  color: var(--fg);
  font-size: var(--fs-200);
}
.upload-chip--rejected {
  background: var(--error-soft);
  border-color: var(--border-strong);
}
.chip-text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.chip-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 600;
}
.chip-detail {
  color: var(--fg-secondary);
  overflow-wrap: anywhere;
}
.upload-chip--rejected .chip-detail {
  color: var(--fg);
}
.chip-bar {
  width: 100%;
  height: 4px;
  accent-color: var(--accent);
}
.chip-action {
  flex: 0 0 auto;
  width: 48px;
  height: 48px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  border: none;
  border-radius: var(--r-sm);
  color: var(--fg-secondary);
}
.chip-action:disabled {
  opacity: 0.5;
}
</style>
