<script setup lang="ts">
import { computed } from 'vue';
import type { SessionRow } from '@pocketshell/core';
import { projectSessionAgentPresentation } from '@/session/agentMetadata';
import { AppIcon } from '@pocketshell/ui';

const props = defineProps<{
  session: SessionRow;
}>();

const presentation = computed(() => projectSessionAgentPresentation(props.session));
</script>

<template>
  <span v-if="presentation.identity || presentation.state" class="session-agent-metadata">
    <span
      v-if="presentation.identity"
      class="session-agent-metadata__chip session-agent-metadata__identity"
      data-testid="session-agent-identity"
      role="img"
      :aria-label="`${presentation.identity.label} agent`"
    >
      <AppIcon :name="presentation.identity.icon" :size="12" aria-hidden="true" />
      <span>{{ presentation.identity.label }}</span>
    </span>
    <span
      v-if="presentation.state && presentation.stateLabel"
      class="session-agent-metadata__chip session-agent-metadata__state"
      :class="`session-agent-metadata__state--${presentation.state}`"
      data-testid="session-agent-state"
      role="img"
      :aria-label="`Agent state: ${presentation.stateLabel}`"
    >
      <span class="session-agent-metadata__dot" aria-hidden="true" />
      <span>{{ presentation.stateLabel }}</span>
    </span>
  </span>
</template>

<style scoped>
.session-agent-metadata {
  display: inline-flex;
  min-width: 0;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
}

.session-agent-metadata__chip {
  display: inline-flex;
  min-width: 0;
  min-height: 20px;
  align-items: center;
  gap: 4px;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  padding: 2px 5px;
  font-size: var(--fs-100);
  font-weight: var(--fw-medium);
  line-height: 1.2;
  white-space: nowrap;
}

.session-agent-metadata__identity {
  background: var(--agent-soft);
  color: var(--agent);
}

.session-agent-metadata__identity :deep(svg) {
  flex: 0 0 auto;
  width: 12px;
  height: 12px;
}

.session-agent-metadata__state--working {
  border-color: var(--success-soft);
  background: var(--success-soft);
  color: var(--success);
}

.session-agent-metadata__state--waiting {
  border-color: var(--warning-soft);
  background: var(--warning-soft);
  color: var(--warning);
}

.session-agent-metadata__state--idle {
  border-color: var(--border);
  background: var(--surface-2);
  color: var(--fg-secondary);
}

.session-agent-metadata__dot {
  width: 5px;
  height: 5px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: currentColor;
}
</style>
