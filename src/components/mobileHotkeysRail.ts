/** A 48px target and its 8px gap must travel together through the clipping rail. */
export const MAIN_KEY_SLOT_PX = 56;

export function mainKeyRailWidth(availableWidth: number): number {
  return Math.max(MAIN_KEY_SLOT_PX, Math.floor(availableWidth / MAIN_KEY_SLOT_PX) * MAIN_KEY_SLOT_PX);
}

export function observeMainKeyRail(rail: HTMLElement): () => void {
  const parent = rail.parentElement;
  if (!parent) return () => {};
  const resize = (width: number) => { rail.style.width = `${mainKeyRailWidth(width)}px`; };
  resize(parent.getBoundingClientRect().width);
  // Observe the available surface, never the width we write, so a rotation or
  // viewport inset updates the rail without an observer feedback loop.
  const observer = new ResizeObserver(([entry]) => { if (entry) resize(entry.contentRect.width); });
  observer.observe(parent);
  return () => observer.disconnect();
}
