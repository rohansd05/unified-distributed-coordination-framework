/** True when the user asked the system for less motion (jsdom has no matchMedia: false). */
export function prefersReducedMotion() {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}
