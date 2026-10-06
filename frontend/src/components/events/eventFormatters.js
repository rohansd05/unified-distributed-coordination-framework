/** Formats an UPPER_SNAKE_CASE event type into sentence case words */
export function formatEventType(type) {
  if (!type) return ''
  const words = type.split('_').map((w) => w.toLowerCase())
  words[0] = words[0].charAt(0).toUpperCase() + words[0].slice(1)
  return words.join(' ')
}

/** Formats an ISO wall-time string into a human-readable relative time */
export function formatRelativeTime(wallTime) {
  if (!wallTime) return '—'
  const time = new Date(wallTime).getTime()
  if (isNaN(time)) return '—'

  const diffMs = Date.now() - time
  if (diffMs < 5000) return 'just now'
  const seconds = Math.floor(diffMs / 1000)
  if (seconds < 60) return `${seconds}s ago`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  const days = Math.floor(hours / 24)
  return `${days}d ago`
}
