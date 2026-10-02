<script setup lang="ts">
// Feather 4.29 `key` (MIT), drawn under AppIcon's contract (24x24 viewBox,
// currentColor stroke, width 2, round caps/joins). The shared AppIcon
// registry has no key mark yet; this Android-only copy follows the
// DictationMicIcon precedent until core gains one (#3021).
withDefaults(defineProps<{ size?: number }>(), { size: 20 });
</script>

<template>
  <svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
    stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4" />
  </svg>
</template>
