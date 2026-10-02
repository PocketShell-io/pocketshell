<script setup lang="ts">
/** Home's only build-check output (#3023): shown when the packaged files fail their integrity check. */
</script>

<template>
  <section class="migration-error" role="alert" data-testid="build-integrity-error">
    <div class="migration-error__copy">
      <strong>This copy of PocketShell looks damaged</strong>
      <p>Its files failed an integrity check. Reinstall the app to fix this.</p>
    </div>
  </section>
</template>
