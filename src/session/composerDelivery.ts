import {
  ComposerDeliveryController,
  type ComposerDeliveryEffects,
  type ComposerWriteContext,
} from '@pocketshell/core';

export interface PtyWriteAcknowledgement {
  ok: boolean;
  message?: string;
}

export type PtyWriteEffect = (
  bytes: Uint8Array,
  context: ComposerWriteContext,
) => Promise<PtyWriteAcknowledgement>;

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Adapt the app's acknowledged PTY write effect to the pinned composer policy. */
export function createComposerDeliveryController(
  writePty: PtyWriteEffect,
  wait: ComposerDeliveryEffects['sleep'] = sleep,
): ComposerDeliveryController {
  return new ComposerDeliveryController({
    write: async (bytes, context) => {
      const acknowledgement = await writePty(bytes, context);
      if (!acknowledgement.ok) {
        throw new Error(acknowledgement.message || 'The PTY did not acknowledge this write.');
      }
    },
    sleep: wait,
  });
}
