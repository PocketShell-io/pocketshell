import { onBeforeUnmount, ref, type Ref } from 'vue';

const REDUCED_MOTION_QUERY = '(prefers-reduced-motion: reduce)';

interface MotionQuery {
  matches: boolean;
  addEventListener?: (type: 'change', listener: (event: { matches: boolean }) => void) => void;
  removeEventListener?: (type: 'change', listener: (event: { matches: boolean }) => void) => void;
}

/**
 * Track the OS reduced-motion preference so a component can pick a static
 * highlight instead of a pulse in markup, not only in CSS (#3062). Browsers
 * without `matchMedia` report full motion; the CSS media rule still applies.
 */
export function usePrefersReducedMotion(): Ref<boolean> {
  const host = globalThis as { matchMedia?: (query: string) => MotionQuery };
  // Call through the global so the browser's matchMedia keeps its receiver.
  const query = typeof host.matchMedia === 'function' ? host.matchMedia(REDUCED_MOTION_QUERY) : null;
  const reduced = ref(query?.matches === true);
  const onChange = (event: { matches: boolean }) => { reduced.value = event.matches; };
  query?.addEventListener?.('change', onChange);
  onBeforeUnmount(() => query?.removeEventListener?.('change', onChange));
  return reduced;
}
