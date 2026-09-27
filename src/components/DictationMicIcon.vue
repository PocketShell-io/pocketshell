<script setup lang="ts">
withDefaults(defineProps<{
  size?: number;
  stopped?: boolean;
}>(), {
  size: 20,
  stopped: false,
});
</script>

<template>
  <svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
    stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path v-if="stopped" d="M7 7h10v10H7z" fill="currentColor" stroke="none" />
    <template v-else>
      <path d="M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z" />
      <path d="M19 10v2a7 7 0 0 1-14 0v-2" />
      <path d="M12 19v3M8 22h8" />
    </template>
  </svg>
</template>
