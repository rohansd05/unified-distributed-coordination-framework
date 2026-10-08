/**
 * True for a value that can be shown: not null or undefined, not an empty string, and not
 * NaN or Infinity. Anything else is "not available" and is shown as "—", never as 0 (R7).
 */
export function isAvailable(value) {
  if (value === null || value === undefined || value === '') {
    return false
  }
  return typeof value !== 'number' || Number.isFinite(value)
}
